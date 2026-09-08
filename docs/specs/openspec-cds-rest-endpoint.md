# OpenSpec: CDS-Native REST Endpoint

**Status:** DRAFT — Awaiting Review  
**Version:** 0.1  
**Date:** 2026-09-08

---

## Table of Contents

1. [Overview](#1-overview)
2. [Current State](#2-current-state)
3. [Scope](#3-scope)
4. [Non-Goals](#4-non-goals)
5. [Requirements](#5-requirements)
6. [Functional Behavior](#6-functional-behavior)
7. [Technical Design](#7-technical-design)
8. [Design Decisions](#8-design-decisions)
9. [Test Strategy](#9-test-strategy)
10. [Acceptance Criteria](#10-acceptance-criteria)
11. [Implementation Tasks](#11-implementation-tasks)
12. [Risks / Open Questions](#12-risks--open-questions)
13. [Summary](#13-summary)

---

## 1. Overview

### What we are building

A new HTTP endpoint — **`CdsRestEndpoint`** — that accepts OData-compatible request query syntax (`$filter`, `$top`, `$skip`, `$orderby`, `$expand`, `$select`, `$count`) and produces OData-compatible JSON responses (`{ value: [...] }` / `{ ... }`), but replaces Apache Olingo as the internal orchestrator with a **CDS-native, direct-dispatch layer** that operates at the same level as the existing `ODataProxyEndpoint`.

The endpoint is mounted alongside the existing OData V4 (`/odata/`) and OData Proxy (`/odataproxy/`) endpoints. All three are driven by the same CDS model; the choice of endpoint is declared per-service via the `@neonbee.endpoint` CDS annotation.

### Why it is needed

**Olingo is a heavy intermediary.** The `ODataV4Endpoint` (backed by `OlingoEndpointHandler`) runs Apache Olingo inside `executeBlocking`, occupies a worker thread for every request, and ties the response format and serialisation contract firmly to Olingo's own object model (`ODataRequest`, `ODataResponse`, Olingo serialisers). Adding capabilities (e.g. streaming, partial hydration, custom serialisation) requires extending Olingo's processor interfaces.

**`ODataProxyEndpoint` is the proof that Olingo can be bypassed.** It already dispatches requests directly via `AbstractEntityVerticle.requestEntity(Buffer.class, …)` without Olingo. However, it shifts all OData request parsing and response serialisation responsibility to the `EntityVerticle` implementation, creating coupling and duplication.

**The gap:** There is no endpoint that handles OData wire-format parsing and serialisation in a framework-owned, CDS-native layer *without* Olingo. Implementing such a layer unblocks:

- Simpler, lighter verticles (no Olingo classes on the verticle side).
- Reactive, non-blocking dispatch on Vert.x event loop threads.
- A clean extension point for future capabilities (streaming, JSON:API, GraphQL-style projections).
- Easier unit-testability of the dispatch layer.

---

## 2. Current State

### How the system behaves today

NeonBee exposes entity data through two OData-capable endpoints:

| Endpoint class | Base path | Olingo used | Verticle contract | Annotation value |
|---|---|---|---|---|
| `ODataV4Endpoint` | `/odata/` | Yes — full pipeline | Returns `EntityWrapper` | `"odata"` (or absent) |
| `ODataProxyEndpoint` | `/odataproxy/` | Metadata/batch only | Returns `Buffer` | `"odataproxy"` |

Both endpoints share the same `ODataV4Endpoint.refreshRouter` infrastructure:
- Listen for `EntityModelManager.EVENT_BUS_MODELS_LOADED_ADDRESS`.
- Iterate `EntityModel.getAllEdmxMetadata()`.
- Filter models by `@neonbee.endpoint` annotation value.
- Register one `Router` route per service.
- Delegate to a per-service `Handler<RoutingContext>`.

The `RawEndpoint` (`/raw/`) dispatches to `DataVerticle` instances by URL path, not by entity model.

### Confirmation that the requested functionality does not already exist

- No endpoint currently parses OData query syntax in a framework layer and dispatches to `EntityVerticle` *without* Olingo.
- `ODataProxyEndpoint` is the closest existing implementation but delegates all OData processing to the verticle.
- No class matching `CdsRestEndpoint`, `NativeRestEndpoint`, or similar exists in `io.neonbee.endpoint.*`.
- The `AbstractOpenAPIEndpoint` is schema-driven but targets `DataVerticle` (arbitrary JSON) rather than the OData entity model.

---

## 3. Scope

This spec covers:

1. A new `CdsRestEndpoint` class implementing `Endpoint`.
2. A new `CdsRestEndpointHandler` class implementing `Handler<RoutingContext>`.
3. A new `CdsRestQueryParser` that translates the OData query string into a `DataQuery` without Olingo.
4. A new `CdsRestResponseSerializer` that serialises `EntityWrapper` results to OData-compatible JSON without Olingo.
5. Integration with the existing model-loading infrastructure (`EntityModelManager`, `EntityModel`).
6. A new `@neonbee.endpoint = "rest"` annotation value for opting services into this endpoint.
7. Unit tests, integration tests, and test fixtures (CSN model, test verticle).
8. Documentation of the new endpoint in `ServerConfig` Javadoc and `README`.

---

## 4. Non-Goals

- **OData V2 support** — out of scope; the endpoint targets OData V4 JSON wire format only.
- **Full OData compliance** — not all OData V4 URL conventions are required in v1. See requirements for exact coverage.
- **$batch support** — explicitly excluded from v1; can be added in a follow-up.
- **Olingo removal** — `ODataV4Endpoint` and `ODataProxyEndpoint` remain unchanged.
- **CDS-native serialisation of $expand with deep navigation** — deferred; single-level expand may be supported, multi-level is not required.
- **Server-side pagination (nextLink)** — deferred.
- **CSRF / cookie-based auth** — out of scope; handled by the existing auth chain.
- **OpenAPI spec generation** from CDS model — out of scope.

---

## 5. Requirements

| ID | Statement |
|---|---|
| **REQ-1** | The system MUST provide a new `CdsRestEndpoint` that implements `io.neonbee.endpoint.Endpoint`. |
| **REQ-2** | The endpoint MUST be configurable via the standard `EndpointConfig` mechanism (FQCN in `type` field). |
| **REQ-3** | The endpoint MUST have a default base path of `/rest/`. |
| **REQ-4** | The endpoint MUST only serve CDS services annotated with `@neonbee.endpoint: 'rest'`. |
| **REQ-5** | The endpoint MUST subscribe to `EntityModelManager.EVENT_BUS_MODELS_LOADED_ADDRESS` and refresh its routes when models are reloaded, following the same lazy-init + lock pattern as `ODataV4Endpoint`. |
| **REQ-6** | The endpoint MUST support the same URI conversion modes (STRICT, CDS, LOOSE) as `ODataV4Endpoint`. |
| **REQ-7** | The endpoint MUST support a configurable `exposedEntities` block/allow list using `RegexBlockList`. |
| **REQ-8** | The endpoint handler MUST parse the following OData query options from the HTTP query string without using Apache Olingo: `$filter`, `$top`, `$skip`, `$orderby`, `$select`, `$count`. |
| **REQ-9** | The endpoint handler MUST pass parsed query options to the target `EntityVerticle` via `DataQuery` parameters using the same parameter key constants as `ProcessorHelper` (`OData.filter`, `OData.top`, etc.). |
| **REQ-10** | The endpoint handler MUST dispatch to the target `EntityVerticle` via `AbstractEntityVerticle.requestEntity(EntityWrapper.class, …)`. |
| **REQ-11** | The endpoint handler MUST serialise `EntityWrapper` results to OData-compatible JSON (i.e. `{ "@odata.context": "...", "value": [...] }` for collections and `{ "@odata.context": "...", ... }` for single entities) without using Apache Olingo serialisers. |
| **REQ-12** | The endpoint MUST return HTTP 200 for successful collection reads, 200 for single-entity reads, 404 when no matching entity is found. |
| **REQ-13** | The endpoint MUST return HTTP 404 when no `EntityVerticle` is registered for the requested entity type. |
| **REQ-14** | The endpoint MUST return HTTP 504 on timeout (i.e. when `DataException.FAILURE_CODE_TIMEOUT` is received). |
| **REQ-15** | The endpoint MUST return HTTP 405 for HTTP methods that cannot be mapped to a `DataAction`. |
| **REQ-16** | The endpoint MUST propagate `DataException` failures to the Vert.x `routingContext.fail(statusCode, cause)` error handler for all unhandled cases. |
| **REQ-17** | The endpoint SHOULD support `$expand` for single-level navigation properties. |
| **REQ-18** | The endpoint MAY be disabled via `EndpointConfig.setEnabled(false)`. |
| **REQ-19** | The endpoint MUST NOT be included in `ServerConfig.DEFAULT_ENDPOINT_CONFIGS`; it MUST be opt-in via explicit configuration. |
| **REQ-20** | The handler MUST run entirely on the Vert.x event loop (no `executeBlocking` for the dispatch path). |

---

## 6. Functional Behavior

### 6.1 Inputs

An HTTP request received at `/<basePath>/<serviceUri>/<EntitySet>` (collection) or `/<basePath>/<serviceUri>/<EntitySet>(<key>)` (single entity) with optional OData query parameters.

| Input | Description |
|---|---|
| HTTP Method | `GET` (READ), `POST` (CREATE), `PUT`/`PATCH` (UPDATE), `DELETE` (DELETE) |
| URL path | `/<basePath>/<uriConvertedServiceName>/<EntitySetName>(<key>)?` |
| Query string | OData system query options: `$filter`, `$top`, `$skip`, `$orderby`, `$select`, `$count`, `$expand` |
| Request body | JSON entity for POST/PUT/PATCH |
| Headers | Standard HTTP headers, forwarded to `DataQuery` |

### 6.2 Processing

```
HTTP request
  → CdsRestEndpointHandler.handle(RoutingContext)
      → map HTTP method → DataAction (HttpMethodToDataActionMapper)
      → parse URL path → schemaNamespace + entitySetName + optional key
      → parse query string → CdsRestQueryParser → Map<String,List<String>> parameters
            (extracts $filter, $top, $skip, $orderby, $select, $count, $expand)
            (places extracted values in DataQuery parameters using ProcessorHelper key constants)
      → build DataQuery(action, uriPath, parameters, headers, body)
      → build DataRequest(entityTypeFQN, dataQuery)
      → AbstractEntityVerticle.requestEntity(EntityWrapper.class, vertx, dataRequest, context)
            [async, non-blocking, event-loop safe]
            → EntityVerticle[<FQN>] event bus dispatch
            → EntityVerticle.retrieve() → EntityWrapper
      → CdsRestResponseSerializer.serialize(EntityWrapper, queryContext)
            → produces OData-compatible JSON (Vert.x JsonObject / JsonArray)
      → set Content-Type: application/json;odata.metadata=minimal
      → set OData-Version: 4.0
      → end response with status 200 (or 201 for CREATE, 204 for no-content)
```

### 6.3 Outputs

| Scenario | Status | Body |
|---|---|---|
| Collection read (no key) | 200 | `{ "@odata.context": "...", "value": [ {...}, ... ] }` |
| Single entity read (with key) | 200 | `{ "@odata.context": "...", "PropertyA": ..., ... }` |
| Single entity not found | 404 | Error JSON (via ErrorHandler) |
| Create (POST) | 201 | Created entity JSON |
| Update (PUT/PATCH) — entity returned | 200 | Updated entity JSON |
| Update — no content | 204 | empty |
| Delete | 204 | empty |
| No verticle registered | 404 | Error JSON |
| Timeout | 504 | Error JSON |
| Method not allowed | 405 | Error JSON |

### 6.4 Validation

- If `entitySetName` cannot be determined from the URL path (using `AbstractEntityVerticle.URI_PATH_PATTERN` or equivalent), return HTTP 400.
- If the entity is on the `exposedEntities` block list, return HTTP 403.
- If the HTTP method cannot be mapped to a `DataAction`, return HTTP 405.
- Query parameter parsing errors (malformed `$top`, `$skip` values) return HTTP 400.

### 6.5 Error handling

All failures that are not explicitly handled above are delegated to the Vert.x `routingContext.fail(statusCode, cause)` and picked up by the configured `ErrorHandler`. The handler follows the same pattern as `RawEndpoint`:

```java
if (cause instanceof DataException de) {
    switch (de.failureCode()) {
    case FAILURE_CODE_NO_HANDLERS → routingContext.fail(404);
    case FAILURE_CODE_TIMEOUT     → routingContext.fail(504);
    default                       → routingContext.fail(-1, cause);
    }
} else {
    routingContext.fail(-1, cause);
}
```

### 6.6 Edge cases

- **No models loaded at startup**: The lazy-init pattern from `ODataV4Endpoint` is reused. If no model is loaded when the first request arrives, the router will have no service routes after the init cycle, resulting in HTTP 404 (routed to `NotFoundHandler`).
- **Model reload mid-request**: The route registration swap pattern (register new routes first, then remove old ones) from `ODataV4Endpoint.refreshRouter` is reused to avoid downtime.
- **Concurrent init requests**: The `SharedDataAccessor.getLocalLock` pattern from `ODataV4Endpoint` is reused.
- **Entity key with special characters**: The URL must be percent-decoded before use in entity matching.
- **$count=true as inline count**: If `$count=true` is present, the response MUST include `"@odata.count": N`.

---

## 7. Technical Design

### 7.1 New components

#### `io.neonbee.endpoint.rest.CdsRestEndpoint`
- Implements `Endpoint`.
- `getDefaultConfig()`: returns `new EndpointConfig().setType(CdsRestEndpoint.class.getName()).setBasePath("/rest/").setAdditionalConfig(new JsonObject().put("uriConversion", "STRICT"))`.
- `createEndpointRouter(Vertx, String, JsonObject)`: identical lazy-init + event-bus-listener pattern to `ODataV4Endpoint.createEndpointRouter`. Calls `refreshRouter` on first request and on model reload.
- `refreshRouter(…)`: identical structure to `ODataV4Endpoint.refreshRouter`; uses `filterModels` (annotation value `"rest"`), uses `CdsRestEndpointHandler` instead of `OlingoEndpointHandler`.
- `filterModels(EntityModel)`: returns true iff `@neonbee.endpoint` annotation value equals `"rest"`.

> **[ASSUMPTION-1]** The `refreshRouter` method and the lazy-init route pattern can be extracted into a shared abstract base class (e.g. `AbstractCdsEndpoint`) to avoid duplication between `ODataV4Endpoint`, `ODataProxyEndpoint`, and `CdsRestEndpoint`. This is marked as an open question (see §12).

#### `io.neonbee.endpoint.rest.CdsRestEndpointHandler`
- Implements `Handler<RoutingContext>`.
- Constructor takes `ServiceMetadata edmxMetadata` (for `@odata.context` URL construction and entity key parsing from EDM), `ODataV4Endpoint.UriConversion uriConversion`.
- `handle(RoutingContext)`:
  1. Map HTTP method → `DataAction`.
  2. Build `NormalizedUri` (reuse `ODataV4Endpoint.normalizeUri`).
  3. Parse key predicate from resource path, if present.
  4. Delegate query parsing to `CdsRestQueryParser.parse(requestQuery, edmxMetadata, entitySetName)`.
  5. Build `DataQuery` with `action`, `uriPath = "/" + schemaNamespace + resourcePath`, parsed parameters, headers, body.
  6. Build `DataRequest` with `new FullQualifiedName(schemaNamespace, entitySetName)`.
  7. Call `AbstractEntityVerticle.requestEntity(EntityWrapper.class, vertx, dataRequest, context)`.
  8. On success: call `CdsRestResponseSerializer.serialize(ew, ...)` and end response.
  9. On failure: map to HTTP status codes and call `routingContext.fail(...)`.

> **[ASSUMPTION-2]** `ServiceMetadata` is passed to the handler (same as `OlingoEndpointHandler`), so the handler can use EDM types for key predicate parsing and `@odata.context` URL construction without re-loading the model.

#### `io.neonbee.endpoint.rest.CdsRestQueryParser`
- Static utility class (no state).
- `parse(String rawQuery, EdmEntitySet edmEntitySet)` → `Map<String, List<String>> parameters`
  - Extracts `$filter`, `$top`, `$skip`, `$orderby`, `$select`, `$count`, `$expand` from the raw query string.
  - Places values under the `ProcessorHelper` key constants (`OData.filter`, `OData.top`, `OData.skip`, `OData.orderby`, `OData.expand`, `OData.count.size`).
  - Validates `$top` and `$skip` are non-negative integers; throws `IllegalArgumentException` on invalid values (caller maps to HTTP 400).
  - **Does NOT use Olingo for parsing.** Uses plain string operations or a minimal expression parser.
  - Unknown `$` system query options return HTTP 400 (to signal unsupported features clearly).
  - Custom query options (no `$` prefix, e.g. `sap-client`) are passed through as-is.

> **[ASSUMPTION-3]** `$filter` syntax validation is deferred: the raw filter string is passed to the `EntityVerticle` as-is; no client-side pre-validation is performed in v1. The verticle is responsible for interpreting it. This aligns with the `ODataProxyEndpoint` pattern.

#### `io.neonbee.endpoint.rest.CdsRestResponseSerializer`
- Static utility class (no state).
- `serialize(EntityWrapper ew, FullQualifiedName fqn, String contextUrl, boolean isCollection, boolean includeCount)` → `JsonObject`
  - Converts Olingo `Entity` objects (in `ew.getEntities()`) to Vert.x `JsonObject` instances.
  - For a collection: `{ "@odata.context": contextUrl, "value": [ ... ], "@odata.count": N (optional) }`.
  - For a single entity: `{ "@odata.context": contextUrl, "PropertyA": v, ... }`.
  - Property value serialisation: uses Olingo `Property.getValue()` for typed values; converts to JSON-compatible types (String, Number, Boolean, null).
  - Returns `null` body JSON for `null` EntityWrapper → caller returns HTTP 204.

> **[ASSUMPTION-4]** Complex Olingo types (Geography, Duration, Decimal with precision) are serialised as their `.toString()` representation in v1. A follow-up can add proper JSON serialisation per OData JSON Format spec.

### 7.2 Modified components

| File | Change |
|---|---|
| `io.neonbee.config.ServerConfig` | Add Javadoc entry for the new endpoint (no code change to `DEFAULT_ENDPOINT_CONFIGS` — opt-in only). |
| `README.md` / `docs/` | Document new endpoint, annotation value, config key. |

> **No production code changes** are required in `ODataV4Endpoint`, `ODataProxyEndpoint`, `EntityModelManager`, `EntityVerticle`, `DataVerticle`, or `ServerVerticle`.

### 7.3 New package

```
src/main/java/io/neonbee/endpoint/rest/
  CdsRestEndpoint.java
  CdsRestEndpointHandler.java
  CdsRestQueryParser.java
  CdsRestResponseSerializer.java
```

### 7.4 Data flow diagram

```
HTTP GET /rest/my.Service/Products?$filter=Price gt 10&$top=5
          │
          ▼
CdsRestEndpointHandler
  normalizeUri(routingContext, "my.Service")
    → schemaNamespace="my.Service", entityName="Products", resourcePath="/Products"
  CdsRestQueryParser.parse("$filter=Price gt 10&$top=5", …)
    → parameters = { "OData.filter": ["Price gt 10"], "OData.top": ["5"] }
  DataQuery(READ, "/my.Service/Products", parameters, headers, null)
  DataRequest(FQN("my.Service", "Products"), query)
          │
          ▼
AbstractEntityVerticle.requestEntity(EntityWrapper.class, …)
  event bus → EntityVerticle[my.Service.Products]
          │
          ▼ EntityWrapper { typeName: my.Service.Products, entities: [...] }
          │
          ▼
CdsRestResponseSerializer.serialize(ew, fqn, contextUrl, isCollection=true, includeCount=false)
  → JsonObject { "@odata.context": "...", "value": [ { "Id": 1, "Price": 15.0 }, ... ] }
          │
          ▼
HTTP 200 application/json;odata.metadata=minimal
```

### 7.5 API changes

None. The new endpoint is additive; no existing API surface changes.

### 7.6 Configuration changes

A user enables the endpoint by adding to `config/server.yaml`:

```yaml
endpoints:
  - type: "io.neonbee.endpoint.rest.CdsRestEndpoint"
    enabled: true
    basePath: "/rest/"           # optional — override default
    uriConversion: "strict"      # optional — defaults to STRICT
    exposedEntities:             # optional — RegexBlockList
      blockList: []
      allowList: []
```

CDS model:
```cds
service ProductService @(neonbee.endpoint: 'rest') {
  entity Products { ... }
}
```

### 7.7 Persistence / database changes

None.

---

## 8. Design Decisions

### DD-1: Reuse `ODataV4Endpoint` lazy-init + refresh pattern

**Decision:** Replicate (not inherit) the `createEndpointRouter`/`refreshRouter` pattern.

**Rationale:** `ODataV4Endpoint.refreshRouter` is `protected` and the class is not abstract. Inheriting would drag in `UriConversion`, `NEONBEE_ENDPOINT_CDS_SERVICE_ANNOTATION`, and other OData-specific fields. A clean copy with the shared infrastructure (lock, event bus, route swap) is preferable until a shared abstract base is introduced.

**Alternative considered:** Extend `ODataV4Endpoint` (like `ODataProxyEndpoint` does). Rejected because the dispatch mechanism is fundamentally different and Olingo references would leak into the new class.

**Alternative considered:** Extract `AbstractModelAwareEndpoint` base class immediately. Deferred to avoid scope creep; marked as open question.

---

### DD-2: `CdsRestQueryParser` does not use Olingo

**Decision:** Parse `$filter`, `$top`, `$skip`, `$orderby`, `$select`, `$expand`, `$count` with plain string operations. Pass `$filter` raw to the verticle without pre-validation.

**Rationale:** The core motivation for this endpoint is to eliminate the Olingo dependency from the dispatch path. If Olingo is used for query parsing, the benefit is lost.

**Alternative considered:** Use Olingo's `UriParser` for query option extraction. Rejected — re-introduces Olingo dependency, requires full EDM loading on handler path.

**Risk:** `$filter` expressions are passed verbatim. The verticle must parse them (or ignore them). This is the same contract as `ODataProxyEndpoint`. If the verticle does nothing with `$filter`, the client gets unfiltered data — no framework error. See §12 for mitigation.

---

### DD-3: Response serialisation without Olingo serialisers

**Decision:** Implement `CdsRestResponseSerializer` as a converter from Olingo `Entity`/`Property` objects to Vert.x `JsonObject`/`JsonArray`.

**Rationale:** Olingo `ODataSerializer` requires a full `ServiceMetadata` + `EdmEntityType` + `ContextURL` chain to produce output. Replicating this is complex but necessary to decouple from Olingo. The output format is well-specified by the OData JSON Format spec.

**Alternative considered:** Reuse Olingo serialiser. Rejected — would require `executeBlocking` and re-introduce the Olingo dependency.

**Risk:** Edge cases in Olingo's type serialisation may differ from the hand-rolled implementation. This is acceptable for v1 given the scope of types NeonBee CDS models typically use.

---

### DD-4: `@neonbee.endpoint = 'rest'` as the filter annotation value

**Decision:** Use annotation value `"rest"` (consistent with the `"odata"` and `"odataproxy"` convention).

**Alternative considered:** `"cdsrest"` or `"native"`. Rejected as more verbose.

**[OPEN-1]** The annotation value must be confirmed by the team (see §12).

---

### DD-5: No `$batch` in v1

**Decision:** HTTP 405 is returned for `POST /$batch` requests.

**Rationale:** Batch support requires multipart parsing (Olingo's `FixedFormatDeserializerImpl` or custom). Deferred to avoid scope creep.

---

### DD-6: `REQ-19` — Not in `DEFAULT_ENDPOINT_CONFIGS`

**Decision:** The `CdsRestEndpoint` is NOT added to `ServerConfig.DEFAULT_ENDPOINT_CONFIGS`.

**Rationale:** It is a new, non-default endpoint. Adding it to defaults would change the exposed surface for every NeonBee instance without operator consent. Users who want it must configure it explicitly.

---

## 9. Test Strategy

### 9.1 Unit tests

| Class | Tests |
|---|---|
| `CdsRestQueryParser` | Parse `$top`, `$skip` (valid/invalid integers); `$filter` passthrough; `$orderby` single and multi-field; `$select`; `$count`; `$expand`; unknown `$` option → exception; non-`$` options passthrough; empty query string |
| `CdsRestResponseSerializer` | Null EntityWrapper → null JSON; empty collection → `{ value: [] }`; single entity → flat JSON; collection with count; property types (String, Int, Boolean, Date, Decimal, null); `@odata.context` URL construction |
| `CdsRestEndpointHandler` | HTTP method mapping; 404 for no verticle; 504 for timeout; 405 for unsupported method; 403 for blocked entity; 400 for bad query |
| `CdsRestEndpoint` | `getDefaultConfig()` values; `filterModels` includes `@neonbee.endpoint: 'rest'` models and excludes others |

### 9.2 Integration tests

Extend `ODataEndpointTestBase`. Tests deploy a test `EntityVerticle` backed by a test CSN model with `@neonbee.endpoint: 'rest'`.

| Test class | Coverage |
|---|---|
| `CdsRestReadEntitiesTest` | GET collection, GET with `$top`/`$skip`, GET with `$orderby`, GET with `$select`, GET with `$count` |
| `CdsRestReadEntityTest` | GET single entity by key (String key, Int key); 404 for missing entity |
| `CdsRestFilterTest` | GET with `$filter` — verifies filter string is passed in `DataQuery.parameters` |
| `CdsRestCreateEntityTest` | POST entity, 201 with body |
| `CdsRestUpdateEntityTest` | PUT entity, 200 with body; 204 no-content |
| `CdsRestDeleteEntityTest` | DELETE entity, 204 |
| `CdsRestErrorHandlerTest` | 400 bad query, 404 no verticle, 504 timeout, 405 method not allowed |
| `CdsRestEndpointConfigTest` | Confirm endpoint is NOT loaded by default; confirm opt-in via config works; confirm `@neonbee.endpoint: 'odata'` services are NOT served at `/rest/` |

### 9.3 Positive cases

- Collection read returns all entities.
- `$top=2` returns at most two entities (verticle must honour parameter).
- Single-entity read returns one entity JSON object.
- POST creates entity and returns 201.
- DELETE returns 204.

### 9.4 Negative cases

- GET `/rest/<service>/NonExistentSet` → 404.
- GET `/rest/<unknownService>/Products` → 404 (no route registered).
- GET with `$top=abc` → 400.
- POST to `$batch` sub-path → 405.
- Service with `@neonbee.endpoint: 'odata'` not served at `/rest/` → 404.

### 9.5 Boundary cases

- Empty collection → `{ "value": [] }`, HTTP 200.
- Single entity in collection → single-element `value` array.
- `$skip` larger than collection size → empty `value` array.
- Entity key with special characters (URL-encoded) → correctly decoded before FQN dispatch.
- Model reload while request in flight → request completes with old model (or new model); no crash.

---

## 10. Acceptance Criteria

| ID | Criterion | Maps to |
|---|---|---|
| **AC-1** | `CdsRestEndpoint` implements `Endpoint` and can be loaded by `MountableEndpoint.create` using its FQCN. | REQ-1, REQ-2 |
| **AC-2** | `CdsRestEndpoint.getDefaultConfig().getBasePath()` returns `"/rest/"`. | REQ-3 |
| **AC-3** | A CSN service with `@neonbee.endpoint: 'rest'` is served at `/rest/<uriConvertedServiceName>/`. A service with `@neonbee.endpoint: 'odata'` is NOT served at `/rest/`. | REQ-4 |
| **AC-4** | After `EntityModelManager.reloadModels()`, the `/rest/` endpoint serves the newly loaded models within one request cycle. | REQ-5 |
| **AC-5** | `GET /rest/<svc>/Products?$top=2` sends a `DataQuery` with `parameters["OData.top"] = ["2"]` to the `EntityVerticle`. | REQ-8, REQ-9 |
| **AC-6** | The handler calls `AbstractEntityVerticle.requestEntity(EntityWrapper.class, …)` (verified by integration test with real verticle). | REQ-10 |
| **AC-7** | `GET /rest/<svc>/Products` returns `{ "@odata.context": "...", "value": [...] }` with `Content-Type: application/json;odata.metadata=minimal` and `OData-Version: 4.0`. | REQ-11 |
| **AC-8** | Correct HTTP status codes: 200 collection, 200 single, 404 missing, 201 created, 204 no-content, 404 no handler, 504 timeout, 405 method not allowed. | REQ-12–REQ-15 |
| **AC-9** | `DataException.FAILURE_CODE_NO_HANDLERS` produces HTTP 404; `FAILURE_CODE_TIMEOUT` produces HTTP 504; other failures delegate to `routingContext.fail(-1, cause)`. | REQ-16 |
| **AC-10** | The endpoint is NOT present in `ServerConfig.DEFAULT_ENDPOINT_CONFIGS`. | REQ-19 |
| **AC-11** | The handler path does not invoke `vertx.executeBlocking` (verified by code review / test). | REQ-20 |
| **AC-12** | All unit tests in §9.1 pass. | REQ-8, REQ-11 |
| **AC-13** | All integration tests in §9.2 pass with a real embedded NeonBee instance. | All REQs |

---

## 11. Implementation Tasks

Tasks are ordered by dependency. Each task references the primary files expected to be created or modified.

| # | Task | Files / classes |
|---|---|---|
| T-1 | Create `CdsRestQueryParser` with full unit tests | `src/main/java/io/neonbee/endpoint/rest/CdsRestQueryParser.java`, `src/test/java/io/neonbee/endpoint/rest/CdsRestQueryParserTest.java` |
| T-2 | Create `CdsRestResponseSerializer` with unit tests | `src/main/java/io/neonbee/endpoint/rest/CdsRestResponseSerializer.java`, `src/test/java/io/neonbee/endpoint/rest/CdsRestResponseSerializerTest.java` |
| T-3 | Create `CdsRestEndpointHandler` (depends on T-1, T-2); unit tests with Mockito | `src/main/java/io/neonbee/endpoint/rest/CdsRestEndpointHandler.java`, `src/test/java/io/neonbee/endpoint/rest/CdsRestEndpointHandlerTest.java` |
| T-4 | Create `CdsRestEndpoint` (depends on T-3); unit tests | `src/main/java/io/neonbee/endpoint/rest/CdsRestEndpoint.java`, `src/test/java/io/neonbee/endpoint/rest/CdsRestEndpointTest.java` |
| T-5 | Create test CSN model file with `@neonbee.endpoint: 'rest'` annotation | `src/test/resources/io/neonbee/endpoint/rest/TestRestService.csn` |
| T-6 | Create test `EntityVerticle` for integration tests | `src/test/java/io/neonbee/endpoint/rest/verticle/TestRestEntityVerticle.java` |
| T-7 | Integration test: collection read, `$top`/`$skip`/`$orderby`/`$select`/`$count` | `src/test/java/io/neonbee/test/endpoint/rest/CdsRestReadEntitiesTest.java` |
| T-8 | Integration test: single entity read, 404 | `src/test/java/io/neonbee/test/endpoint/rest/CdsRestReadEntityTest.java` |
| T-9 | Integration test: `$filter` parameter passthrough | `src/test/java/io/neonbee/test/endpoint/rest/CdsRestFilterTest.java` |
| T-10 | Integration test: CREATE, UPDATE, DELETE | `src/test/java/io/neonbee/test/endpoint/rest/CdsRestWriteEntityTest.java` |
| T-11 | Integration test: error cases (400, 404, 504, 405) | `src/test/java/io/neonbee/test/endpoint/rest/CdsRestErrorHandlerTest.java` |
| T-12 | Integration test: endpoint config — opt-in only, annotation filtering | `src/test/java/io/neonbee/test/endpoint/rest/CdsRestEndpointConfigTest.java` |
| T-13 | Update `ServerConfig` Javadoc to document new endpoint type | `src/main/java/io/neonbee/config/ServerConfig.java` |
| T-14 | Update `README.md` with new endpoint documentation | `README.md` |

---

## 12. Risks / Open Questions

### Technical risks

| Risk | Severity | Mitigation |
|---|---|---|
| Hand-rolled response serialisation diverges from OData JSON Format spec edge cases (e.g. Decimal formatting, Date/Time types, null vs. missing properties) | Medium | Define a type-mapping table in `CdsRestResponseSerializer` and add boundary tests per type. |
| `$filter` is passed verbatim to the verticle; if the verticle ignores it, clients receive unfiltered data with no error | Medium | Add an `ASSUMPTION-3` note in Javadoc. Recommendation: the verticle SHOULD validate and apply the filter, and the framework MAY add a warning log if the parameter is present but the verticle returns all entities. |
| Model reload race during the first lazy-init request | Low | Already handled by the `SharedDataAccessor.getLocalLock` pattern (same as `ODataV4Endpoint`). |
| `refreshRouter` code duplication across three endpoint classes | Low | Acceptable for v1. Tracked as **[OPEN-2]**. |
| URI_PATH_PATTERN in `AbstractEntityVerticle` does not match all valid OData path forms | Low | Use `ODataV4Endpoint.NormalizedUri` for path parsing (already shared) to ensure consistency. |

### Compatibility risks

| Risk | Severity | Note |
|---|---|---|
| Enabling the endpoint at `/rest/` conflicts with an existing route from another module | Low | Base path is configurable. |
| `@neonbee.endpoint: 'rest'` annotation value clashes with a future standard | Low | This is an internal NeonBee annotation; no external standard is affected. |

### Open questions requiring decision

| ID | Question | Impact |
|---|---|---|
| **[OPEN-1]** | Confirm the annotation value: `'rest'` or `'cdsrest'`? | REQ-4, AC-3, CDS model examples, docs |
| **[OPEN-2]** | Should a shared abstract base class (e.g. `AbstractCdsEndpoint`) be introduced in this change, or deferred? | T-4 scope |
| **[OPEN-3]** | Is `$expand` in scope for v1? REQ-17 says SHOULD. What is the minimum viable expand behavior (single-level navigation only, or any)? | T-3, T-7 scope |
| **[OPEN-4]** | Should the endpoint expose the `$metadata` document (like `ODataProxyEndpoint`)? Required for some OData clients. | T-4 scope |
| **[OPEN-5]** | Should `$filter` parsing errors (syntax errors in the filter expression) produce HTTP 400 at the endpoint level, or be passed to the verticle? | REQ-8, CdsRestQueryParser design |
| **[OPEN-6]** | Default `uriConversion` for the new endpoint: should it match `ODataV4Endpoint` (STRICT) or default to CDS? | REQ-6, EndpointConfig default |
| **[OPEN-7]** | Should the new endpoint be added to `DEFAULT_ENDPOINT_CONFIGS` in a future release, or remain permanently opt-in? | REQ-19, migration guide |

---

## 13. Summary

### Proposed solution

Introduce a new `CdsRestEndpoint` (and associated handler + utilities) in a new package `io.neonbee.endpoint.rest` that:

1. Plugs into the existing `Endpoint` SPI and `MountableEndpoint` lifecycle — no changes to `ServerVerticle`, `EntityModelManager`, or any existing verticle.
2. Reuses the model-loading and route-refresh infrastructure from `ODataV4Endpoint` (lazy-init, event-bus listener, route swap, `RegexBlockList`, `UriConversion`).
3. Dispatches requests directly to `EntityVerticle` via `AbstractEntityVerticle.requestEntity(EntityWrapper.class, …)` — no Olingo on the request path.
4. Parses OData query syntax in a lightweight, non-Olingo `CdsRestQueryParser` and serialises `EntityWrapper` results in a framework-owned `CdsRestResponseSerializer`.
5. Is opt-in: requires explicit endpoint configuration and `@neonbee.endpoint: 'rest'` on the CDS service.

### Files / modules likely to change

**New files (production):**
- `src/main/java/io/neonbee/endpoint/rest/CdsRestEndpoint.java`
- `src/main/java/io/neonbee/endpoint/rest/CdsRestEndpointHandler.java`
- `src/main/java/io/neonbee/endpoint/rest/CdsRestQueryParser.java`
- `src/main/java/io/neonbee/endpoint/rest/CdsRestResponseSerializer.java`

**Modified (documentation only):**
- `src/main/java/io/neonbee/config/ServerConfig.java` — Javadoc update
- `README.md`

**New files (test):**
- 8 test classes under `src/test/java/io/neonbee/endpoint/rest/` and `src/test/java/io/neonbee/test/endpoint/rest/`
- 1 test CSN resource under `src/test/resources/io/neonbee/endpoint/rest/`

### Open questions requiring your decision

1. **[OPEN-1]** Annotation value: `'rest'` or `'cdsrest'`?
2. **[OPEN-2]** Abstract base class for endpoint infrastructure: in scope for this change or deferred?
3. **[OPEN-3]** Is `$expand` required in v1?
4. **[OPEN-4]** Is `$metadata` endpoint required?
5. **[OPEN-5]** `$filter` syntax errors: HTTP 400 at framework level or pass to verticle?
6. **[OPEN-6]** Default `uriConversion`: STRICT or CDS?
7. **[OPEN-7]** Will this endpoint ever enter `DEFAULT_ENDPOINT_CONFIGS`?

---

**Recommendation: NEEDS CLARIFICATION**

The architecture is fully understood and the implementation path is clear. However, 7 open questions (particularly OPEN-1 through OPEN-4) need your decisions before implementation begins, as they affect the public API surface, CDS model conventions, and test scope.
