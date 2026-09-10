package io.neonbee.endpoint.odatav4.internal.cds;

import static io.neonbee.internal.helper.CollectionHelper.multiMapToMap;

import java.util.List;
import java.util.Map;

import io.neonbee.data.DataAction;
import io.neonbee.data.DataQuery;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;

/**
 * Parses the OData query string and request headers into a {@link DataQuery} without using any Olingo UriInfo or
 * uri.parser.Parser internals. All OData system query options ($filter, $orderby, $top, $skip, $expand, $select,
 * $count, $format) are passed through as raw query parameters so that downstream {@code EntityVerticle} implementations
 * and the CDS-native in-process evaluators can access them.
 */
public final class CdsQueryParser {

    private CdsQueryParser() {}

    /**
     * Builds a {@link DataQuery} from the normalized URI components and the HTTP request.
     *
     * @param action       the data action derived from the HTTP method
     * @param uriPath      the resource path including the service namespace (e.g. {@code /Namespace/EntitySet(1)})
     * @param requestQuery the raw (URL-encoded) OData query string (may be empty, never null)
     * @param request      the incoming HTTP request (used to copy request headers)
     * @param body         the request body buffer (may be null for read requests)
     * @return a fully populated {@link DataQuery}
     */
    public static DataQuery parse(DataAction action, String uriPath, String requestQuery, HttpServerRequest request,
            Buffer body) {
        Map<String, List<String>> params = DataQuery.parseEncodedQueryString(requestQuery);
        Map<String, List<String>> headers = multiMapToMap(request.headers());
        return new DataQuery(action, uriPath, params, headers, body)
                .addHeader("X-HTTP-Method", request.method().name());
    }
}
