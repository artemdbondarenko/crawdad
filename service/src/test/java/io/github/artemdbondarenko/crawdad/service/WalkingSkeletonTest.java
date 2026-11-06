package io.github.artemdbondarenko.crawdad.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.artemdbondarenko.crawdad.protocol.ProtocolCodec;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageDelta;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageEnd;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageStart;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.SessionStarted;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.UserMessage;
import io.github.artemdbondarenko.crawdad.service.adapter.AgentAdapter;
import io.github.artemdbondarenko.crawdad.service.adapter.AgentEvent;
import io.github.artemdbondarenko.crawdad.service.adapter.FakeAgentAdapter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The end-to-end path with a real server, a real {@link FakeAgentAdapter} and a real WebSocket, as
 * scenarios E1–E3 describe it. No browser takes part: the page is fetched over HTTP and the
 * handshake is made by a test client with the headers a browser would send.
 */
class WalkingSkeletonTest {

    private final CountingAdapter adapter = new CountingAdapter(new FakeAgentAdapter());
    private final WebSocketClient webSocketClient = new WebSocketClient();
    private CrawdadServer server;
    private String authority;

    @BeforeEach
    void start() throws Exception {
        server = CrawdadServer.start(new ServiceConfig("127.0.0.1", 0), "static/", adapter);
        authority = server.address().getAuthority();
        webSocketClient.start();
    }

    @AfterEach
    void stop() throws Exception {
        webSocketClient.stop();
        server.stop();
    }

    @Test
    void e1ClientPageAndWebSocketOnOnePort() throws Exception {
        // The owner opens the printed address: the service serves the client page from its own port.
        HttpResponse<String> page = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(server.address()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);

        // The page connects to /ws on the same address; the service opens a session and says so.
        Inbox inbox = connect();
        assertThat(inbox.next()).isInstanceOf(SessionStarted.class);
    }

    @Test
    void e2MessageGetsAStreamedAnswer() throws Exception {
        Inbox inbox = connect();
        String sessionId = inbox.next().sessionId();

        // The owner sends a text; the answer arrives as start, several parts and end.
        inbox.send(new UserMessage(sessionId, "hello walking skeleton"));
        List<ProtocolMessage> first = inbox.answer();
        assertThat(first.getFirst()).isInstanceOf(AgentMessageStart.class);
        assertThat(first.getLast()).isInstanceOf(AgentMessageEnd.class);
        assertThat(first.subList(1, first.size() - 1))
                .hasSizeGreaterThanOrEqualTo(2)
                .allMatch(AgentMessageDelta.class::isInstance);

        // Every event belongs to the conversation and to one answer.
        assertThat(first).extracting(ProtocolMessage::sessionId).containsOnly(sessionId);
        assertThat(first).extracting(WalkingSkeletonTest::messageId).containsOnly(messageId(first.getFirst()));

        // The parts joined give exactly the stub's answer to this text.
        assertThat(text(first)).isEqualTo("You said: hello walking skeleton");

        // The client is ready again: a second text in the same connection gets its own answer.
        inbox.send(new UserMessage(sessionId, "second"));
        List<ProtocolMessage> second = inbox.answer();
        assertThat(messageId(second.getFirst())).isNotEqualTo(messageId(first.getFirst()));
        assertThat(text(second)).isEqualTo("You said: second");
    }

    @Test
    void e3ForeignPageIsRefused() throws Exception {
        // A page from another origin tries to open the WebSocket: refused before the upgrade.
        assertThat(handshake(authority, "http://evil.example")).startsWith("HTTP/1.1 403");

        // A request naming another host is refused the same way.
        assertThat(handshake("evil.example", "http://" + authority)).startsWith("HTTP/1.1 403");

        // No session was opened and nothing reached the adapter.
        assertThat(adapter.calls.get()).isZero();
    }

    @Test
    void aTooLargeMessageClosesTheConnection() throws Exception {
        Inbox inbox = connect();
        inbox.next();

        // A frame one byte over the limit: the service closes the connection instead of reading it.
        inbox.session.sendText("x".repeat(CrawdadServer.MAX_TEXT_MESSAGE_SIZE + 1), Callback.NOOP);

        assertThat(inbox.closed.get(5, TimeUnit.SECONDS)).isEqualTo(StatusCode.MESSAGE_TOO_LARGE);
        // The adapter only opened the session; the message never reached it.
        assertThat(adapter.calls.get()).isEqualTo(1);
    }

    private Inbox connect() throws Exception {
        Inbox inbox = new Inbox();
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setHeader("Origin", "http://" + authority);
        URI uri = URI.create("ws://" + authority + CrawdadServer.WEBSOCKET_PATH);
        inbox.session = webSocketClient.connect(inbox, uri, request).get(5, TimeUnit.SECONDS);
        return inbox;
    }

    /** Sends a raw WebSocket handshake to the server and returns the status line of the answer. */
    private String handshake(String host, String origin) throws IOException {
        URI address = server.address();
        try (Socket socket = new Socket(address.getHost(), address.getPort())) {
            socket.setSoTimeout(5000);
            String request = "GET " + CrawdadServer.WEBSOCKET_PATH + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Origin: " + origin + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
        }
    }

    private static String messageId(ProtocolMessage message) {
        return switch (message) {
            case AgentMessageStart start -> start.messageId();
            case AgentMessageDelta delta -> delta.messageId();
            case AgentMessageEnd end -> end.messageId();
            default -> throw new AssertionError("Not a part of an answer: " + message);
        };
    }

    private static String text(List<ProtocolMessage> answer) {
        StringBuilder text = new StringBuilder();
        answer.stream().filter(AgentMessageDelta.class::isInstance)
                .forEach(delta -> text.append(((AgentMessageDelta) delta).text()));
        return text.toString();
    }

    /** The test's end of the WebSocket: collects incoming messages. Public because Jetty requires it. */
    public static final class Inbox implements Session.Listener.AutoDemanding {

        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        Session session;

        @Override
        public void onWebSocketText(String text) {
            frames.add(text);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            closed.complete(statusCode);
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

        /** Reads one whole answer, from its start up to and including its end. */
        List<ProtocolMessage> answer() throws InterruptedException {
            List<ProtocolMessage> answer = new ArrayList<>();
            do {
                answer.add(next());
            } while (!(answer.getLast() instanceof AgentMessageEnd));
            return answer;
        }
    }

    /** Passes everything to the real adapter and counts how often it was called. */
    private static final class CountingAdapter implements AgentAdapter {

        private final AgentAdapter delegate;
        final AtomicInteger calls = new AtomicInteger();

        CountingAdapter(AgentAdapter delegate) {
            this.delegate = delegate;
        }

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
