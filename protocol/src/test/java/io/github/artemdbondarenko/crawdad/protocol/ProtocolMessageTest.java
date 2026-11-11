package io.github.artemdbondarenko.crawdad.protocol;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageDelta;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageEnd;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageStart;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.Error;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.SessionStarted;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.UserMessage;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every record checks every one of its fields, the same way the schema does. */
class ProtocolMessageTest {

    /** A required non-empty field: the record built with the given value in that field, valid values elsewhere. */
    static Stream<Arguments> nonEmptyFields() {
        return Stream.of(
                field("session_id", v -> new SessionStarted(v)),
                field("session_id", v -> new UserMessage(v, "hello")),
                field("text", v -> new UserMessage("s1", v)),
                field("session_id", v -> new AgentMessageStart(v, "m1")),
                field("message_id", v -> new AgentMessageStart("s1", v)),
                field("session_id", v -> new AgentMessageDelta(v, "m1", "part")),
                field("message_id", v -> new AgentMessageDelta("s1", v, "part")),
                field("session_id", v -> new AgentMessageEnd(v, "m1")),
                field("message_id", v -> new AgentMessageEnd("s1", v)),
                field("session_id", v -> new Error(v, "bad")),
                field("message", v -> new Error("s1", v)));
    }

    @ParameterizedTest(name = "{0} of {1}")
    @MethodSource("nonEmptyFields")
    @Tag("MSG-7")
    void rejectsAMissingField(String field, String record, Function<String, ProtocolMessage> build) {
        assertThatThrownBy(() -> build.apply(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing field: " + field);
    }

    @ParameterizedTest(name = "{0} of {1}")
    @MethodSource("nonEmptyFields")
    @Tag("MSG-7")
    void rejectsAnEmptyField(String field, String record, Function<String, ProtocolMessage> build) {
        assertThatThrownBy(() -> build.apply(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Empty field: " + field);
    }

    @Test
    @Tag("MSG-7")
    void aDeltaMayBeEmptyButNotMissing() {
        assertThatCode(() -> new AgentMessageDelta("s1", "m1", "")).doesNotThrowAnyException();
        assertThatThrownBy(() -> new AgentMessageDelta("s1", "m1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing field: text");
    }

    private static Arguments field(String field, Function<String, ProtocolMessage> build) {
        String record = build.apply("x").getClass().getSimpleName();
        return Arguments.of(field, record, build);
    }
}
