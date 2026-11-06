package io.github.artemdbondarenko.crawdad.service;

import io.github.artemdbondarenko.crawdad.protocol.ProtocolCodec;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolException;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage;
import io.github.artemdbondarenko.crawdad.service.adapter.AgentAdapter;
import io.github.artemdbondarenko.crawdad.service.adapter.AgentEvent;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.function.Consumer;

/**
 * The service side of one WebSocket connection, which is one agent session. Passes the user's
 * messages to the adapter and the agent's answer back as {@code agent.message.*}. A message it
 * cannot accept, or one the agent fails to answer, gets an {@code error} reply; the connection stays open.
 */
final class ClientConnection {

    private static final Logger LOG = System.getLogger(ClientConnection.class.getName());

    private final AgentAdapter adapter;
    private final Consumer<String> send;
    private String sessionId;

    /** {@code send} delivers one text frame to the client. */
    ClientConnection(AgentAdapter adapter, Consumer<String> send) {
        this.adapter = adapter;
        this.send = send;
    }

    /** Starts the session and tells the client its id. */
    void open() {
        sessionId = adapter.startSession();
        send(new ProtocolMessage.SessionStarted(sessionId));
    }

    /** Handles one text frame from the client. */
    void receive(String text) {
        ProtocolMessage message;
        try {
            message = ProtocolCodec.read(text);
        } catch (ProtocolException e) {
            sendError(e.getMessage());
            return;
        }
        if (!(message instanceof ProtocolMessage.UserMessage user)) {
            sendError("A client may only send user.message");
            return;
        }
        if (!sessionId.equals(user.sessionId())) {
            sendError("Unknown session: " + user.sessionId());
            return;
        }
        try {
            adapter.sendMessage(sessionId, user.text(), event -> send(toMessage(event)));
        } catch (RuntimeException e) {
            // The cause stays in the log: it may hold internals the client has no business seeing.
            LOG.log(Level.WARNING, "The agent failed to answer in session " + sessionId, e);
            sendError("The agent could not answer");
        }
    }

    private ProtocolMessage toMessage(AgentEvent event) {
        return switch (event) {
            case AgentEvent.Start start -> new ProtocolMessage.AgentMessageStart(sessionId, start.messageId());
            case AgentEvent.Delta delta ->
                    new ProtocolMessage.AgentMessageDelta(sessionId, delta.messageId(), delta.text());
            case AgentEvent.End end -> new ProtocolMessage.AgentMessageEnd(sessionId, end.messageId());
        };
    }

    private void sendError(String text) {
        send(new ProtocolMessage.Error(sessionId, text));
    }

    private void send(ProtocolMessage message) {
        send.accept(ProtocolCodec.write(message));
    }
}
