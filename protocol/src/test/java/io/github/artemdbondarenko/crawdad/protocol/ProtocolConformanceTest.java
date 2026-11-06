package io.github.artemdbondarenko.crawdad.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageDelta;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageEnd;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.AgentMessageStart;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.Error;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.SessionStarted;
import io.github.artemdbondarenko.crawdad.protocol.ProtocolMessage.UserMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks that the hand-written Java messages and the protocol schema describe the same messages. */
class ProtocolConformanceTest {

    /** One instance of every message record. */
    private static final List<ProtocolMessage> SAMPLES = List.of(
            new SessionStarted("s1"),
            new UserMessage("s1", "hello"),
            new AgentMessageStart("s1", "m1"),
            new AgentMessageDelta("s1", "m1", "You said:"),
            new AgentMessageDelta("s1", "m1", ""),
            new AgentMessageEnd("s1", "m1"),
            new Error("s1", "Unknown message type: user.typing"));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode schemaJson;
    private static Schema schema;

    @BeforeAll
    static void loadSchemaFromClasspath() throws IOException {
        // Read the same way the service will: as a resource of this module.
        try (InputStream in = ProtocolMessage.class.getResourceAsStream(ProtocolCodec.SCHEMA_RESOURCE)) {
            assertThat(in).as("schema on the classpath").isNotNull();
            schemaJson = JSON.readTree(in);
        }
        schema = SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(schemaJson);
    }

    @Test
    void samplesCoverEveryMessageRecord() {
        Set<Class<?>> sampled = new HashSet<>();
        SAMPLES.forEach(sample -> sampled.add(sample.getClass()));

        assertThat(sampled).containsExactlyInAnyOrder(ProtocolMessage.class.getPermittedSubclasses());
    }

    @Test
    void everyRecordIsWrittenAsAValidSchemaMessage() {
        for (ProtocolMessage sample : SAMPLES) {
            String json = ProtocolCodec.write(sample);

            assertThat(schema.validate(json, InputFormat.JSON)).as("%s as %s", sample, json).isEmpty();
        }
    }

    @Test
    void recordsAndSchemaNameTheSameMessageKinds() {
        Set<String> written = new HashSet<>();
        SAMPLES.forEach(sample -> written.add(JSON.readTree(ProtocolCodec.write(sample)).get("type").asString()));

        Set<String> inSchema = new HashSet<>();
        for (JsonNode ref : schemaJson.get("oneOf")) {
            String name = ref.get("$ref").asString().replace("#/$defs/", "");
            inSchema.add(schemaJson.get("$defs").get(name).at("/properties/type/const").asString());
        }

        assertThat(written).isEqualTo(inSchema);
    }

    @Test
    void everySchemaExampleIsReadAndWrittenBackUnchanged() {
        List<JsonNode> examples = new ArrayList<>();
        schemaJson.get("$defs").properties().forEach(def -> {
            JsonNode defExamples = def.getValue().get("examples");
            if (defExamples != null) {
                defExamples.forEach(examples::add);
            }
        });
        assertThat(examples).isNotEmpty();

        for (JsonNode example : examples) {
            ProtocolMessage message = ProtocolCodec.read(example.toString());

            assertThat(JSON.readTree(ProtocolCodec.write(message))).as("round trip of %s", example).isEqualTo(example);
        }
    }
}
