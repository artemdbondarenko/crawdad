package io.github.artemdbondarenko.crawdad.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaValidityTest {

    private static final Path PROTOCOL_SCHEMA = Path.of("schema", "crawdad-protocol.schema.json");

    @Test
    void protocolSchemaConformsToDraft202012() throws IOException {
        List<Error> errors = validateAgainstMetaSchema(Files.readString(PROTOCOL_SCHEMA));

        assertThat(errors).isEmpty();
    }

    @Test
    void brokenSchemaIsRejected() throws IOException {
        // Guards against a check that accepts anything: an unknown type name must be reported.
        String schema = Files.readString(PROTOCOL_SCHEMA);
        String broken = schema.replace("\"type\": \"object\"", "\"type\": \"no-such-type\"");
        assertThat(broken).isNotEqualTo(schema);

        List<Error> errors = validateAgainstMetaSchema(broken);

        assertThat(errors).isNotEmpty();
    }

    private static List<Error> validateAgainstMetaSchema(String schemaJson) {
        SchemaRegistry registry = SchemaRegistry.withDialect(Dialects.getDraft202012());
        Schema metaSchema = registry.getSchema(SchemaLocation.of(Dialects.getDraft202012().getId()));
        return metaSchema.validate(schemaJson, InputFormat.JSON);
    }
}
