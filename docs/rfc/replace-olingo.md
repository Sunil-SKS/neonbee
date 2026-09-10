# Replace  Apache Olingo

### Contents
- [Abstract](#abstract)
- [Technical Details](#technical-details)

### Abstract

Apache Olingo was archived on June 2, 2026 (read-only). NeonBee uses it deeply — 39 main source files, including heavy reliance on Olingo internals (MetadataParser, SchemaBasedEdmProvider,
uri.parser.Parser, BatchParserCommon, etc.), not just the public API.

Currently, there is no other Java OData v4 library with an equivalent API. The options are:

| Option                                                         | Effort                        | Risk                                                            |
|----------------------------------------------------------------|-------------------------------|-----------------------------------------------------------------|
| Fork Olingo latest Version                                                   | Minimal — zero source changes | Only CVE patching in transitive deps.Check the license with OSS |
| SAP CAP Java SDK                                               | Large — ecosystem lock-in     | Tightly coupled to SAP BTP                                      |
| Introduce new REST endpoint alongside the existing OData V4 endpoint | Complete Rewrite              | High; completely different API                                      |



### Technical Details

#### Fork Olingo latest Version (5.0.0) into the NeonBee GitHub org and publish to Maven Central or GitHub Packages.
    Fork https://github.com/apache/olingo-odata4 (tag rel/olingo-odata4-5.0.0) into the SAP/NeonBee GitHub org.<br/>
    Change the group ID from org.apache.olingo → io.neonbee (or keep it under a new org namespace).<br/>
    Publish the 3 needed artifacts to Maven Central or GitHub Packages:
        - odata-commons-api, odata-commons-core
        - odata-server-api, odata-server-core
        - odata-server-core-ext<br/>
    Update build.gradle to point to the new group ID — no Java source changes needed.<br/>

#### Introduce new REST endpoint alongside the existing OData V4 endpoint

Introduce a new REST endpoint alongside the existing OData V4 endpoint, with both endpoints powered by the same CDS model.<br/>
The new endpoint retains the OData wire format—including request query syntax and response JSON—while replacing Olingo as the internal orchestrator
with a cleaner, CDS-native dispatch layer.<br/>
 [RFC: Replace Olingo with a new REST endpoint](replace-olingo-with-rest-endpoint.md)