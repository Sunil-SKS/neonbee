package io.neonbee.endpoint.odatav4;

import org.apache.olingo.server.api.ServiceMetadata;

import io.neonbee.config.EndpointConfig;
import io.neonbee.endpoint.odatav4.internal.cds.CdsODataEndpointHandler;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * CDS-native OData V4 endpoint that replaces the Olingo orchestration layer with a direct CDS-model dispatch.
 *
 * <p>
 * Mount alongside the existing {@link ODataV4Endpoint} in the server config:
 *
 * <pre>
 * {@code
 * { "type": "io.neonbee.endpoint.odatav4.ODataV4CdsEndpoint", "basePath": "/odata2/" }
 * }
 * </pre>
 *
 * <p>
 * All URI conversion, model management, block-list filtering, and {@code EntityVerticle} dispatch are inherited
 * unchanged from {@link ODataV4Endpoint}. Only the per-request handler is replaced.
 */
public class ODataV4CdsEndpoint extends ODataV4Endpoint {

    private static final String BASE_PATH_SEGMENT = "odata2";

    /**
     * The default base path for the CDS-native OData V4 endpoint.
     */
    public static final String DEFAULT_BASE_PATH = "/" + BASE_PATH_SEGMENT + "/";

    @Override
    public EndpointConfig getDefaultConfig() {
        return new EndpointConfig().setType(ODataV4CdsEndpoint.class.getName()).setBasePath(DEFAULT_BASE_PATH)
                .setAdditionalConfig(new JsonObject().put("uriConversion", UriConversion.STRICT.name()));
    }

    @Override
    protected Handler<RoutingContext> getRequestHandler(ServiceMetadata edmxModel, UriConversion uriConversion,
            JsonObject config) {
        return new CdsODataEndpointHandler(edmxModel);
    }
}
