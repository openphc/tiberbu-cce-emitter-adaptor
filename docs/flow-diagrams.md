# tibERbu CCE Emitter Adaptor — Flow Diagrams

All diagrams use Mermaid notation.

## 1. End-to-End Event Flow

```mermaid
flowchart LR
    subgraph Sources
        EBZ[tibERbu<br/>FHIR Bundle]
    end

    GW[CCE Gateway<br/>auth + routing]

    subgraph "tibERbu CCE Emitter Adaptor (Spring Boot)"
        CTRL[InboundEvent<br/>Controller]
        SA[SourceAdaptor<br/>Service]
        IGN["Skip any<br/>Patient entry"]
        PARSE[Parse remaining<br/>bundle entries]
        NORM[CloudEvent<br/>Builder]
        FWD["Collector<br/>Forwarding<br/>@Retryable"]
    end

    subgraph CCE
        COL[CCE Collector]
        KAFKA[Kafka<br/>cce.events.inbound]
    end

    EBZ -->|"POST /inbound"| GW
    GW -->|"routed to adaptor"| CTRL
    CTRL --> SA
    SA --> IGN
    IGN --> PARSE
    PARSE --> NORM
    NORM --> FWD
    FWD -->|"POST /v1/events"| COL
    COL --> KAFKA

    FWD -->|"202 application/json"| EBZ
```

## 2. Request Processing Sequence

```mermaid
sequenceDiagram
    participant Src as Source System
    participant Ctrl as InboundEventController
    participant EvtSvc as InboundEventService
    participant Svc as SourceAdaptorService
    participant CE as CloudEventEnvelopeBuilder
    participant Fwd as CollectorForwardingService
    participant Col as CCE Collector

    Src->>Ctrl: POST /inbound (FHIR Bundle, routed by the gateway)
    activate Ctrl

    Ctrl->>Ctrl: InboundRequest.from(body, headers, path)

    Ctrl->>EvtSvc: process(inboundRequest)
    activate EvtSvc

    Note over EvtSvc: Counter: tiberbu.cce.emitter.events.received

    EvtSvc->>Svc: processBundleEntries(inboundRequest)
    activate Svc
    Svc->>Svc: Parse Bundle, skip every entry confirmed to be a Patient, iterate the rest

    loop Each candidate entry, independently
        Svc->>Svc: parse resource, extract patient id, extract facility id
        Svc->>Svc: FacilityFilter.enforceFilter(facilityId, sourceKey)
        alt Denied by the filter
            Note over Svc: terminal BundleEntryResult — SKIPPED,<br/>this entry never reaches CloudEventEnvelopeBuilder
        else Passed (or no facility id to filter on)
            Svc->>CE: build(eventResource, patientId, type, metadata)
            CE-->>Svc: CloudEventDto
            Note over Svc: BundleEntryResult — ready to forward
        end
        Note over Svc: a parse/patient-id failure here also becomes<br/>a terminal BundleEntryResult — FAILED, and does NOT<br/>stop the loop from reaching the remaining entries
    end

    Svc-->>EvtSvc: List<BundleEntryResult> (forwarded-pending, skipped, and failed entries, in bundle order)
    deactivate Svc

    loop Each BundleEntryResult ready to forward
        Note over EvtSvc: MDC: correlationId, source, eventType, subject
        EvtSvc->>Fwd: forward(cloudEvent)
        activate Fwd
        Note over Fwd: Timer: tiberbu.cce.emitter.collector.latency
        Note over Fwd: CollectorTokenService: get Bearer token<br/>(OAuth2 Keycloak or static fallback)
        Fwd->>Col: POST /v1/events
        Col-->>Fwd: 202 Accepted (or 200 duplicate)
        Fwd-->>EvtSvc: CollectorResponse
        deactivate Fwd
        Note over EvtSvc: a forwarding failure here becomes a FAILED<br/>TransformationResult too — still doesn't stop the loop
        Note over EvtSvc: MDC.clear()
    end

    alt At least one entry forwarded
        EvtSvc->>EvtSvc: ProcessedEventsResponse.from(results) — status "processed"
        EvtSvc-->>Ctrl: InboundOutcome(202, body)
    else No entry forwarded, but at least one was skipped
        EvtSvc->>EvtSvc: ProcessedEventsResponse.from(results) — status "skipped"
        EvtSvc-->>Ctrl: InboundOutcome(200, body)
    else Every entry failed
        Note over EvtSvc: re-throw the first entry's own exception —<br/>flows to GlobalExceptionHandler (§4) instead
    end
    deactivate EvtSvc

    Ctrl-->>Src: 202 or 200 (application/json)
    deactivate Ctrl
```

## 3. Bundle Processing & the Ignore / Skip Decision

There is no source-level filter — the source is fixed by `cce.emitter.source`. The only
two non-forwarding whole-request outcomes are `ignored` (the payload produced no candidate
entries at all) and `skipped` (at least one entry was facility-filtered and none reached
the Collector); both return `200 OK`. This diagram shows the per-entry decision that feeds
into that aggregate — see [§2](#2-request-processing-sequence) for how the per-entry results
combine into the final response, including the mixed-outcome case (some forwarded, some
skipped or failed, still `202`).

```mermaid
flowchart TD
    A[POST /inbound] --> B{"resource is a Bundle<br/>with a non-empty entry[] ?"}

    B -->|No| J["Non-processable payload<br/>→ 200 OK (status: ignored)"]
    B -->|Yes| C["For every entry: resourceType<br/>= Patient?"]

    C -->|Yes, for that entry| C1[Skip that entry]
    C -->|No, for that entry| C1b[Keep it as a candidate]
    C1 --> C2{"Any candidate entries remain,<br/>once every entry has been checked?"}
    C1b --> C2
    C2 -->|No| J
    C2 -->|Yes| D["Process each candidate entry independently<br/>(one entry's outcome never affects another)"]
    D --> E{"Per entry — facility filter pass?<br/>(no facility ID = pass)"}

    E -->|No| S["This entry: SKIPPED"]
    E -->|Yes| F["This entry: forward to Collector<br/>→ FORWARDED or FAILED"]

    S --> G{"Aggregate — did ANY entry<br/>forward or get skipped?"}
    F --> G
    G -->|Yes| R["→ 202 processed, or 200 skipped<br/>(events[] lists every entry's real outcome)"]
    G -->|"No (every entry FAILED)"| X["re-throw the first entry's exception<br/>→ flows to §4 Error Responses"]

    style F fill:#e8f5e9
    style R fill:#e8f5e9
    style S fill:#fff3e0
    style J fill:#fff3e0
    style X fill:#ffebee
```

## 4. Collector Forwarding with Retry

```mermaid
sequenceDiagram
    participant Fwd as CollectorForwardingService
    participant Col as CCE Collector
    participant Log as Logger

    Fwd->>Col: POST /v1/events (attempt 1)

    alt 202 Accepted
        Col-->>Fwd: 202 + { status: accepted }
        Fwd->>Log: Event forwarded successfully
    else 200 Duplicate
        Col-->>Fwd: 200 + { status: duplicate }
        Fwd->>Log: Event is duplicate (idempotent)
    else 400 Client Error
        Col-->>Fwd: 400 + { error: ... }
        Fwd->>Log: Client error — no retry
        Note over Fwd: Counter: tiberbu.cce.emitter.entries.rejected
        Fwd->>Fwd: throw CollectorClientException
    else 5xx / Timeout
        Col-->>Fwd: 503 Service Unavailable
        Fwd->>Log: Retry 1 of 3 (backoff 1s)

        Note over Fwd: @Retryable backoff: 1s

        Fwd->>Col: POST /v1/events (attempt 2)

        alt Success
            Col-->>Fwd: 202 Accepted
        else Still failing
            Col-->>Fwd: 503
            Fwd->>Log: Retry 2 of 3 (backoff 2s)

            Note over Fwd: Exponential backoff: 2s

            Fwd->>Col: POST /v1/events (attempt 3)

            alt Success
                Col-->>Fwd: 202 Accepted
            else Exhausted
                Col-->>Fwd: 503
                Note over Fwd: Counter: tiberbu.cce.emitter.collector.retries
                Fwd->>Fwd: @Recover → throw CollectorForwardingException
            end
        end
    else SocketTimeoutException (read timeout)
        Note over Fwd: Caught via catch-all with instanceof check
        Fwd->>Log: Collector read timed out (will retry)
        Note over Fwd: Wraps as CollectorForwardingException → triggers @Retryable
    end
```

## 5. Error Handling Flow

Every candidate entry reaches one of four per-entry outcomes independently — one
entry's parse/patient-id/filter/Collector failure never stops a sibling entry from
being attempted (see [§2](#2-request-processing-sequence)). Only once every candidate
entry in the bundle has reached a terminal per-entry outcome does the aggregation step
below decide the single response for the whole request.

```mermaid
flowchart TD
    A[Inbound Request] --> B{Parse OK?}

    B -->|No| C[GlobalExceptionHandler<br/>400/500]
    B -->|Yes| D{"Any candidate entries remain<br/>after skipping every confirmed Patient entry?"}

    D -->|No| E["→ 200 OK (ignored)"]
    D -->|Yes| PerEntry["For EACH candidate entry, independently:"]

    PerEntry --> F{FHIR resource valid?}
    F -->|No| G["FhirMappingException<br/>this entry: FAILED"]
    F -->|Yes| H{Patient ID found?}

    H -->|No| I["PatientIdNotFoundException<br/>this entry: FAILED"]
    H -->|Yes| FF{Facility filter pass?}

    FF -->|No| FE["this entry: SKIPPED"]
    FF -->|Yes| J{Collector accepts?}

    J -->|202 OK| K["this entry: FORWARDED (accepted)"]
    J -->|200 Dup| L["this entry: FORWARDED (duplicate)"]
    J -->|400 Client| M["CollectorClientException<br/>this entry: FAILED"]
    J -->|5xx × 3| N["CollectorForwardingException<br/>this entry: FAILED"]

    G --> Agg{"Aggregate across every entry —<br/>did ANY entry forward or get skipped?"}
    I --> Agg
    FE --> Agg
    K --> Agg
    L --> Agg
    M --> Agg
    N --> Agg

    Agg -->|Yes| R["→ 202 processed, or 200 skipped<br/>(events[] lists every entry's real outcome)"]
    Agg -->|"No (every entry FAILED)"| X["re-throw the first entry's own exception"]

    C --> O[GlobalExceptionHandler<br/>plain JSON error body]
    X --> O
    E --> P
    R --> P

    O --> P[Return to source system]

    style R fill:#e8f5e9
    style K fill:#e8f5e9
    style L fill:#e8f5e9
    style E fill:#fff3e0
    style FE fill:#fff3e0
    style G fill:#ffebee
    style I fill:#ffebee
    style M fill:#ffebee
    style N fill:#ffebee
    style X fill:#ffebee
```

## 6. Component Dependency Graph

```mermaid
flowchart TD
    CTRL[InboundEventController]
    EVTSVC[InboundEventService]
    REG[SourceAdaptorService]
    FWD[CollectorForwardingService]

    SA_ABS["(internal FHIR→CloudEvent logic)"]

    CE[CloudEventEnvelopeBuilder]
    PIE[PatientIdExtractor]
    FRP[FhirResourceParser]
    IDG[EventIdGenerator]

    FC["FhirConfig<br/>@Bean FhirContext"]
    RC["RestClientConfig<br/>@Bean RestClient"]

    CTRL --> EVTSVC

    EVTSVC --> REG
    EVTSVC --> FWD

    REG --> SA_ABS

    SA_ABS --> CE
    SA_ABS --> PIE

    CE --> IDG

    FWD --> RC

    SA_ABS --> FC
    FRP --> FC

    style CTRL fill:#bbdefb
    style FWD fill:#c8e6c9
    style REG fill:#fff9c4
```

## 7. Deployment Topology

```mermaid
flowchart LR
    subgraph "External Sources"
        A2[tibERbu<br/>FHIR Bundle]
    end

    subgraph "CCE Platform"
        GW[CCE Gateway]
        EA[tibERbu CCE Emitter Adaptor<br/>Spring Boot<br/>:8080]
        COL[CCE Collector]
        KAFKA[Kafka]
    end

    A2 -->|"POST /inbound"| GW
    GW -->|"routed to adaptor"| EA
    EA -->|"POST /v1/events"| COL
    COL --> KAFKA
```
