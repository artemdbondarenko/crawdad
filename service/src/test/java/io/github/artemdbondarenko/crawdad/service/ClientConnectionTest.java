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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ClientConnectionTest {

    private static final String SESSION = "session-1";

    private final StubAdapter adapter = new StubAdapter();
    private final List<String> sent = new ArrayList<>();
    private final ClientConnection connection = new ClientConnection(adapter, sent::add);

    @BeforeEach
    void open() {
        connection.open();
    }

    @Test
    void startsASessionWhenOpened() {
        assertThat(adapter.sessionsStarted).isEqualTo(1);
        assertThat(received()).containsExactly(new SessionStarted(SESSION));
    }

    @Test
    @Tag("MSG-3")
    @Tag("MSG-4")
    @Tag("MSG-6")
    void forwardsTheAgentAnswerInOrder() {
        sent.clear();

        connection.receive(ProtocolCodec.write(new UserMessage(SESSION, "hi there")));

        assertThat(adapter.texts).containsExactly("hi there");
        assertThat(received()).containsExactly(
                new AgentMessageStart(SESSION, "m1"),
                new AgentMessageDelta(SESSION, "m1", "part one"),
                new AgentMessageDelta(SESSION, "m1", " part two"),
                new AgentMessageEnd(SESSION, "m1"));
    }

    @Test
    @Tag("MSG-8")
    void reportsBrokenJsonAndKeepsWorking() {
        sent.clear();

        connection.receive("{not json");

        assertThat(errors()).containsExactly("Not a protocol message");
        assertThat(adapter.texts).isEmpty();
        assertNextMessageIsHandled();
    }

    @Test
    @Tag("MSG-7")
    @Tag("MSG-8")
    void reportsAnUnknownTypeAndKeepsWorking() {
        sent.clear();

        connection.receive("{\"type\":\"user.shout\",\"session_id\":\"" + SESSION + "\"}");

        assertThat(errors()).containsExactly("Unknown message type: user.shout");
        assertThat(adapter.texts).isEmpty();
        assertNextMessageIsHandled();
    }

    @Test
    @Tag("MSG-8")
    void reportsAMessageOfAnotherSessionAndKeepsWorking() {
        sent.clear();

        connection.receive(ProtocolCodec.write(new UserMessage("session-2", "hi")));

        assertThat(errors()).containsExactly("Unknown session: session-2");
        assertThat(adapter.texts).isEmpty();
        assertNextMessageIsHandled();
    }

    @Test
    @Tag("MSG-8")
    void reportsAMessageOnlyTheServiceMaySend() {
        sent.clear();

        connection.receive(ProtocolCodec.write(new AgentMessageEnd(SESSION, "m1")));

        assertThat(errors()).containsExactly("A client may only send user.message");
        assertThat(adapter.texts).isEmpty();
    }

    @Test
    @Tag("MSG-9")
    @Tag("LOG-2")
    void reportsAFailingAgentWithoutItsDetailsAndKeepsWorking() {
        IllegalStateException failure = new IllegalStateException("internal detail");
        adapter.failure = failure;
        sent.clear();
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler collect = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger log = Logger.getLogger(ClientConnection.class.getName());
        log.addHandler(collect);
        try {
            connection.receive(ProtocolCodec.write(new UserMessage(SESSION, "hi")));
        } finally {
            log.removeHandler(collect);
        }

        // The client learns only that the agent failed; the cause goes to the service log.
        assertThat(errors()).containsExactly("The agent could not answer");
        assertThat(sent).noneMatch(frame -> frame.contains("internal detail"));
        assertThat(logged).anySatisfy(record -> assertThat(record.getThrown()).isSameAs(failure));
        adapter.failure = null;
        assertNextMessageIsHandled();
    }

    private void assertNextMessageIsHandled() {
        sent.clear();
        connection.receive(ProtocolCodec.write(new UserMessage(SESSION, "again")));
        assertThat(adapter.texts).containsExactly("again");
        assertThat(received()).first().isEqualTo(new AgentMessageStart(SESSION, "m1"));
    }

    private List<ProtocolMessage> received() {
        return sent.stream().map(ProtocolCodec::read).toList();
    }

    /** The texts of the error messages sent; every message sent must be an error of this session. */
    private List<String> errors() {
        return received().stream().map(message -> {
            assertThat(message).isInstanceOf(ProtocolMessage.Error.class);
            assertThat(message.sessionId()).isEqualTo(SESSION);
            return ((ProtocolMessage.Error) message).message();
        }).toList();
    }

    /** Answers every message with the same two parts, or throws {@link #failure}; remembers what it was asked. */
    private static final class StubAdapter implements AgentAdapter {

        int sessionsStarted;
        final List<String> texts = new ArrayList<>();
        RuntimeException failure;

        @Override
        public String startSession() {
            sessionsStarted++;
            return SESSION;
        }

        @Override
        public void sendMessage(String sessionId, String text, Consumer<AgentEvent> listener) {
            assertThat(sessionId).isEqualTo(SESSION);
            if (failure != null) {
                throw failure;
            }
            texts.add(text);
            listener.accept(new AgentEvent.Start("m1"));
            listener.accept(new AgentEvent.Delta("m1", "part one"));
            listener.accept(new AgentEvent.Delta("m1", " part two"));
            listener.accept(new AgentEvent.End("m1"));
        }
    }
}
