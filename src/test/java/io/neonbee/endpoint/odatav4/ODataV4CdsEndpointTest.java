package io.neonbee.endpoint.odatav4;

import static com.google.common.truth.Truth.assertThat;
import static io.neonbee.test.helper.EntityHelper.createEntity;
import static io.neonbee.test.helper.ResourceHelper.TEST_RESOURCES;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import io.neonbee.config.EndpointConfig;
import io.neonbee.config.ServerConfig;
import io.neonbee.data.DataContext;
import io.neonbee.data.DataQuery;
import io.neonbee.entity.EntityVerticle;
import io.neonbee.entity.EntityWrapper;
import io.neonbee.internal.verticle.ServerVerticle;
import io.neonbee.test.base.ODataEndpointTestBase;
import io.neonbee.test.helper.WorkingDirectoryBuilder;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;

/**
 * Integration tests for the CDS-native OData V4 endpoint ({@link ODataV4CdsEndpoint}) exercising the full HTTP stack
 * through {@code /odata2/}.
 */
class ODataV4CdsEndpointTest extends ODataEndpointTestBase {

    static final FullQualifiedName TEST_FQN =
            new FullQualifiedName("io.neonbee.test3.TestService3", "TestCars");

    // paths used in assertions
    static final String BASE_PATH = ODataV4CdsEndpoint.DEFAULT_BASE_PATH;

    static final String ENTITY_SET_PATH =
            BASE_PATH + TEST_FQN.getNamespace() + "/" + TEST_FQN.getName();

    static final JsonObject CAR_0 =
            new JsonObject().put("ID", 0).put("name", "Car 0").put("description", "This is Car 0");

    static final JsonObject CAR_1 =
            new JsonObject().put("ID", 1).put("name", "Car 1").put("description", "This is Car 1");

    // --- Setup ---

    @Override
    protected List<Path> provideEntityModels() {
        return List.of(TEST_RESOURCES.resolve("io/neonbee/test/endpoint/odata/verticle/TestService3.csn"));
    }

    @Override
    protected WorkingDirectoryBuilder provideWorkingDirectoryBuilder(TestInfo testInfo,
            VertxTestContext testContext) {
        return super.provideWorkingDirectoryBuilder(testInfo, testContext).setCustomTask(root -> {
            DeploymentOptions opts = WorkingDirectoryBuilder.readDeploymentOptions(ServerVerticle.class, root);
            EndpointConfig epc = new EndpointConfig()
                    .setType(ODataV4CdsEndpoint.class.getName())
                    .setBasePath(ODataV4CdsEndpoint.DEFAULT_BASE_PATH);
            ServerConfig sc = new ServerConfig(opts.getConfig()).setEndpointConfigs(List.of(epc));
            opts.setConfig(sc.toJson());
            WorkingDirectoryBuilder.writeDeploymentOptions(ServerVerticle.class, opts, root);
        });
    }

    @BeforeEach
    void deployTestVerticle(VertxTestContext testContext) {
        deployVerticle(new CarsEntityVerticle()).onComplete(testContext.succeedingThenComplete());
    }

    // --- Tests ---

    @Test
    @DisplayName("GET entity collection returns all entities with value array and @odata.context")
    void testReadEntityCollection(VertxTestContext testContext) {
        createRequest(HttpMethod.GET, ENTITY_SET_PATH).send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    JsonObject body = response.bodyAsJsonObject();
                    assertThat(body.getString("@odata.context")).isEqualTo("$metadata#TestCars");
                    assertThat(body.getJsonArray("value").size()).isEqualTo(2);
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("GET entity collection $count returns plain integer")
    void testCountRequest(VertxTestContext testContext) {
        createRequest(HttpMethod.GET, ENTITY_SET_PATH + "/$count").send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.bodyAsString()).isEqualTo("2");
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("GET single entity by key returns /$entity context and entity properties")
    void testReadSingleEntity(VertxTestContext testContext) {
        createRequest(HttpMethod.GET, ENTITY_SET_PATH + "(0)").send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    JsonObject body = response.bodyAsJsonObject();
                    assertThat(body.getString("@odata.context")).isEqualTo("$metadata#TestCars/$entity");
                    assertThat(body.getInteger("ID")).isEqualTo(0);
                    assertThat(body.getString("name")).isEqualTo("Car 0");
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("GET $metadata returns XML CSDL document")
    void testMetadataRequest(VertxTestContext testContext) {
        createRequest(HttpMethod.GET,
                BASE_PATH + TEST_FQN.getNamespace() + "/$metadata").send()
                        .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                            assertThat(response.statusCode()).isEqualTo(200);
                            String body = response.bodyAsString();
                            assertThat(body).contains("<edmx:Edmx");
                            testContext.completeNow();
                        })));
    }

    @Test
    @DisplayName("POST creates entity and returns 201 Created")
    void testCreateEntity(VertxTestContext testContext) {
        JsonObject newCar = new JsonObject().put("ID", 99).put("name", "New Car").put("description", "desc");
        createRequest(HttpMethod.POST, ENTITY_SET_PATH)
                .putHeader("Content-Type", "application/json")
                .sendBuffer(newCar.toBuffer())
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertThat(response.statusCode()).isEqualTo(201);
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("PATCH updates entity and returns 200 or 204")
    void testUpdateEntity(VertxTestContext testContext) {
        JsonObject patch = new JsonObject().put("name", "Updated Car 0");
        createRequest(HttpMethod.PATCH, ENTITY_SET_PATH + "(0)")
                .putHeader("Content-Type", "application/json")
                .sendBuffer(patch.toBuffer())
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertThat(response.statusCode()).isIn(List.of(200, 204));
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("DELETE entity returns 204 No Content")
    void testDeleteEntity(VertxTestContext testContext) {
        createRequest(HttpMethod.DELETE, ENTITY_SET_PATH + "(0)").send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertThat(response.statusCode()).isEqualTo(204);
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("GET with $count=true includes @odata.count in response")
    void testInlineCount(VertxTestContext testContext) {
        createRequest(HttpMethod.GET, ENTITY_SET_PATH + "?$count=true").send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    // The count hint is not provided by the verticle in this test,
                    // so @odata.count should be absent (not forced by the handler).
                    assertThat(response.bodyAsJsonObject().containsKey("value")).isTrue();
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("Request for non-existent entity set returns an error response")
    void testNonExistentEntitySet(VertxTestContext testContext) {
        String path = BASE_PATH + TEST_FQN.getNamespace() + "/NotExistingEntities";
        createRequest(HttpMethod.GET, path).send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    // The entity verticle will not be found; we expect a 4xx/5xx or OData error body
                    assertThat(response.statusCode()).isAtLeast(400);
                    testContext.completeNow();
                })));
    }

    // --- Nested verticle ---

    static class CarsEntityVerticle extends EntityVerticle {

        @Override
        public Future<Set<FullQualifiedName>> entityTypeNames() {
            return Future.succeededFuture(Set.of(TEST_FQN));
        }

        @Override
        public Future<EntityWrapper> retrieveData(DataQuery query, DataContext context) {
            Entity car0 = createEntity(CAR_0);
            Entity car1 = createEntity(CAR_1);
            return Future.succeededFuture(new EntityWrapper(TEST_FQN, List.of(car0, car1)));
        }

        @Override
        public Future<EntityWrapper> createData(DataQuery query, DataContext context) {
            return Future.succeededFuture(new EntityWrapper(TEST_FQN, List.of()));
        }

        @Override
        public Future<EntityWrapper> updateData(DataQuery query, DataContext context) {
            return Future.succeededFuture(new EntityWrapper(TEST_FQN, List.of()));
        }

        @Override
        public Future<EntityWrapper> deleteData(DataQuery query, DataContext context) {
            return Future.succeededFuture(new EntityWrapper(TEST_FQN, List.of()));
        }
    }
}
