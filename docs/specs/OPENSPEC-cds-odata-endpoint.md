# OpenSpec: CDS-Native OData Endpoint

**ID:** SPEC-CDS-ODATA-001  
**Status:** NEEDS CLARIFICATION (see Open Questions)  
**Author:** (to be filled)  
**Created:** 2026-09-08

---

## 1. Overview

### What we are building

A new HTTP endpoint class, **`CdsODataEndpoint`**, that:

- Is mounted alongside the existing `ODataV4Endpoint` (`/odata/`) and `ODataProxyEndpoint` (`/odataproxy/`)
- Exposes entity data using the **same OData V4 wire format**: OData query syntax in request parameters (`$select`, `$filter`, `$top`, `$skip`, `$orderby`, `$count`) and OData-flavoured JSON in response bodies
- Replaces Apache **Olingo** as the internal orchestrator with a **CDS-native, direct-dispatch** approach: the endpoint builds a `DataQuery` from the incoming HTTP request and calls `AbstractEntityVerticle.requestEntity(Buffer.class, ...)` directly — exactly as `ODataProxyEndpointHandler` does today

### Why it is needed

The existing Olingo-based `ODataV4Endpoint` couples the HTTP-to-entity mapping tightly to Olingo's processor pipeline, which imposes:

1. **Synchronous blocking** — every request must be `executeBlocking`-wrapped because Olingo is synchronous
2. **Olingo internals exposure** — verticle implementations must work within Olingo's processor contract even when they do not need full OData semantics
3. **Impedance mismatch** — CDS-annotated services have their own canonical form; Olingo re-derives types from the EDMX document rather than the CDS model

The `ODataProxyEndpoint` (`/odataproxy/`) already solves (1) and (2) by dispatching directly via `requestEntity(Buffer.class, ...)` and leaving the verticle responsible for producing the wire-format buffer. The new `CdsODataEndpoint` applies the same philosophy but:

- Is CDS-model-aware (opt-in annotation `neonbee.endpoint: 'cdsodata'`)
- Has its own base path (`/cdsodata/`) separate from `odataproxy`
- Is a clean, purpose-built class rather than a proxy-specific extension

---

## 2. Current State

### Endpoint landscape today

| Endpoint class | Base path | Handler | Annotation value | In `DEFAULT_ENDPOINT_CONFIGS` |
|---|---|---|---|---|
| `ODataV4Endpoint` | `/odata/` | `OlingoEndpointHandler` | `"odata"` (or absent) | Yes |
| `ODataProxyEndpoint` | `/odataproxy/` | `ODataProxyEndpointHandler` | `"odataproxy"` | No |
| `RawEndpoint` | `/raw/` | `RawEndpointHandler` | N/A | Yes |
| `HealthEndpoint` | `/health/` | — | N/A | Yes |
| `MetricsEndpoint` | `/metrics/` | — | N/A | Yes |
| `StatusEndpoint` | `/status/` | — | N/A | Yes |

### Confirmation: CdsODataEndpoint does not exist

A search of `src/main/java` finds no class with a name containing `CdsOdata`, `CdsRest`, or any variant. No `@neonbee.endpoint` annotation value `"cdsodata"` is in use. **The requested endpoint is genuinely new.**

### Key existing patterns (reusable as-is)

| Pattern | Where | Relevance |
|---|---|---|
| Lazy-init router with event-bus refresh | `ODataV4Endpoint.createEndpointRouter` | Full reuse via inheritance |
| `UriConversion` enum (STRICT / CDS / LOOSE) | `ODataV4Endpoint.UriConversion` | Reuse: default to CDS for this endpoint |
| `NormalizedUri` URI parsing | `ODataV4Endpoint.normalizeUri` | Reuse as-is |
| `@neonbee.endpoint` CDS annotation filter | `ODataV4Endpoint.filterModels` + `ODataProxyEndpoint.filterModels` | Override `filterModels` in new class |
| Direct entity dispatch | `ODataProxyEndpointHandler.handleEntityRequest` | Extract or replicate for new handler |
| `$metadata` via Olingo raw handler | `ODataProxyEndpointHandler.handleMetadataRequest` | Reuse pattern exactly |
| `requestEntity(Buffer.class, ...)` dispatch | `AbstractEntityVerticle.requestEntity` | Core dispatch mechanism |
| `DataQuery` construction from OData request | `ODataProxyEndpointHandler.odataRequestToQuery` | Reuse as-is |
| `completeResponse` / status/header propagation | `ODataProxyEndpointHandler.completeResponse` | Reuse pattern |
| Test base | `ODataEndpointTestBase` + `NeonBeeTestBase` | Same test infrastructure |
| Entity model mocking | `ODataV4EndpointTest.mockEntityModel` | Same helper |

---

## 3. Scope

The following is **in scope** for this implementation:

- New class `CdsODataEndpoint` in package `io.neonbee.endpoint.odatav4` (or a new sub-package, see OQ-5)
- New class `CdsODataEndpointHandler` (request handler)
- CDS annotation `neonbee.endpoint: 'cdsodata'` as the opt-in mechanism
- Default base path `/cdsodata/`
- Supported query options: `$select`, `$filter`, `$top`, `$skip`, `$orderby`, `$count`
- `$metadata` document endpoint (via Olingo `executeBlocking` — same as `ODataProxyEndpointHandler`)
- Default `UriConversion`: **CDS** (assumption — see OQ-3)
- Entity-collection GET (`EntitySet`) and single-entity GET (`EntitySet(key)`)
- Mutation operations: POST (create), PATCH/PUT (update), DELETE
- Unit tests for `CdsODataEndpoint` and `CdsODataEndpointHandler`
- Integration tests extending `ODataEndpointTestBase`
- YAML configuration documentation

---

## 4. Non-Goals

The following are **explicitly out of scope** for this implementation:

- Changes to `ODataV4Endpoint`, `ODataProxyEndpoint`, `OlingoEndpointHandler`, or any existing production class
- `$batch` support (deferred — see OQ-1)
- `$expand` across navigation properties (deferred — see OQ-2)
- Adding the endpoint to `ServerConfig.DEFAULT_ENDPOINT_CONFIGS` (opt-in only — see OQ-4)
- CDS-model-level query validation inside the endpoint layer
- Schema migration or persistence changes
- OData V2 compatibility

---

## 5. Requirements

| ID | Requirement | Priority |
|---|---|---|
| REQ-1 | The system MUST provide a new `CdsODataEndpoint` class implementing `Endpoint`. | MUST |
| REQ-2 | The endpoint MUST only expose CDS services annotated with `@neonbee.endpoint: 'cdsodata'`. | MUST |
| REQ-3 | The endpoint MUST mount at a configurable base path, defaulting to `/cdsodata/`. | MUST |
| REQ-4 | The endpoint MUST support `UriConversion` (STRICT / CDS / LOOSE), configured via `uriConversion` key in `EndpointConfig.additionalConfig`. Default MUST be CDS. | MUST |
| REQ-5 | The endpoint MUST support `exposedEntities` block/allow-list (same semantics as `ODataV4Endpoint`). | MUST |
| REQ-6 | On first request the endpoint router MUST initialise lazily and register an event-bus listener for `EVENT_BUS_MODELS_LOADED_ADDRESS` to refresh routes when models reload. | MUST |
| REQ-7 | The handler MUST dispatch entity requests by calling `AbstractEntityVerticle.requestEntity(Buffer.class, ...)` with a `DataQuery` constructed from the OData request path and query parameters. | MUST |
| REQ-8 | The handler MUST support `GET` (collection and single entity), `POST`, `PATCH`, `PUT`, and `DELETE` HTTP methods. | MUST |
| REQ-9 | The handler MUST serve `$metadata` requests via Olingo's raw handler (`OData.newInstance().createRawHandler(serviceMetadata)`) wrapped in `executeBlocking`. | MUST |
| REQ-10 | The handler MUST forward status code and response headers set in `DataContext.responseData()` back to the HTTP response, including `OData-Version: 4.0`. | MUST |
| REQ-11 | On entity request failure the handler MUST fail the routing context with an appropriate HTTP status code (derived from the cause, defaulting to 500). | MUST |
| REQ-12 | The endpoint MUST NOT be included in `ServerConfig.DEFAULT_ENDPOINT_CONFIGS`. It MUST be enabled only by explicit configuration. | MUST |
| REQ-13 | The endpoint SHOULD support the `exposedEntities` block/allow-list check before dispatching. | SHOULD |
| REQ-14 | The endpoint MAY support `$expand` in a future iteration. | MAY |
| REQ-15 | All requirements MUST be covered by at least one automated test. | MUST |

---

## 6. Functional Behavior

### 6.1 Inputs

An incoming HTTP request to `/cdsodata/<service-path>/<EntitySet>[(<key>)][?<query-options>]`.

Supported query options:
- `$select` — comma-separated property names
- `$filter` — OData filter expression
- `$top` — integer; maximum number of entities to return
- `$skip` — integer; number of entities to skip
- `$orderby` — property name(s) with optional `asc`/`desc`
- `$count` — `true`/`false`; whether to include total count

### 6.2 Processing

```
Incoming HTTP request
    │
    ▼
ODataV4Endpoint.refreshRouter  (inherited)
    ├── filters models by @neonbee.endpoint = 'cdsodata'
    ├── applies UriConversion to derive service URI path
    └── registers route → block-list check → CdsODataEndpointHandler
                                                      │
                                       ┌──────────────┴──────────────┐
                                       │                             │
                              isMetadataRequest?           isEntityRequest?
                                       │                             │
                              Olingo raw handler         ODataProxyEndpointHandler
                              (executeBlocking)          .odataRequestToQuery(...)
                                       │                             │
                                       │              AbstractEntityVerticle
                                       │              .requestEntity(Buffer.class,...)
                                       │                             │
                                       └─────────────────────────────┘
                                                      │
                                             completeResponse(...)
                                          (status + headers + buffer)
```

### 6.3 Outputs

- **Success**: HTTP 200 (or status from `DataContext.responseData()`), `Content-Type: application/json`, `OData-Version: 4.0`, body = `Buffer` returned by the entity verticle
- **Not found**: HTTP 404 when no verticle is registered for the requested entity type
- **Forbidden**: HTTP 403 when `exposedEntities` block-list rejects the entity
- **Bad request**: HTTP 400 on malformed query syntax (assumption — see OQ-6)
- **Internal error**: HTTP 500 on unexpected verticle failure
- **Metadata**: HTTP 200, `Content-Type: application/xml`, body = OData EDMX document

### 6.4 Validation

- URI path MUST resolve to a known schema namespace registered in `EntityModelManager`
- Entity name MUST be a valid OData simple identifier
- Query options MUST be parseable; malformed options result in 400 (see OQ-6)

### 6.5 Error Handling

Follows `ODataProxyEndpointHandler.completeResponse` pattern:
- `routingContext.fail(statusCode, cause)` on failure
- Status code derived from `OlingoEndpointHandler.getStatusCode(cause)` (already handles `ODataApplicationException` and `ReplyException`)

### 6.6 Edge Cases

| Case | Behaviour |
|---|---|
| No verticle registered for entity type | `requestEntity` fails → handler passes 404 to routing context |
| Service registered but no entity container | Skipped during `refreshRouter` with error log (same as `ODataV4Endpoint`) |
| Two services map to same CDS URI path | Non-deterministic — operator responsibility to choose unique names (same constraint as existing `CDS` mapping) |
| Model reload while request in flight | Request uses the handler registered at dispatch time; new handler registered after refresh |
| `$metadata` request | Served via Olingo on the worker thread pool, not dispatched to entity verticles |

---

## 7. Technical Design

### 7.1 New Components

#### `CdsODataEndpoint` — `io.neonbee.endpoint.odatav4`

```
CdsODataEndpoint extends ODataV4Endpoint
```

Overrides only:

| Method | Change |
|---|---|
| `getDefaultConfig()` | Returns `EndpointConfig` with type = `CdsODataEndpoint.class.getName()`, basePath = `/cdsodata/`, `uriConversion = CDS` |
| `filterModels(EntityModel)` | Returns `true` iff `@neonbee.endpoint` annotation value equals `"cdsodata"` (case-insensitive) |
| `getRequestHandler(ServiceMetadata, UriConversion, JsonObject)` | Returns `new CdsODataEndpointHandler(serviceMetadata, uriConversion)` |

Everything else — lazy-init, event-bus refresh, route registration, `NormalizedUri`, block-list — is **inherited unchanged** from `ODataV4Endpoint`.

> **Note on the existing TODO comment** at `ODataV4Endpoint:307`:
> ```java
> // TODO depending on the config either create Olingo or CDS based OData V4 handlers here
> ```
> The `CdsODataEndpoint` sub-class approach avoids modifying `ODataV4Endpoint.getRequestHandler`. The TODO remains for future consolidation if desired.

#### `CdsODataEndpointHandler` — `io.neonbee.endpoint.odatav4`

```
CdsODataEndpointHandler implements Handler<RoutingContext>
```

Internal structure closely mirrors `ODataProxyEndpointHandler` without `$batch`:

| Responsibility | Source of logic |
|---|---|
| Detect `$metadata` request | Copy of `ODataProxyEndpointHandler.isMetadataRequest` |
| Serve `$metadata` | Copy of `ODataProxyEndpointHandler.handleMetadataRequest` |
| Build `ODataRequest` from routing context | Reuse `OlingoEndpointHandler.mapToODataRequest` (static, package-visible) |
| Build `DataQuery` from `ODataRequest` | Reuse `ODataProxyEndpointHandler.odataRequestToQuery` (static, package-visible) |
| Dispatch to entity verticle | `AbstractEntityVerticle.requestEntity(Buffer.class, vertx, dataRequest, context)` |
| Complete response | Adopt `ODataProxyEndpointHandler.completeResponse` pattern inline |
| Status/header propagation | Adopt `ODataProxyEndpointHandler.setHeaderValues` / `getStatusCode` |

**Assumption (see OQ-7):** `odataRequestToQuery` and `setHeaderValues` are currently `static` package-private in `ODataProxyEndpointHandler`. They will need to be either:
- Made package-accessible / extracted to a shared utility class, OR
- Duplicated in `CdsODataEndpointHandler`

Preference: extract to a package-private utility class `ODataEndpointUtil` in `io.neonbee.endpoint.odatav4`. This avoids duplication and keeps both handlers thin. The extracted methods are already pure static functions with no state.

### 7.2 Data Flow (sequence)

```
Client
  │  GET /cdsodata/catalog/Books?$top=10
  │
  ▼
ServerVerticle (Vert.x router)
  │  mounted at /cdsodata/*
  │
  ▼
ODataV4Endpoint (inherited router)
  │  lazy-init → refreshRouter → route per service
  │
  ▼
block-list check handler
  │  normalizeUri → check exposedEntities
  │
  ▼
CdsODataEndpointHandler.handle(routingContext)
  │  new DataContextImpl(routingContext)
  │  mapToODataRequest(routingContext, schemaNamespace)
  │  odataRequestToQuery(odataRequest, action, body)
  │  → DataRequest(FullQualifiedName("my.CatalogService", "Books"), dataQuery)
  │
  ▼
AbstractEntityVerticle.requestEntity(Buffer.class, vertx, dataRequest, context)
  │  → event bus → EntityVerticle[my.CatalogService.Books]
  │
  ▼
EntityVerticle (user implementation)
  │  retrieve() → returns Buffer (OData JSON)
  │
  ▼
CdsODataEndpointHandler.completeResponse(...)
  │  setHeaderValues, OData-Version: 4.0, status code
  │
  ▼
HTTP Response  ← buffer written
```

### 7.3 API Changes

None to existing public APIs. `CdsODataEndpoint` and `CdsODataEndpointHandler` are **new public classes**.

If `ODataEndpointUtil` is introduced (recommended), it is package-private and not a public API change.

### 7.4 Configuration Changes

No changes to `ServerConfig` defaults. To enable the endpoint, operators add to `config/ServerVerticle.yaml`:

```yaml
endpoints:
  - type: io.neonbee.endpoint.odatav4.CdsODataEndpoint
    enabled: true
    basePath: /cdsodata/
    uriConversion: CDS
    exposedEntities:
      block: []
      allow: []
```

CDS services opt in via:

```cds
service CatalogService @(neonbee.endpoint: 'cdsodata') { ... }
```

### 7.5 Persistence / Database Changes

None.

---

## 8. Design Decisions

### DD-1: Extend `ODataV4Endpoint` rather than implement `Endpoint` from scratch

**Decision:** `CdsODataEndpoint extends ODataV4Endpoint`.

**Rationale:** `ODataV4Endpoint` encapsulates ~200 lines of non-trivial logic: lazy-init, event-bus listener, shared-lock guard, route-swap pattern, URI normalisation, block-list check. These are all correct and tested. Duplicating them would be a maintenance liability. The extension point (`getRequestHandler`, `filterModels`) is already used by `ODataProxyEndpoint` with the same pattern — this is the established convention.

**Alternative considered:** Implement `Endpoint` independently and share utility logic via a common abstract base. Rejected: the router lifecycle in `ODataV4Endpoint` is complex and not designed as an abstract base yet. Refactoring it into an abstract base is a separate, larger change.

**Alternative considered:** Add a config flag to `ODataV4Endpoint` that switches the handler. Rejected: this conflates two distinct endpoints with different annotation contracts, base paths, and future evolution paths. It also modifies existing production code.

### DD-2: Use `@neonbee.endpoint: 'cdsodata'` as opt-in annotation

**Decision:** Annotation value `"cdsodata"` (matching the base path segment).

**Rationale:** Consistent with the existing pattern — `ODataProxyEndpoint` uses `"odataproxy"`, the default `ODataV4Endpoint` uses `"odata"`. Value matches base path segment for predictability.

**[ASSUMPTION]** — confirm annotation value in OQ-3.

### DD-3: Extract shared static utilities to `ODataEndpointUtil`

**Decision:** Extract `odataRequestToQuery`, `setHeaderValues`, `getStatusCode(respData)`, `getStatusCode(cause)` from `ODataProxyEndpointHandler` into a package-private `ODataEndpointUtil` class.

**Rationale:** These methods are pure functions with no state. Both `ODataProxyEndpointHandler` and the new `CdsODataEndpointHandler` need them. Extraction avoids duplication without changing any public API. `ODataProxyEndpointHandler` delegates to the utility and retains its existing contract.

**Alternative considered:** Copy the methods into `CdsODataEndpointHandler`. Rejected: code duplication with no benefit.

### DD-4: `$metadata` via Olingo `executeBlocking`

**Decision:** Serve `$metadata` via `OData.newInstance().createRawHandler(serviceMetadata)` wrapped in `executeBlocking`, identical to `ODataProxyEndpointHandler.handleMetadataRequest`.

**Rationale:** The EDMX metadata document is generated from the `ServiceMetadata` object, which Olingo holds. There is no CDS-native alternative in the current NeonBee stack. The `executeBlocking` wrapping is correct because Olingo is synchronous.

### DD-5: Default `UriConversion = CDS`

**Decision:** Default to `CDS` uri conversion (not `STRICT`).

**Rationale:** This endpoint is specifically positioned as "CDS-native", so the CDS URI mapping convention (drop namespace, lowercase-kebab service name) is the natural default. `ODataV4Endpoint` defaults to `STRICT` because it must remain backward-compatible.

**[ASSUMPTION]** — confirm in OQ-3.

---

## 9. Test Strategy

### 9.1 Unit Tests

#### `CdsODataEndpointTest` (mirrors `ODataProxyEndpointTest`)

- `getDefaultConfig()` — verify type, basePath, uriConversion
- `getRequestHandler()` — verify returns `CdsODataEndpointHandler`
- `filterModels()` — parameterized:
  - annotation `neonbee.endpoint = "cdsodata"` → `true`
  - annotation `neonbee.endpoint = "odata"` → `false`
  - annotation `neonbee.endpoint = "odataproxy"` → `false`
  - no annotation → `false`
  - annotation with different name → `false`

#### `CdsODataEndpointHandlerTest` (mirrors `ODataProxyEndpointHandlerTest`)

- `handle()` with metadata path → invokes Olingo raw handler
- `handle()` with entity collection path → calls `requestEntity(Buffer.class, ...)`
- `handle()` with single entity path + key → correct `DataQuery` with key
- `handle()` with `$top`/`$skip`/`$filter` → correct `DataQuery.rawQuery`
- `handle()` on verticle failure → `routingContext.fail(statusCode)` called
- `completeResponse()` — status from `DataContext.responseData()` forwarded
- `completeResponse()` — `OData-Version: 4.0` header always set

#### `ODataEndpointUtilTest` (new, if utility class is introduced)

- `odataRequestToQuery()` — verify path and query string construction
- `setHeaderValues()` — multi-value header, status-code hint exclusion
- `getStatusCode()` variants

### 9.2 Integration Tests

#### `CdsODataEndpointIT` extending `ODataEndpointTestBase`

Setup: deploy a test `EntityVerticle` annotated with `@neonbee.endpoint: 'cdsodata'`; configure `CdsODataEndpoint` in test working directory.

**Positive cases:**
- `GET /cdsodata/<service>/EntitySet` → 200, valid OData JSON body from verticle
- `GET /cdsodata/<service>/EntitySet('<key>')` → 200, single entity
- `GET /cdsodata/<service>/EntitySet?$top=5&$skip=2` → `DataQuery` contains correct params
- `GET /cdsodata/<service>/$metadata` → 200, XML content type, EDMX body
- `POST /cdsodata/<service>/EntitySet` with body → 201 (or configured status)
- `PATCH /cdsodata/<service>/EntitySet('<key>')` → 200
- `DELETE /cdsodata/<service>/EntitySet('<key>')` → 204

**Negative cases:**
- `GET /cdsodata/<service>/BlockedEntity` (entity in block list) → 403
- `GET /cdsodata/<service>/UnknownEntity` → 404 (no verticle registered)
- `GET /cdsodata/<non-existent-service>/EntitySet` → 404

**Boundary cases:**
- `GET` with `$top=0` → empty collection with `@odata.count`
- `GET` with all query options combined
- `GET` on service with CDS URI conversion: `my.CatalogService` → `/cdsodata/catalog/`
- Simultaneous model reload + in-flight request (no 500 or routing exception)

### 9.3 Karate / API Tests

No existing `.feature` files were found in the repository. If the project adopts Karate in future, a `cdsodata-endpoint.feature` covering the positive and negative HTTP cases above would be the natural addition.

---

## 10. Acceptance Criteria

| ID | Criterion | Maps to |
|---|---|---|
| AC-1 | `CdsODataEndpoint.getDefaultConfig()` returns a config with `type = CdsODataEndpoint.class.getName()`, `basePath = "/cdsodata/"`, `uriConversion = "CDS"`. | REQ-1, REQ-3, REQ-4 |
| AC-2 | A CDS service **without** `@neonbee.endpoint: 'cdsodata'` annotation is NOT reachable at `/cdsodata/`. | REQ-2 |
| AC-3 | A CDS service **with** `@neonbee.endpoint: 'cdsodata'` IS reachable at `/cdsodata/<cds-path>/`. | REQ-2 |
| AC-4 | `GET /cdsodata/<service>/EntitySet` returns HTTP 200 and the buffer returned by the entity verticle. | REQ-7, REQ-8, REQ-10 |
| AC-5 | `GET /cdsodata/<service>/$metadata` returns HTTP 200 with `Content-Type: application/xml` and a valid EDMX document. | REQ-9 |
| AC-6 | `POST`, `PATCH`, `PUT`, `DELETE` requests are dispatched with the correct `DataAction` in `DataQuery`. | REQ-8 |
| AC-7 | HTTP status code set in `DataContext.responseData()` by the entity verticle is reflected in the HTTP response. | REQ-10 |
| AC-8 | `OData-Version: 4.0` header is always present in entity responses. | REQ-10 |
| AC-9 | A request to a blocked entity (in `exposedEntities` block-list) returns HTTP 403. | REQ-5, REQ-13 |
| AC-10 | A request when no verticle is registered for the entity type returns HTTP 404. | REQ-11 |
| AC-11 | The endpoint is NOT present in `DEFAULT_ENDPOINT_CONFIGS` and does NOT start unless explicitly configured. | REQ-12 |
| AC-12 | After a model reload (event bus `EVENT_BUS_MODELS_LOADED_ADDRESS`), the endpoint router serves the updated model. | REQ-6 |
| AC-13 | All unit tests for `CdsODataEndpoint` and `CdsODataEndpointHandler` pass. | REQ-15 |
| AC-14 | All integration tests in `CdsODataEndpointIT` pass. | REQ-15 |
| AC-15 | No existing test regressions in `ODataV4EndpointTest`, `ODataProxyEndpointTest`, `ODataProxyEndpointHandlerTest`. | backward compat |

---

## 11. Implementation Tasks

Tasks are ordered; later tasks depend on earlier ones unless marked as parallel.

| # | Task | Files / Classes | Notes |
|---|---|---|---|
| T-1 | Extract static utility methods to `ODataEndpointUtil` | New: `io.neonbee.endpoint.odatav4.ODataEndpointUtil` | Extract from `ODataProxyEndpointHandler`: `odataRequestToQuery`, `setHeaderValues`, `getStatusCode(Map)`, `getStatusCode(Throwable)`. Update `ODataProxyEndpointHandler` to delegate to `ODataEndpointUtil`. |
| T-2 | Write tests for extracted utility | New: `ODataEndpointUtilTest` | Verify moved methods still pass `ODataProxyEndpointHandlerTest` cases |
| T-3 | Implement `CdsODataEndpointHandler` | New: `io.neonbee.endpoint.odatav4.CdsODataEndpointHandler` | Depends on T-1. Handles `$metadata`, entity collection, single entity. No `$batch`. |
| T-4 | Write unit tests for `CdsODataEndpointHandler` | New: `CdsODataEndpointHandlerTest` | See §9.1 |
| T-5 | Implement `CdsODataEndpoint` | New: `io.neonbee.endpoint.odatav4.CdsODataEndpoint` | Depends on T-3. Override `getDefaultConfig`, `filterModels`, `getRequestHandler`. |
| T-6 | Write unit tests for `CdsODataEndpoint` | New: `CdsODataEndpointTest` | See §9.1 |
| T-7 | Write integration tests | New: `io.neonbee.test.endpoint.cdsodata.CdsODataEndpointIT` | Depends on T-5. Extend `ODataEndpointTestBase`. Add test CDS model + verticle. |
| T-8 | Update `ServerConfig` Javadoc / YAML comment | `ServerConfig.java` (comment only), relevant YAML docs | Document the new endpoint type and annotation value. No code change. |
| T-9 | Add CHANGELOG entry | `CHANGELOG.md` or `docs/changelog/` | Follow existing release note format. |

**Parallel opportunities:** T-4 and T-6 can be written in parallel with T-3 and T-5 respectively (TDD style).

---

## 12. Risks and Open Questions

### Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-1 | `ODataProxyEndpointHandler` static methods are package-private and extraction (T-1) might break `ODataProxyEndpointHandlerTest` | Low | Low | Tests will fail immediately and are easy to fix |
| R-2 | CDS URI conversion ambiguity: two services map to the same CDS path | Low | Medium | Document constraint; same limitation exists today for `ODataV4Endpoint` with `CDS` conversion |
| R-3 | Entity verticle returns `EntityWrapper` instead of `Buffer` for this endpoint | Medium | High | See OQ-8 — must confirm the return type contract |
| R-4 | `mapToODataRequest` in `OlingoEndpointHandler` is not accessible from `CdsODataEndpointHandler` | Medium | Medium | Check visibility; may need package-level access or copy |

### Open Questions

> Items marked **BLOCKING** prevent implementation from starting until resolved.

| ID | Question | Blocking? | Default assumption if not answered |
|---|---|---|---|
| **OQ-1** | Is `$batch` support required in the initial implementation? | No (scope item) | OUT of scope for v1 |
| **OQ-2** | Is `$expand` across navigation properties required in v1? | No | OUT of scope for v1 |
| **OQ-3** | Confirm the `@neonbee.endpoint` annotation value: `"cdsodata"` or another string? Also confirm default `UriConversion` should be `CDS`. | No | `"cdsodata"`, default `CDS` |
| **OQ-4** | Should this endpoint eventually be added to `DEFAULT_ENDPOINT_CONFIGS`? (Does not affect this spec, but affects future migration planning.) | No | NOT in defaults; opt-in only |
| **OQ-5** | Should `CdsODataEndpoint` and `CdsODataEndpointHandler` live in package `io.neonbee.endpoint.odatav4` (alongside `ODataProxyEndpoint`) or a new sub-package (e.g. `io.neonbee.endpoint.cdsodata`)? | No | `io.neonbee.endpoint.odatav4` (consistent with `ODataProxyEndpoint`) |
| **OQ-6** | Should malformed `$filter` / `$orderby` syntax be rejected with HTTP 400 at the endpoint layer, or should the raw query string be forwarded to the entity verticle which may handle it? | No | Forward raw — verticle decides |
| **OQ-7** | Confirm whether extracting static methods from `ODataProxyEndpointHandler` into `ODataEndpointUtil` (T-1) is acceptable, vs. duplicating them in `CdsODataEndpointHandler`. | No | Extract preferred |
| **OQ-8 (BLOCKING)** | When an `EntityVerticle` is deployed and `requestEntity(Buffer.class, ...)` is called, does the verticle's `retrieveData` already return a `Buffer` (i.e., the complete OData JSON body including `@odata.context`, `value` array, etc.) — or does it return an `EntityWrapper`, making `Buffer.class` the wrong type? The answer determines whether `CdsODataEndpointHandler` needs to serialize the result or can write the buffer directly. | **YES** | N/A — must be confirmed |

---

## Summary

### Proposed Solution

`CdsODataEndpoint extends ODataV4Endpoint`, overriding three methods. A new `CdsODataEndpointHandler` replaces Olingo for entity requests, dispatching directly via `AbstractEntityVerticle.requestEntity(Buffer.class, ...)` — the same mechanism as `ODataProxyEndpointHandler`. Services opt in via `@neonbee.endpoint: 'cdsodata'`. Static utility methods shared between `ODataProxyEndpointHandler` and the new handler are extracted to a package-private `ODataEndpointUtil`.

### Files / Modules Likely to Change

| File | Change type |
|---|---|
| `src/main/java/io/neonbee/endpoint/odatav4/CdsODataEndpoint.java` | **NEW** |
| `src/main/java/io/neonbee/endpoint/odatav4/CdsODataEndpointHandler.java` | **NEW** |
| `src/main/java/io/neonbee/endpoint/odatav4/ODataEndpointUtil.java` | **NEW** (extracted) |
| `src/main/java/io/neonbee/endpoint/odatav4/ODataProxyEndpointHandler.java` | **MODIFY** (delegate to `ODataEndpointUtil`) |
| `src/test/java/io/neonbee/endpoint/odatav4/CdsODataEndpointTest.java` | **NEW** |
| `src/test/java/io/neonbee/endpoint/odatav4/CdsODataEndpointHandlerTest.java` | **NEW** |
| `src/test/java/io/neonbee/endpoint/odatav4/ODataEndpointUtilTest.java` | **NEW** |
| `src/test/java/io/neonbee/test/endpoint/cdsodata/CdsODataEndpointIT.java` | **NEW** |
| `src/main/java/io/neonbee/config/ServerConfig.java` | **COMMENT/DOC only** |

No changes to `ODataV4Endpoint`, `OlingoEndpointHandler`, `EntityVerticle`, `AbstractEntityVerticle`, `DataVerticle`, or any test infrastructure class.

### Open Questions Requiring Your Decision

1. **OQ-8 (BLOCKING):** Does `requestEntity(Buffer.class, ...)` work for a standard `EntityVerticle`, or does it require a verticle subclassing `AbstractEntityVerticle<Buffer>` directly? This is the single most important question before T-3 can start.
2. **OQ-1:** Is `$batch` in scope for v1?
3. **OQ-2:** Is `$expand` in scope for v1?
4. **OQ-3:** Confirm annotation value and default `UriConversion`.
5. **OQ-7:** Confirm preference for extraction vs. duplication of shared static methods.

### Recommendation

**NEEDS CLARIFICATION**

Specifically: **OQ-8** must be answered before implementation begins. All other open questions have reasonable defaults that can proceed with. Once OQ-8 is resolved and the remaining questions confirmed, this spec is ready for implementation (T-1 through T-9).
