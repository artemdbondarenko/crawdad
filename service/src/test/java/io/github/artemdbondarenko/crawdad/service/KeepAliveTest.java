package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.artemdbondarenko.crawdad.protocol.ProtocolCodec;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageStart;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.SessionStarted;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.UserMessage;
import io.github.artemdbondarenko.crawdad.service.CrawdadServer.Heartbeat;
import io.github.artemdbondarenko.crawdad.service.adapter.FakeAgentAdapter;
import io.github.artemdbondarenko.crawdad.service.device.ConnectionRegistry;
import io.github.artemdbondarenko.crawdad.service.device.DeviceRegistry;
import io.github.artemdbondarenko.crawdad.service.pairing.PairingCodes;
import io.github.artemdbondarenko.crawdad.service.storage.DataDirectory;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A conversation the owner pauses: the service pings the client and keeps the connection while the client
 * answers, and closes a connection the client has gone silent on (S-MSG-3). The intervals are shortened from
 * 30 and 90 seconds so that the tests take a second each.
 */
class KeepAliveTest {

    private static final Heartbeat SHORT = new Heartbeat(Duration.ofMillis(100), Duration.ofMillis(600));

    @TempDir
    Path temp;

    private final WebSocketClient webSocketClient = new WebSocketClient();
    private final PairingCodes codes = new PairingCodes();
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private CrawdadServer server;
    private DeviceRegistry devices;

    @AfterEach
    void stop() throws Exception {
        webSocketClient.stop();
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @Tag("MSG-12")
    @Tag("MSG-13")
    void keepsWebSocketsAliveWithThe30And90SecondsOfTheSpecification() throws Exception {
        server = start(Optional.empty());

        assertThat(server.heartbeat()).isEqualTo(new Heartbeat(Duration.ofSeconds(30), Duration.ofSeconds(90)));
        // Jetty's own timeout must not close a connection before the service's own close does.
        assertThat(server.webSocketIdleTimeout()).isGreaterThan(Duration.ofSeconds(90));
    }

    @Test
    @Tag("MSG-12")
    void aSilentConversationStaysOpenWhileTheClientAnswersThePings() throws Exception {
        server = start(Optional.of(SHORT));
        Client client = connect(new Client(true));
        String sessionId = client.next().sessionId();

        // The owner sends nothing for three times as long as a silent connection may last.
        Thread.sleep(SHORT.idleTimeout().multipliedBy(3).toMillis());

        assertThat(client.closed).isNotDone();
        assertThat(client.pings.get()).isGreaterThanOrEqualTo(10);
        // The next text gets an answer in the same session.
        client.send(new UserMessage(sessionId, "still here"));
        assertThat(client.next()).isInstanceOf(AgentMessageStart.class).extracting(ProtocolMessage::sessionId)
                .isEqualTo(sessionId);
    }

    @Test
    @Tag("MSG-12")
    @Tag("MSG-13")
    void aClientThatStopsAnsweringIsClosedAfterTheIdleTimeout() throws Exception {
        server = start(Optional.of(SHORT));
        long connected = System.nanoTime();
        // A phone that left the network: pings reach nothing that answers.
        Client client = connect(new Client(false));
        assertThat(client.next()).isInstanceOf(SessionStarted.class);

        // Closed by the service's own check, not by Jetty's backstop, which would close later with another reason.
        assertThat(client.closed.get(5, TimeUnit.SECONDS)).isEqualTo("1001 idle");

        Duration open = Duration.ofNanos(System.nanoTime() - connected);
        assertThat(open).isGreaterThanOrEqualTo(SHORT.idleTimeout()).isLessThan(SHORT.jettyIdleTimeout());
        assertThat(client.pings.get()).isGreaterThanOrEqualTo(3);
        // The closed connection leaves the registry: revoking the device later has nothing to close.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!connections.connectionsOf(onlyDevice()).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(connections.connectionsOf(onlyDevice())).isEmpty();
    }

    @Test
    @Tag("MSG-13")
    void messagesAloneKeepAConnectionOpen() throws Exception {
        server = start(Optional.of(SHORT));
        Client client = connect(new Client(false));
        String sessionId = client.next().sessionId();

        // No pongs, but a text more often than the idle timeout: the connection carries traffic.
        for (int i = 0; i < 4; i++) {
            Thread.sleep(SHORT.idleTimeout().dividedBy(2).toMillis());
            client.send(new UserMessage(sessionId, "text " + i));
        }

        assertThat(client.closed).isNotDone();
    }

    @Test
    void refusesPingsTooRareToKeepAConnectionAlive() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new Heartbeat(Duration.ofSeconds(90), Duration.ofSeconds(90)));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new Heartbeat(Duration.ZERO, Duration.ofSeconds(90)));
    }

    private String onlyDevice() {
        return devices.devices(true).getFirst().id();
    }

    private CrawdadServer start(Optional<Heartbeat> heartbeat) throws Exception {
        devices = DeviceRegistry.open(new DataDirectory(temp.resolve("data"), temp.resolve("run")),
                Clock.systemUTC());
        ServiceConfig config = new ServiceConfig("127.0.0.1", 0);
        CrawdadServer started = heartbeat.isPresent()
                ? CrawdadServer.start(config, "static/", new FakeAgentAdapter(), Optional.empty(), devices,
                        connections, codes, heartbeat.get())
                : CrawdadServer.start(config, "static/", new FakeAgentAdapter(), Optional.empty(), devices,
                        connections, codes);
        webSocketClient.start();
        return started;
    }

    private Client connect(Client client) throws Exception {
        String authority = server.address().getAuthority();
        HttpResponse<String> paired = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(server.address().resolve("/pair"))
                        .header("Origin", "http://" + authority)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"code\":\"" + codes.issue(null).code() + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(paired.statusCode()).isEqualTo(204);
        HttpCookie key = HttpCookie.parse(paired.headers().firstValue("Set-Cookie").orElseThrow()).getFirst();

        ClientUpgradeRequest request = new ClientUpgradeRequest(
                URI.create("ws://" + authority + CrawdadServer.WEBSOCKET_PATH));
        request.setHeader("Origin", "http://" + authority);
        request.setCookies(List.of(new HttpCookie(key.getName(), key.getValue())));
        client.session = webSocketClient.connect(client, request).get(5, TimeUnit.SECONDS);
        return client;
    }

    /**
     * The test's end of the WebSocket: counts the service's pings and answers them only if told to, as a
     * browser does or as a phone that has left the network does not. Public because Jetty requires it.
     */
    public static final class Client implements Session.Listener.AutoDemanding {

        private final boolean answersPings;
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final AtomicInteger pings = new AtomicInteger();
        /** The status code and reason the service closed with; -1 and none if it dropped the connection. */
        final CompletableFuture<String> closed = new CompletableFuture<>();
        Session session;

        Client(boolean answersPings) {
            this.answersPings = answersPings;
        }

        @Override
        public void onWebSocketText(String text) {
            frames.add(text);
        }

        /** Overriding this turns off the automatic pong of Jetty's client. */
        @Override
        public void onWebSocketPing(ByteBuffer payload) {
            pings.incrementAndGet();
            if (answersPings) {
                session.sendPong(payload, Callback.NOOP);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason, Callback callback) {
            closed.complete(statusCode + " " + reason);
            callback.succeed();
        }

        /** The service dropping the connection is what some tests expect; nothing to report. */
        @Override
        public void onWebSocketError(Throwable cause) {
            closed.complete("-1");
        }

        void send(ProtocolMessage message) {
            session.sendText(ProtocolCodec.write(message), Callback.NOOP);
        }

        ProtocolMessage next() throws InterruptedException {
            String frame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("a message from the service within 5 s").isNotNull();
            return ProtocolCodec.read(frame);
        }
    }
}
