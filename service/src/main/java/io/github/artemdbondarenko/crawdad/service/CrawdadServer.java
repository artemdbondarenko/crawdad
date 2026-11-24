package io.github.artemdbondarenko.crawdad.service;

import io.github.artemdbondarenko.crawdad.service.adapter.AgentAdapter;
import io.github.artemdbondarenko.crawdad.service.adapter.FakeAgentAdapter;
import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry;
import io.github.artemdbondarenko.crawdad.service.device.Device;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.device.Listener;
import io.github.artemdbondarenko.crawdad.service.log.LogText;
import io.github.artemdbondarenko.crawdad.service.log.WarningAggregator;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingHandler;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingHandler.KeyedDevice;
import io.github.artemdbondarenko.crawdad.service.pairing.Site;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import io.github.artemdbondarenko.crawdad.service.tls.LocalCa;
import io.github.artemdbondarenko.crawdad.service.tls.LocalCa.ServerCertificate;
import io.github.artemdbondarenko.crawdad.service.tls.LocalCa.UnusableRootException;
import io.github.artemdbondarenko.crawdad.service.tls.TlsMaterial;
import io.github.artemdbondarenko.crawdad.service.tls.TlsMaterial.UnusableFileException;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnectionLimit;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

/**
 * The HTTP server of the service. It always serves {@code 127.0.0.1} over plain HTTP; with
 * {@code --host} it also serves that network address, over TLS only. Both serve the files of the web
 * client, exchange a pairing code for a device key and accept the WebSocket of a paired device on
 * {@link #WEBSOCKET_PATH}, each checking {@code Origin} and {@code Host} against its own address.
 * Started with {@link #start}.
 */
public final class CrawdadServer {

    public static final String WEBSOCKET_PATH = "/ws";

    /** The largest text frame a client may send, in bytes; a larger one closes the connection (1009). */
    public static final int MAX_TEXT_MESSAGE_SIZE = 64 * 1024;

    /** Connections the network address serves at once; further ones wait in the queue of the OS. */
    static final int MAX_NETWORK_CONNECTIONS = 32;

    /** A connection to the network address that stays silent this long is closed. */
    static final Duration NETWORK_IDLE_TIMEOUT = Duration.ofSeconds(30);

    private static final Logger LOG = System.getLogger(CrawdadServer.class.getName());

    /**
     * The certificate the network address presents, the server certificate first; the name the address
     * is printed with; and, for the service's own root, the SHA-256 fingerprint of that root.
     */
    record NetworkCertificate(PrivateKey privateKey, List<X509Certificate> chain, String addressName,
            Optional<String> rootFingerprint) {

        NetworkCertificate {
            Objects.requireNonNull(privateKey, "privateKey");
            chain = List.copyOf(chain);
            Objects.requireNonNull(addressName, "addressName");
            Objects.requireNonNull(rootFingerprint, "rootFingerprint");
        }

        /** The certificate for {@code config}, with the own root under the data directory of the user. */
        static Optional<NetworkCertificate> forConfig(ServiceConfig config)
                throws IOException, UnusableRootException, UnusableFileException {
            return forConfig(config, () -> LocalCa.forThisMachine(DataDirectory.fromEnvironment()));
        }

        /**
         * None without {@code --host}; the owner's files, named after their certificate, when given;
         * otherwise one issued by {@code ownRoot} for {@code --host} as given. Never falls back from one to the other.
         */
        static Optional<NetworkCertificate> forConfig(ServiceConfig config, Supplier<LocalCa> ownRoot)
                throws IOException, UnusableRootException, UnusableFileException {
            if (config.host().equals(ServiceConfig.DEFAULT_HOST)) {
                return Optional.empty();
            }
            if (config.tls().isPresent()) {
                TlsMaterial files = TlsMaterial.read(config.tls().get());
                return Optional.of(new NetworkCertificate(files.privateKey(), files.chain(), files.addressName(),
                        Optional.empty()));
            }
            ServerCertificate own = ownRoot.get().issue(config.host());
            return Optional.of(new NetworkCertificate(own.privateKey(), own.chain(), config.host(),
                    Optional.of(own.rootFingerprint())));
        }

        /** An in-memory PKCS#12 key store with this one entry, protected by the password. */
        KeyStore keyStore(char[] password) throws GeneralSecurityException, IOException {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry(ServerCertificate.ALIAS, privateKey, password, chain.toArray(Certificate[]::new));
            return store;
        }

        /** Keeps the private key out of logs and error messages. */
        @Override
        public String toString() {
            return "NetworkCertificate[privateKey=<hidden>, subject="
                    + chain.getFirst().getSubjectX500Principal().getName() + ", addressName=" + addressName
                    + ", rootFingerprint=" + rootFingerprint + "]";
        }
    }

    /** The TLS connector of the network address and the name its address is printed with. */
    private record Network(ServerConnector connector, String name, SslContextFactory.Server tls) {}

    private final Server server;
    private final ServerConnector loopback;
    /** Null without {@code --host}. */
    private final Network network;
    private final AgentAdapter adapter;
    private final DeviceRegistry devices;
    private final ConnectionRegistry connections;
    private final WarningAggregator refusals = new WarningAggregator(LOG);

    private CrawdadServer(Server server, ServerConnector loopback, Network network, AgentAdapter adapter,
            DeviceRegistry devices, ConnectionRegistry connections) {
        this.server = server;
        this.loopback = loopback;
        this.network = network;
        this.adapter = adapter;
        this.devices = devices;
        this.connections = connections;
    }

    /** Starts the server with the {@link FakeAgentAdapter}, the only adapter so far, and a throwaway registry. */
    static CrawdadServer start(ServiceConfig config, String staticRoot) throws Exception {
        return start(config, staticRoot, new FakeAgentAdapter());
    }

    /**
     * Starts the server. {@code staticRoot} is a classpath directory with the client files, such as
     * {@code "static/"}; the service refuses to start without it. Each WebSocket connection becomes
     * one session of {@code adapter}. With {@code --host} the certificate comes from the files in
     * {@code config} or from the service's own root; an unusable one stops the start. Devices pair into
     * a throwaway registry, as with {@link #start(ServiceConfig, String, AgentAdapter, Optional)}.
     */
    static CrawdadServer start(ServiceConfig config, String staticRoot, AgentAdapter adapter)
            throws Exception {
        return start(config, staticRoot, adapter, NetworkCertificate.forConfig(config));
    }

    /**
     * Starts the server with {@code certificate}, a throwaway device registry in a temporary directory,
     * removed when the server stops, and its own connection registry; for tests that do not look at the devices.
     */
    static CrawdadServer start(ServiceConfig config, String staticRoot, AgentAdapter adapter,
            Optional<NetworkCertificate> certificate) throws Exception {
        Path temporary = Files.createTempDirectory("crawdad-");
        try {
            DeviceRegistry devices = DeviceRegistry.open(
                    new DataDirectory(temporary.resolve("data"), temporary.resolve("run")), Clock.systemUTC());
            CrawdadServer started = start(config, staticRoot, adapter, certificate, devices,
                    new ConnectionRegistry(), new PairingCodes());
            started.whenStopped(() -> deleteQuietly(temporary));
            return started;
        } catch (Exception e) {
            deleteQuietly(temporary);
            throw e;
        }
    }

    private static void deleteQuietly(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete the temporary directory " + LogText.safe(directory.toString()), e);
        }
    }

    /**
     * Starts the server with {@code certificate} for the network address, needed exactly with {@code --host}.
     * Devices pair into {@code devices} with codes from {@code codes}; their open WebSockets are kept in
     * {@code connections}, where revoking a device finds them.
     */
    static CrawdadServer start(ServiceConfig config, String staticRoot, AgentAdapter adapter,
            Optional<NetworkCertificate> certificate, DeviceRegistry devices, ConnectionRegistry connections,
            PairingCodes codes) throws Exception {
        boolean hasNetworkAddress = !config.host().equals(ServiceConfig.DEFAULT_HOST);
        if (hasNetworkAddress != certificate.isPresent()) {
            throw new IllegalArgumentException(hasNetworkAddress
                    ? "The network address " + config.host() + " needs a certificate"
                    : "A certificate needs a network address");
        }
        Server server = new Server();
        ServerConnector loopback = new ServerConnector(server);
        loopback.setHost(ServiceConfig.DEFAULT_HOST);
        loopback.setPort(config.port());
        server.addConnector(loopback);
        Network network = null;
        if (certificate.isPresent()) {
            network = addNetwork(server, config, certificate.get());
        }
        CrawdadServer crawdad = new CrawdadServer(server, loopback, network, adapter, devices, connections);

        ResourceHandler files = new ResourceHandler();
        Resource root = ResourceFactory.of(files).newClassLoaderResource(staticRoot);
        if (root == null || !root.exists()) {
            throw new IllegalStateException("Client files not found on the classpath: " + staticRoot);
        }
        files.setBaseResource(root);
        files.setWelcomeFiles(List.of("index.html"));
        files.setDirAllowed(false);

        WebSocketUpgradeHandler webSocket = WebSocketUpgradeHandler.from(server, container -> {
            container.setMaxTextMessageSize(MAX_TEXT_MESSAGE_SIZE);
            container.addMapping(WEBSOCKET_PATH, crawdad::createWebSocket);
        });
        // Pairing answers carry the security headers of the page too.
        webSocket.setHandler(new SecurityHeaders(new PairingHandler(files, codes, devices, crawdad::siteFor)));
        server.setHandler(webSocket);

        // Ctrl+C or a stop signal closes the port and open connections cleanly.
        server.setStopAtShutdown(true);
        try {
            openConnectors(server);
            server.start();
        } catch (Exception e) {
            stopAfterFailedStart(server, e);
            throw e;
        }
        return crawdad;
    }

    /**
     * An address the server cannot listen on. The operating system's own reason is in the language of the system
     * and does not tell a port in use from an address of another machine, so the service finds out which it is
     * ({@code START-4}, {@code START-5}); any other reason is not the owner's to fix and keeps the system's words.
     */
    public static final class CannotListenException extends IOException {

        private static final long serialVersionUID = 1L;

        /** Why the address cannot be listened on. */
        public enum Reason {
            /** No interface of this machine has the address ({@code START-4}). */
            NOT_AN_ADDRESS_OF_THIS_MACHINE,
            /** Another program listens on the port ({@code START-5}). */
            PORT_IN_USE,
            /** Anything else, told in the system's words ({@code START-8}). */
            OTHER
        }

        private final Reason reason;

        CannotListenException(String message, Reason reason, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** How long a probe of a port in use waits for the program that listens on it. */
    private static final int PORT_PROBE_MILLIS = 500;

    /**
     * Binds every connector before the server starts, so that a port in use or a foreign address is told
     * by its address; the ones bound before a failure are closed again. Another port is never tried instead
     * ({@code START-5}): the owner's devices know this one.
     */
    private static void openConnectors(Server server) throws CannotListenException {
        for (Connector connector : server.getConnectors()) {
            ServerConnector bound = (ServerConnector) connector;
            try {
                bound.open();
            } catch (IOException e) {
                for (Connector other : server.getConnectors()) {
                    ((ServerConnector) other).close();
                }
                throw cannotListen(bound.getHost(), bound.getPort(), e);
            }
        }
    }

    /**
     * The IPv4 address the network connector listens on: a name of {@code --host} can resolve to an IPv6 address
     * first, which the service does not serve ({@code NET-3}). The options have checked that one exists.
     */
    private static String ipv4Of(String host) throws UnknownHostException {
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
        }
        throw new UnknownHostException(host + " has no IPv4 address");
    }

    /** The first port a user who is not root may listen on, by default; Linux tells its own value. */
    private static final int FIRST_UNPRIVILEGED_PORT = 1024;

    private static final Path UNPRIVILEGED_PORT_START = Path.of("/proc/sys/net/ipv4/ip_unprivileged_port_start");

    private static CannotListenException cannotListen(String host, int port, IOException failure) {
        String where = "Cannot listen on " + host + ":" + port + ": ";
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            address = null;
        }
        if (address != null && !isOfThisMachine(address)) {
            return new CannotListenException(where + host + " is not an address of this machine; check that the "
                    + "network it belongs to, such as a VPN, is up",
                    CannotListenException.Reason.NOT_AN_ADDRESS_OF_THIS_MACHINE, failure);
        }
        // The address is this machine's, so the port is what fails: taken, unless it needs privileges.
        if (address != null && port != 0 && (listensThere(address, port) || port >= firstUnprivilegedPort())) {
            return new CannotListenException(where + "the port is in use; stop the program that uses it or start "
                    + "with --port <number>", CannotListenException.Reason.PORT_IN_USE, failure);
        }
        Throwable reason = failure.getCause() == null ? failure : failure.getCause();
        return new CannotListenException(where + LogText.safe(String.valueOf(reason.getMessage())),
                CannotListenException.Reason.OTHER, failure);
    }

    /** Below this port a user who is not root cannot listen, so a failure there may be for privileges. */
    private static int firstUnprivilegedPort() {
        try {
            return Integer.parseInt(Files.readString(UNPRIVILEGED_PORT_START).strip());
        } catch (IOException | NumberFormatException e) {
            return FIRST_UNPRIVILEGED_PORT;
        }
    }

    private static boolean isOfThisMachine(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return true;
        }
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException e) {
            // Without the list of interfaces the address is taken as this machine's; the reason is then the system's.
            return true;
        }
    }

    /** Whether a program accepts connections on the port, which tells it is taken even below the free ports. */
    private static boolean listensThere(InetAddress address, int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(address, port), PORT_PROBE_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Leaves no thread or port behind a start that failed. */
    private static void stopAfterFailedStart(Server server, Exception failure) {
        try {
            server.stop();
        } catch (Exception e) {
            failure.addSuppressed(e);
        }
        for (Connector connector : server.getConnectors()) {
            ((ServerConnector) connector).close();
        }
    }

    /**
     * A TLS-only connector on {@code --host}: plain HTTP there fails the TLS handshake, with no redirect.
     * {@link ServerNameCheck} answers 400 to a {@code Host} that differs from the TLS server name, or that
     * the certificate does not name.
     */
    private static Network addNetwork(Server server, ServiceConfig config, NetworkCertificate certificate)
            throws GeneralSecurityException, IOException {
        // The key store lives only in memory; the password just satisfies the API.
        String password = UUID.randomUUID().toString();
        SslContextFactory.Server tls = new SslContextFactory.Server();
        tls.setKeyStore(certificate.keyStore(password.toCharArray()));
        tls.setKeyStorePassword(password);
        tls.setIncludeProtocols("TLSv1.3", "TLSv1.2");

        HttpConfiguration https = new HttpConfiguration();
        https.addCustomizer(new ServerNameCheck());
        ServerConnector connector = new ServerConnector(server,
                new SslConnectionFactory(tls, HttpVersion.HTTP_1_1.asString()), new HttpConnectionFactory(https));
        connector.setHost(ipv4Of(config.host()));
        connector.setPort(config.port());
        connector.setIdleTimeout(NETWORK_IDLE_TIMEOUT.toMillis());
        server.addConnector(connector);
        server.addBean(new NetworkConnectionLimit(MAX_NETWORK_CONNECTIONS, connector));
        return new Network(connector, certificate.addressName(), tls);
    }

    /** The address to open in a browser on this machine, such as {@code http://127.0.0.1:8460/}. */
    public URI address() {
        return guardFor(loopback).address();
    }

    /** The address for other devices, such as {@code https://laptop.ts.net:8460/}; none without {@code --host}. */
    public Optional<URI> networkAddress() {
        return Optional.ofNullable(network).map(n -> guardFor(n.connector()).address());
    }

    /** How long a connection to the network address may stay silent; none without {@code --host}. */
    Optional<Duration> networkIdleTimeout() {
        return Optional.ofNullable(network).map(n -> Duration.ofMillis(n.connector().getIdleTimeout()));
    }

    /** The TLS context of the network address, for reloading a renewed certificate. */
    Optional<SslContextFactory.Server> networkTls() {
        return Optional.ofNullable(network).map(Network::tls);
    }

    /** Runs {@code action} once the server has stopped, also when it is stopped by Ctrl+C. */
    void whenStopped(Runnable action) {
        server.addEventListener(new LifeCycle.Listener() {
            @Override
            public void lifeCycleStopped(LifeCycle event) {
                action.run();
            }
        });
    }

    public void stop() throws Exception {
        server.stop();
    }

    /** Waits until the server stops. */
    public void join() throws InterruptedException {
        server.join();
    }

    /** The guard of the connector a request arrived on; the port is the one actually bound. */
    private OriginGuard guardFor(Connector connector) {
        return siteFor(connector).guard();
    }

    /** The connector a request arrived on as pairing sees it; the port is the one actually bound. */
    private Site siteFor(Connector connector) {
        if (network != null && connector == network.connector()) {
            int port = network.connector().getLocalPort();
            return new Site(new OriginGuard("https", network.name(), port), Listener.NETWORK, true, port);
        }
        int port = loopback.getLocalPort();
        return new Site(new OriginGuard("http", ServiceConfig.DEFAULT_HOST, port), Listener.LOOPBACK, false, port);
    }

    /**
     * Accepts the handshake only from the page this connector serves, 403 otherwise, and only with a key
     * paired on this connector, 401 otherwise. The key check and the registration of the connection hold
     * the registry's monitor, so a revocation sees either no connection or one it can close.
     */
    private Object createWebSocket(ServerUpgradeRequest request, ServerUpgradeResponse response,
            Callback callback) {
        Site site = siteFor(request.getConnectionMetaData().getConnector());
        String address = Request.getRemoteAddr(request);
        String origin = request.getHeaders().get(HttpHeader.ORIGIN);
        String host = request.getHeaders().get(HttpHeader.HOST);
        if (!site.guard().allows(origin, host)) {
            refusals.warn(address, "WebSocket refused: Origin={0}, Host={1}", origin, host);
            Response.writeError(request, response, callback, 403);
            return null;
        }
        Endpoint endpoint = null;
        Device device = null;
        synchronized (devices) {
            Optional<KeyedDevice> found = PairingHandler.deviceOf(request, site, devices);
            if (found.isPresent()) {
                device = found.get().device();
                recordLogin(device, address, request.getHeaders().get(HttpHeader.USER_AGENT));
                endpoint = new Endpoint(adapter, connections, device.id());
                connections.register(device.id(), endpoint);
            }
        }
        if (endpoint == null) {
            refusals.warn(address, "WebSocket refused: no valid device key");
            Response.writeError(request, response, callback, 401);
            return null;
        }
        LOG.log(Level.INFO, "Device connected: {0} from {1}", LogText.safe(device.name()), LogText.safe(address));
        return endpoint;
    }

    /** The last login is for the owner to read; a disk that fails to keep it does not cost the connection. */
    private void recordLogin(Device device, String address, String userAgent) {
        try {
            devices.recordLogin(device.id(), address, userAgent);
        } catch (IOException e) {
            // The cause names the file, never the key.
            LOG.log(Level.WARNING, "Could not record the login of " + LogText.safe(device.name()) + " from "
                    + LogText.safe(address), e);
        }
    }

    /**
     * Adds the security headers of the client page to every answer of the handler it wraps, a 404 for a
     * missing file included. The policy allows nothing inline, so the built client must have no inline
     * script or style.
     */
    static final class SecurityHeaders extends Handler.Wrapper {

        private static final List<HttpField> HEADERS = List.of(
                new PreEncodedHttpField("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                        + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
                        + "form-action 'self'"),
                new PreEncodedHttpField("X-Content-Type-Options", "nosniff"),
                new PreEncodedHttpField("Referrer-Policy", "no-referrer"),
                new PreEncodedHttpField("Cross-Origin-Opener-Policy", "same-origin"));

        SecurityHeaders(Handler handler) {
            super(handler);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            // Set before the wrapped handler runs: the 404 the server writes after it keeps them.
            HEADERS.forEach(response.getHeaders()::put);
            return super.handle(request, response, callback);
        }
    }

    /**
     * Jetty's listener for one accepted WebSocket; hands frames to a {@link ClientConnection}. Public
     * because Jetty calls the listener methods only on a public class.
     */
    public static final class Endpoint implements Session.Listener.AutoDemanding, ConnectionRegistry.Connection {

        /** A close asked for before the connection opened. */
        private record PendingClose(int statusCode, String reason) {}

        private final AgentAdapter adapter;
        private final ConnectionRegistry connections;
        private final String deviceId;
        /** Null until open; guarded by {@code this}, like {@link #pendingClose}. */
        private Session session;
        private PendingClose pendingClose;
        private ClientConnection connection;

        Endpoint(AgentAdapter adapter, ConnectionRegistry connections, String deviceId) {
            this.adapter = adapter;
            this.connections = connections;
            this.deviceId = deviceId;
        }

        @Override
        public void onWebSocketOpen(Session session) {
            synchronized (this) {
                this.session = session;
                if (pendingClose != null) {
                    // Closed before it opened, as by a revocation right after the key check: no conversation.
                    session.close(pendingClose.statusCode(), pendingClose.reason(),
                            org.eclipse.jetty.websocket.api.Callback.NOOP);
                    return;
                }
            }
            connection = new ClientConnection(adapter, text -> session.sendText(text,
                    org.eclipse.jetty.websocket.api.Callback.from(() -> {}, Endpoint::logSendFailure)));
            connection.open();
        }

        @Override
        public void close(int statusCode, String reason) {
            Session open;
            synchronized (this) {
                if (session == null) {
                    pendingClose = new PendingClose(statusCode, reason);
                    return;
                }
                open = session;
            }
            open.close(statusCode, reason, org.eclipse.jetty.websocket.api.Callback.NOOP);
        }

        @Override
        public void onWebSocketText(String text) {
            connection.receive(text);
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            if (isClientGone(cause)) {
                LOG.log(Level.INFO, "WebSocket client disconnected: {0}", LogText.safe(cause.toString()));
            } else {
                LOG.log(Level.WARNING, "WebSocket error", cause);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason, org.eclipse.jetty.websocket.api.Callback callback) {
            connections.unregister(deviceId, this);
            // The reason comes from the client.
            LOG.log(Level.INFO, "WebSocket closed: {0} {1}", String.valueOf(statusCode), LogText.safe(reason));
            callback.succeed();
        }

        /** A client that left mid-send is routine: one line, no stack trace. */
        private static void logSendFailure(Throwable failure) {
            if (isClientGone(failure)) {
                LOG.log(Level.INFO, "WebSocket send skipped, the client has disconnected: {0}",
                        LogText.safe(failure.toString()));
            } else {
                LOG.log(Level.WARNING, "WebSocket send failed", failure);
            }
        }

        /** Jetty marks errors of a closed peer as {@link QuietException}. */
        static boolean isClientGone(Throwable failure) {
            return failure instanceof QuietException || failure instanceof ClosedChannelException;
        }
    }
}
