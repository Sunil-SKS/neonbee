package io.neonbee.endpoint.odatav4.internal.cds;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Calendar;
import java.util.List;

import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

import io.neonbee.entity.EntityWrapper;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Serializes an {@link EntityWrapper} to the OData V4 JSON format ({@code application/json;odata.metadata=minimal})
 * without any dependency on Olingo's internal serializer. The wire format matches the existing endpoint exactly:
 *
 * <pre>
 * {@code
 * {
 *   "@odata.context": "$metadata#EntitySet",
 *   "@odata.count": 42,        // present only when $count=true was requested
 *   "value": [ { ... }, ... ]  // collection; omitted for single-entity responses
 * }
 * }
 * </pre>
 *
 * <p>
 * For single-entity responses the top-level {@code value} array is replaced by the entity's properties directly.
 */
public final class CdsEntitySerializer {

    /**
     * Content-type header value for OData JSON minimal metadata.
     */
    public static final String CONTENT_TYPE_ODATA_JSON = "application/json;odata.metadata=minimal";

    private CdsEntitySerializer() {}

    /**
     * Serializes a collection of entities.
     *
     * @param entityWrapper the wrapper containing the list of entities and their type name
     * @param entitySetName the entity set name used in the {@code @odata.context} annotation
     * @param inlineCount   the total count to include in the response (null = omit {@code @odata.count})
     * @return a UTF-8 encoded JSON buffer
     */
    public static Buffer serializeCollection(EntityWrapper entityWrapper, String entitySetName, Long inlineCount) {
        JsonObject response = new JsonObject();
        response.put("@odata.context", "$metadata#" + entitySetName);
        if (inlineCount != null) {
            response.put("@odata.count", inlineCount);
        }
        JsonArray value = new JsonArray();
        for (Entity entity : entityWrapper.getEntities()) {
            value.add(serializeEntityToJson(entity, entityWrapper.getTypeName()));
        }
        response.put("value", value);
        return Buffer.buffer(response.encode());
    }

    /**
     * Serializes a single entity (e.g. for {@code GET /EntitySet(key)} or {@code POST /EntitySet} responses).
     *
     * @param entityWrapper the wrapper containing a single entity and its type name
     * @param entitySetName the entity set name used in the {@code @odata.context} annotation
     * @return a UTF-8 encoded JSON buffer
     */
    public static Buffer serializeEntity(EntityWrapper entityWrapper, String entitySetName) {
        Entity entity = entityWrapper.getEntity();
        JsonObject response = new JsonObject();
        response.put("@odata.context", "$metadata#" + entitySetName + "/$entity");
        if (entity != null) {
            JsonObject entityJson = serializeEntityToJson(entity, entityWrapper.getTypeName());
            for (String key : entityJson.fieldNames()) {
                response.put(key, entityJson.getValue(key));
            }
        }
        return Buffer.buffer(response.encode());
    }

    /**
     * Serializes a raw {@code $count} response (plain integer body, no JSON wrapper).
     *
     * @param count the count value
     * @return a buffer containing the integer as text
     */
    public static Buffer serializeCount(long count) {
        return Buffer.buffer(Long.toString(count));
    }

    /**
     * Serializes an OData error response body.
     *
     * @param code    OData error code string (may be null, defaults to empty string)
     * @param message human-readable error message
     * @return a UTF-8 encoded JSON buffer
     */
    public static Buffer serializeError(String code, String message) {
        JsonObject error = new JsonObject()
                .put("code", code != null ? code : "")
                .put("message", message);
        return Buffer.buffer(new JsonObject().put("error", error).encode());
    }

    private static JsonObject serializeEntityToJson(Entity entity, FullQualifiedName typeName) {
        JsonObject json = new JsonObject();
        if (typeName != null) {
            json.put("@odata.type", "#" + typeName.getFullQualifiedNameAsString());
        }
        for (Property property : entity.getProperties()) {
            json.put(property.getName(), serializeValue(property.getValue()));
        }
        for (Link link : entity.getNavigationLinks()) {
            if (link.getInlineEntitySet() != null) {
                JsonArray expanded = new JsonArray();
                for (Entity inlineEntity : link.getInlineEntitySet().getEntities()) {
                    expanded.add(serializeEntityToJson(inlineEntity, null));
                }
                json.put(link.getTitle(), expanded);
            } else if (link.getInlineEntity() != null) {
                json.put(link.getTitle(), serializeEntityToJson(link.getInlineEntity(), null));
            }
        }
        return json;
    }

    @SuppressWarnings("checkstyle:ReturnCount")
    private static Object serializeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            JsonArray arr = new JsonArray();
            for (Object item : list) {
                arr.add(serializeValue(item));
            }
            return arr;
        }
        // OData primitive types that need special string encoding
        if (value instanceof OffsetDateTime odt) {
            return odt.toString();
        }
        if (value instanceof LocalDate ld) {
            return ld.toString();
        }
        if (value instanceof LocalTime lt) {
            return lt.toString();
        }
        if (value instanceof Calendar cal) {
            return cal.toInstant().toString();
        }
        if (value instanceof byte[] bytes) {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        // Numbers, booleans, and strings are JSON-native — pass through directly
        return value;
    }
}
