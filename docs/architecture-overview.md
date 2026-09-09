# tibERbu CCE Emitter Adaptor — Architecture Overview

## 1. Purpose

The Emitter Adaptor is a standalone **Spring Boot 3.x** application that sits behind the gateway and serves a single source system — **TibERbu**. It is responsible for:

1. **Receiving** Bundle-based clinical event payloads from TibERbu through the gateway to `POST /inbound`
2. **Ignoring** any bundle entry whose resource type is `Patient`, as it is not an event payload
3. **Normalizing** each remaining bundle entry as an individual FHIR event for downstream handling
4. **Filtering** the event by facility allowlist before dispatch to downstream systems
5. **Constructing** CloudEvents v1.0 envelopes with CCE-required fields and extensions
6. **Forwarding** the CloudEvents to the CCE Collector Service via `RestClient`

> The current contract is explicit: the top-level payload is always a Bundle; any entry whose `resourceType` is `Patient` is ignored; every other entry is a candidate event for forwarding.

## 2. System Context

```
┌─────────────────────────────────────────────────────────────┐
│                           tibERbu                           │
│         (Sends Bundle-based FHIR R4 event payloads)         │
└──────────────────────────┬──────────────────────────────────┘
                           │  HTTP POST /inbound
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                 CCE Gateway (auth + routing)                │
│    Authenticates the source system, routes to the adaptor   │
└──────────────────────────┬──────────────────────────────────┘
                           │  routed to the adaptor
                           ▼
┌─────────────────────────────────────────────────────────────┐
│        ★ tibERbu CCE Emitter Adaptor (this service) ★       │
│             Spring Boot 3.4.x + HAPI FHIR 7.4.0             │
│                                                             │
│ 1. Receive Bundle payload via POST /inbound                 │
│ 2. Ignore any confirmed Patient entry                       │
│ 3. Parse remaining entries as individual events             │
│ 4. Apply the facility filter                                │
│ 5. Build a CloudEvents v1.0 envelope per event              │
│ 6. Forward via RestClient to the CCE Collector              │
└──────────────────────────┬──────────────────────────────────┘
                           │  HTTP POST /v1/events (CloudEvents JSON)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    CCE Collector Service                    │
│    Validate (type only) → Deduplicate → Publish to Kafka    │
└──────────────────────────┬──────────────────────────────────┘
                           │  Kafka: cce.events.inbound
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    CCE Compliance Service                   │
│     Match → Enroll → Complete Steps → Detect Deviations     │
└─────────────────────────────────────────────────────────────┘
```

## 3. Architecture Principles

| Principle | Application |
|-----------|-------------|
| **Spring Boot Standard** | Standard Spring Boot application — embedded Tomcat, DI, `@ConfigurationProperties`, Actuator health/metrics |
| **Stateless** | No local database; no session state; all context derived from inbound request |
| **Single Responsibility** | Serves one source system, named by `cce.emitter.source` — currently tibERbu |
| **Single Source** | Serves tibERbu only; the emitted `source` attribute is fixed by `cce.emitter.source` |
| **Idempotent Output** | Same source event produces the same CloudEvents `id`, whenever the inbound envelope carries a `traceId` — Collector handles dedup from there. Absent a `traceId`, the `id` is random per delivery |
| **Fail-Fast** | Invalid payloads rejected immediately with descriptive errors |
| **Retry with Backoff** | Collector forwarding uses Spring Retry with exponential backoff on 5xx/timeout |

## 4. Technology Stack

| Concern | Technology | Version |
|---------|------------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.4.x |
| Build tool | Gradle (Kotlin DSL) | 8.x |
| HTTP server | Embedded Tomcat | (via Spring Boot) |
| REST endpoints | Spring Web (`@RestController`) | |
| HTTP client | Spring `RestClient` | (Spring 6.1+) |
| FHIR library | HAPI FHIR | 7.4.0 |
| JSON | Jackson | (via Spring Boot) |
| Retry | Spring Retry | |
| Health & metrics | Spring Boot Actuator + Micrometer + Prometheus | |
| Configuration | `application.yml` + `@ConfigurationProperties` | |
| Testing | JUnit 5, Spring Boot Test, WireMock | |
| Coverage | JaCoCo | (Gradle plugin) |
| Static analysis | SonarQube Cloud (`org.sonarqube`) | 6.3.1.5724 |

## 5. Source Routing

The adaptor is a plain HTTP service — there is no mediator registration, heartbeat, or
response envelope. tibERbu posts Bundle payloads through the gateway, which routes them
to `/inbound`; the adaptor itself does no source resolution.

### Runtime shape

```
┌──────────────────────────────────────────────────────────┐
│           Spring Boot Application                         │
│                                                           │
│  ┌──────────────────┐  ┌─────────────────────────────┐   │
│  │ Embedded Tomcat  │  │ @RestController (InboundCtrl)│   │
│  │ port: 8080       │──│  POST /inbound               │   │
│  └──────────────────┘  └─────────────────────────────┘   │
│  ┌──────────────────┐  ┌─────────────────────────────┐   │
│  │ SourceAdaptor    │  │ CollectorForwardingService  │   │
│  │ Service          │  │ (@Retryable RestClient →    │   │
│  │ (header routing) │  │  CCE Collector)             │   │
│  └──────────────────┘  └─────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────┐    │
│  │ Spring Boot Actuator                              │    │
│  │  /actuator/health, /actuator/prometheus            │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

### Source attribution

The adaptor serves a single source system, configured as `cce.emitter.source`. That value
is stamped on every CloudEvent as the `source` attribute and is used downstream as part of
the deduplication key. Startup fails fast if it is unset.

## 6. Package Structure

```
org.openphc.tiberbu.cce.emitter/
├── CceEmitterAdaptorApplication.java             # @SpringBootApplication entry point
│
├── config/                                        # Spring configuration
│   ├── FhirConfig.java                            #   @Bean FhirContext.forR4() singleton (IParser is created per call, not shared)
│   ├── RestClientConfig.java                      #   @Bean RestClient for the CCE Collector
│   ├── RetryConfig.java                           #   Spring Retry configuration
│   ├── CollectorProperties.java                   #   @ConfigurationProperties for cce.collector.*
│   ├── EmitterProperties.java                     #   @ConfigurationProperties for cce.emitter.* (source identity)
│   └── EmitterStartupValidator.java               #   Fail-fast on missing cce.emitter.source; logs resolved config
│
├── controller/                                    # Spring MVC controllers
│   └── InboundEventController.java                #   @RestController: POST /inbound
│
├── adaptor/                                       # Source system adaptors
│   └── SourceAdaptorService.java                  #   Resolves source from config, FHIR parsing + CloudEvents building
│
├── cloudevents/                                   # CloudEvents envelope construction
│   ├── CloudEventEnvelopeBuilder.java             #   Builds CloudEvents v1.0 JSON
│   └── EventIdGenerator.java                      #   Deterministic UUID v5 from traceId + entry resource id
│
├── fhir/                                          # FHIR utilities
│   ├── BundleEntryExtractor.java                  #   Bundle-first extraction: skip any confirmed Patient entry, yield rest as BundleEntry
│   ├── BundleEntry.java                           #   (index, resourceJson, resourceType) for one extracted entry
│   ├── FhirResourceParser.java                    #   HAPI FHIR parse of one entry's resourceJson (fresh IParser per call)
│   ├── FacilityIdExtractor.java                   #   Facility ID: the resource's organization reference, based on real tibERbu data
│   └── PatientIdExtractor.java                    #   Extract patient identifier from FHIR resources
│
├── filter/                                        # Facility filter
│   ├── FacilityFilter.java                        #   Spring bean: enforceFilter() — throws on deny, increments counter
│   └── FacilityFilterProperties.java              #   @ConfigurationProperties("cce.emitter.facility-filter")
│
├── service/                                       # Business logic
│   ├── InboundEventService.java                   #   Orchestrates pipeline: process → forward (+ metrics + MDC)
│   ├── CollectorForwardingService.java            #   @Retryable: POST to Collector via RestClient (+ latency timer)
│   └── CollectorTokenService.java                 #   OAuth2 client_credentials token management (Keycloak)
│
├── model/                                         # DTOs
│   ├── CloudEventDto.java                         #   CloudEvents v1.0 output DTO
│   ├── InboundRequest.java                        #   Wraps incoming HTTP body + lowercased headers
│   ├── SourceMetadata.java                        #   sourceIdentifier, facilityId, correlationId, traceId, bundleEntryIndex
│   ├── BundleEntryResult.java                     #   One bundle entry's outcome: ready to forward, or already terminal (skipped/failed)
│   ├── TransformationResult.java                  #   Per-event success/failure detail
│   ├── InboundOutcome.java                        #   HTTP status + body returned to the controller
│   ├── AcknowledgementResponse.java               #   Body for the ignored outcome
│   ├── ProcessedEventsResponse.java               #   Typed success/skipped response body model
│   ├── CollectorResponse.java                     #   Response DTO from Collector
│   └── ErrorResponse.java                         #   {error: {code, message}, timestamp} — every GlobalExceptionHandler body
│
├── exception/                                     # Custom exceptions
│   ├── FhirMappingException.java                  #   FHIR parsing failures → 422
│   ├── PatientIdNotFoundException.java            #   Patient identifier not extractable → 400
│   ├── FacilityFilterRejectedException.java       #   Facility filter denial → caught in InboundEventService → 200 skipped
│   ├── CollectorForwardingException.java          #   Retryable Collector errors (5xx/timeout) → 502
│   ├── CollectorClientException.java              #   Non-retryable Collector errors (4xx)
│   └── GlobalExceptionHandler.java                #   @RestControllerAdvice for consistent error responses
```

**37 source files** across 9 packages.

### Resources

```
src/main/resources/
├── application.yml                                # Base config (all profiles inherit)
├── application-dev.yml                            # Dev profile
├── application-prod.yml                           # Prod profile (env-var driven)
└── logback-spring.xml                             # Structured logging: dev (human-readable + MDC) / prod (JSON)
```

### Test Resources

```
src/test/
├── java/org/openphc/tiberbu/cce/emitter/
│   ├── integration/                               # Integration tests (@ActiveProfiles("integration"))
│   │   ├── FullPipelineIntegrationTest.java       #   End-to-end FHIR → CloudEvent → Collector WireMock
│   │   ├── RetryIntegrationTest.java              #   Retry behavior (503, 422, 400, eventual success)
│   │   └── ActuatorMetricsIntegrationTest.java    #   Health probes, Prometheus, custom metrics
│   └── ...                                        # Unit test packages mirror main structure
└── resources/
    ├── application-integration.yml                # Integration test profile (random port, WireMock, fast retry)
    ├── fhir/                                      # FHIR test fixtures
    │   ├── encounter-visit.json
    │   └── observation-lab.json
    └── tiberbu/                                   # tibERbu-specific test fixtures
        ├── fhir-bundle.json
        ├── fhir-encounter.json
        └── fhir-observation.json
```

## 7. Request Processing Pipeline

### 7.1 SourceAdaptorService

Single `@Component` that reads `cce.emitter.source` config and transforms FHIR R4 resources into CloudEvents, stamping that source on each one.

### 7.2 Processing Steps

| Step | Component | Description |
|------|-----------|-------------|
| 1 | `InboundEventController` | Receives HTTP POST, creates `InboundRequest`, delegates to `InboundEventService`, maps the returned `InboundOutcome` onto status + JSON body |
| 2 | `InboundEventService.process()` | Orchestrates the full pipeline (steps 3–5), returns `InboundOutcome` |
| 3 | `SourceAdaptorService.processBundleEntries()` | Parses `resource` as a FHIR Bundle, skips any entry whose `resourceType` is `Patient`, and iterates the remaining entries as candidate events |
| 4 | `SourceAdaptorService.processBundleEntries()` | Per entry, independently: extracts patient identifier, resolves facility ID, applies the facility filter (a denial is a terminal SKIPPED outcome for that entry only, not an aborting exception), stamps `cce.emitter.source`, builds a `CloudEventDto` — returns `List<BundleEntryResult>`, one per candidate entry |
| 5 | `CollectorForwardingService.forward()` | POSTs each CloudEvent to Collector via `RestClient`; `@Retryable` on 5xx |

## 8. External Interfaces

### 8.1 Inbound (from tibERbu)

| Direction | Protocol | Endpoint | Content |
|-----------|----------|----------|---------|
| **IN** | HTTP POST | `/inbound` | FHIR R4 resource (source identified via headers) |

### 8.2 Outbound (to CCE Collector)

| Direction | Protocol | Endpoint | Content |
|-----------|----------|----------|---------|
| **OUT** | HTTP POST | `{collector-url}/v1/events` | CloudEvents v1.0 JSON with FHIR R4 payload |


## 9. Configuration

```
┌─────────────────────────────────────┐
│ Highest Priority                     │
│                                      │
│  1. Environment Variables            │  ← SPRING_APPLICATION_JSON, --server.port
│  2. Profile-specific YAML            │  ← application-prod.yml
│  3. application.yml                  │  ← default config
│                                      │
│ Lowest Priority                      │
└─────────────────────────────────────┘
```

## 10. Error Handling Strategy

Errors are handled by `GlobalExceptionHandler` (`@RestControllerAdvice`):

| Scenario | Action | HTTP Status |
|----------|--------|-------------|
| Payload yields no events (`resource` not a Bundle, empty `entry[]`, or patient entry only) | Log debug + silently ignore | 200 OK with `status: "ignored"` |
| Facility filter denied (facility not in configured list) | Log + acknowledge | 200 OK with `status: "skipped"` |
| FHIR resource unparseable | Log + reject | 422 with `FHIR_MAPPING_ERROR` |
| Patient identifier not extractable | Log + reject | 400 with `PATIENT_ID_NOT_FOUND` |
| Collector returns any 4xx (non-retryable) | Log + return error | the Collector's own status with `COLLECTOR_CLIENT_ERROR` |
| Collector returns 200 (duplicate) | Log + return success | 200 (idempotent) |
| Collector returns 5xx | Retry with backoff (max 3) | 502 with `COLLECTOR_FORWARDING_ERROR` if all retries exhausted |
| Collector unreachable (timeout/refused) | Retry with backoff (max 3) | 502 with `COLLECTOR_FORWARDING_ERROR` if all retries exhausted |
| Unsupported HTTP method on `/inbound` | Log debug + reject | 405 with `METHOD_NOT_ALLOWED` (never reported as 500) |
| Any other unanticipated exception | Log full details server-side + reject with a generic message | 500 with `INTERNAL_ERROR` (never the real exception's message or class name) |

## 11. Security

| Concern | Mechanism |
|---------|-----------|
| **Inbound (tibERbu → adaptor)** | Handled by the CCE Gateway before the request reaches the adaptor. The adaptor does not authenticate inbound callers itself and reads no credentials from the request. |
| **Outbound (adaptor → Collector)** | Configurable — see the table below. Independent of inbound auth; the two are separate trust boundaries. |
| **TLS** | HTTPS connections configurable via Spring Boot `server.ssl.*` properties |

### 11.1 Outbound authentication options

`CollectorTokenService` resolves the outbound credential at request time and
`RestClientConfig` attaches it as `Authorization: Bearer <token>`. Three modes, selected
purely by configuration:

| Mode | Configuration | Behaviour |
|------|---------------|-----------|
| **OAuth2 client credentials** | `cce.collector.auth.keycloak-host`, `.realm`, `.client-id`, `.client-secret` — all four required | Token fetched via the `client_credentials` grant, cached, and refreshed 30s before expiry. |
| **Static Bearer token** | `cce.collector.auth.token` | The configured token is sent as-is. |
| **No authentication** | Leave both unset | No `Authorization` header is sent. |

OAuth2 takes precedence whenever all four Keycloak properties are set; otherwise the static
token is used; if that is also blank, the header is omitted entirely. All three are selected
by configuration alone — no code change.

## 12. Deployment

| Aspect | Value |
|--------|-------|
| **Artifact** | `tiberbu-cce-emitter-adaptor-1.0.0-SNAPSHOT.jar` (Spring Boot fat JAR) |
| **Port** | 8080 |
| **Liveness** | `/actuator/health/liveness` |
| **Readiness** | `/actuator/health/readiness` |
| **Metrics** | `/actuator/prometheus` |
| **Key env vars** | `CCE_COLLECTOR_URL`, `KEYCLOAK_HOST`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET`, `FACILITY_FILTER_IDS`, `SPRING_PROFILES_ACTIVE` |

### Docker

| Stage | Base Image | Purpose |
|-------|-----------|--------|
| `build` | `eclipse-temurin:21-jdk-jammy` | Compile + `bootJar` (tests skipped) |
| `runtime` | `eclipse-temurin:21-jre-jammy` | Run the fat JAR |

Security: runs as non-root `appuser`. Includes `HEALTHCHECK` via `/actuator/health/liveness`.

### Docker Compose (Local Development)

| Service | Image | Port(s) | Purpose |
|---------|-------|---------|--------|
| `collector-stub` | `wiremock/wiremock:3.9.0` | 5055 | WireMock stub for CCE Collector |

WireMock mappings in `wiremock/mappings/`. Dockerfile is sufficient for production — `docker-compose.yml` is a local development convenience.

## 13. Observability

> **See also:** [Data Dictionary — §7 Metrics Reference](data-dictionary.md#7-metrics-reference) and [§8 MDC Context Fields](data-dictionary.md#8-mdc-context-fields) for authoritative field-level definitions. [Monitoring & Alerting Guide](monitoring-alerting.md) for Prometheus alert rules, Grafana dashboards, and log-based monitoring.

### Custom Metrics (Micrometer)

Registered in `InboundEventService` and `CollectorForwardingService` via constructor-injected `MeterRegistry`.

| Metric | Type | Tags | Registered In | Description |
|--------|------|------|---------------|-------------|
| `tiberbu.cce.emitter.events.received` | Counter | `source`, `path` | `InboundEventService` | Inbound requests received (one per `POST /inbound` call, regardless of bundle size) |
| `tiberbu.cce.emitter.entries.received` | Counter | `source` | `InboundEventService` | Candidate bundle entries extracted, before any outcome is decided — the correct entry-level denominator |
| `tiberbu.cce.emitter.entries.forwarded` | Counter | `source` | `InboundEventService` | Events successfully forwarded to Collector |
| `tiberbu.cce.emitter.entries.duplicate` | Counter | — | `InboundEventService` | Duplicate events (Collector returned 200) |
| `tiberbu.cce.emitter.entries.rejected` | Counter | — | `CollectorForwardingService` | Events rejected by Collector (4xx) |
| `tiberbu.cce.emitter.entries.failed` | Counter | `source`, `reason` | `InboundEventService` | Entries that failed adaptation (FHIR parsing or missing patient identifier), before ever reaching the Collector. `reason` is the exception's simple class name. |
| `tiberbu.cce.emitter.collector.latency` | Timer | — | `CollectorForwardingService` | Collector forwarding round-trip latency |
| `tiberbu.cce.emitter.collector.retries` | Counter | — | `CollectorForwardingService` | Retry attempts exhausted |
| `tiberbu.cce.emitter.entries.filtered` | Counter | `source`, `facility`, `reason` | `FacilityFilter` | Events denied by facility filter (`reason`: `NOT_IN_ALLOWLIST`). Events with no facility ID pass through and are not counted. |

### Structured Logging (MDC)

`InboundEventService` populates SLF4J MDC per-event with `try/finally` to ensure cleanup:

| MDC Key | Source | Description |
|---------|--------|-------------|
| `correlationId` | Adaptor-generated | Trace correlation ID |
| `source` | Resolved source key | Source system identifier (e.g., `"tiberbu"`) |
| `eventType` | FHIR `resourceType` | CloudEvents `type` field |
| `subject` | Patient identifier | Identifies the patient for the event |

### Logback Configuration (`logback-spring.xml`)

| Profile | Format | Description |
|---------|--------|-------------|
| `!prod` (default/dev) | Human-readable with MDC | `%d [%thread] %-5level %logger [correlationId] [source] [eventType] [subject] - %msg` |
| `prod` | Pattern-based JSON | `{"timestamp":...,"level":...,"logger":...,"correlationId":...,"source":...,"eventType":...,"subject":...,"message":...}` |

> Production JSON logging uses pattern-based layout — no extra dependencies (e.g., logback-contrib) required.

### Prometheus

Spring Boot 3.4.x requires explicit Prometheus enablement in `application.yml`:

```yaml
management:
  endpoint:
    prometheus:
      enabled: true
  prometheus:
    metrics:
      export:
        enabled: true
```

These settings are in the base `application.yml` and carry through to all profiles via Spring Boot's additive YAML merging.

## 14. Non-Functional Requirements

| Requirement | Target |
|-------------|--------|
| **Availability** | 99.9% uptime |
| **Latency** | < 500ms end-to-end (receive → forward) |
| **Throughput** | 100 events/sec sustained |
| **Stateless** | No local database; all state lives in the CCE platform |
| **Retry** | Max 3 retries with exponential backoff for Collector calls |
| **Max payload** | 1 MB (matching Collector limit) |
