package io.github.artemdbondarenko.crawdad.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageDelta;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.UserMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProtocolCodecTest {

    @Test
    @Tag("MSG-2")
    void readsAMessageIntoItsRecord() {
        ProtocolMessage message = ProtocolCodec.read("{\"type\":\"user.message\",\"session_id\":\"s1\",\"text\":\"hello\"}");

        assertThat(message).isEqualTo(new UserMessage("s1", "hello"));
    }

    @Test
    @Tag("MSG-2")
    void writesFieldNamesInSnakeCaseWithTheTypeFirst() {
        String json = ProtocolCodec.write(new AgentMessageDelta("s1", "m1", "You"));

        assertThat(json).isEqualTo(
                "{\"type\":\"agent.message.delta\",\"session_id\":\"s1\",\"message_id\":\"m1\",\"text\":\"You\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // not JSON
        "hello",
        // not an object
        "[1,2]",
        // no type
        "{\"session_id\":\"s1\"}",
        // unknown message kind
        "{\"type\":\"user.typing\",\"session_id\":\"s1\"}",
        // a field the message does not have
        "{\"type\":\"session.started\",\"session_id\":\"s1\",\"extra\":true}",
        // a required field is missing
        "{\"type\":\"user.message\",\"session_id\":\"s1\"}",
        // a required field is empty
        "{\"type\":\"session.started\",\"session_id\":\"\"}",
        // a required field is null
        "{\"type\":\"agent.message.delta\",\"session_id\":\"s1\",\"message_id\":\"m1\",\"text\":null}",
        // a string field holds a number, a boolean or a fraction: the schema wants a string
        "{\"type\":\"user.message\",\"session_id\":\"s1\",\"text\":42}",
        "{\"type\":\"user.message\",\"session_id\":\"s1\",\"text\":true}",
        "{\"type\":\"user.message\",\"session_id\":\"s1\",\"text\":1.5}",
        "{\"type\":\"user.message\",\"session_id\":7,\"text\":\"hello\"}",
        // the same field twice: which one counts would be a guess
        "{\"type\":\"user.message\",\"session_id\":\"s1\",\"text\":\"a\",\"text\":\"b\"}"
    })
    @Tag("MSG-7")
    void rejectsWhatIsNotAProtocolMessage(String json) {
        assertThatThrownBy(() -> ProtocolCodec.read(json))
                .isInstanceOf(ProtocolException.class)
                .hasMessageNotContaining("tools.jackson");
    }

    @Test
    void namesTheUnknownMessageKindInTheError() {
        assertThatThrownBy(() -> ProtocolCodec.read("{\"type\":\"user.typing\",\"session_id\":\"s1\"}"))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("user.typing");
    }
}
