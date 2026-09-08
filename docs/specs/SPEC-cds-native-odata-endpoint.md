# OpenSpec: CDS-Native OData Endpoint

**Status:** DRAFT  
**Version:** 0.1  
**Date:** 2026-09-08  
**Author:** [OPEN]

---

## 1. Overview

### What We Are Building

A new REST endpoint — `CdsODataEndpoint` — that exposes the same CDS-derived entity models as the existing `ODataV4Endpoint` but replaces Apache Olingo as the internal request-processing layer with a CDS-native dispatch layer. The endpoint:

- Accepts the same OData V4 wire format on the request side: standard OData URL syntax, system query options (`$filter`, `$top`, `$skip`, `$select`, `$expand`, `$orderby`, `$count`), and OData JSON bodies.
- Returns the same OData V4 JSON response format (e.g. `{"@odata.context": "...", "value": [...]}`) as the current endpoint.
- Is mounted at a configurable base path (default: `/cdsodata/`) and registered in `ServerConfig` alongside the existing endpoints.
- Dispatches to the same `EntityVerticle` infrastructure already used by `ODataProxyEndpoint`—NOT through Olingo's processor pipeline.

### Why It Is Needed

The existing OData V4 endpoint (`ODataV4Endpoint` + `OlingoEndpointHandler`) runs all request handling through Apache Olingo's blocking processor pipeline. Olingo is called via `vertx.executeBlocking`, meaning each request occupies a worker thread for the full duration of parsing, dispatch, serialisation, and de-serialisation. This creates:

1. **Architectural coupling** – business logic that lives in `EntityVerticle` is orchestrated by Olingo's internal processor SPI, requiring NeonBee to implement `EntityCollectionProcessor`, `EntityProcessor`, `PrimitiveProcessor`, etc., and to adapt between Olingo and Vert.x futures in complex ways.
2. **Throughput ceiling** – worker-thread pool saturation under high load; the CDS-native path stays on the event loop for dispatch.
3. **Complexity overhead** – 20+ classes under `endpoint/odatav4/internal/olingo/` are required to drive a dispatch that is ultimately just: "parse the URL, call the right entity verticle, serialise the response."

An `ODataProxyEndpoint` already demonstrates that the entity-verticle dispatch can be done without Olingo (for entities), but it still uses Olingo for `$metadata` and reuses `mapToODataRequest` from `OlingoEndpointHandler`. The new endpoint makes that pattern first-class, adds proper OData JSON serialisation, and is configured as an independent endpoint type in `ServerConfig`.

---

## 2. Current State

### How the System Works Today

| Endpoint class | Default base path | Dispatch layer | CDS annotation filter |
|---|---|---|---|
| `ODataV4Endpoint` | `/odata/` | Olingo (`OlingoEndpointHandler`) | `@neonbee.endpoint: "odata"` or absent |
| `ODataProxyEndpoint` | `/odataproxy/` | CDS-native for entities; Olingo for `$metadata` | `@neonbee.endpoint: "odataproxy"` |
| `RawEndpoint` | `/raw/` | Direct `DataVerticle` dispatch | N/A |

Both OData endpoints read the same `EntityModel` registry (managed by `EntityModelManager`) and use `ODataV4Endpoint.UriConversion` (STRICT, CDS, LOOSE) for URL-to-service mapping.

### Confirmation That the Feature Does Not Exist

- No class named `CdsODataEndpoint`, `CdsEndpoint`, or similar exists anywhere in `src/main/`.
- `ODataProxyEndpoint` is the closest prior art but is not the feature requested: it still calls `OlingoEndpointHandler.mapToODataRequest` and delegates metadata generation to Olingo's `OData.newInstance().createRawHandler()`.
- There is no handler that parses OData V4 query options natively (without Olingo) and serialises a standards-compliant `application/json;odata.metadata=minimal` response.

---

## 3. Scope

The following are **in scope**:

- New `CdsODataEndpoint` class implementing `Endpoint`, mounted at configurable base path (default `/cdsodata/`).
- New `CdsODataEndpointHandler` implementing `Handler<RoutingContext>`, responsible for all request routing logic.
- CDS-native URL parsing: extract service namespace, entity set name, key predicate, and OData system query options from the incoming URL using the existing `ODataV4Endpoint.NormalizedUri` helper.
- Forwarding to `EntityVerticle` via the existing `AbstractEntityVerticle.requestEntity(...)` infrastructure (same as `ODataProxyEndpointHandler`).
- Serialising the `EntityWrapper` result to OData V4 minimal JSON format using the Olingo serialiser API (not its processor pipeline) or a direct JSON construction approach (see Design Decisions).
- Support for all three `UriConversion` modes (STRICT, CDS, LOOSE).
- `$metadata` document served via Olingo (same approach as `ODataProxyEndpointHandler.handleMetadataRequest`).
- `CDS annotation`-based service filtering: `@neonbee.endpoint: "cdsodata"` includes a service; absence of the annotation excludes the service (opposite of `ODataV4Endpoint`'s default-include behaviour — see Open Questions).
- HTTP methods: GET (read entity/entity set/count), POST (create), PUT/PATCH (update), DELETE.
- Query options: `$filter`, `$top`, `$skip`, `$select`, `$expand`, `$orderby`, `$count`.
- Error mapping from `DataException` failure codes to HTTP status codes (same mapping as `RawEndpoint`).
- Configuration via `EndpointConfig.additionalConfig` (same pattern as `ODataV4Endpoint`).
- Registration in `ServerConfig.DEFAULT_ENDPOINT_CONFIGS` as disabled by default (opt-in).
- Unit tests and integration tests following existing conventions.

---

## 4. Non-Goals

The following are **explicitly not in scope**:

- OData V2 support.
- OData batch (`$batch`) requests. *(ASSUMPTION: excluded for initial implementation; see Open Questions)*
- OData action/function imports.
- Server-side pagination beyond what `$top`/`$skip` already provides.
- Change tracking (`$deltatoken`).
- Olingo's metadata E-tag caching.
- A migration path or compatibility shim for existing `ODataV4Endpoint` consumers.
- Changes to `ODataV4Endpoint`, `ODataProxyEndpoint`, or `OlingoEndpointHandler`.
- Modifications to `EntityVerticle`, `EntityWrapper`, or `DataQuery`.
- Karate/external API tests (no Karate framework is present in this repository).

---

## 5. Requirements

| ID | Requirement | Priority |
|---|---|---|
| REQ-1 | The system MUST expose a new `CdsODataEndpoint` that implements `io.neonbee.endpoint.Endpoint` and can be registered via `EndpointConfig`. | MUST |
| REQ-2 | The endpoint MUST be mountable at a configurable `basePath`, defaulting to `/cdsodata/`. | MUST |
| REQ-3 | The endpoint MUST support `UriConversion` modes STRICT, CDS, and LOOSE, configured via `uriConversion` in `EndpointConfig.additionalConfig`. | MUST |
| REQ-4 | The endpoint MUST serve `$metadata` documents as valid OData V4 EDMX XML responses. | MUST |
| REQ-5 | The endpoint MUST serve entity-set (`GET /ServicePath/EntitySet`) requests, returning OData V4 JSON with `{"@odata.context": "...", "value": [...]}`. | MUST |
| REQ-6 | The endpoint MUST serve single-entity (`GET /ServicePath/EntitySet(key)`) requests, returning OData V4 JSON with the entity properties plus `@odata.context`. | MUST |
| REQ-7 | The endpoint MUST pass OData system query options (`$filter`, `$top`, `$skip`, `$select`, `$expand`, `$orderby`, `$count`) through to the `DataQuery` parameters map for the `EntityVerticle` to consume, following the same convention as `ProcessorHelper.odataRequestToQuery`. | MUST |
| REQ-8 | The endpoint MUST map `DataException.failureCode()` `FAILURE_CODE_NO_HANDLERS` → HTTP 404, `FAILURE_CODE_TIMEOUT` → HTTP 504, and any other `DataException` code to its embedded status code. | MUST |
| REQ-9 | The endpoint MUST respond with HTTP 403 for entity sets or services blocked by `exposedEntities` `RegexBlockList` (same as `ODataV4Endpoint`). | MUST |
| REQ-10 | The endpoint MUST reload its router when the `EntityModelManager` publishes `EVENT_BUS_MODELS_LOADED_ADDRESS` (same lazy-init + reload pattern as `ODataV4Endpoint`). | MUST |
| REQ-11 | The endpoint SHOULD be disabled by default in `ServerConfig.DEFAULT_ENDPOINT_CONFIGS` to avoid unintentional exposure. | SHOULD |
| REQ-12 | The endpoint MUST include a service only when the CDS model carries `@neonbee.endpoint: "cdsodata"` (or a configurable annotation value). *(see Open Question OQ-3)* | MUST |
| REQ-13 | The endpoint MUST respond with HTTP 405 for unsupported HTTP methods. | MUST |
| REQ-14 | The endpoint MUST set `Content-Type: application/json;odata.metadata=minimal;odata.streaming=true` on entity set/entity responses. | MUST |
| REQ-15 | The endpoint MUST set `OData-Version: 4.0` response header. | MUST |

---

## 6. Functional Behavior

### Inputs

- HTTP requests arriving at `{basePath}/{serviceSegment}/{entitySet}[({key})]?{queryOptions}`.
- OData system query options as URL query parameters: `$filter`, `$top`, `$skip`, `$select`, `$expand`, `$orderby`, `$count`, `$format`.
- HTTP method determines `DataAction`: GET→READ, POST→CREATE, PUT/PATCH→UPDATE, DELETE→DELETE.
- Request body (for POST/PUT/PATCH): OData JSON entity body.

### Processing

1. **Router initialisation (lazy):** On first request, call `EntityModelManager.getSharedModels()`, register routes per service using `UriConversion`, arm the event-bus refresh listener. Same locking pattern as `ODataV4Endpoint.createEndpointRouter`.
2. **Request classification:**
   - Path ends with `$metadata` or equals the service root → `handleMetadataRequest`.
   - All other paths → `handleEntityRequest`.
3. **Entity dispatch:**
   - Use `ODataV4Endpoint.normalizeUri(routingContext, schemaNamespace)` to extract `resourcePath`, `requestQuery`, etc.
   - Build `DataQuery` from `NormalizedUri` (action, uriPath = `"/" + schemaNamespace + resourcePath`, parameters from `DataQuery.parseEncodedQueryString(requestQuery)`, headers, body).
   - Determine `FullQualifiedName` by parsing the first path segment of `resourcePath` (same regex as `ODataProxyEndpointHandler.ENTITY_NAME_PATTERN`).
   - Call `AbstractEntityVerticle.requestEntity(Buffer.class, vertx, dataRequest, context)` (same as `ODataProxyEndpointHandler.handleEntityRequest`).
4. **Response serialisation:**
   - The `EntityVerticle` returns a `Buffer` (since `requestEntity(Buffer.class, ...)` is used). *(ASSUMPTION: see OQ-4)*
   - Set `Content-Type`, `OData-Version`, status code from `DataContext.responseData()` or defaults.
   - Write buffer to `HttpServerResponse`.
5. **Metadata request:** Delegate to Olingo via `vertx.executeBlocking` (identical to `ODataProxyEndpointHandler.handleMetadataRequest`).

### Outputs

- `200 OK` with OData JSON body for successful reads.
- `201 Created` with OData JSON body for successful creates.
- `204 No Content` for successful updates/deletes.
- `404 Not Found` when no handler exists for the entity.
- `403 Forbidden` when the entity is blocked.
- `405 Method Not Allowed` for unsupported HTTP methods.
- `504 Gateway Timeout` on timeout.
- `500 Internal Server Error` for unexpected failures.

### Validation

- Entity set name MUST be parseable from the resource path; if not, respond HTTP 400.
- If `UriConversion` config value is unrecognised, fall back to STRICT (same as `ODataV4Endpoint.UriConversion.byName`).
- `EntityContainer` MUST NOT be null after loading the EDMX model; if null, log error and skip service registration (same as `ODataV4Endpoint.refreshRouter`).

### Error Handling

| Condition | HTTP status |
|---|---|
| `DataException(FAILURE_CODE_NO_HANDLERS)` | 404 |
| `DataException(FAILURE_CODE_TIMEOUT)` | 504 |
| `DataException(customCode)` | `customCode` |
| Other `Throwable` | 500 |
| Entity name not parseable | 400 |
| Entity on block list | 403 |
| Unsupported HTTP method | 405 |

### Edge Cases

- Service root request (`/cdsodata/Namespace.Service/`) — serve `$metadata` root document.
- Entity set with no registered verticle — return 404 (no handlers).
- Concurrent model reload during an in-flight request — the old route handles the in-flight request; new routes service subsequent requests (same as `ODataV4Endpoint`).
- Empty entity container (model loaded but `getEntityContainer()` is null) — skip route registration, log error.

---

## 7. Technical Design

### New Components

| Component | Package | Role |
|---|---|---|
| `CdsODataEndpoint` | `io.neonbee.endpoint.cdsodata` | Implements `Endpoint`; owns lazy-init router + event-bus refresh (extends or closely mirrors `ODataV4Endpoint`) |
| `CdsODataEndpointHandler` | `io.neonbee.endpoint.cdsodata` | `Handler<RoutingContext>`; handles entity and metadata requests without Olingo processor pipeline |

### Reused Components (no changes)

| Component | How reused |
|---|---|
| `ODataV4Endpoint.NormalizedUri` | URI normalisation: extracts `resourcePath`, `requestQuery`, `schemaNamespace`, `entityName`, `fullQualifiedName` |
| `ODataV4Endpoint.UriConversion` | Service-name-to-URL mapping |
| `ODataV4Endpoint.refreshRouter` | **Option A:** `CdsODataEndpoint extends ODataV4Endpoint`, overrides `getRequestHandler` to return `CdsODataEndpointHandler`. **Option B:** copy-minimal router init logic. See Design Decisions. |
| `OlingoEndpointHandler.mapToODataRequest` | Used only for `handleMetadataRequest`, identical to `ODataProxyEndpointHandler` |
| `AbstractEntityVerticle.requestEntity(Buffer.class, ...)` | Entity dispatch |
| `DataQuery.parseEncodedQueryString` | Query string parsing |
| `ProcessorHelper` constants (`ODATA_FILTER_KEY`, etc.) | Not directly needed by the handler, but the `DataQuery` parameters carry these keys for the verticle layer |
| `RegexBlockList` | Entity access control |
| `DataContextImpl` | Context creation from `RoutingContext` |
| `DataContext.STATUS_CODE_HINT` | Reading status from response data |
| `ODataProxyEndpointHandler.setHeaderValues` | Response header propagation (copy or extract to utility) |
| `ODataProxyEndpointHandler.getStatusCode(Map, int)` | Status code extraction |

### Inheritance Approach (Recommended)

```
Endpoint (interface)
  └── ODataV4Endpoint
        ├── ODataProxyEndpoint (existing, unchanged)
        └── CdsODataEndpoint (NEW — overrides getRequestHandler + filterModels + getDefaultConfig)
```

`CdsODataEndpoint` overrides:
- `getDefaultConfig()` — returns `basePath=/cdsodata/`, type = `CdsODataEndpoint.class.getName()`.
- `getRequestHandler(ServiceMetadata, UriConversion, JsonObject)` — returns `new CdsODataEndpointHandler(serviceMetadata, uriConversion)`.
- `filterModels(EntityModel)` — includes only models annotated with `@neonbee.endpoint: "cdsodata"`.

All router lifecycle code (lazy init, event-bus refresh, route sorting, `exposedEntities` block list, URI normalisation) is **inherited unchanged** from `ODataV4Endpoint`.

### Data Flow

```
HTTP Request
    │
    ▼
[ServerVerticle router]
    │  basePath match
    ▼
[CdsODataEndpoint router]  (lazy-init, refreshed on model reload)
    │  route: /{serviceSegment}/*
    ▼
[block-list handler]  (from ODataV4Endpoint.refreshRouter)
    │
    ▼
[CdsODataEndpointHandler.handle(RoutingContext)]
    │
    ├── $metadata / service root
    │       └──> Olingo executeBlocking → ODataResponse → Buffer
    │
    └── entity request
            ├── normalizeUri() → NormalizedUri
            ├── build DataQuery (action, uriPath, params, headers, body)
            ├── determine FullQualifiedName from resourcePath
            └── requestEntity(Buffer.class, ...) → Buffer
                    │
                    ▼ (event bus)
            [EntityVerticle.retrieve]
                    │
                    ▼
            Buffer (OData JSON) returned by verticle
                    │
                    ▼
            [CdsODataEndpointHandler] writes to HttpServerResponse
```

### API Changes

- `ServerConfig.DEFAULT_ENDPOINT_CONFIGS`: add `CdsODataEndpoint` entry (disabled by default — see REQ-11).
- `ServerConfig` Javadoc comment: document the new endpoint entry.
- No changes to any existing endpoint or handler class.

### Configuration

New `EndpointConfig` for `CdsODataEndpoint`:

```json
{
  "type": "io.neonbee.endpoint.cdsodata.CdsODataEndpoint",
  "enabled": false,
  "basePath": "/cdsodata/",
  "authenticationChain": [],
  "uriConversion": "STRICT",
  "exposedEntities": {}
}
```

Fields:
- `uriConversion` — string, one of STRICT / CDS / LOOSE, default STRICT.
- `exposedEntities` — same `RegexBlockList` JSON format as `ODataV4Endpoint`, default allow-all.

### Persistence / Database Changes

None.

---

## 8. Design Decisions

### DD-1: Extend `ODataV4Endpoint` vs. Standalone

**Chosen:** Extend `ODataV4Endpoint` (override `getRequestHandler` and `filterModels`).

**Rationale:** The entire lazy-init router, event-bus refresh, URI normalisation, block-list check, and route-sorting logic in `ODataV4Endpoint` is exactly what the new endpoint needs. Duplicating ~130 lines of tested code would introduce divergence. The extension point `getRequestHandler` already exists (introduced in commit history via `ODataProxyEndpoint`), so the pattern is established.

**Alternative considered:** Standalone class implementing `Endpoint` from scratch. Rejected because it would copy-paste the router lifecycle logic, creating two codepaths to maintain.

### DD-2: Response Serialisation — Use Olingo Serialiser vs. Direct JSON

**Chosen:** Use Olingo's `ODataSerializer` API (not the processor pipeline) for entity-set and entity responses.

**Context:** The OData V4 JSON format requires `@odata.context`, `@odata.count`, and correct primitive type mappings (Edm.DateTime as ISO-8601, Edm.Decimal as string, etc.). Re-implementing this from scratch is risky and creates a standards-compliance burden.

**Approach:** Use `OData.newInstance().createSerializer(ContentType.JSON)` to serialise `EntityCollection` or `Entity` objects. The `EntityWrapper` returned by `EntityVerticle` wraps Olingo `Entity` objects. The `CdsODataEndpointHandler` constructs an Olingo `EntityCollection`/`Entity` from the `EntityWrapper` result and serialises via `ODataSerializer`.

**ASSUMPTION (OQ-4):** `requestEntity(Buffer.class, ...)` returns the raw buffer already serialised by the verticle. If the verticle returns an `EntityWrapper` (not a `Buffer`), the handler must deserialise and re-serialise. Needs clarification — see OQ-4.

**Alternative considered:** Always return a raw Buffer from the verticle and skip Olingo serialisation entirely. This pushes serialisation responsibility to the verticle layer, which breaks the convention that verticles return domain objects, not wire-format bytes.

### DD-3: `$metadata` — Delegate to Olingo

**Chosen:** Reuse `OlingoEndpointHandler.mapToODataRequest` + `OData.newInstance().createRawHandler(serviceMetadata)` for `$metadata` requests, identical to `ODataProxyEndpointHandler.handleMetadataRequest`.

**Rationale:** `$metadata` is a static EDMX XML document derived entirely from the loaded `ServiceMetadata`. Olingo already generates it correctly. There is no business logic or verticle dispatch involved.

### DD-4: CDS Annotation Filter Logic

**Chosen:** Include a model only when the annotation `@neonbee.endpoint` is present **and** equals `"cdsodata"`. Models without the annotation are excluded (contrast with `ODataV4Endpoint`, which includes models that are absent-or-`"odata"`).

**Rationale:** The new endpoint is opt-in. A CDS author must explicitly annotate a service to expose it. This prevents accidental double-exposure.

**ASSUMPTION:** See OQ-3 — the annotation key name `"cdsodata"` may need confirmation.

---

## 9. Test Strategy

### Test Infrastructure

All tests SHOULD use the existing `ODataEndpointTestBase` / `NeonBeeTestBase` / `ODataResponseVerifier` infrastructure. Test verticles from `io.neonbee.test.endpoint.odata.verticle` (e.g. `TestService1EntityVerticle`) SHOULD be reused where possible.

### Unit Tests

**`CdsODataEndpointTest`** (mirrors `ODataV4EndpointTest`):
- Verify `getDefaultConfig()` returns correct type, basePath, and uriConversion.
- Verify `getRequestHandler` returns a `CdsODataEndpointHandler` instance.
- Verify `filterModels` includes only models with `@neonbee.endpoint: "cdsodata"` and excludes all others.
- Verify STRICT / CDS / LOOSE `UriConversion` modes produce correct route paths (inherited test, but confirm behaviour with new endpoint class).
- Verify that a service with null `EntityContainer` is skipped and an error is logged.

**`CdsODataEndpointHandlerTest`** (mirrors `OlingoEndpointHandlerTest`):
- `handleMetadataRequest`: verify that a `$metadata` response contains valid EDMX XML (`<edmx:Edmx`).
- `handleEntityRequest`: verify DataQuery is constructed correctly (action, uriPath, parameters) from a mock `RoutingContext`.
- `getFullQualifiedName`: verify correct FQN from various resource paths (same cases as `ODataProxyEndpointHandler`).
- Error mapping: verify `DataException(FAILURE_CODE_NO_HANDLERS)` → 404, timeout → 504, custom codes pass through.

### Integration Tests

**`CdsODataReadEntitiesTest`** (mirrors `ODataReadEntitiesTest`):
- GET entity set → returns all entities with `@odata.context` and `"value"` array.
- GET entity set with `$filter` → returns filtered subset.
- GET entity set with `$count=true` → returns `@odata.count` in response body.
- GET entity set with `$top`/`$skip` → returns paginated result.
- GET `/$count` → returns plain integer count.
- GET non-existent entity set → HTTP 404 with OData error body.

**`CdsODataReadEntityTest`** (mirrors `ODataReadEntityTest`):
- GET single entity by key (string / long key) → correct entity JSON.
- GET non-existent entity key → HTTP 404.

**`CdsODataErrorHandlerTest`** (mirrors `ODataErrorHandlerTest`):
- `DataException(400)` → HTTP 400.
- `DataException(403)` → HTTP 403.
- `DataException(404)` → HTTP 404.
- `DataException(500)` → HTTP 500.

**`CdsODataEndpointConfigTest`**:
- Endpoint is disabled by default in `ServerConfig`.
- When enabled with CDS URI conversion, service URLs are converted correctly.
- `exposedEntities` block list blocks entity with 403.

### Positive Cases

- Entity set read (all items, filter, top/skip, count).
- Single entity read (string key, long key, compound key).
- Metadata document returned for STRICT, CDS, and LOOSE URI conversion.

### Negative Cases

- Unknown entity set → 404.
- Entity on block list → 403.
- Unsupported HTTP method (e.g. TRACE) → 405.
- Gateway timeout → 504.
- Malformed query string → 400.

### Boundary Cases

- Entity set with zero entities (`[]`) → 200 with `{"value":[]}`.
- Service with `UriConversion.CDS` producing an empty path segment (service named `Service`) → served at root `/cdsodata/`.
- Model reload mid-flight: old routes continue serving in-flight requests.

---

## 10. Acceptance Criteria

| ID | Criterion | Requirement |
|---|---|---|
| AC-1 | `CdsODataEndpoint` is instantiable via reflection (no-arg constructor) and `getDefaultConfig()` returns `basePath="/cdsodata/"` and `type=CdsODataEndpoint.class.getName()`. | REQ-1, REQ-2 |
| AC-2 | A GET to `/cdsodata/{service}/$metadata` returns HTTP 200 with `Content-Type: application/xml` and body containing `<edmx:Edmx`. | REQ-4 |
| AC-3 | A GET to `/cdsodata/{service}/EntitySet` returns HTTP 200, `Content-Type` includes `application/json`, body is valid JSON with `"value"` array and `"@odata.context"`. | REQ-5, REQ-14, REQ-15 |
| AC-4 | A GET to `/cdsodata/{service}/EntitySet(key)` returns HTTP 200 with the single entity JSON. | REQ-6 |
| AC-5 | Query options `$filter`, `$top`, `$skip`, `$orderby`, `$count`, `$select`, `$expand` appear as entries in the `DataQuery.parameters` map forwarded to the `EntityVerticle`. | REQ-7 |
| AC-6 | A verticle returning `DataException(FAILURE_CODE_NO_HANDLERS)` results in HTTP 404. | REQ-8 |
| AC-7 | A verticle returning `DataException(FAILURE_CODE_TIMEOUT)` results in HTTP 504. | REQ-8 |
| AC-8 | An entity on the `exposedEntities` block list returns HTTP 403. | REQ-9 |
| AC-9 | After reloading models via `EVENT_BUS_MODELS_LOADED_ADDRESS`, the endpoint serves the new model's entity sets without restarting. | REQ-10 |
| AC-10 | `CdsODataEndpoint` is present in `ServerConfig.DEFAULT_ENDPOINT_CONFIGS` with `enabled=false`. | REQ-11 |
| AC-11 | A CDS service without `@neonbee.endpoint: "cdsodata"` is NOT registered by `CdsODataEndpoint`. A service with the annotation IS registered. | REQ-12 |
| AC-12 | TRACE (or any unmapped HTTP method) to an entity URL returns HTTP 405. | REQ-13 |
| AC-13 | All three `UriConversion` modes (STRICT, CDS, LOOSE) route to the correct service namespace. | REQ-3 |

---

## 11. Implementation Tasks

Tasks are ordered; later tasks depend on earlier ones unless noted.

| # | Task | Files / Classes | Notes |
|---|---|---|---|
| T-1 | Create package `io.neonbee.endpoint.cdsodata` | `src/main/java/io/neonbee/endpoint/cdsodata/` | |
| T-2 | Implement `CdsODataEndpoint` (extend `ODataV4Endpoint`) | `CdsODataEndpoint.java` | Override `getDefaultConfig`, `getRequestHandler`, `filterModels`. Default path `/cdsodata/`. |
| T-3 | Implement `CdsODataEndpointHandler` | `CdsODataEndpointHandler.java` | Copy the entity-dispatch structure from `ODataProxyEndpointHandler`, adapt serialisation. |
| T-4 | Implement `handleMetadataRequest` in `CdsODataEndpointHandler` | `CdsODataEndpointHandler.java` | Reuse `ODataProxyEndpointHandler.handleMetadataRequest` pattern. |
| T-5 | Implement `handleEntityRequest` in `CdsODataEndpointHandler` | `CdsODataEndpointHandler.java` | Use `normalizeUri`, build `DataQuery`, call `requestEntity(Buffer.class, ...)`. |
| T-6 | Implement error mapping in `CdsODataEndpointHandler` | `CdsODataEndpointHandler.java` | Map `DataException` failure codes to HTTP status codes. |
| T-7 | Register `CdsODataEndpoint` in `ServerConfig.DEFAULT_ENDPOINT_CONFIGS` (disabled) | `ServerConfig.java` | Add `CdsODataEndpoint.class` to the stream with `enabled=false`. Update Javadoc comment block. |
| T-8 | Unit test: `CdsODataEndpointTest` | `src/test/java/io/neonbee/endpoint/cdsodata/CdsODataEndpointTest.java` | Test `getDefaultConfig`, `getRequestHandler`, `filterModels`, URI conversion. |
| T-9 | Unit test: `CdsODataEndpointHandlerTest` | `src/test/java/io/neonbee/endpoint/cdsodata/CdsODataEndpointHandlerTest.java` | Test metadata, entity dispatch, FQN extraction, error mapping. |
| T-10 | Integration test: `CdsODataReadEntitiesTest` | `src/test/java/io/neonbee/test/endpoint/cdsodata/CdsODataReadEntitiesTest.java` | Reuse `TestService1EntityVerticle`; annotate the test CSN with `@neonbee.endpoint: "cdsodata"`. |
| T-11 | Integration test: `CdsODataReadEntityTest` | `src/test/java/io/neonbee/test/endpoint/cdsodata/CdsODataReadEntityTest.java` | |
| T-12 | Integration test: `CdsODataErrorHandlerTest` | `src/test/java/io/neonbee/test/endpoint/cdsodata/CdsODataErrorHandlerTest.java` | |
| T-13 | Integration test: `CdsODataEndpointConfigTest` | `src/test/java/io/neonbee/test/endpoint/cdsodata/CdsODataEndpointConfigTest.java` | Test disabled-by-default, block list, URI conversions. |
| T-14 | Update `ServerConfigTest` to cover the new endpoint entry | `src/test/java/io/neonbee/config/ServerConfigTest.java` | Assert `CdsODataEndpoint` appears in default config as disabled. |
| T-15 | *(Optional)* Extract shared static helpers (`setHeaderValues`, `getStatusCode`) from `ODataProxyEndpointHandler` to a package-accessible utility class | `io.neonbee.endpoint.odatav4.ODataResponseHelper` or similar | Avoids duplication between proxy handler and new handler. Coordinate with owner of `ODataProxyEndpoint`. |

---

## 12. Risks and Open Questions

### Technical Risks

| Risk | Mitigation |
|---|---|
| **R-1:** Serialisation fidelity — if `requestEntity(Buffer.class, ...)` returns raw bytes already serialised by the verticle, those bytes may lack `@odata.context` or `@odata.count` annotations required by OData V4 JSON. | Clarify what format the `Buffer` contains (see OQ-4). If the buffer is plain entity JSON without OData annotations, the handler must wrap it. |
| **R-2:** Thread safety — `ODataV4Endpoint.refreshRouter` modifies the router's route list while requests may be in-flight. | Already handled by the inherited router-refresh logic; no additional risk. |
| **R-3:** `UriConversion.CDS` can map multiple services to the same path if names collide (documented in `ODataV4Endpoint`). | Same risk exists for `ODataV4Endpoint`; no mitigation needed beyond the existing log warning. |
| **R-4:** Olingo worker-thread usage for `$metadata` is still present (via `executeBlocking`). | Acceptable: `$metadata` requests are infrequent and metadata generation is read-only. Noted as a future optimisation. |

### Open Questions

| ID | Question | Impact | Owner |
|---|---|---|---|
| OQ-1 | Should `$batch` be included in the initial implementation? The `ODataProxyEndpointHandler` supports it; omitting it means feature parity is incomplete. | High — affects scope of T-3/T-5. | Decision required. |
| OQ-2 | Should the new endpoint be **enabled** or **disabled** by default in `ServerConfig.DEFAULT_ENDPOINT_CONFIGS`? This spec recommends disabled (opt-in), but if it is meant to replace `ODataV4Endpoint` for all new services, enabled might be preferred. | Medium — affects REQ-11, AC-10, T-7. | Decision required. |
| OQ-3 | What annotation value should filter services into this endpoint? Options: `"cdsodata"` (clean, new), `"cds"`, or something else. The spec proposes `"cdsodata"` but the team must agree. | Low — easy to change before implementation. | Decision required. |
| OQ-4 | Does `AbstractEntityVerticle.requestEntity(Buffer.class, ...)` return a Buffer that is already a complete OData V4 JSON response (including `@odata.context`), or does it return the raw entity bytes without OData envelope? The `ODataProxyEndpointHandler` uses this method and writes the buffer directly. If the buffer is a complete OData response, no serialisation step is needed; if not, the handler must add the OData envelope. | **Critical** — determines whether T-5 needs an OData serialisation step or can write the buffer directly. | Investigation required before T-3. |
| OQ-5 | Should `CdsODataEndpoint` also support the `ODataProxyEndpoint`'s raw-batch interception mechanism (`CONFIG_RAW_BATCH_PROCESSING`)? | Low if OQ-1 resolves to no batch support. | Decision required. |
| OQ-6 | The existing `ODataProxyEndpointHandler` uses `AbstractEntityVerticle.requestEntity(Buffer.class, ...)` but the `OlingoEndpointHandler` uses `requestEntity` returning `EntityWrapper`. The "CDS-native" aspect of the spec says we bypass Olingo. Does "CDS-native dispatch" mean using the same `Buffer`-returning path as `ODataProxyEndpointHandler`, or does it mean something else? | High — shapes the entire handler design. | Needs clarification from feature owner. |

---

## Summary

### Proposed Solution

Introduce `CdsODataEndpoint extends ODataV4Endpoint` in a new package `io.neonbee.endpoint.cdsodata`. This class overrides exactly three methods (`getDefaultConfig`, `getRequestHandler`, `filterModels`) and inherits all router-lifecycle, URI-normalisation, block-list, and UriConversion logic from `ODataV4Endpoint`. A new `CdsODataEndpointHandler` replaces Olingo's processor pipeline for entity dispatch: it uses `NormalizedUri` to parse the URL, builds a `DataQuery`, and calls `AbstractEntityVerticle.requestEntity(Buffer.class, ...)` directly. `$metadata` continues to be served via Olingo's raw handler (same as `ODataProxyEndpointHandler`). The endpoint is disabled by default in `ServerConfig`.

### Files / Modules Likely to Change

| File | Change |
|---|---|
| `src/main/java/io/neonbee/endpoint/cdsodata/CdsODataEndpoint.java` | **NEW** |
| `src/main/java/io/neonbee/endpoint/cdsodata/CdsODataEndpointHandler.java` | **NEW** |
| `src/main/java/io/neonbee/config/ServerConfig.java` | Add `CdsODataEndpoint` to `DEFAULT_ENDPOINT_CONFIGS`; update Javadoc |
| `src/test/java/io/neonbee/endpoint/cdsodata/CdsODataEndpointTest.java` | **NEW** |
| `src/test/java/io/neonbee/endpoint/cdsodata/CdsODataEndpointHandlerTest.java` | **NEW** |
| `src/test/java/io/neonbee/test/endpoint/cdsodata/CdsODataReadEntitiesTest.java` | **NEW** |
| `src/test/java/io/neonbee/test/endpoint/cdsodata/CdsODataReadEntityTest.java` | **NEW** |
| `src/test/java/io/neonbee/test/endpoint/cdsodata/CdsODataErrorHandlerTest.java` | **NEW** |
| `src/test/java/io/neonbee/test/endpoint/cdsodata/CdsODataEndpointConfigTest.java` | **NEW** |
| `src/test/java/io/neonbee/config/ServerConfigTest.java` | Add assertion for `CdsODataEndpoint` in default config |

### Open Questions Requiring Your Decision

1. **OQ-4 (CRITICAL):** What does `requestEntity(Buffer.class, ...)` actually return — a complete OData V4 JSON envelope, or raw entity bytes? This determines whether the handler needs an OData serialisation step.
2. **OQ-6 (HIGH):** Does "CDS-native dispatch" mean using the `Buffer`-returning path (same as `ODataProxyEndpointHandler`), or something different?
3. **OQ-1 (HIGH):** Is `$batch` in scope for the initial implementation?
4. **OQ-2 (MEDIUM):** Should the new endpoint be enabled or disabled by default?
5. **OQ-3 (LOW):** Confirm annotation filter value: `"cdsodata"` or something else?

### Recommendation

**NEEDS CLARIFICATION** — OQ-4 and OQ-6 are blocking for T-3 and T-5. The serialisation strategy and the exact contract of `requestEntity(Buffer.class, ...)` must be confirmed before the handler implementation can begin. All other aspects of the spec are ready for review.
