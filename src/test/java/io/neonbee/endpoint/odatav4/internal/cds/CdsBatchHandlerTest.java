package io.neonbee.endpoint.odatav4.internal.cds;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

class CdsBatchHandlerTest {

    // --- extractBoundary ---

    @Test
    @DisplayName("extractBoundary parses simple boundary value")
    void extractBoundarySimple() {
        assertThat(CdsBatchHandler.extractBoundary("multipart/mixed; boundary=batch_abc123"))
                .isEqualTo("batch_abc123");
    }

    @Test
    @DisplayName("extractBoundary strips surrounding quotes")
    void extractBoundaryQuoted() {
        assertThat(CdsBatchHandler.extractBoundary("multipart/mixed; boundary=\"batch xyz\""))
                .isEqualTo("batch xyz");
    }

    @Test
    @DisplayName("extractBoundary returns null for missing boundary")
    void extractBoundaryMissing() {
        assertThat(CdsBatchHandler.extractBoundary("multipart/mixed")).isNull();
        assertThat(CdsBatchHandler.extractBoundary(null)).isNull();
    }

    // --- parseMultipart ---

    @Test
    @DisplayName("parseMultipart produces one BatchPart per non-changeset boundary section")
    void parseMultipartSinglePart() {
        String body = "--batch\r\nContent-Type: application/http\r\n\r\nGET /Books HTTP/1.1\r\n\r\n--batch--";
        List<CdsBatchHandler.BatchPart> parts = CdsBatchHandler.parseMultipart(body, "batch");

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).isChangeSet()).isFalse();
    }

    @Test
    @DisplayName("parseMultipart detects changeset part via Content-Type: multipart/mixed")
    void parseMultipartChangeSet() {
        String inner = "--cs\r\nContent-Type: application/http\r\n\r\nPOST /Books HTTP/1.1\r\n\r\n--cs--";
        String body = "--batch\r\nContent-Type: multipart/mixed; boundary=cs\r\n\r\n" + inner + "\r\n--batch--";
        List<CdsBatchHandler.BatchPart> parts = CdsBatchHandler.parseMultipart(body, "batch");

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0)).isInstanceOf(CdsBatchHandler.ChangeSetBatchPart.class);
        CdsBatchHandler.ChangeSetBatchPart changeSet = (CdsBatchHandler.ChangeSetBatchPart) parts.get(0);
        assertThat(changeSet.parts()).hasSize(1);
    }

    @Test
    @DisplayName("parseMultipart handles multiple parts")
    void parseMultipartMultipleParts() {
        String body = "--b\r\nContent-Type: application/http\r\n\r\nGET /Books HTTP/1.1\r\n\r\n"
                + "--b\r\nContent-Type: application/http\r\n\r\nGET /Authors HTTP/1.1\r\n\r\n--b--";
        List<CdsBatchHandler.BatchPart> parts = CdsBatchHandler.parseMultipart(body, "b");

        assertThat(parts).hasSize(2);
    }

    @Test
    @DisplayName("parseMultipart returns empty list when body has no parts")
    void parseMultipartEmpty() {
        String body = "--b--";
        List<CdsBatchHandler.BatchPart> parts = CdsBatchHandler.parseMultipart(body, "b");

        assertThat(parts).isEmpty();
    }

    // --- handle ---

    @Test
    @DisplayName("handle dispatches each part and produces multipart response")
    void handleDispatchesParts() {
        String body = "--b\r\nContent-Type: application/http\r\n\r\nGET /Books HTTP/1.1\r\n\r\n"
                + "--b\r\nContent-Type: application/http\r\n\r\nGET /Authors HTTP/1.1\r\n\r\n--b--";
        AtomicInteger dispatchCount = new AtomicInteger(0);

        Future<Buffer> result = CdsBatchHandler.handle(
                Buffer.buffer(body),
                "multipart/mixed; boundary=b",
                partBody -> {
                    dispatchCount.incrementAndGet();
                    return Future.succeededFuture(Buffer.buffer("HTTP/1.1 200\r\n\r\n"));
                });

        assertThat(result.succeeded()).isTrue();
        assertThat(dispatchCount.get()).isEqualTo(2);
        String responseStr = result.result().toString();
        assertThat(responseStr).contains("--batch_");
        assertThat(responseStr).contains("HTTP/1.1 200");
    }

    @Test
    @DisplayName("handle fails when Content-Type is missing boundary")
    void handleMissingBoundaryFails() {
        Future<Buffer> result = CdsBatchHandler.handle(
                Buffer.buffer("--b--"),
                "multipart/mixed",
                partBody -> Future.succeededFuture(Buffer.buffer()));

        assertThat(result.failed()).isTrue();
        assertThat(result.cause().getMessage()).contains("boundary");
    }

    // --- serializeMultipart ---

    @Test
    @DisplayName("serializeMultipart wraps each response in application/http content part")
    void serializeMultipart() {
        List<CdsBatchHandler.PartResponse> responses = List.of(
                new CdsBatchHandler.PartResponse(Buffer.buffer("HTTP/1.1 200\r\n\r\n"), false),
                new CdsBatchHandler.PartResponse(Buffer.buffer("HTTP/1.1 201\r\n\r\n"), false));

        Buffer result = CdsBatchHandler.serializeMultipart(responses, "resp_boundary");
        String resultStr = result.toString();

        assertThat(resultStr).contains("--resp_boundary\r\n");
        assertThat(resultStr).contains("Content-Type: application/http");
        assertThat(resultStr).contains("HTTP/1.1 200");
        assertThat(resultStr).contains("HTTP/1.1 201");
        assertThat(resultStr).contains("--resp_boundary--");
    }

    @Test
    @DisplayName("serializeMultipart wraps change-set responses in multipart/mixed sub-envelope")
    void serializeMultipartChangeSet() {
        List<CdsBatchHandler.PartResponse> responses = List.of(
                new CdsBatchHandler.PartResponse(Buffer.buffer("HTTP/1.1 201\r\n\r\n"), true));

        Buffer result = CdsBatchHandler.serializeMultipart(responses, "outer");
        String resultStr = result.toString();

        assertThat(resultStr).contains("Content-Type: multipart/mixed");
        assertThat(resultStr).contains("boundary=changeset_");
    }
}
