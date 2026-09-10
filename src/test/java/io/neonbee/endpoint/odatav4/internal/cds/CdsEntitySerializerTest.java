package io.neonbee.endpoint.odatav4.internal.cds;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;

import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.neonbee.entity.EntityWrapper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

class CdsEntitySerializerTest {

    private static final FullQualifiedName FQN = new FullQualifiedName("io.neonbee.test.TestService", "Books");

    private static Entity bookEntity(int id, String title) {
        return new Entity()
                .addProperty(new Property(null, "ID", ValueType.PRIMITIVE, id))
                .addProperty(new Property(null, "title", ValueType.PRIMITIVE, title));
    }

    // --- serializeCollection ---

    @Test
    @DisplayName("serializeCollection produces @odata.context and value array")
    void serializeCollectionBasic() {
        EntityWrapper wrapper = new EntityWrapper(FQN, List.of(bookEntity(1, "Dune"), bookEntity(2, "Foundation")));
        JsonObject result = new JsonObject(CdsEntitySerializer.serializeCollection(wrapper, "Books", null).toString());

        assertThat(result.getString("@odata.context")).isEqualTo("$metadata#Books");
        assertThat(result.containsKey("@odata.count")).isFalse();
        JsonArray value = result.getJsonArray("value");
        assertThat(value.size()).isEqualTo(2);
        assertThat(value.getJsonObject(0).getInteger("ID")).isEqualTo(1);
        assertThat(value.getJsonObject(1).getString("title")).isEqualTo("Foundation");
    }

    @Test
    @DisplayName("serializeCollection includes @odata.count when provided")
    void serializeCollectionWithCount() {
        EntityWrapper wrapper = new EntityWrapper(FQN, List.of(bookEntity(1, "Dune")));
        JsonObject result = new JsonObject(CdsEntitySerializer.serializeCollection(wrapper, "Books", 42L).toString());

        assertThat(result.getLong("@odata.count")).isEqualTo(42L);
    }

    @Test
    @DisplayName("serializeCollection handles empty entity list")
    void serializeCollectionEmpty() {
        EntityWrapper wrapper = new EntityWrapper(FQN, List.of());
        JsonObject result = new JsonObject(CdsEntitySerializer.serializeCollection(wrapper, "Books", null).toString());

        assertThat(result.getJsonArray("value").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("serializeCollection serializes null property values as JSON null")
    void serializeCollectionNullProperties() {
        Entity entity = new Entity().addProperty(new Property(null, "ID", ValueType.PRIMITIVE, null));
        EntityWrapper wrapper = new EntityWrapper(FQN, List.of(entity));
        JsonArray value = new JsonObject(
                CdsEntitySerializer.serializeCollection(wrapper, "Books", null).toString()).getJsonArray("value");

        assertThat(value.getJsonObject(0).getValue("ID")).isNull();
    }

    // --- serializeEntity ---

    @Test
    @DisplayName("serializeEntity produces @odata.context /$entity and entity properties at top level")
    void serializeEntityBasic() {
        EntityWrapper wrapper = new EntityWrapper(FQN, bookEntity(7, "Hyperion"));
        JsonObject result = new JsonObject(CdsEntitySerializer.serializeEntity(wrapper, "Books").toString());

        assertThat(result.getString("@odata.context")).isEqualTo("$metadata#Books/$entity");
        assertThat(result.getInteger("ID")).isEqualTo(7);
        assertThat(result.getString("title")).isEqualTo("Hyperion");
    }

    @Test
    @DisplayName("serializeEntity handles null entity gracefully")
    void serializeEntityNull() {
        EntityWrapper wrapper = new EntityWrapper(FQN, (Entity) null);
        JsonObject result = new JsonObject(CdsEntitySerializer.serializeEntity(wrapper, "Books").toString());

        assertThat(result.getString("@odata.context")).isEqualTo("$metadata#Books/$entity");
    }

    // --- serializeCount ---

    @Test
    @DisplayName("serializeCount returns plain integer text")
    void serializeCount() {
        assertThat(CdsEntitySerializer.serializeCount(17L).toString()).isEqualTo("17");
        assertThat(CdsEntitySerializer.serializeCount(0L).toString()).isEqualTo("0");
    }

    // --- serializeError ---

    @Test
    @DisplayName("serializeError produces error envelope with code and message")
    void serializeError() {
        JsonObject result = new JsonObject(CdsEntitySerializer.serializeError("404", "Not found").toString());
        JsonObject error = result.getJsonObject("error");

        assertThat(error.getString("code")).isEqualTo("404");
        assertThat(error.getString("message")).isEqualTo("Not found");
    }

    @Test
    @DisplayName("serializeError uses empty string when code is null")
    void serializeErrorNullCode() {
        JsonObject error = new JsonObject(
                CdsEntitySerializer.serializeError(null, "oops").toString()).getJsonObject("error");

        assertThat(error.getString("code")).isEqualTo("");
    }

    // --- navigation link expansion ---

    @Test
    @DisplayName("serializeCollection includes expanded navigation properties as arrays")
    void serializeCollectionWithExpandedNavProp() {
        Entity author = new Entity().addProperty(new Property(null, "name", ValueType.PRIMITIVE, "Frank Herbert"));
        EntityCollection authorCol = new EntityCollection();
        authorCol.getEntities().add(author);

        Link link = new Link();
        link.setTitle("authors");
        link.setInlineEntitySet(authorCol);

        Entity book = bookEntity(1, "Dune");
        book.getNavigationLinks().add(link);

        EntityWrapper wrapper = new EntityWrapper(FQN, List.of(book));
        JsonObject result = new JsonObject(CdsEntitySerializer.serializeCollection(wrapper, "Books", null).toString());

        JsonArray authors = result.getJsonArray("value").getJsonObject(0).getJsonArray("authors");
        assertThat(authors.size()).isEqualTo(1);
        assertThat(authors.getJsonObject(0).getString("name")).isEqualTo("Frank Herbert");
    }

    // --- CONTENT_TYPE_ODATA_JSON constant ---

    @Test
    @DisplayName("CONTENT_TYPE_ODATA_JSON is the expected OData minimal metadata content type")
    void contentTypeConstant() {
        assertThat(CdsEntitySerializer.CONTENT_TYPE_ODATA_JSON)
                .isEqualTo("application/json;odata.metadata=minimal");
    }
}
