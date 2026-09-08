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
    Note over EvtSvc: any bundle entry whose resourceType is Patient is ignored

    EvtSvc->>Svc: adapt(inboundRequest)
    activate Svc
    Svc->>Svc: Parse Bundle, skip every entry confirmed to be a Patient, iterate the rest
    Svc->>Svc: buildSourceMetadata (resolve facilityId: header → FHIR fallback)
    Svc->>Svc: FacilityFilter.enforceFilter(facilityId, sourceKey)
    Note over Svc: FacilityFilterRejectedException if denied →<br/>caught by InboundEventService → 200 OK (skipped)
    Svc->>CE: build(eventResource, patientUpid, type, metadata)
    CE-->>Svc: CloudEventDto
    Svc-->>EvtSvc: List<CloudEventDto>
    deactivate Svc

    loop Each CloudEvent
        Note over EvtSvc: MDC: correlationId, source, eventType, subject
        EvtSvc->>Fwd: forward(cloudEvent)
        activate Fwd
        Note over Fwd: Timer: tiberbu.cce.emitter.collector.latency
        Note over Fwd: CollectorTokenService: get Bearer token<br/>(OAuth2 Keycloak or static fallback)
        Fwd->>Col: POST /v1/events
        Col-->>Fwd: 202 Accepted
        Fwd-->>EvtSvc: CollectorResponse
        deactivate Fwd
        Note over EvtSvc: Counter: tiberbu.cce.emitter.events.forwarded
        Note over EvtSvc: MDC.clear()
    end

    EvtSvc->>EvtSvc: ProcessedEventsResponse.from(results)

    EvtSvc-->>Ctrl: InboundOutcome(202, body)
    deactivate EvtSvc

    Ctrl-->>Src: 202 (application/json)
    deactivate Ctrl
```

## 3. Bundle Processing & the Ignore / Skip Decision

There is no source-level filter — the source is fixed by `cce.emitter.source`. The only
two non-forwarding outcomes are `ignored` (the payload produced no events) and
`skipped` (the facility filter denied the event); both return `200 OK`.

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
    C2 -->|Yes| D["Process candidate entries as individual events"]
    D --> E{"Facility filter pass?<br/>(no facility ID = pass)"}

    E -->|No| S["→ 200 OK (status: skipped)"]
    E -->|Yes| F[Forward each event to Collector<br/>→ 202 per processed event]

    style F fill:#e8f5e9
    style S fill:#fff3e0
    style J fill:#fff3e0
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
        Note over Fwd: Counter: tiberbu.cce.emitter.events.rejected
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

```mermaid
flowchart TD
    A[Inbound Request] --> B{Parse OK?}

    B -->|No| C[GlobalExceptionHandler<br/>400/500]
    B -->|Yes| D{"Any candidate entries remain<br/>after skipping every confirmed Patient entry?"}

    D -->|No| E["Log debug + silently ignore<br/>→ 200 OK (ignored)"]
    D -->|Yes| F{FHIR resource valid?}

    F -->|No| G[FhirMappingException<br/>→ 422 FHIR_MAPPING_ERROR]
    F -->|Yes| H{Patient ID found?}

    H -->|No| I[PatientIdNotFoundException<br/>→ 400 PATIENT_ID_NOT_FOUND]
    H -->|Yes| FF{Facility filter pass?}

    FF -->|No| FE["FacilityFilterRejectedException caught<br/>→ 200 OK (skipped)"]
    FF -->|Yes| J{Collector accepts?}

    J -->|202 OK| K[Success → 202 processed]
    J -->|200 Dup| L[Duplicate → still 202]
    J -->|400 Client| M[CollectorClientException<br/>→ include in batch result]
    J -->|5xx × 3| N[CollectorForwardingException<br/>→ 502 COLLECTOR_FORWARDING_ERROR]
    J -->|Unexpected| UE[RuntimeException<br/>→ 500 INTERNAL_ERROR]

    C --> O[GlobalExceptionHandler<br/>plain JSON error body]
    E --> P
    FE --> P
    G --> O
    I --> O
    K --> P
    L --> P
    M --> P
    N --> O
    UE --> O

    O --> P[Return to source system]

    style K fill:#e8f5e9
    style L fill:#fff3e0
    style E fill:#fff3e0
    style FE fill:#fff3e0
    style G fill:#ffebee
    style I fill:#ffebee
    style N fill:#ffebee
    style UE fill:#ffebee
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
