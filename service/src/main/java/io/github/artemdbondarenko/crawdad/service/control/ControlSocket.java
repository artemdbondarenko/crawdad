package io.github.artemdbondarenko.crawdad.service.control;

import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry;
import io.github.artemdbondarenko.crawdad.service.device.Device;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry.RevokeResult;
import io.github.artemdbondarenko.crawdad.service.log.LogText;
import io.github.artemdbondarenko.crawdad.service.log.WarningAggregator;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes.Issued;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingOutcome;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import java.io.Closeable;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jdk.net.ExtendedSocketOptions;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Unix socket {@code control.sock} through which the {@code crawdad} commands of the owner pair, list
 * and revoke devices. One request per connection: a JSON line in, the answer out (two lines for
 * {@code pair}: the code, then how it ended), then the connection closes. The socket is 0600 in a 0700
 * directory, and a peer that is not the user owning the socket is dropped without an answer.
 */
public final class ControlSocket implements Closeable {

    public static final String FILE_NAME = "control.sock";

    /** The longest request line, without its newline; a longer one is a bad request. */
    static final int MAX_REQUEST_BYTES = 4096;

    /** A connection that sends no complete request line this long after it opened is closed. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** The longest name {@code pair --name} accepts. */
    public static final int MAX_NAME_LENGTH = 64;

    private static final Logger LOG = System.getLogger(ControlSocket.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Who is on the other end of a connection; the production source asks the kernel ({@code SO_PEERCRED}). */
    @FunctionalInterface
    interface PeerSource {
        UserPrincipal userOf(SocketChannel channel) throws IOException;
    }

    static final PeerSource KERNEL = channel -> channel.getOption(ExtendedSocketOptions.SO_PEERCRED).user();

    /** Where the service answers, for the link and the QR code that {@code crawdad pair} prints. */
    public record ServiceUrls(URI loopback, Optional<URI> network) {

        public ServiceUrls {
            Objects.requireNonNull(loopback, "loopback");
            Objects.requireNonNull(network, "network");
        }
    }

    private final Path path;
    private final ServerSocketChannel server;
    private final UserPrincipal owner;
    private final PairingCodes codes;
    private final DeviceRegistry devices;
    private final ConnectionRegistry connections;
    private final ServiceUrls urls;
    private final Duration requestTimeout;
    private final PeerSource peers;
    private final WarningAggregator refusals = new WarningAggregator(LOG);
    private final ScheduledThreadPoolExecutor deadlines;
    private final Set<SocketChannel> open = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private ControlSocket(Path path, ServerSocketChannel server, UserPrincipal owner, PairingCodes codes,
            DeviceRegistry devices, ConnectionRegistry connections, ServiceUrls urls, Duration requestTimeout,
            PeerSource peers) {
        this.path = path;
        this.server = server;
        this.owner = owner;
        this.codes = codes;
        this.devices = devices;
        this.connections = connections;
        this.urls = urls;
        this.requestTimeout = requestTimeout;
        this.peers = peers;
        this.deadlines = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("crawdad-control-deadline").factory());
        deadlines.setRemoveOnCancelPolicy(true);
    }

    /**
     * Listens on {@code control.sock} in {@code runtimeDirectory}, creating the directory 0700. The caller
     * holds the {@link io.github.artemdbondarenko.crawdad.service.storage.InstanceLock InstanceLock}, so a
     * socket file already there is a leftover of a process that is gone and is replaced without a probe.
     * {@code urls} go into the first answer line of every {@code pair}.
     */
    public static ControlSocket start(Path runtimeDirectory, PairingCodes codes, DeviceRegistry devices,
            ConnectionRegistry connections, ServiceUrls urls) throws IOException {
        return start(runtimeDirectory, codes, devices, connections, urls, REQUEST_TIMEOUT, KERNEL);
    }

    static ControlSocket start(Path runtimeDirectory, PairingCodes codes, DeviceRegistry devices,
            ConnectionRegistry connections, ServiceUrls urls, Duration requestTimeout, PeerSource peers)
            throws IOException {
        Objects.requireNonNull(codes, "codes");
        Objects.requireNonNull(devices, "devices");
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(urls, "urls");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(peers, "peers");
        Path path = DataDirectory.createPrivateDirectory(runtimeDirectory).resolve(FILE_NAME);
        Files.deleteIfExists(path);
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        ControlSocket socket;
        try {
            server.bind(UnixDomainSocketAddress.of(path));
            // Bound with the umask's mode; the 0700 directory keeps other users out until this narrows it.
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            // The socket file was just created by this process, so its owner is the user the service runs as.
            UserPrincipal owner = Files.getOwner(path);
            socket = new ControlSocket(path, server, owner, codes, devices, connections, urls, requestTimeout,
                    peers);
        } catch (IOException | RuntimeException e) {
            server.close();
            Files.deleteIfExists(path);
            throw e;
        }
        Thread.ofVirtual().name("crawdad-control-accept").start(socket::accept);
        return socket;
    }

    /** The socket file. */
    public Path path() {
        return path;
    }

    /** How many connections are being served now; for tests. */
    int openConnections() {
        return open.size();
    }

    /** Stops listening, drops open connections (a waiting {@code pair} stops waiting) and deletes the socket file. */
    @Override
    public void close() {
        closed = true;
        closeQuietly(server);
        open.forEach(ControlSocket::closeQuietly);
        deadlines.shutdownNow();
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete the control socket " + LogText.safe(path.toString()), e);
        }
    }

    private void accept() {
        while (!closed) {
            SocketChannel channel;
            try {
                channel = server.accept();
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "The control socket could not accept a connection", e);
                continue;
            }
            open.add(channel);
            if (closed) {
                // Accepted while the socket was closing, after the open channels were dropped.
                closeQuietly(channel);
                return;
            }
            Thread.ofVirtual().name("crawdad-control").start(() -> serve(channel));
        }
    }

    private void serve(SocketChannel channel) {
        try (channel) {
            if (!fromOwner(channel)) {
                return;
            }
            // The deadline closes the channel, which ends a read blocked on a silent client.
            ScheduledFuture<?> deadline = deadlines.schedule(() -> closeQuietly(channel), requestTimeout.toMillis(),
                    TimeUnit.MILLISECONDS);
            byte[] line;
            try {
                line = readLine(channel);
            } finally {
                deadline.cancel(false);
            }
            if (line != null) {
                answer(channel, line);
            }
        } catch (IOException e) {
            // The client left, or stayed silent past the deadline; nobody is there to answer.
            LOG.log(Level.DEBUG, "Control socket connection ended: {0}", LogText.cause(e));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Control socket request failed", e);
        } finally {
            open.remove(channel);
        }
    }

    /** Whether the peer is the user the service runs as; any other is refused with a WARNING. */
    private boolean fromOwner(SocketChannel channel) {
        UserPrincipal peer;
        try {
            peer = peers.userOf(channel);
        } catch (IOException e) {
            peer = null;
        }
        if (owner.equals(peer)) {
            return true;
        }
        refusals.warn(peer == null ? "an unknown user" : "user " + peer.getName(),
                "Control socket connection refused: not the owner of the service");
        return false;
    }

    /**
     * The request line without its newline; {@link #MAX_REQUEST_BYTES} + 1 bytes when it is longer, and
     * {@code null} when the client closed before ending a line.
     */
    private static byte[] readLine(SocketChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(MAX_REQUEST_BYTES + 1);
        int scanned = 0;
        while (true) {
            for (; scanned < buffer.position(); scanned++) {
                if (buffer.get(scanned) == '\n') {
                    return Arrays.copyOf(buffer.array(), scanned);
                }
            }
            if (!buffer.hasRemaining()) {
                return buffer.array();
            }
            if (channel.read(buffer) < 0) {
                return null;
            }
        }
    }

    private void answer(SocketChannel channel, byte[] line) throws IOException {
        JsonNode request = line.length > MAX_REQUEST_BYTES ? null : parse(line);
        JsonNode op = request == null ? null : request.get("op");
        if (op == null || !op.isString()) {
            send(channel, badRequest());
            return;
        }
        switch (op.stringValue()) {
            case "pair" -> pair(channel, request);
            case "devices" -> devices(channel, request);
            case "revoke" -> revoke(channel, request);
            default -> send(channel, badRequest());
        }
    }

    /** The request object, or {@code null} when the line is not one JSON object. */
    private static JsonNode parse(byte[] line) {
        try {
            JsonNode node = JSON.readTree(line);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * Issues a code, answers with it and waits for how it ends. A client that leaves stops the wait only:
     * the code stays valid and its outcome goes nowhere; a burn then waits for the next {@code pair}.
     */
    private void pair(SocketChannel channel, JsonNode request) throws IOException {
        JsonNode given = request.get("name");
        String name = null;
        if (given != null && !given.isNull()) {
            if (!given.isString()) {
                send(channel, badRequest());
                return;
            }
            Optional<String> valid = validName(given.stringValue());
            if (valid.isEmpty()) {
                send(channel, error("invalid_name"));
                return;
            }
            name = valid.get();
        }
        Issued issued = codes.issue(name);
        ObjectNode first = JSON.createObjectNode();
        first.put("code", issued.code());
        first.put("expires_in", issued.expiresIn().toSeconds());
        issued.previousBurnedFrom().ifPresent(addresses -> first.putObject("burned").set("addresses", array(addresses)));
        ObjectNode addresses = first.putObject("urls");
        addresses.put("loopback", urls.loopback().toString());
        urls.network().ifPresent(network -> addresses.put("network", network.toString()));
        send(channel, first);

        // The outcome is the waiter's own copy: cancelling it leaves the code and its other waiters alone.
        CompletableFuture<PairingOutcome> outcome = issued.outcome();
        Thread.ofVirtual().name("crawdad-control-hangup").start(() -> {
            awaitHangUp(channel);
            outcome.cancel(false);
        });
        PairingOutcome ended;
        try {
            ended = outcome.join();
        } catch (CancellationException e) {
            return;
        }
        send(channel, outcomeLine(ended));
        // Written to the waiter, so the next pair need not repeat it.
        if (ended instanceof PairingOutcome.Burned burned) {
            codes.burnReported(burned);
        }
    }

    /** Returns once the client closes its end or the channel closes; anything it sends meanwhile is ignored. */
    private static void awaitHangUp(SocketChannel channel) {
        ByteBuffer ignored = ByteBuffer.allocate(256);
        try {
            while (channel.read(ignored) >= 0) {
                ignored.clear();
            }
        } catch (IOException e) {
            // Closed by the answer, the deadline or the stop of the service: the wait is over either way.
        }
    }

    private static ObjectNode outcomeLine(PairingOutcome outcome) {
        ObjectNode line = JSON.createObjectNode();
        switch (outcome) {
            case PairingOutcome.Paired paired -> {
                ObjectNode device = line.putObject("paired");
                device.put("name", paired.name());
                device.put("address", paired.address());
                device.put("user_agent", paired.userAgent());
            }
            case PairingOutcome.Expired expired -> line.put("expired", true);
            case PairingOutcome.Burned burned -> line.putObject("burned").set("addresses", array(burned.addresses()));
            case PairingOutcome.Replaced replaced -> line.put("replaced", true);
            case PairingOutcome.Failed failed -> line.put("failed", true);
        }
        return line;
    }

    /**
     * The name trimmed, if it is 1 to {@link #MAX_NAME_LENGTH} characters with no control, format (such as
     * direction marks), line or paragraph separator characters and no unpaired surrogate.
     */
    static Optional<String> validName(String given) {
        String name = given.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > MAX_NAME_LENGTH || !name.codePoints().allMatch(ControlSocket::allowedInName)) {
            return Optional.empty();
        }
        return Optional.of(name);
    }

    private static boolean allowedInName(int c) {
        int type = Character.getType(c);
        return !Character.isISOControl(c) && type != Character.FORMAT && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR && type != Character.SURROGATE;
    }

    private void devices(SocketChannel channel, JsonNode request) throws IOException {
        JsonNode all = request.get("all");
        if (all != null && !all.isNull() && !all.isBoolean()) {
            send(channel, badRequest());
            return;
        }
        ObjectNode answer = JSON.createObjectNode();
        ArrayNode list = answer.putArray("devices");
        for (Device device : devices.devices(all != null && all.isBoolean() && all.booleanValue())) {
            ObjectNode entry = list.addObject();
            entry.put("name", device.name());
            entry.put("paired_at", device.pairedAt().toString());
            entry.put("last_seen", timestamp(device.lastSeen()));
            entry.put("last_address", device.lastAddress());
            entry.put("last_user_agent", device.lastUserAgent());
            entry.put("listener", switch (device.listener()) {
                case LOOPBACK -> "loopback";
                case NETWORK -> "network";
            });
            if (!device.active()) {
                entry.put("revoked_at", device.revokedAt().toString());
            }
        }
        send(channel, answer);
    }

    private void revoke(SocketChannel channel, JsonNode request) throws IOException {
        JsonNode name = request.get("name");
        if (name == null || !name.isString()) {
            send(channel, badRequest());
            return;
        }
        RevokeResult result;
        try {
            result = devices.revoke(name.stringValue(), connections);
        } catch (IOException e) {
            // The cause names the registry file; a revoked device's hash is never in it.
            LOG.log(Level.ERROR, "Could not revoke the device " + LogText.safe(name.stringValue()), e);
            send(channel, error("server_error"));
            return;
        }
        ObjectNode answer = JSON.createObjectNode();
        switch (result) {
            case RevokeResult.Revoked revoked -> answer.put("revoked", revoked.device().name());
            case RevokeResult.NotFound notFound -> {
                answer.put("error", "not_found");
                answer.set("active", array(notFound.active()));
            }
            case RevokeResult.AlreadyRevoked already -> {
                answer.put("error", "already_revoked");
                answer.put("revoked_at", already.revokedAt().toString());
            }
        }
        send(channel, answer);
    }

    private static String timestamp(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static ArrayNode array(List<String> values) {
        ArrayNode array = JSON.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static ObjectNode badRequest() {
        return error("bad_request");
    }

    private static ObjectNode error(String code) {
        ObjectNode answer = JSON.createObjectNode();
        answer.put("error", code);
        return answer;
    }

    private static void send(SocketChannel channel, ObjectNode line) throws IOException {
        byte[] json = JSON.writeValueAsBytes(line);
        ByteBuffer buffer = ByteBuffer.allocate(json.length + 1).put(json).put((byte) '\n').flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void closeQuietly(Channel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Could not close a control socket channel: {0}", LogText.cause(e));
        }
    }
}
