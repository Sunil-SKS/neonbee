# CDS-Native OData V4 Endpoint — Flow Diagram

Overview of how an HTTP request is processed by `ODataV4CdsEndpoint` (`/odata2/`).

## Request Routing

```mermaid
flowchart TD
    A([HTTP Request\n/odata2/namespace/...]) --> B[ODataV4CdsEndpoint\nmounts handler per schema namespace]
    B --> C[CdsODataEndpointHandler.handle]

    C --> D{resourcePath?}

    D -- "/$metadata" --> E[handleMetadata\nexecuteBlocking]
    E --> F[Olingo ODataHandler\ngenerates CSDL XML]
    F --> G([HTTP 200\napplication/xml])

    D -- "ends with /$batch" --> H[handleBatch]
    H --> I[CdsBatchHandler\nparse multipart/mixed]
    I --> J[dispatchBatchPart\nper part]
    J --> K[requestEntity\nevent bus]
    K --> L[formatRawHttpResponse\nper part]
    L --> M([HTTP 202\nmultipart/mixed response])

    D -- "other" --> N{HTTP method\nmappable?}
    N -- "no" --> O([405 Method Not Allowed])
    N -- "yes" --> P{entityName\nextractable?}
    P -- "no" --> Q([400 Bad Request])
    P -- "yes" --> R[CdsQueryParser.parse\nDataQuery]
```

## Data Dispatch & Response

```mermaid
flowchart TD
    R[DataQuery + DataRequest\nFQN = namespace.EntityName] --> S[EntityVerticle.requestEntity\nVert.x event bus]

    S -- failure --> T[sendErrorResponse\nOData JSON error]
    T --> U([HTTP 500\napplication/json])

    S -- success --> V[transferResponseHint\ncopy DataContext hints\nto RoutingContext]
    V --> W{isCountRequest?}

    W -- "yes\n/$count" --> X[resolveCount\nfrom hint or entity list size]
    X --> Y([HTTP 200\ntext/plain integer])

    W -- "no" --> Z{action?}

    Z -- "DELETE\nor empty UPDATE" --> AA([HTTP 204 No Content])

    Z -- "other" --> AB{isSingleEntity?\npath has key predicate}

    AB -- "yes\nor single result\nfrom CREATE/UPDATE" --> AC[CdsEntitySerializer\n.serializeEntity\n@odata.context /$entity]
    AC --> AD([HTTP 200/201\napplication/json\nodata.metadata=minimal])

    AB -- "no\ncollection" --> AE[resolveInlineCount\nfrom response hint]
    AE --> AF[CdsEntitySerializer\n.serializeCollection\n@odata.context + value array]
    AF --> AG([HTTP 200\napplication/json\nodata.metadata=minimal])
```

## $batch Part Dispatch

```mermaid
flowchart TD
    BA([multipart/mixed body]) --> BB[CdsBatchHandler\nextractBoundary]
    BB --> BC[parseMultipart\nsplit on boundary]
    BC --> BD{part type?}

    BD -- "regular part" --> BE[dispatchBatchPart\nparse request line\nmethod + path + body]
    BE --> BF[requestEntity\nevent bus]
    BF --> BG[formatRawHttpResponse\nraw HTTP fragment]

    BD -- "changeset\nContent-Type: multipart/mixed" --> BH[dispatchChangeSet\nsequential execution]
    BH --> BI{response status\n>= 400?}
    BI -- "yes" --> BJ[abort changeset\nreturn error part only]
    BI -- "no" --> BK[continue next part]
    BK --> BH

    BG --> BL[serializeMultipart\nwrap parts in boundary]
    BJ --> BL
    BL --> BM([HTTP 202\nmultipart/mixed])
```

## Component Overview

```mermaid
flowchart LR
    subgraph Endpoint
        E1[ODataV4CdsEndpoint\n/odata2/]
    end

    subgraph Handler
        H1[CdsODataEndpointHandler]
    end

    subgraph Parsing
        P1[CdsQueryParser\nOData query → DataQuery]
    end

    subgraph Serialization
        S1[CdsEntitySerializer\nEntityWrapper → OData JSON]
    end

    subgraph Batch
        B1[CdsBatchHandler\nmultipart/mixed §11.7]
    end

    subgraph NeonBee_Core
        N1[EntityVerticle.requestEntity\nVert.x event bus]
        N2[DataQuery / DataRequest\n/ DataContext]
        N3[EntityWrapper]
    end

    subgraph Olingo_Remnant
        O1[OData.newInstance\nCSDL XML for $metadata only]
    end

    E1 --> H1
    H1 --> P1
    H1 --> B1
    H1 --> N1
    H1 --> O1
    P1 --> N2
    N1 --> N3
    N3 --> S1
```

## Key Design Decisions

| Concern | Decision |
|---|---|
| `$metadata` | Delegates to Olingo via `executeBlocking` — only remaining Olingo usage |
| All data requests | Event loop only — no `executeBlocking` |
| FQN resolution | Built from `schemaNamespace + "." + extractEntityName(resourcePath)` — `NormalizedUri.fullQualifiedName` is always null for paths starting with `/` |
| Response format | `application/json;odata.metadata=minimal` with `@odata.context`, `@odata.count`, `value` envelope |
| Batch | Multipart/mixed per OData V4 §11.7; changesets abort on first 4xx/5xx |
| Response hints | `EntityVerticle` signals applied query options via `DataContext.responseData()`; copied to `RoutingContext` under `response.` prefix |
