# CDS-Native OData V4 Endpoint (Olingo-Free)

### Contents
- [Abstract](#abstract)
- [Motivation](#motivation)
- [Current Architecture](#current-architecture)
- [New Architecture](#new-architecture)
- [URL Structure](#url-structure)
- [Dispatcher Flow](#dispatcher-flow)
- [Technical Details](#technical-details)
- [Performance Considerations](#performance-considerations)
- [Impact on Existing Functionalities](#impact-on-existing-functionalities)
- [Open Questions](#open-questions)


### Abstract

Introduce a new OData V4 endpoint alongside the existing one, backed by the same CDS model but replacing Apache Olingo
as the internal orchestrator with a CDS-native dispatch layer. The new endpoint retains full OData wire compatibility —
the same request query syntax (`$filter`, `$orderby`, `$top`, `$skip`, `$expand`, `$count`) and the same
`application/json;odata.metadata=minimal` response format — while removing the dependency on Olingo's internal APIs
(`uri.parser.Parser`, `SchemaBasedEdmProvider`, `BatchParserCommon`, etc.).

The existing `/odata/` endpoint continues to operate unchanged so consumers can migrate at their own pace.


### Motivation

Apache Olingo was archived on 2 June 2026 (read-only). NeonBee currently couples 39 main-source files to Olingo
internals, not just its public API. This means every future CVE fix or Java upgrade must either be hand-patched in a
fork or block on a third party that no longer accepts contributions.

The Olingo layer was always an impedance mismatch: it requires a synchronous blocking execution context
(`vertx.executeBlocking`), bridges between Olingo's `ODataRequest`/`ODataResponse` envelope and Vert.x's native HTTP
objects, and carries processors (`EntityProcessor`, `CountEntityCollectionProcessor`, `BatchProcessor`,
`PrimitiveProcessor`) that duplicate logic already expressible through NeonBee's `DataQuery` / `EntityVerticle` model.

A CDS-native dispatch layer can implement the same query parsing and serialisation directly against the `CdsModel` and
the existing `EntityVerticle` contract, eliminating the blocking bridge and all Olingo-internal imports.


### Current Architecture

The existing OData V4 endpoint is built on Apache Olingo as its central orchestrator. The full call chain, from an
incoming HTTP request to a response, passes through several layers:

```
HTTP Request
    │
    ▼
Vert.x Router  (ODataV4Endpoint.createEndpointRouter)
    │  route: /<uriConversion(namespace)>/*
    │  lazy init on first request; re-routes after model refresh
    ▼
EntityModel filter + block-list check  (RegexBlockList.isAllowed)
    │
    ▼
OlingoEndpointHandler.handle(RoutingContext)
    │  bridges Vert.x HTTP ↔ Olingo ODataRequest/ODataResponse
    │  wraps everything in vertx.executeBlocking(...)  ← blocking thread pool
    │
    ▼
OData.newInstance().createRawHandler(ServiceMetadata)
    │  registers four processors:
    ├─ CountEntityCollectionProcessor
    ├─ EntityProcessor
    ├─ BatchProcessor
    └─ PrimitiveProcessor
    │
    ▼
ODataHandler.process(ODataRequest)   ← Olingo internal dispatcher
    │  parses URI via Olingo uri.parser.Parser
    │  resolves EDM type via SchemaBasedEdmProvider
    │  routes to matching processor
    │
    ▼  (inside each processor)
ProcessorHelper.forwardRequest(ODataRequest, DataAction, UriInfo, ...)
    │  builds DataQuery from ODataRequest + UriInfo
    │  constructs DataRequest(FullQualifiedName, DataQuery)
    │
    ▼
EntityVerticle.requestEntity(vertx, DataRequest, DataContext)
    │  dispatches over the Vert.x event bus
    ▼
EntityVerticle implementation  (user-provided)
    │
    ▼  (back up the chain)
Olingo ODataSerializer  (serialises EntityWrapper → OData JSON)
    │  uses ServiceMetadata / EdmEntityType for type information
    ▼
OlingoEndpointHandler.mapODataResponse  (copies headers + body to Vert.x response)
    ▼
HTTP Response
```

**Key components and their responsibilities:**

| Component | Role |
|---|---|
| `EntityModelLoader` | Reads `.csn` / `.edmx` files; builds `CdsModel` + `ServiceMetadata` (Olingo) per service namespace |
| `EntityModelManager` | Holds the shared `Map<String, EntityModel>`; publishes `EntityModelManagerLoaded` on the event bus when models change |
| `ODataV4Endpoint` | Vert.x `Endpoint`; creates and refreshes the sub-router; owns `UriConversion`, `NormalizedUri`, `RegexBlockList` |
| `OlingoEndpointHandler` | Bridges Vert.x ↔ Olingo; runs in `executeBlocking`; maps `RoutingContext` → `ODataRequest` → `ODataResponse` → `RoutingContext` |
| `CountEntityCollectionProcessor` | Handles `GET /EntitySet` and `GET /EntitySet/$count`; applies in-process filter/order/skip/top/expand if the verticle did not push them down |
| `EntityProcessor` | Handles `GET /EntitySet(key)`, `POST`, `PATCH`, `PUT`, `DELETE` on a single entity |
| `PrimitiveProcessor` | Handles `GET /EntitySet(key)/PrimitiveProperty` |
| `BatchProcessor` | Handles `POST /$batch`; delegates individual parts back through Olingo's batch facade |
| `ProcessorHelper` | Translates `ODataRequest` + `UriInfo` → `DataQuery` → `DataRequest`; calls `EntityVerticle.requestEntity` |
| `FilterExpressionVisitor` | Evaluates OData `$filter` expressions in-process against `List<Entity>` using Olingo's expression tree |
| `OrderExpressionExecutor` | Sorts `List<Entity>` in-process according to OData `$orderby` using Olingo's comparator types |
| `EntityExpander` | Resolves `$expand` by issuing nested `EntityVerticle.requestEntity` calls per navigation property |


### New Architecture

The new endpoint replaces the Olingo orchestration layer with a CDS-native handler that works directly with the
`CdsModel` already held by `EntityModel`. The Vert.x router infrastructure, model management, URI normalisation, and
`EntityVerticle` dispatch are all reused unchanged.

```
HTTP Request
    │
    ▼
Vert.x Router  (ODataV4CdsEndpoint — same refreshRouter logic as today)
    │  route: /<uriConversion(namespace)>/*
    │
    ▼
EntityModel filter + block-list check  (RegexBlockList.isAllowed)  ← identical to today
    │
    ▼
CdsODataEndpointHandler.handle(RoutingContext)
    │  runs entirely on the Vert.x event-loop  ← no executeBlocking
    │
    ├─ 1. NormalizedUri  (ODataV4Endpoint.normalizeUri — reused as-is)
    │       extracts schemaNamespace, resourcePath, requestQuery, entityName
    │
    ├─ 2. CdsQueryParser
    │       parses $filter / $orderby / $top / $skip / $expand / $select / $count
    │       against CdsModel (no Olingo UriInfo / uri.parser.Parser)
    │       produces a plain DataQuery
    │
    ├─ 3. DataRequest assembly
    │       DataRequest(FullQualifiedName, DataQuery)
    │       DataContext from RoutingContext
    │
    ▼
EntityVerticle.requestEntity(vertx, DataRequest, DataContext)  ← identical to today
    │  event-bus dispatch
    ▼
EntityVerticle implementation  (user-provided, unchanged)
    │
    ▼  (back up the chain)
CdsEntitySerializer
    │  serialises EntityWrapper → OData JSON using CdsModel for type info
    │  produces identical wire format: @odata.context, @odata.count, value
    │
    ├─ 4. Response header mapping  (status, Content-Type, OData headers)
    ▼
HTTP Response
```

**What is removed vs. added:**

| Removed (Olingo layer) | Replaced by |
|---|---|
| `OlingoEndpointHandler` | `CdsODataEndpointHandler` |
| `ODataRequest` / `ODataResponse` bridge | Direct `RoutingContext` access |
| `OData.createRawHandler` + processor registration | `CdsODataEndpointHandler` internal dispatch |
| Olingo `uri.parser.Parser` + `UriInfo` | `CdsQueryParser` against `CdsModel` |
| `FilterExpressionVisitor` (Olingo expression tree) | CDS-native filter evaluator |
| `OrderExpressionExecutor` (Olingo comparator) | CDS-native comparator |
| `ODataSerializer` (Olingo JSON serialiser) | `CdsEntitySerializer` |
| `BatchProcessor` (Olingo batch facade) | `CdsBatchHandler` (multipart/mixed parser) |
| `vertx.executeBlocking(...)` wrapper | Removed — all on event loop |

**What is kept unchanged:**

- `EntityModelLoader`, `EntityModelManager`, `EntityModel`
- `ODataV4Endpoint.UriConversion`, `NormalizedUri`, `normalizeUri(...)`
- `ODataV4Endpoint.filterModels(...)`, `refreshRouter(...)`, `RegexBlockList`
- `EntityVerticle.requestEntity(...)`, `DataQuery`, `DataRequest`, `DataContext`
- All existing Olingo processors, EDM helpers, and expression operators


### URL Structure

Both endpoints are mounted side-by-side on the same NeonBee HTTP server. The URI structure within each endpoint is
identical — only the base path prefix differs.

#### Anatomy of an OData URL

```
http://host:port / <basePath> / <servicePath> / <resourcePath> ? <queryOptions>
                   ──────────   ─────────────   ─────────────   ──────────────
                   endpoint      converted        entity set      $filter etc.
                   base path     namespace        + key pred.
```

#### Existing endpoint (Olingo-backed)

```
Base path (default):  /odata/
URI conversion:       STRICT | LOOSE | CDS  (configurable)

Examples (STRICT conversion, namespace = io.neonbee.example.CatalogService):

  GET  /odata/io.neonbee.example.CatalogService/Books
  GET  /odata/io.neonbee.example.CatalogService/Books(1)
  GET  /odata/io.neonbee.example.CatalogService/Books?$filter=year gt 2000&$top=10
  GET  /odata/io.neonbee.example.CatalogService/Books(1)/Author
  GET  /odata/io.neonbee.example.CatalogService/Books/$count
  GET  /odata/io.neonbee.example.CatalogService/$metadata
  POST /odata/io.neonbee.example.CatalogService/$batch

Examples (CDS conversion, namespace = io.neonbee.example.CatalogService):

  GET  /odata/catalog/Books
  GET  /odata/catalog/Books(1)
  GET  /odata/catalog/Books?$filter=year gt 2000&$top=10
```

#### New endpoint (CDS-native)

The URL structure is **byte-for-byte identical** to the existing endpoint; only the base path changes.

```
Base path (default):  /odata2/   (placeholder; configurable)

Examples (STRICT conversion, same namespace):

  GET  /odata2/io.neonbee.example.CatalogService/Books
  GET  /odata2/io.neonbee.example.CatalogService/Books(1)
  GET  /odata2/io.neonbee.example.CatalogService/Books?$filter=year gt 2000&$top=10
  GET  /odata2/io.neonbee.example.CatalogService/Books(1)/Author
  GET  /odata2/io.neonbee.example.CatalogService/Books/$count
  GET  /odata2/io.neonbee.example.CatalogService/$metadata
  POST /odata2/io.neonbee.example.CatalogService/$batch
```

A consumer migrates by changing the base path prefix from `/odata/` to `/odata2/`; no other part of the URL changes.

#### NormalizedUri fields for the example `GET /odata2/io.neonbee.example.CatalogService/Books(1)?$top=1`

| Field | Value |
|---|---|
| `requestUri` | `http://host/odata2/io.neonbee.example.CatalogService/Books(1)?$top=1` |
| `basePath` | `/odata2/` |
| `schemaNamespace` | `io.neonbee.example.CatalogService` |
| `resourcePath` | `/Books(1)` |
| `entityName` | `Books` |
| `fullQualifiedName` | `io.neonbee.example.CatalogService.Books` |
| `requestQuery` | `$top=1` |


### Dispatcher Flow

The following traces show the full per-request flow for the two most important request types.

#### Read entity collection — `GET /odata2/<namespace>/Books?$filter=year gt 2020&$top=5`

```
RoutingContext (Vert.x event-loop thread)
│
├─ [1] ODataV4CdsEndpoint router match
│        route: /io.neonbee.example.CatalogService/*
│
├─ [2] Block-list check
│        RegexBlockList.isAllowed("io.neonbee.example.CatalogService.Books")
│        → allowed, continue
│
├─ [3] CdsODataEndpointHandler.handle(routingContext)
│
│   ├─ [3a] NormalizedUri  (reused from ODataV4Endpoint)
│   │         schemaNamespace = "io.neonbee.example.CatalogService"
│   │         resourcePath    = "/Books"
│   │         entityName      = "Books"
│   │         requestQuery    = "$filter=year gt 2020&$top=5"
│   │
│   ├─ [3b] CdsQueryParser.parse(requestQuery, cdsModel)
│   │         action   = READ
│   │         uriPath  = "/io.neonbee.example.CatalogService/Books"
│   │         params   = { "$filter" → ["year gt 2020"], "$top" → ["5"] }
│   │         → DataQuery(READ, uriPath, params, headers, body=null)
│   │
│   ├─ [3c] DataRequest assembly
│   │         fullQualifiedName = "io.neonbee.example.CatalogService.Books"
│   │         → DataRequest(fullQualifiedName, dataQuery)
│   │         → DataContextImpl(routingContext)
│   │
│   ├─ [3d] EntityVerticle.requestEntity(vertx, dataRequest, dataContext)
│   │         event-bus send to address "io.neonbee.example.CatalogService.Books"
│   │         ▼
│   │     EntityVerticle impl  (fetches data, optionally applies filter/top push-down)
│   │         ▼
│   │     EntityWrapper  { entities: [Book{...}, Book{...}, ...] }
│   │
│   ├─ [3e] Post-fetch query application (if not pushed down by verticle)
│   │         check routingContext hints: OData.filter, OData.top, etc.
│   │         apply CDS-native filter evaluator if "OData.filter" hint not set
│   │         apply top/skip if "OData.top" / "OData.skip" hints not set
│   │
│   └─ [3f] CdsEntitySerializer.serializeCollection(entityWrapper, cdsModel)
│             Content-Type: application/json;odata.metadata=minimal
│             {
│               "@odata.context": "$metadata#Books",
│               "@odata.count":   42,          // if $count=true
│               "value": [ { "ID": 1, ... }, ... ]
│             }
│
└─ HTTP 200 response  (written directly to RoutingContext.response())
```

#### Write single entity — `POST /odata2/<namespace>/Books`

```
RoutingContext
│
├─ [1–2]  Router match + block-list check  (same as above)
│
├─ [3] CdsODataEndpointHandler.handle(routingContext)
│
│   ├─ [3a] NormalizedUri  → resourcePath = "/Books", action target = Books
│   │
│   ├─ [3b] HTTP method → DataAction mapping
│   │         POST   → CREATE
│   │         PATCH  → UPDATE
│   │         PUT    → UPDATE
│   │         DELETE → DELETE
│   │
│   ├─ [3c] CdsEntityDeserializer.deserialize(body, cdsModel, "Books")
│   │         reads application/json body → Entity
│   │         wraps in EntityWrapper(fullQualifiedName, entity).toBuffer()
│   │
│   ├─ [3d] DataQuery(CREATE, uriPath, params, headers, body=entityBuffer)
│   │         DataRequest(fullQualifiedName, dataQuery)
│   │
│   ├─ [3e] EntityVerticle.requestEntity(...)  → EntityWrapper (created entity)
│   │
│   └─ [3f] CdsEntitySerializer.serializeEntity(...)
│             HTTP 201 Created
│             Location: /odata2/<namespace>/Books(newId)
│
└─ HTTP 201 response
```

#### Batch request — `POST /odata2/<namespace>/$batch`

The current `BatchProcessor` relies heavily on Olingo internals: `BatchFacade`, `odata.createFixedFormatDeserializer().parseBatchRequest(...)`, `facade.handleBatchRequest(part)` (which re-enters Olingo's processor dispatch), and `odata.createFixedFormatSerializer().batchResponse(...)`. The `AsynchronousProcessor` base class manages a per-`Context` stack of `Promise<Void>` lists (`processingStack`) so that async results from individual parts can be collected before the batch response is serialised. Change sets are executed sequentially; transaction rollback is not supported.

The `RawBatchDecision` / `RawBatchResult` escape hatch lets an `EntityVerticle` intercept the raw multipart body and produce a response buffer directly, bypassing Olingo's batch parsing entirely.

The new `CdsBatchHandler` replaces all Olingo-specific pieces while preserving the same wire protocol and the raw-batch escape hatch:

```
POST /odata2/io.neonbee.example.CatalogService/$batch
Content-Type: multipart/mixed; boundary=batch_abc123

RoutingContext
│
├─ [1–2]  Router match + block-list check
│
├─ [3] CdsODataEndpointHandler detects /$batch resource path
│       delegates to CdsBatchHandler.handle(routingContext)
│
├─ [4] Raw-batch escape hatch  (preserved from current design)
│       CdsBatchHandler checks RawBatchDecision via EntityVerticle event-bus
│       if HANDLED_RAW  → write RawBatchResult.buffer() as response, done
│       if DELEGATE_TO_DEFAULT  → continue below
│
├─ [5] CdsBatchHandler.parseMultipart(body, boundary)
│       pure multipart/mixed parser — no Olingo dependency
│       produces List<BatchPart>  where each part is either:
│         • a single request  (GET / POST / PATCH / PUT / DELETE)
│         • a change set      (multipart/mixed sub-envelope, sequential writes)
│
├─ [6] For each BatchPart — dispatch via CdsODataEndpointHandler
│       single requests and change-set members are each fed back through
│       the same CdsODataEndpointHandler.handle logic (steps 3a–3f above)
│       results are collected as Future<BatchPartResponse>
│       → Future.all(partFutures)  — no processing-stack bookkeeping needed
│                                    because the event-loop is not blocked
│
│       Change-set semantics (identical to today):
│         • parts executed sequentially within the change set
│         • if any part returns HTTP 4xx/5xx the change set is aborted
│           and a single error response part is returned for the set
│         • no transaction rollback (same limitation as today)
│
├─ [7] CdsBatchHandler.serializeMultipart(List<BatchPartResponse>, responseBoundary)
│       pure multipart/mixed serialiser — no Olingo dependency
│       Content-Type: multipart/mixed; boundary=batch_<uuid>
│       HTTP 202 Accepted
│
└─ HTTP 202 response
```

**Wire format preserved** — the batch request and response envelopes follow OData V4 §11.7 multipart/mixed exactly, so existing batch clients work against the new endpoint without changes.

**What is removed vs. added for batch:**

| Removed (Olingo) | Replaced by |
|---|---|
| `BatchFacade` + `odata.createFixedFormatDeserializer().parseBatchRequest` | `CdsBatchHandler.parseMultipart` |
| `facade.handleBatchRequest(part)` re-entering Olingo dispatch | `CdsODataEndpointHandler` re-invocation per part |
| `odata.createFixedFormatSerializer().batchResponse` | `CdsBatchHandler.serializeMultipart` |
| `AsynchronousProcessor` processing-stack (`enterBatchProcessing` / `wrapUpBatchProcessing`) | `Future.all(partFutures)` — no stack needed |
| `BatchSerializerException` from Olingo serialiser | Plain `IOException` / custom exception |

**Preserved:**

- `RawBatchDecision` / `RawBatchResult` escape hatch — API unchanged
- Sequential change-set execution with early-abort on 4xx/5xx
- No transaction / rollback support (explicit known limitation)


#### Response-hint push-down protocol (unchanged from today)

`EntityVerticle` implementations signal that they have already applied a query option by setting a hint in
`DataContext.responseData()`. `ProcessorHelper.transferResponseHint` copies these into the `RoutingContext` under the
`response.` prefix. The new handler reads the same keys:

| Hint key (in RoutingContext) | Meaning |
|---|---|
| `response.OData.filter` | Verticle applied `$filter`; skip in-process filtering |
| `response.OData.orderby` | Verticle applied `$orderby`; skip in-process sort |
| `response.OData.skip` | Verticle applied `$skip`; skip in-process slice |
| `response.OData.top` | Verticle applied `$top`; skip in-process slice |
| `response.OData.expand` | Verticle resolved `$expand`; skip nested requests |
| `response.OData.key` | Verticle applied key-predicate filter; skip in-process search |
| `response.OData.count.size` | Verticle provides total count for `$count` response |


### Technical Details

#### New endpoint class

A new class `ODataV4CdsEndpoint` (working title) extends or mirrors `ODataV4Endpoint`, registering under a separate
base path (e.g. `/odata2/`) configurable via `EndpointConfig`. The `filterModels` logic and `UriConversion` machinery
are shared or inherited unchanged; only `getRequestHandler(...)` is overridden to return the new handler instead of
`OlingoEndpointHandler`.

```
ODataV4Endpoint           ←  existing, unchanged
  └─ getRequestHandler()  →  OlingoEndpointHandler  (Olingo)

ODataV4CdsEndpoint        ←  new
  └─ getRequestHandler()  →  CdsODataEndpointHandler  (no Olingo)
```

#### CdsODataEndpointHandler

A Vert.x `Handler<RoutingContext>` that implements the OData dispatch loop without Olingo.

**Responsibilities:**

1. **URI parsing** — reuse `ODataV4Endpoint.normalizeUri(routingContext, schemaNamespace)` to extract
   `resourcePath`, `requestQuery`, `schemaNamespace`, `entityName`, and `fullQualifiedName`, exactly as today.

2. **Query parsing** — parse the raw query string (`$filter`, `$top`, `$skip`, `$orderby`, `$expand`, `$count`,
   `$select`, `$format`) into a `DataQuery` without going through Olingo's `UriInfo`. An OData query parser that works
   directly against the `CdsModel` (e.g. using the CDS4j reflection API) replaces `FilterExpressionVisitor` and
   `OrderExpressionExecutor`.

3. **Dispatch** — forward the assembled `DataRequest` to the relevant `EntityVerticle` via
   `EntityVerticle.requestEntity(vertx, request, context)`, identical to how `ProcessorHelper.forwardRequest` does
   today.

4. **Serialisation** — serialise the returned `EntityWrapper` to `application/json;odata.metadata=minimal` using the
   `CdsModel` for type metadata instead of Olingo's `ODataSerializer`. The `@odata.context`, `@odata.count`, and
   `value` envelope fields must match the existing wire format exactly.

5. **Mutations (POST / PATCH / PUT / DELETE)** — map HTTP method to `DataAction` and deserialise the request body
   from JSON using the `CdsModel`, then dispatch as above.

6. **$batch** — implement the multipart/mixed batch protocol independently (the raw batch request body is already
   parsed today in `RawBatchDecision` / `RawBatchResult`); no Olingo `BatchProcessor` needed.

7. **Error mapping** — produce OData-compliant error JSON (`error.code`, `error.message`) for 4xx/5xx responses.

#### No blocking thread

Because no Olingo synchronous API is invoked, all dispatch can remain on the Vert.x event-loop thread; the
`vertx.executeBlocking(...)` wrapper present in `OlingoEndpointHandler` is removed.

#### Configuration

The new endpoint is opt-in. It is registered by adding `ODataV4CdsEndpoint` to the `endpoints` list in the NeonBee
server configuration, alongside the existing `ODataV4Endpoint` entry.

```json
{
  "endpoints": [
    { "type": "io.neonbee.endpoint.odatav4.ODataV4Endpoint",    "basePath": "/odata/"  },
    { "type": "io.neonbee.endpoint.odatav4.ODataV4CdsEndpoint", "basePath": "/odata2/" }
  ]
}
```

Both endpoints read the same `EntityModel` map from `EntityModelManager`; no model duplication occurs.

#### Shared infrastructure reused as-is

| Concern | Reused component |
|---|---|
| URI normalisation | `ODataV4Endpoint.NormalizedUri` |
| URI conversion (STRICT / CDS / LOOSE) | `ODataV4Endpoint.UriConversion` |
| Entity block / allow list | `RegexBlockList` + `exposedEntities` config key |
| Model refresh on event-bus update | `refreshRouter(...)` logic |
| Entity dispatch | `EntityVerticle.requestEntity(...)` |
| DataQuery construction | `DataQuery(action, uriPath, queryParams, headers, body)` |

#### Files affected

| Action | File |
|---|---|
| New | `endpoint/odatav4/ODataV4CdsEndpoint.java` |
| New | `endpoint/odatav4/internal/cds/CdsODataEndpointHandler.java` |
| New | `endpoint/odatav4/internal/cds/CdsQueryParser.java` |
| New | `endpoint/odatav4/internal/cds/CdsEntitySerializer.java` |
| New | `endpoint/odatav4/internal/cds/CdsBatchHandler.java` |
| Modified | `endpoint/odatav4/ODataV4Endpoint.java` — extract `normalizeUri`, `UriConversion`, `filterModels`, and `NormalizedUri` to a shared location so `ODataV4CdsEndpoint` can reuse them without inheriting the Olingo handler. |
| Unchanged | All existing Olingo processors, EDM helpers, expression operators. |

Olingo dependencies are not removed from `build.gradle` in this step; they are removed only after the old endpoint is
deprecated and dropped in a follow-up.


### Performance Considerations

Removing `vertx.executeBlocking(...)` keeps all OData processing on the event-loop. This eliminates the thread-pool
hop and the associated context-switch overhead on every request.

The CDS-native query parser operates on the already-loaded in-memory `CdsModel`, so there is no additional I/O. Filter
evaluation that is currently done in-process by `FilterExpressionVisitor` (post-fetch, in-memory) remains in-process
until push-down to the `EntityVerticle` is implemented separately.

No additional shared state is introduced; the new endpoint shares the existing `EntityModel` reference.


### Impact on Existing Functionalities

- The existing `/odata/` endpoint and all its Olingo-backed behaviour are **unchanged**. No consumer is affected until
  they explicitly switch to the new base path.
- `ODataV4Endpoint.getRequestHandler(...)` gains a `// TODO` comment pointing to the new handler, but its default
  return value (`new OlingoEndpointHandler(edmxModel)`) is not altered.
- `ODataProxyEndpoint` and `ODataProxyEndpointHandler` are unaffected.
- The `EntityModel` class keeps its `Map<String, ServiceMetadata>` (Olingo) field; the CSN-only path used by the new
  handler accesses `getCsnModel()` instead.
- Test infrastructure (`ODataEndpointTestBase`, `ODataRequest`, `ODataBatchRequest`) continues to target the existing
  endpoint; new integration tests are added for the CDS endpoint under a parallel test base class.


### Open Questions

1. **OData query parser library** — Should the new `CdsQueryParser` implement OData `$filter` grammar from scratch
   (ANTLR or hand-written recursive-descent), or is there a lightweight library that parses OData system query options
   without pulling in the full Olingo stack? The CAP Java SDK ships a parser but brings ecosystem lock-in.

2. **$expand depth** — The current `EntityExpander` handles multi-level expand by making nested `EntityVerticle`
   requests. Should the new handler replicate this, or should deep expand be deferred to a follow-up?

3. **Wire-format parity gate** — Should a contract test suite be introduced that runs the same HTTP request corpus
   against both endpoints and diffs the responses, to guarantee the new endpoint is a drop-in replacement before
   the old one is removed?

4. **Base path strategy** — `/odata2/` is a placeholder. Should the new endpoint share `/odata/` and be selected
   via a config flag (`handler: cds` vs `handler: olingo`), or should it always live at a separate path to allow
   side-by-side comparison in production?

5. **Batch support scope** — Is `$batch` required in the first iteration of the new endpoint, or can it be deferred
   while the rest of the endpoint is validated?