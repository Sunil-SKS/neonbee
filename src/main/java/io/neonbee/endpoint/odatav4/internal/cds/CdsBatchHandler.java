package io.neonbee.endpoint.odatav4.internal.cds;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.neonbee.endpoint.odatav4.rawbatch.RawBatchDecision;
import io.neonbee.endpoint.odatav4.rawbatch.RawBatchResult;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

/**
 * Handles OData V4 {@code $batch} requests (§11.7 multipart/mixed) without any Olingo dependency.
 *
 * <p>
 * The raw-batch escape hatch ({@link RawBatchDecision} / {@link RawBatchResult}) is preserved: if the caller has
 * already resolved a raw result before invoking this handler, the decision is honoured and default multipart processing
 * is only entered when {@link RawBatchDecision#DELEGATE_TO_DEFAULT} is returned.
 *
 * <p>
 * Change-set semantics are identical to the existing Olingo-based implementation: parts within a change set are
 * executed sequentially, and the change set is aborted on the first 4xx/5xx response. No transaction / rollback support
 * is provided.
 */
public final class CdsBatchHandler {

    static final String MULTIPART_MIXED = "multipart/mixed";

    private static final int DEFAULT_STATUS_OK = 200;

    private static final int BAD_REQUEST_STATUS = 400;

    private static final String CRLF = "\r\n";

    private static final Pattern BOUNDARY_PATTERN =
            Pattern.compile("boundary=(?:\"([^\"]*)\"|([^;\\s]*))", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONTENT_TYPE_HEADER_PATTERN =
            Pattern.compile("^Content-Type:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern HTTP_STATUS_PATTERN =
            Pattern.compile("^HTTP/1\\.1\\s+(\\d{3})", Pattern.MULTILINE);

    private CdsBatchHandler() {}

    /**
     * Parses the multipart/mixed body and dispatches each part through the provided handler.
     *
     * @param body           the raw request body
     * @param contentType    the value of the {@code Content-Type} request header (used to extract the boundary)
     * @param partDispatcher a function that takes a single-request body buffer and returns a response buffer future
     * @return a future resolving to the complete multipart response buffer (HTTP 202 body), or failed if parsing fails
     */
    public static Future<Buffer> handle(Buffer body, String contentType,
            PartDispatcher partDispatcher) {
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            return Future.failedFuture("Missing or unparseable boundary in Content-Type: " + contentType);
        }

        List<BatchPart> parts;
        try {
            parts = parseMultipart(body.toString(StandardCharsets.UTF_8), boundary);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }

        String responseBoundary = "batch_" + UUID.randomUUID();
        return dispatchAll(parts, partDispatcher)
                .map(responses -> serializeMultipart(responses, responseBoundary));
    }

    // --- Parsing ---

    static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher m = BOUNDARY_PATTERN.matcher(contentType);
        if (!m.find()) {
            return null;
        }
        // group 1 = quoted boundary, group 2 = unquoted boundary
        String raw = m.group(1) != null ? m.group(1) : m.group(2);
        return raw != null ? raw.trim() : null;
    }

    static List<BatchPart> parseMultipart(String body, String boundary) {
        String delimiter = "--" + boundary;
        String closeDelimiter = "--" + boundary + "--";
        String[] lines = body.split("\r\n|\n", -1);

        List<BatchPart> parts = new ArrayList<>();
        List<String> currentPartLines = null;
        boolean inPart = false;

        for (String line : lines) {
            if (closeDelimiter.equals(line.trim())) {
                if (currentPartLines != null) {
                    parts.add(buildPart(currentPartLines));
                }
                break;
            } else if (delimiter.equals(line.trim())) {
                if (currentPartLines != null && inPart) {
                    parts.add(buildPart(currentPartLines));
                }
                currentPartLines = new ArrayList<>();
                inPart = true;
            } else if (inPart) {
                currentPartLines.add(line);
            }
        }
        return parts;
    }

    private static BatchPart buildPart(List<String> lines) {
        // find the empty line that separates headers from body
        int emptyLineIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                emptyLineIdx = i;
                break;
            }
        }
        if (emptyLineIdx == -1) {
            // no body
            return new BatchPart(String.join(CRLF, lines), "", false);
        }

        String headers = String.join(CRLF, lines.subList(0, emptyLineIdx));
        String partBody = String.join(CRLF, lines.subList(emptyLineIdx + 1, lines.size()));

        // detect change set: Content-Type: multipart/mixed
        Matcher ctMatcher = CONTENT_TYPE_HEADER_PATTERN.matcher(headers);
        if (ctMatcher.find() && ctMatcher.group(1).trim().toLowerCase(Locale.ROOT).startsWith(MULTIPART_MIXED)) {
            String changeSetBoundary = extractBoundary(ctMatcher.group(1));
            List<BatchPart> changeSetParts = changeSetBoundary != null
                    ? parseMultipart(partBody, changeSetBoundary)
                    : List.of();
            return new ChangeSetBatchPart(headers, partBody, changeSetParts);
        }

        return new BatchPart(headers, partBody, false);
    }

    // --- Dispatch ---

    private static Future<List<PartResponse>> dispatchAll(List<BatchPart> parts, PartDispatcher dispatcher) {
        List<Future<PartResponse>> futures = new ArrayList<>();
        for (BatchPart part : parts) {
            if (part instanceof ChangeSetBatchPart changeset) {
                futures.add(dispatchChangeSet(changeset.parts(), dispatcher));
            } else {
                futures.add(dispatcher.dispatch(Buffer.buffer(part.body()))
                        .map(buf -> new PartResponse(buf, false)));
            }
        }
        return Future.all(futures).map(cf -> futures.stream().map(Future::result).toList());
    }

    private static Future<PartResponse> dispatchChangeSet(List<BatchPart> parts, PartDispatcher dispatcher) {
        // sequential execution; abort on first 4xx/5xx
        Future<List<Buffer>> sequential = Future.succeededFuture(new ArrayList<>());
        for (BatchPart part : parts) {
            sequential = sequential.compose(results -> dispatcher.dispatch(Buffer.buffer(part.body())).compose(buf -> {
                int status = extractStatus(buf);
                if (status >= BAD_REQUEST_STATUS) {
                    // abort: return only the error response
                    List<Buffer> aborted = new ArrayList<>();
                    aborted.add(buf);
                    return Future.failedFuture(new ChangeSetAbortException(aborted));
                }
                results.add(buf);
                return Future.succeededFuture(results);
            }));
        }
        return sequential
                .map(results -> new PartResponse(serializeChangeSetResponse(results), true))
                .recover(cause -> {
                    if (cause instanceof ChangeSetAbortException abort) {
                        return Future.succeededFuture(
                                new PartResponse(serializeChangeSetResponse(abort.responses()), true));
                    }
                    return Future.failedFuture(cause);
                });
    }

    private static int extractStatus(Buffer buf) {
        Matcher m = HTTP_STATUS_PATTERN.matcher(buf.toString(StandardCharsets.UTF_8));
        return m.find() ? Integer.parseInt(m.group(1)) : DEFAULT_STATUS_OK;
    }

    private static Buffer serializeChangeSetResponse(List<Buffer> responses) {
        StringBuilder sb = new StringBuilder();
        for (Buffer r : responses) {
            sb.append(r.toString(StandardCharsets.UTF_8));
        }
        return Buffer.buffer(sb.toString());
    }

    // --- Serialization ---

    static Buffer serializeMultipart(List<PartResponse> responses, String boundary) {
        StringBuilder sb = new StringBuilder();
        for (PartResponse part : responses) {
            sb.append("--").append(boundary).append(CRLF);
            if (part.isChangeSet()) {
                String innerBoundary = "changeset_" + UUID.randomUUID();
                sb.append("Content-Type: ").append(MULTIPART_MIXED).append("; boundary=").append(innerBoundary)
                        .append(CRLF).append(CRLF)
                        .append(part.body().toString(StandardCharsets.UTF_8))
                        .append(CRLF);
            } else {
                sb.append("Content-Type: application/http").append(CRLF)
                        .append("Content-Transfer-Encoding: binary").append(CRLF).append(CRLF)
                        .append(part.body().toString(StandardCharsets.UTF_8))
                        .append(CRLF);
            }
        }
        sb.append("--").append(boundary).append("--");
        return Buffer.buffer(sb.toString());
    }

    // --- Supporting types ---

    /**
     * Functional interface for dispatching a single batch part request body.
     */
    @FunctionalInterface
    public interface PartDispatcher {
        /**
         * Dispatches the raw HTTP request body of a single batch part.
         *
         * @param partBody the raw HTTP request (status-line + headers + body) as a buffer
         * @return a future resolving to the raw HTTP response buffer
         */
        Future<Buffer> dispatch(Buffer partBody);
    }

    static class BatchPart {
        private final String headers;

        private final String body;

        private final boolean isChangeSet;

        BatchPart(String headers, String body, boolean isChangeSet) {
            this.headers = headers;
            this.body = body;
            this.isChangeSet = isChangeSet;
        }

        String headers() {
            return headers;
        }

        String body() {
            return body;
        }

        boolean isChangeSet() {
            return isChangeSet;
        }
    }

    static final class ChangeSetBatchPart extends BatchPart {
        private final List<BatchPart> parts;

        ChangeSetBatchPart(String headers, String body, List<BatchPart> parts) {
            super(headers, body, true);
            this.parts = List.copyOf(parts);
        }

        List<BatchPart> parts() {
            return parts;
        }
    }

    record PartResponse(Buffer body, boolean isChangeSet) {
    }

    private static final class ChangeSetAbortException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final List<Buffer> responses;

        ChangeSetAbortException(List<Buffer> responses) {
            super("Change set aborted");
            this.responses = List.copyOf(responses);
        }

        List<Buffer> responses() {
            return responses;
        }
    }
}
