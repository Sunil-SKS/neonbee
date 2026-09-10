package io.neonbee.endpoint.odatav4.internal.cds;

import static io.neonbee.endpoint.HttpMethodToDataActionMapper.mapMethodToAction;
import static io.neonbee.endpoint.odatav4.ODataV4Endpoint.normalizeUri;
import static io.neonbee.endpoint.odatav4.internal.olingo.processor.ProcessorHelper.ODATA_COUNT_SIZE_KEY;
import static io.neonbee.endpoint.odatav4.internal.olingo.processor.ProcessorHelper.RESPONSE_HEADER_PREFIX;
import static io.neonbee.entity.EntityVerticle.requestEntity;
import static io.netty.handler.codec.http.HttpResponseStatus.ACCEPTED;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.CREATED;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static io.netty.handler.codec.http.HttpResponseStatus.NO_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.vertx.core.Future.failedFuture;

import java.util.List;
import java.util.Optional;

import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.server.api.ServiceMetadata;

import io.neonbee.data.DataAction;
import io.neonbee.data.DataContext;
import io.neonbee.data.DataQuery;
import io.neonbee.data.DataRequest;
import io.neonbee.data.internal.DataContextImpl;
import io.neonbee.endpoint.odatav4.ODataV4Endpoint.NormalizedUri;
import io.neonbee.entity.EntityWrapper;
import io.neonbee.logging.LoggingFacade;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

/**
 * Vert.x request handler for the CDS-native OData V4 endpoint. All processing runs on the event-loop thread — no
 * {@code vertx.executeBlocking} is used.
 *
 * <p>
 * Responsibilities:
 * <ol>
 * <li>URI normalisation — via {@link NormalizedUri} (reused from {@code ODataV4Endpoint}).</li>
 * <li>Query parsing — raw OData query string → {@link DataQuery} via {@link CdsQueryParser}.</li>
 * <li>Dispatch — forwards {@link DataRequest} to the relevant {@code EntityVerticle}.</li>
 * <li>Serialisation — converts the returned {@link EntityWrapper} to OData JSON.</li>
 * <li>Mutations — maps HTTP method to {@link DataAction} and deserialises request body.</li>
 * <li>Batch — delegates {@code $batch} requests to {@link CdsBatchHandler}.</li>
 * <li>Error mapping — writes OData-compliant error JSON for 4xx/5xx.</li>
 * </ol>
 */
@SuppressWarnings("PMD.GodClass")
public final class CdsODataEndpointHandler implements Handler<RoutingContext> {

    private static final LoggingFacade LOGGER = LoggingFacade.create();

    private static final int REQUEST_LINE_PARTS = 3;

    private static final int HTTP_RESPONSE_BUILDER_SIZE = 64;

    private final ServiceMetadata serviceMetadata;

    /**
     * Creates a new handler.
     *
     * @param serviceMetadata the EDMX service metadata (used to derive the schema namespace)
     */
    public CdsODataEndpointHandler(ServiceMetadata serviceMetadata) {
        this.serviceMetadata = serviceMetadata;
    }

    @Override
    public void handle(RoutingContext routingContext) {
        String schemaNamespace = serviceMetadata.getEdm().getEntityContainer().getNamespace();

        NormalizedUri normalizedUri = normalizeUri(routingContext, schemaNamespace);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.correlateWith(routingContext).debug("CDS OData handler: {}", normalizedUri);
        }

        String resourcePath = normalizedUri.resourcePath;

        // $metadata — served directly from Olingo ServiceMetadata via executeBlocking (unavoidable for metadata CSDL)
        if ("/$metadata".equals(resourcePath)) {
            handleMetadata(routingContext, schemaNamespace);
            return;
        }

        // $batch
        if (resourcePath.endsWith("/$batch")) {
            handleBatch(routingContext, normalizedUri);
            return;
        }

        HttpServerRequest request = routingContext.request();
        DataAction action = mapMethodToAction(request.method());
        if (action == null) {
            routingContext.fail(METHOD_NOT_ALLOWED.code());
            return;
        }

        // $count suffix
        boolean isCountRequest = resourcePath.endsWith("/$count");

        String effectiveResourcePath = isCountRequest
                ? resourcePath.substring(0, resourcePath.length() - "/$count".length())
                : resourcePath;

        String entityName = extractEntityName(effectiveResourcePath);
        if (entityName == null) {
            routingContext.fail(BAD_REQUEST.code());
            return;
        }

        String fqnStr = schemaNamespace + "." + entityName;
        Buffer body = Optional.ofNullable(routingContext.body()).map(rb -> rb.buffer()).orElse(null);
        String uriPath = "/" + schemaNamespace + effectiveResourcePath;
        DataQuery dataQuery = CdsQueryParser.parse(action, uriPath, normalizedUri.requestQuery, request, body);
        FullQualifiedName fqn = new FullQualifiedName(fqnStr);
        DataContext dataContext = new DataContextImpl(routingContext);
        DataRequest dataRequest = new DataRequest(fqn, dataQuery);

        requestEntity(routingContext.vertx(), dataRequest, dataContext)
                .map(ew -> transferResponseHint(dataContext, routingContext, ew))
                .onSuccess(entityWrapper -> {
                    try {
                        respond(routingContext, dataContext, entityWrapper, normalizedUri, action, isCountRequest,
                                entityName, fqn);
                    } catch (Exception e) {
                        routingContext.fail(-1, e);
                    }
                })
                .onFailure(cause -> {
                    LOGGER.correlateWith(routingContext).error("Entity request failed", cause);
                    sendErrorResponse(routingContext, -1, cause.getMessage());
                });
    }

    private void handleMetadata(RoutingContext routingContext, String schemaNamespace) {
        Vertx vertx = routingContext.vertx();
        vertx.executeBlocking(() -> {
            org.apache.olingo.server.api.OData odata = org.apache.olingo.server.api.OData.newInstance();
            org.apache.olingo.server.api.ODataHandler odataHandler = odata.createRawHandler(serviceMetadata);
            return odataHandler.process(
                    io.neonbee.endpoint.odatav4.internal.olingo.OlingoEndpointHandler.mapToODataRequest(
                            routingContext, schemaNamespace));
        }).onComplete(asyncResult -> {
            if (asyncResult.failed()) {
                routingContext.fail(-1, asyncResult.cause());
                return;
            }
            try {
                io.neonbee.endpoint.odatav4.internal.olingo.OlingoEndpointHandler
                        .mapODataResponse(asyncResult.result(), routingContext.response());
            } catch (Exception e) {
                routingContext.fail(-1, e);
            }
        });
    }

    private void handleBatch(RoutingContext routingContext, NormalizedUri normalizedUri) {
        HttpServerRequest request = routingContext.request();
        String contentType = request.getHeader("Content-Type");
        Buffer body = Optional.ofNullable(routingContext.body()).map(rb -> rb.buffer()).orElse(Buffer.buffer());

        CdsBatchHandler
                .handle(body, contentType, partBody -> dispatchBatchPart(routingContext, normalizedUri, partBody))
                .onSuccess(responseBuffer -> {
                    HttpServerResponse response = routingContext.response();
                    response.setStatusCode(ACCEPTED.code());
                    response.putHeader("Content-Type", "multipart/mixed");
                    response.putHeader("OData-Version", "4.0");
                    response.end(responseBuffer);
                })
                .onFailure(cause -> {
                    LOGGER.correlateWith(routingContext).error("Batch request failed", cause);
                    sendErrorResponse(routingContext, BAD_REQUEST.code(), cause.getMessage());
                });
    }

    private Future<Buffer> dispatchBatchPart(RoutingContext routingContext, NormalizedUri normalizedUri,
            Buffer partBody) {
        // Each part body is a raw HTTP request fragment; extract method, path, and body from it
        String raw = partBody.toString(java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = raw.split("\r\n|\n", -1);
        if (lines.length == 0) {
            return failedFuture("Empty batch part");
        }

        // First line: METHOD path HTTP/1.1
        String[] requestLine = lines[0].trim().split("\\s+", REQUEST_LINE_PARTS);
        if (requestLine.length < 2) {
            return failedFuture("Invalid batch part request line: " + lines[0]);
        }
        String methodName = requestLine[0];

        io.vertx.core.http.HttpMethod httpMethod;
        try {
            httpMethod = io.vertx.core.http.HttpMethod.valueOf(methodName);
        } catch (IllegalArgumentException e) {
            return failedFuture("Unknown HTTP method in batch part: " + methodName);
        }

        DataAction action = mapMethodToAction(httpMethod);
        if (action == null) {
            return failedFuture("Unsupported HTTP method in batch part: " + methodName);
        }

        String partPath = requestLine[1];

        // Separate headers from body (blank line delimiter)
        int emptyIdx = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                emptyIdx = i;
                break;
            }
        }

        // Parse query from path
        String path = partPath;
        String query = "";
        int queryStart = partPath.indexOf('?');
        if (queryStart >= 0) {
            path = partPath.substring(0, queryStart);
            query = partPath.substring(queryStart + 1);
        }

        // Derive entity name from path segment (last word before optional key predicate)
        String entityName = extractEntityName(path);
        if (entityName == null) {
            return failedFuture("Cannot determine entity name from batch part path: " + path);
        }

        String partBodyStr = emptyIdx >= 0 && emptyIdx + 1 < lines.length
                ? String.join("\r\n", java.util.Arrays.asList(lines).subList(emptyIdx + 1, lines.length))
                : "";
        String fqnStr = normalizedUri.schemaNamespace + "." + entityName;
        FullQualifiedName fqn = new FullQualifiedName(fqnStr);
        String uriPath = "/" + normalizedUri.schemaNamespace + path;

        Buffer partBuf = partBodyStr.isEmpty() ? null : Buffer.buffer(partBodyStr);
        DataQuery dataQuery = new DataQuery(action, uriPath, DataQuery.parseEncodedQueryString(query), null, partBuf);
        DataContext dataContext = new DataContextImpl(routingContext);
        DataRequest dataRequest = new DataRequest(fqn, dataQuery);

        return requestEntity(routingContext.vertx(), dataRequest, dataContext)
                .map(ew -> {
                    Buffer entityBuf = ew != null && !ew.getEntities().isEmpty()
                            ? CdsEntitySerializer.serializeEntity(ew, entityName)
                            : Buffer.buffer();
                    int status = action == DataAction.CREATE ? CREATED.code()
                            : (entityBuf.length() == 0 ? NO_CONTENT.code() : OK.code());
                    return formatRawHttpResponse(status, entityBuf);
                })
                .recover(cause -> {
                    Buffer errorBuf = CdsEntitySerializer.serializeError(null, cause.getMessage());
                    return Future.succeededFuture(formatRawHttpResponse(INTERNAL_SERVER_ERROR.code(), errorBuf));
                });
    }

    private static Buffer formatRawHttpResponse(int statusCode, Buffer body) {
        StringBuilder sb = new StringBuilder(HTTP_RESPONSE_BUILDER_SIZE);
        sb.append("HTTP/1.1 ").append(statusCode).append("\r\n");
        if (body != null && body.length() > 0) {
            sb.append("Content-Type: ").append(CdsEntitySerializer.CONTENT_TYPE_ODATA_JSON)
                    .append("\r\nContent-Length: ").append(body.length()).append("\r\n");
        }
        sb.append("\r\n");
        Buffer result = Buffer.buffer(sb.toString());
        if (body != null) {
            result.appendBuffer(body);
        }
        return result;
    }

    private void respond(RoutingContext routingContext, DataContext dataContext, EntityWrapper entityWrapper,
            NormalizedUri normalizedUri, DataAction action, boolean isCountRequest, String entityName,
            FullQualifiedName fqn) {
        HttpServerResponse response = routingContext.response();
        response.putHeader("OData-Version", "4.0");

        if (isCountRequest) {
            long count = resolveCount(dataContext, entityWrapper);
            response.setStatusCode(OK.code());
            response.putHeader("Content-Type", "text/plain");
            response.end(CdsEntitySerializer.serializeCount(count));
            return;
        }

        if (action == DataAction.DELETE
                || ((entityWrapper == null || entityWrapper.getEntities().isEmpty())
                        && action == DataAction.UPDATE)) {
            response.setStatusCode(NO_CONTENT.code());
            response.end();
            return;
        }

        // Status code override from verticle response hints
        int statusCode = Optional.ofNullable((Integer) dataContext.responseData().get(DataContext.STATUS_CODE_HINT))
                .orElseGet(() -> defaultStatusCode(action, entityWrapper));

        boolean isSingleEntity = hasSingleEntityKeyPredicate(normalizedUri.resourcePath);

        if (entityWrapper == null || entityWrapper.getEntities().isEmpty()) {
            if (isSingleEntity) {
                response.setStatusCode(statusCode);
                response.putHeader("Content-Type", CdsEntitySerializer.CONTENT_TYPE_ODATA_JSON);
                response.end(CdsEntitySerializer.serializeEntity(new EntityWrapper(fqn, List.of()), entityName));
                return;
            }
            response.setStatusCode(statusCode);
            response.putHeader("Content-Type", CdsEntitySerializer.CONTENT_TYPE_ODATA_JSON);
            response.end(CdsEntitySerializer.serializeCollection(new EntityWrapper(fqn, List.of()), entityName, null));
            return;
        }

        response.setStatusCode(statusCode);
        response.putHeader("Content-Type", CdsEntitySerializer.CONTENT_TYPE_ODATA_JSON);

        if (isSingleEntity
                || (entityWrapper.getEntities().size() == 1
                        && (action == DataAction.CREATE || action == DataAction.UPDATE))) {
            response.end(CdsEntitySerializer.serializeEntity(entityWrapper, entityName));
        } else {
            Long inlineCount = resolveInlineCount(dataContext);
            response.end(CdsEntitySerializer.serializeCollection(entityWrapper, entityName, inlineCount));
        }
    }

    private static long resolveCount(DataContext dataContext, EntityWrapper entityWrapper) {
        Object hint = dataContext.responseData().get(ODATA_COUNT_SIZE_KEY);
        if (hint instanceof Number n) {
            return n.longValue();
        }
        return entityWrapper != null ? entityWrapper.getEntities().size() : 0L;
    }

    private static Long resolveInlineCount(DataContext dataContext) {
        Object hint = dataContext.responseData().get(RESPONSE_HEADER_PREFIX + ODATA_COUNT_SIZE_KEY);
        if (hint instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static int defaultStatusCode(DataAction action, EntityWrapper entityWrapper) {
        return switch (action) {
        case CREATE -> CREATED.code();
        case DELETE -> NO_CONTENT.code();
        case UPDATE -> entityWrapper != null && !entityWrapper.getEntities().isEmpty() ? OK.code() : NO_CONTENT.code();
        default -> OK.code();
        };
    }

    private static boolean hasSingleEntityKeyPredicate(String resourcePath) {
        // e.g. /Books(1) or /Books('key') — parentheses anywhere in the last segment
        return resourcePath != null && resourcePath.matches(".*/[^/]+\\([^)]*\\)\\s*");
    }

    private static String extractEntityName(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        // Take the last non-empty path segment, strip key predicates
        String[] segments = path.split("/", -1);
        for (int i = segments.length - 1; i >= 0; i--) {
            String seg = segments[i];
            if (!seg.isEmpty()) {
                int parenIdx = seg.indexOf('(');
                return parenIdx >= 0 ? seg.substring(0, parenIdx) : seg;
            }
        }
        return null;
    }

    private static EntityWrapper transferResponseHint(DataContext dataContext, RoutingContext routingContext,
            EntityWrapper result) {
        dataContext.responseData().forEach((key, value) -> routingContext.put(RESPONSE_HEADER_PREFIX + key, value));
        return result;
    }

    private static void sendErrorResponse(RoutingContext routingContext, int statusCode, String message) {
        HttpServerResponse response = routingContext.response();
        if (response.ended()) {
            return;
        }
        int code = statusCode <= 0 ? INTERNAL_SERVER_ERROR.code() : statusCode;
        response.setStatusCode(code);
        response.putHeader("Content-Type", CdsEntitySerializer.CONTENT_TYPE_ODATA_JSON);
        response.end(CdsEntitySerializer.serializeError(String.valueOf(code), message));
    }
}
