package io.github.artemdbondarenko.crawdad.protocol;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.exc.ValueInstantiationException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/** Converts protocol messages to and from their JSON text. */
public final class ProtocolCodec {

    /** Classpath location of the protocol schema, relative to this package. */
    public static final String SCHEMA_RESOURCE = "crawdad-protocol.schema.json";

    /** As strict as the schema: no unknown fields, no repeated fields, no number or boolean for a string. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfig(LogicalType.Textual, strings -> strings
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private ProtocolCodec() {}

    /** Reads one message; anything that is not a valid message gives {@link ProtocolException}. */
    public static ProtocolMessage read(String json) {
        try {
            return JSON.readValue(json, ProtocolMessage.class);
        } catch (InvalidTypeIdException e) {
            String type = e.getTypeId();
            throw new ProtocolException(
                    type == null ? "Missing message type" : "Unknown message type: " + type, e);
        } catch (UnrecognizedPropertyException e) {
            throw new ProtocolException("Unknown field: " + e.getPropertyName(), e);
        } catch (ValueInstantiationException e) {
            // The record rejected a field value; its message names the field.
            throw new ProtocolException(e.getCause().getMessage(), e);
        } catch (JacksonException e) {
            throw new ProtocolException("Not a protocol message", e);
        }
    }

    public static String write(ProtocolMessage message) {
        return JSON.writeValueAsString(message);
    }
}
