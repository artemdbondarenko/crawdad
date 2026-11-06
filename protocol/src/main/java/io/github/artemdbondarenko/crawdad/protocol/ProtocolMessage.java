package io.github.artemdbondarenko.crawdad.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A message between the service and a client, as described in {@code crawdad-protocol.schema.json}.
 * Each record is one message kind; the JSON {@code type} field selects it. Records check their own
 * fields, so a message that exists in code is always valid against the schema.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ProtocolMessage.SessionStarted.class, name = "session.started"),
    @JsonSubTypes.Type(value = ProtocolMessage.UserMessage.class, name = "user.message"),
    @JsonSubTypes.Type(value = ProtocolMessage.AgentMessageStart.class, name = "agent.message.start"),
    @JsonSubTypes.Type(value = ProtocolMessage.AgentMessageDelta.class, name = "agent.message.delta"),
    @JsonSubTypes.Type(value = ProtocolMessage.AgentMessageEnd.class, name = "agent.message.end"),
    @JsonSubTypes.Type(value = ProtocolMessage.Error.class, name = "error")
})
public sealed interface ProtocolMessage {

    /** The session the message belongs to; one WebSocket connection is one session. */
    String sessionId();

    /** Service to client, right after the connection opens. */
    record SessionStarted(String sessionId) implements ProtocolMessage {
        public SessionStarted {
            requireNotEmpty(sessionId, "session_id");
        }
    }

    /** Client to service: text the user sends to the agent. */
    record UserMessage(String sessionId, String text) implements ProtocolMessage {
        public UserMessage {
            requireNotEmpty(sessionId, "session_id");
            requireNotEmpty(text, "text");
        }
    }

    /** Service to client: the agent starts an answer. */
    record AgentMessageStart(String sessionId, String messageId) implements ProtocolMessage {
        public AgentMessageStart {
            requireNotEmpty(sessionId, "session_id");
            requireNotEmpty(messageId, "message_id");
        }
    }

    /** Service to client: the next part of the answer; a part may be empty. */
    record AgentMessageDelta(String sessionId, String messageId, String text) implements ProtocolMessage {
        public AgentMessageDelta {
            requireNotEmpty(sessionId, "session_id");
            requireNotEmpty(messageId, "message_id");
            requirePresent(text, "text");
        }
    }

    /** Service to client: the answer is complete. */
    record AgentMessageEnd(String sessionId, String messageId) implements ProtocolMessage {
        public AgentMessageEnd {
            requireNotEmpty(sessionId, "session_id");
            requireNotEmpty(messageId, "message_id");
        }
    }

    /** Service to client: the last message from the client was rejected; the connection stays open. */
    record Error(String sessionId, String message) implements ProtocolMessage {
        public Error {
            requireNotEmpty(sessionId, "session_id");
            requireNotEmpty(message, "message");
        }
    }

    private static void requirePresent(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
    }

    private static void requireNotEmpty(String value, String field) {
        requirePresent(value, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty field: " + field);
        }
    }
}
