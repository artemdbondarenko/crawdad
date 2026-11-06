package io.github.artemdbondarenko.crawdad.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks the messages described in the protocol schema: what it accepts and what it rejects. */
class ProtocolSchemaTest {

    private static final Path PROTOCOL_SCHEMA = Path.of("schema", "crawdad-protocol.schema.json");

    /** Message kinds of the protocol, keyed by their name in {@code $defs}. */
    private static final Map<String, String> MESSAGE_TYPES = Map.of(
            "SessionStarted", "session.started",
            "UserMessage", "user.message",
            "AgentMessageStart", "agent.message.start",
            "AgentMessageDelta", "agent.message.delta",
            "AgentMessageEnd", "agent.message.end",
            "Error", "error");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode schemaJson;
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        String text = Files.readString(PROTOCOL_SCHEMA);
        schemaJson = JSON.readTree(text);
        schema = SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(text, InputFormat.JSON);
    }

    @Test
    void definesExactlyTheMessagesOfTheContract() {
        Map<String, String> defined = new HashMap<>();
        messageDefinitions().forEach((name, definition) ->
                defined.put(name, definition.at("/properties/type/const").asString()));

        assertThat(defined).isEqualTo(MESSAGE_TYPES);
    }

    @Test
    void everyMessageHasExamplesAndTheyAreValid() {
        List<String> checked = new ArrayList<>();
        messageDefinitions().forEach((name, definition) -> {
            JsonNode examples = definition.get("examples");
            assertThat(examples).as("examples of %s", name).isNotNull().isNotEmpty();
            for (JsonNode example : examples) {
                assertThat(validate(example.toString())).as("example %s", example).isEmpty();
                checked.add(example.get("type").asString());
            }
        });

        assertThat(checked).containsAll(MESSAGE_TYPES.values());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // no session_id
        "{\"type\":\"user.message\",\"text\":\"hello\"}",
        // unknown message kind
        "{\"type\":\"user.typing\",\"session_id\":\"s1\"}",
        // a field the message does not have
        "{\"type\":\"session.started\",\"session_id\":\"s1\",\"extra\":true}",
        // a field of the wrong type
        "{\"type\":\"agent.message.delta\",\"session_id\":\"s1\",\"message_id\":\"m1\",\"text\":42}",
        // a response event without message_id
        "{\"type\":\"agent.message.end\",\"session_id\":\"s1\"}",
        // an empty session_id
        "{\"type\":\"session.started\",\"session_id\":\"\"}",
        // no type at all
        "{\"session_id\":\"s1\"}"
    })
    void rejectsMessagesOutsideTheContract(String message) {
        assertThat(validate(message)).isNotEmpty();
    }

    /** The message kinds the root accepts: definitions referenced from its {@code oneOf}, by name. */
    private static Map<String, JsonNode> messageDefinitions() {
        Map<String, JsonNode> definitions = new HashMap<>();
        for (JsonNode ref : schemaJson.get("oneOf")) {
            String name = ref.get("$ref").asString().replace("#/$defs/", "");
            definitions.put(name, schemaJson.get("$defs").get(name));
        }
        return definitions;
    }

    private static List<Error> validate(String message) {
        return schema.validate(message, InputFormat.JSON);
    }
}
