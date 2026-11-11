package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.artemdbondarenko.crawdad.protocol.ProtocolCodec;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageDelta;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageEnd;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.SessionStarted;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.UserMessage;
import io.github.artemdbondarenko.crawdad.service.adapter.AgentAdapter;
import io.github.artemdbondarenko.crawdad.service.adapter.AgentEvent;
import io.github.artemdbondarenko.crawdad.service.adapter.FakeAgentAdapter;
import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry;
import io.github.artemdbondarenko.crawdad.service.device.Device;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry.RevokeResult;
import io.github.artemdbondarenko.crawdad.service.device.Listener;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpCookie;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Revoking a device on a real server: its open WebSockets close with 4001 {@code unpaired}, those of
 * other devices keep working, and its key no longer opens {@code /ws} or {@code /device}. Includes a
 * handshake caught between the key check and the revocation.
 */
class RevokeTest {

    @TempDir
    Path temp;

    private final CountingAdapter adapter = new CountingAdapter();
    private final WebSocketClient webSocketClient = new WebSocketClient();
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler collect = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    // Held in fields: java.util.logging keeps loggers only weakly.
    private final Logger registryLog = Logger.getLogger(DeviceRegistry.class.getName());
    private final Logger serverLog = Logger.getLogger(CrawdadServer.class.getName());

    private DataDirectory data;
    private DeviceRegistry devices;
    private CrawdadServer server;
    private String laptopKey;
    private String phoneKey;

    @BeforeEach
    void start() throws Exception {
        registryLog.addHandler(collect);
        data = new DataDirectory(temp.resolve("data"), temp.resolve("run"));
        devices = DeviceRegistry.open(data, Clock.systemUTC());
        laptopKey = devices.add("laptop", Listener.LOOPBACK).key();
        phoneKey = devices.add("phone", Listener.LOOPBACK).key();
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/", adapter, Optional.empty(),
                devices, connections, new PairingCodes());
        webSocketClient.start();
    }

    @AfterEach
    void stop() throws Exception {
        registryLog.removeHandler(collect);
        webSocketClient.stop();
        server.stop();
    }

    @Test
    void revokingClosesEveryConnectionOfTheDeviceAndLeavesOtherDevicesWorking() throws Exception {
        // The laptop is open in two tabs, the phone in one; each has its conversation.
        Inbox firstTab = connect(laptopKey);
        Inbox secondTab = connect(laptopKey);
        Inbox phone = connect(phoneKey);
        assertThat(firstTab.next()).isInstanceOf(SessionStarted.class);
        assertThat(secondTab.next()).isInstanceOf(SessionStarted.class);
        String phoneSession = phone.next().sessionId();

        // The owner revokes the laptop.
        RevokeResult result = devices.revoke("laptop", connections);

        // Both tabs are closed with the code that tells the client not to reconnect.
        assertThat(result).isInstanceOfSatisfying(RevokeResult.Revoked.class,
                revoked -> assertThat(revoked.device().name()).isEqualTo("laptop"));
        assertThat(firstTab.closed.get(5, TimeUnit.SECONDS)).isEqualTo("4001 unpaired");
        assertThat(secondTab.closed.get(5, TimeUnit.SECONDS)).isEqualTo("4001 unpaired");
        assertThat(infoLines()).containsExactly("Device revoked: laptop, closed 2 connections");
        awaitTrue(() -> connections.connectionsOf(device("laptop").id()).isEmpty());

        // The phone's connection stays open and still answers.
        phone.send(new UserMessage(phoneSession, "still here"));
        assertThat(phone.answerText()).isEqualTo("You said: still here");
        assertThat(phone.closed).isNotDone();
    }

    @Test
    void afterRevocationTheOldKeyIsUnauthorizedOnWsAndDevice() throws Exception {
        String cookie = cookieName() + "=" + laptopKey;
        // The key works before the revocation.
        assertThat(deviceStatus(cookie)).isEqualTo("HTTP/1.1 200 OK");

        devices.revoke("laptop", connections);

        assertThat(handshake(cookie)).isEqualTo("HTTP/1.1 401 Unauthorized");
        assertThat(deviceStatus(cookie)).isEqualTo("HTTP/1.1 401 Unauthorized");
    }

    @Test
    void anUnknownOrAlreadyRevokedNameClosesNothing() throws Exception {
        Inbox laptop = connect(laptopKey);
        String laptopSession = laptop.next().sessionId();
        devices.revoke("phone", connections);
        records.clear();

        assertThat(devices.revoke("watch", connections))
                .isEqualTo(new RevokeResult.NotFound(List.of("laptop")));
        assertThat(devices.revoke("phone", connections)).isInstanceOf(RevokeResult.AlreadyRevoked.class);

        assertThat(infoLines()).isEmpty();
        laptop.send(new UserMessage(laptopSession, "still here"));
        assertThat(laptop.answerText()).isEqualTo("You said: still here");
        assertThat(laptop.closed).isNotDone();
        assertThat(connections.connectionsOf(device("laptop").id())).hasSize(1);
    }

    @Test
    void aRevocationThatCannotBeSavedClosesNothingAndKeepsTheDevice() throws Exception {
        Inbox laptop = connect(laptopKey);
        String laptopSession = laptop.next().sessionId();
        // The registry writes next to devices.json; without its directory every write fails.
        Files.move(data.data(), temp.resolve("moved"));

        assertThatThrownBy(() -> devices.revoke("laptop", connections)).isInstanceOf(IOException.class);

        assertThat(infoLines()).isEmpty();
        assertThat(devices.devices(false)).extracting(Device::name).containsExactly("laptop", "phone");
        assertThat(devices.authenticate(laptopKey, Listener.LOOPBACK)).isPresent();
        laptop.send(new UserMessage(laptopSession, "still here"));
        assertThat(laptop.answerText()).isEqualTo("You said: still here");
        assertThat(laptop.closed).isNotDone();
    }

    @Test
    void aHandshakeAdmittedJustBeforeTheRevocationClosesAsSoonAsItOpens() throws Exception {
        // The server announces an admitted device after the key check and the registration, before the 101.
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Handler gate = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (String.valueOf(record.getMessage()).startsWith("Device connected")) {
                    admitted.countDown();
                    awaitQuietly(release);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        serverLog.addHandler(gate);
        try {
            CompletableFuture<String> outcome = CompletableFuture.supplyAsync(() -> handshakeAndClose(laptopKey));
            assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();

            // The revocation runs while the admitted connection has not opened yet.
            assertThat(devices.revoke("laptop", connections)).isInstanceOf(RevokeResult.Revoked.class);
            assertThat(infoLines()).containsExactly("Device revoked: laptop, closed 1 connections");
            release.countDown();

            // The upgrade completes and the connection closes at once, without a conversation.
            assertThat(outcome.get(5, TimeUnit.SECONDS))
                    .isEqualTo("HTTP/1.1 101 Switching Protocols, closed 4001 unpaired");
            assertThat(adapter.calls.get()).isZero();
            awaitTrue(() -> connections.connectionsOf(device("laptop").id()).isEmpty());
        } finally {
            release.countDown();
            serverLog.removeHandler(gate);
        }
    }

    @Test
    void aHandshakeRacingTheRevocationIsClosedOrRefusedButNeverLeftOpen() throws Exception {
        for (int round = 0; round < 5; round++) {
            String name = "tablet-" + round;
            String key = devices.add(name, Listener.LOOPBACK).key();
            CompletableFuture<String> outcome;
            Thread revoker = new Thread(() -> revokeQuietly(name));
            // Holding the registry's monitor lines up the handshake and the revocation at its door.
            synchronized (devices) {
                outcome = CompletableFuture.supplyAsync(() -> handshakeAndClose(key));
                awaitTrue(() -> threadBlockedIn("createWebSocket"));
                revoker.start();
                awaitTrue(() -> revoker.getState() == Thread.State.BLOCKED);
            }
            revoker.join(5000);

            assertThat(outcome.get(5, TimeUnit.SECONDS)).as(name).isIn(
                    "HTTP/1.1 401 Unauthorized", "HTTP/1.1 101 Switching Protocols, closed 4001 unpaired");
            assertThat(device(name).active()).isFalse();
            awaitTrue(() -> connections.connectionsOf(device(name).id()).isEmpty());
        }
    }

    private Device device(String name) {
        return devices.devices(true).stream().filter(device -> device.name().equals(name)).findFirst().orElseThrow();
    }

    private void revokeQuietly(String name) {
        try {
            devices.revoke(name, connections);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean threadBlockedIn(String method) {
        return Thread.getAllStackTraces().entrySet().stream()
                .anyMatch(entry -> entry.getKey().getState() == Thread.State.BLOCKED
                        && Arrays.stream(entry.getValue()).anyMatch(frame -> frame.getMethodName().equals(method)));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String cookieName() {
        return "crawdad_device_" + server.address().getPort();
    }

    private Inbox connect(String key) throws Exception {
        String authority = server.address().getAuthority();
        Inbox inbox = new Inbox();
        ClientUpgradeRequest request = new ClientUpgradeRequest(
                URI.create("ws://" + authority + CrawdadServer.WEBSOCKET_PATH));
        request.setHeader("Origin", "http://" + authority);
        request.setCookies(List.of(new HttpCookie(cookieName(), key)));
        inbox.session = webSocketClient.connect(inbox, request).get(5, TimeUnit.SECONDS);
        return inbox;
    }

    /** The status line of a {@code GET /device} with this {@code Cookie} header. */
    private String deviceStatus(String cookie) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            socket.setSoTimeout(5000);
            String request = "GET /device HTTP/1.1\r\n"
                    + "Host: " + server.address().getAuthority() + "\r\n"
                    + "Cookie: " + cookie + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return readLine(socket.getInputStream());
        }
    }

    /** The status line of a {@code /ws} handshake from the loopback page with this {@code Cookie} header. */
    private String handshake(String cookie) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            return sendHandshake(socket, cookie);
        }
    }

    /**
     * The status line of a handshake with the key and, if it was upgraded, the close frame that ended the
     * connection; any text frame before it is skipped.
     */
    private String handshakeAndClose(String key) {
        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            String status = sendHandshake(socket, cookieName() + "=" + key);
            if (!status.startsWith("HTTP/1.1 101")) {
                return status;
            }
            InputStream in = socket.getInputStream();
            while (!readLine(in).isEmpty()) {
                // The rest of the upgrade headers.
            }
            DataInputStream frames = new DataInputStream(in);
            while (true) {
                int opcode = frames.readUnsignedByte() & 0x0F;
                int length = frames.readUnsignedByte() & 0x7F;
                if (length == 126) {
                    length = frames.readUnsignedShort();
                }
                byte[] payload = frames.readNBytes(length);
                if (opcode == 0x8) {
                    int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                    return status + ", closed " + code + " "
                            + new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private String sendHandshake(Socket socket, String cookie) throws IOException {
        socket.setSoTimeout(5000);
        String authority = server.address().getAuthority();
        String request = "GET " + CrawdadServer.WEBSOCKET_PATH + " HTTP/1.1\r\n"
                + "Host: " + authority + "\r\n"
                + "Origin: http://" + authority + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Cookie: " + cookie + "\r\n"
                + "\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return readLine(socket.getInputStream());
    }

    /** One CRLF-terminated line, read byte by byte so that the frames after the headers stay in the stream. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b = in.read(); b != '\n'; b = in.read()) {
            if (b < 0) {
                throw new IOException("The connection closed mid-line");
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        Instant end = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("the condition within 5 s").isBefore(end);
            Thread.sleep(10);
        }
    }

    private List<String> infoLines() {
        SimpleFormatter formatter = new SimpleFormatter();
        return records.stream().filter(record -> record.getLevel() == Level.INFO).map(formatter::formatMessage)
                .toList();
    }

    /** The test's end of the WebSocket: collects messages and how it was closed. Public because Jetty requires it. */
    public static final class Inbox implements Session.Listener.AutoDemanding {

        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<String> closed = new CompletableFuture<>();
        Session session;

        @Override
        public void onWebSocketText(String text) {
            frames.add(text);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason, Callback callback) {
            closed.complete(statusCode + " " + reason);
            callback.succeed();
        }

        /** The server closing the connection is what some tests expect; nothing to report. */
        @Override
        public void onWebSocketError(Throwable cause) {}

        void send(ProtocolMessage message) {
            session.sendText(ProtocolCodec.write(message), Callback.NOOP);
        }

        ProtocolMessage next() throws InterruptedException {
            String frame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("a message from the service within 5 s").isNotNull();
            return ProtocolCodec.read(frame);
        }

        /** The text of one whole answer, from its start up to and including its end. */
        String answerText() throws InterruptedException {
            StringBuilder text = new StringBuilder();
            for (ProtocolMessage message = next(); !(message instanceof AgentMessageEnd); message = next()) {
                if (message instanceof AgentMessageDelta delta) {
                    text.append(delta.text());
                }
            }
            return text.toString();
        }
    }

    /** The real fake adapter, counting how often a session was started or a message sent. */
    private static final class CountingAdapter implements AgentAdapter {

        private final AgentAdapter delegate = new FakeAgentAdapter();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String startSession() {
            calls.incrementAndGet();
            return delegate.startSession();
        }

        @Override
        public void sendMessage(String sessionId, String text, Consumer<AgentEvent> listener) {
            calls.incrementAndGet();
            delegate.sendMessage(sessionId, text, listener);
        }
    }
}
