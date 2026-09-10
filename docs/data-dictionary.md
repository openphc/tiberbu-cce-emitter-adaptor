# tibERbu CCE Emitter Adaptor — Data Dictionary

## 1. CloudEvents Output Envelope

The adaptor outputs CloudEvents v1.0-compliant JSON to the CCE Collector.

### 1.1 Field Definitions

| Field | Type | Required | Source | Description |
|-------|------|----------|--------|-------------|
| `specversion` | string | **Yes** — always `"1.0"` | Static | CloudEvents specification version |
| `id` | string (UUID) | **Yes** | Generated | Unique event identifier (`UUID.randomUUID()` or deterministic hash) |
| `source` | string (URI) | **Yes** | Adaptor | Source system identifier (e.g. `"tiberbu"`) |
| `type` | string | **Yes** | From FHIR resource | FHIR `resourceType` value as-is (e.g., `"Consent"`, `"Observation"`, `"Encounter"`) |
| `subject` | string | Recommended | Extracted from FHIR | Patient identifier, from the entry resource's own `subject.reference` or `patient.reference` (prefix stripped). A `Patient` resource never reaches this extraction, since the bundle contract skips it before extraction runs. Used as Kafka partition key. |
| `time` | string (ISO-8601) | Recommended | Adaptor | Event creation timestamp in UTC |
| `datacontenttype` | string | Recommended | Static | Always `"application/fhir+json"` |
| `data` | object | Recommended | From FHIR resource | FHIR R4 resource JSON |
| `facilityid` | string | Optional | FHIR resource | Facility ID, via `FacilityIdExtractor`: the resource's `organization` reference (reflective `getOrganization()`), based on real tibERbu `Consent` payloads. A `ResourceType/` prefix is stripped (`Organization/1302` → `1302`). `null` when the resource has no `organization` reference (e.g. it has no such field at all, or the field is unpopulated). |
| `sourceeventid` | string | Optional | *(not populated)* | Not populated |
| `correlationid` | string | Recommended | Adaptor-generated | Adaptor-generated UUID |
| `protocolinstanceid` | string | Optional | Usually null | Protocol instance — emitter normally does not set this |
| `protocoldefinitionid` | string | Optional | Usually null | Protocol definition — emitter normally does not set this |
| `actionid` | string | Optional | Usually null | Action — emitter normally does not set this |

### 1.2 Core Field Population

| Rule | Detail |
|------|--------|
| **Adaptor populates all required fields** | The adaptor's core responsibility is to populate `id`, `source`, `type`, `subject`, `time`, `datacontenttype`, and `data` for correct downstream processing by the Compliance Service. |
| **Extension attributes are lowercase** | Per CloudEvents spec, custom extension attributes use `lowercase` without separators: `facilityid`, `sourceeventid`, `correlationid`, `protocolinstanceid`, etc. |

### 1.3 Sample CloudEvent

```json
{
  "specversion": "1.0",
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "source": "tiberbu",
  "type": "Consent",
  "subject": "KE-SHRP-170CDF0A-1363-4972-B36A",
  "time": "2026-09-01T11:55:42.118Z",
  "datacontenttype": "application/fhir+json",
  "facilityid": "FAC-0001",
  "correlationid": "7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d",
  "data": {
    "resourceType": "Consent",
    "id": "VCR-20260901-57098420",
    "status": "active",
    "patient": {"reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"}
  }
}
```

## 2. Inbound Request Model

### 2.1 HTTP Request (from tibERbu)

For the full list of inbound headers, see [API Reference — §2.1 POST /inbound](api-reference.md#21-post-inbound).

### 2.2 InboundRequest Fields

| Field | Type | Populated From | Description |
|-------|------|----------------|-------------|
| `body` | String | HTTP body | Raw request body |
| `headers` | Map<String, String> | HTTP headers (normalized to lowercase keys) | All request headers |
| `path` | String | `HttpServletRequest.getRequestURI()` | Request path |

> **Note:** The TibERbu contract is bundle-first: the request body is always a FHIR Bundle, entry `0` is the patient record and is ignored, and every entry from `1..n` is treated as an individual event payload that is forwarded independently after filtering.

> **Note:** `InboundRequest` is immutable — created via static `from()` factory methods. Source metadata (`SourceMetadata`) is built separately in `SourceAdaptorService` from request headers.

### 2.3 SourceMetadata Fields

| Field | Type | Source | Description |
|-------|------|--------|-------------|
| `sourceIdentifier` | String | The configured `cce.emitter.source` | e.g., `"tiberbu"` |
| `facilityId` | String | `FacilityIdExtractor` — the resource's `organization` reference (see §3.3) | Nullable — null when there is no `organization` reference to resolve |
| `correlationId` | String | Adaptor-generated | For downstream tracing. Never null. |
| `eventTime` | OffsetDateTime | `Instant.now(ZoneOffset.UTC)` | When the adaptor received the event |
| `sourcePath` | String | Request URI path | e.g., `/inbound` |
| `traceId` | String | The inbound envelope's `meta.traceId` | Nullable. The primary input to `EventIdGenerator`'s deterministic CloudEvents `id` — a null/blank value makes the id random instead of deterministic for that event. There is no `sourceEventId` field: tibERbu sends no per-event header. |
| `bundleEntryIndex` | int | Bundle iteration context | This entry's position in `resource.entry[]`. Used by `EventIdGenerator` as the per-entry id differentiator only when the entry's own `resource.id` is absent. |

## 3. Configuration Properties

### 3.1 Collector Properties

Prefix: `cce.collector`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `cce.collector.url` | String | `http://localhost:5001` | Collector base URL |
| `cce.collector.events-path` | String | `/v1/events` | Events endpoint path |
| `cce.collector.timeout` | int | `5000` | HTTP connect + read timeout in ms |
| `cce.collector.retry.max-attempts` | int | `3` | Maximum retry attempts for 5xx/timeout |
| `cce.collector.retry.backoff-ms` | int | `1000` | Initial backoff delay in ms (doubles per retry) |
| `cce.collector.auth.token` | String | — | Static Bearer token (fallback when Keycloak is not configured) |
| `cce.collector.auth.keycloak-host` | String | — | Keycloak base URL (e.g., `https://keycloak.cce.mdtlabs.org`) |
| `cce.collector.auth.realm` | String | — | Keycloak realm name (e.g., `cce`) |
| `cce.collector.auth.client-id` | String | — | OAuth2 client ID for `client_credentials` grant |
| `cce.collector.auth.client-secret` | String | — | OAuth2 client secret |

> **Auth mode selection:** If all four Keycloak properties (`keycloak-host`, `realm`, `client-id`, `client-secret`) are set, OAuth2 `client_credentials` flow is used and tokens are cached/refreshed automatically. Otherwise, the static `token` value is used as a Bearer token.

### 3.2 Emitter Source Properties

Prefix: `cce.emitter`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `cce.emitter.source` | String | `tiberbu` | The single source system this adaptor serves. Emitted as the CloudEvents `source` attribute; startup fails if unset. |

> **No `patient-identifier-system` property.** The bundle contract skips any entry whose `resourceType` is
> `Patient`, so no Patient resource ever reaches per-entry processing, and there is no case where a Patient's
> own `identifier[]` needs matching. The patient identifier for every processed entry instead comes from
> that entry's own
> `subject.reference` or `patient.reference` (see § 1.1's `subject` row above).

### 3.3 Facility Filter Properties

Prefix: `cce.emitter.facility-filter`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `cce.emitter.facility-filter.ids` | List\<String\> | `[]` | `FACILITY_FILTER_IDS` | facility IDs to allow. Empty list = filter inactive (all events pass). Non-empty = only listed IDs are admitted. Comma-separated in env var form (e.g. `"0030,0042,0099"`). **In YAML, always quote IDs** to preserve leading zeros and avoid integer coercion (e.g. `["0234", "0030"]` — without quotes YAML strips the leading zero). Whitespace trimmed and IDs lowercased. Stored as `Set<String>` for O(1) lookup. |

**Matching is case-insensitive.** Both the configured IDs and the resolved facility ID are lowercased before comparison, so `abc-123` in the allowlist admits an inbound `ABC-123`. Numeric facility codes are unaffected.

**Skip behaviour:** Events with a facility ID that is not in the allowlist return `200 OK` with `status: "skipped"` — they are not forwarded to the Collector. A skip is a normal outcome, not an error. Events with no facility ID are passed through unconditionally — only events that carry a resolved facility ID are subject to filtering.

`FacilityIdExtractor` resolves the facility ID reflectively, trying three accessor names in order — `getOrganization()`, `getServiceProvider()`, `getManagingOrganization()` — the same multi-accessor approach `PatientIdExtractor` uses for `subject`/`patient`. A real survey of every resource type actually seen in tibERbu's production `inbound_event_log` found exactly three that carry one of these: `Consent.organization` (a `List<Reference>` — the first populated entry is used), `Encounter.serviceProvider`, and `EpisodeOfCare.managingOrganization` (both a single `Reference`). Every other resource type seen (`AllergyIntolerance`, `Condition`, `MedicationRequest`, `Observation`) declares none of the three and resolves to `null` — including `MedicationDispense` and `Procedure`, which carry a `location` reference instead. That one is deliberately excluded: `location` points to a `Location/`, not an `Organization/`, and the two are not treated as interchangeable.

A `ResourceType/` prefix is stripped generically so `Organization/1302` resolves to `1302`; a bare ID with no prefix passes through unchanged. When a reference carries no reference string, `identifier.value` is used instead. Resources with no `organization` reference — either because the resource type doesn't declare the field at all (e.g. `Observation`, `Encounter`), or the field exists but is unpopulated — resolve to `null` and always pass through.

### 3.4 Server Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `server.port` | int | `8080` (`${SERVER_PORT:8080}`) | Application HTTP port |
| `server.servlet.context-path` | String | `/` | Servlet context path |
| `spring.application.name` | String | `tiberbu-cce-emitter-adaptor` | Spring application name |

### 3.5 Actuator & Metrics Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `management.endpoints.web.exposure.include` | String | `health,info,prometheus,metrics` | Exposed Actuator endpoints |
| `management.endpoint.health.show-details` | String | `when-authorized` (`never` in prod) | How much detail `/actuator/health` reveals |
| `management.endpoint.health.probes.enabled` | boolean | `true` | Enables the liveness/readiness probe groups |
| `management.endpoint.prometheus.enabled` | boolean | `true` | Enables the `/actuator/prometheus` endpoint |
| `management.health.livenessState.enabled` | boolean | `true` | Exposes `/actuator/health/liveness` |
| `management.health.readinessState.enabled` | boolean | `true` | Exposes `/actuator/health/readiness` |
| `management.prometheus.metrics.export.enabled` | boolean | `true` | Enables Prometheus metrics export |
| `management.metrics.tags.application` | String | `tiberbu-cce-emitter-adaptor` | Common tag applied to every metric — see monitoring-alerting.md §1 |

### 3.6 Logging Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `logging.level.org.openphc.tiberbu.cce` | String | `DEBUG` (`INFO` in prod) | Log level for this adaptor's own packages |
| `logging.level.org.springframework.web` | String | `INFO` (`DEBUG` in dev, `WARN` in prod) | Log level for Spring MVC |
| `logging.pattern.console` | String | see `application.yml` | Console pattern for the `!prod` profile — carries the MDC fields (`correlationId`/`source`/`eventType`/`subject`) inline. Superseded by `logback-spring.xml`'s single-line JSON encoder in `prod`; see monitoring-alerting.md §5.1. |

## 4. Collector Response Model

### 4.1 Success (202 Accepted)

```json
{
  "data": {
    "eventId": "evt-uuid",
    "status": "accepted",
    "correlationId": "corr-uuid",
    "timestamp": "2026-02-25T08:00:04.500Z"
  }
}
```

### 4.2 Duplicate (200 OK)

```json
{
  "data": {
    "eventId": "evt-uuid",
    "status": "duplicate",
    "correlationId": "corr-uuid",
    "timestamp": "2026-02-25T08:00:04.500Z"
  }
}
```

### 4.3 Error (400 Bad Request)

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Missing required field: type"
  }
}
```

## 5. Inbound Response Model

`POST /inbound` returns plain JSON — there is no mediator envelope.

### 5.1 InboundOutcome

Internal record returned by `InboundEventService` and mapped onto the HTTP response
by `InboundEventController`.

| Field | Type | Description |
|-------|------|-------------|
| `status` | `HttpStatus` | `202 ACCEPTED` when at least one entry reached the Collector, `200 OK` otherwise |
| `body` | Object | `ProcessedEventsResponse` (202, or 200 when `status: "skipped"`) or `AcknowledgementResponse` (200, `status: "ignored"` only) |

When every candidate entry in a bundle fails outright (none forwarded, none filtered), `InboundEventService.process()` does not return an `InboundOutcome` at all — it re-throws the first entry's original exception, which flows through the normal exception-handling path (API Reference §4) instead.

### 5.2 AcknowledgementResponse (200 Body — `status: "ignored"` only)

Returned only when the bundle produced no candidate entries at all (not JSON, not a Bundle, empty `entry[]`, or every entry confirmed to be a Patient). A facility-filter denial is **not** represented by this type — see `ProcessedEventsResponse` below, which reports per-entry detail even when nothing forwarded.

| Field | Type | Description |
|-------|------|-------------|
| `status` | String | Always `"ignored"` |
| `message` | String | Human-readable explanation |

### 5.3 ProcessedEventsResponse (202, or 200 `status: "skipped"`)

Returned whenever at least one candidate entry was attempted and the request as a whole is a success — `"processed"` when at least one entry reached the Collector, `"skipped"` when none did but at least one was cleanly facility-filtered. Every candidate entry appears in `events[]`, including ones that were skipped or failed — see [Multi-entry failure policy](#multi-entry-failure-policy) below.

| Field | Type | Description |
|-------|------|-------------|
| `status` | String | `"processed"` (`eventsForwarded >= 1`) or `"skipped"` (`eventsForwarded == 0`, all filtered) |
| `eventsForwarded` | int | Number of entries that actually reached the Collector |
| `events` | Array | Every candidate entry's outcome, in bundle order |
| `events[].entryIndex` | int | The entry's position in `resource.entry[]` |
| `events[].eventId` | String | The CloudEvent ID; omitted unless `outcome` is `"forwarded"` |
| `events[].type` | String | FHIR `resourceType` (CloudEvent `type`) — always present |
| `events[].subject` | String | Patient identifier; omitted when it was never resolved |
| `events[].outcome` | String | `"forwarded"`, `"skipped"`, or `"failed"` |
| `events[].collectorStatus` | String | Collector-reported status (`"accepted"` or `"duplicate"`); omitted unless `outcome` is `"forwarded"` |
| `events[].reason` | String | Why the entry was skipped or failed; omitted when `outcome` is `"forwarded"` |

**Example — all forwarded:**

```json
{
  "status": "processed",
  "eventsForwarded": 1,
  "events": [
    {
      "entryIndex": 1,
      "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "type": "Consent",
      "subject": "KE-SHRP-170CDF0A-1363-4972-B36A",
      "outcome": "forwarded",
      "collectorStatus": "accepted"
    }
  ]
}
```

**Example — one forwarded, one failed (still `202`):**

```json
{
  "status": "processed",
  "eventsForwarded": 1,
  "events": [
    {
      "entryIndex": 1,
      "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "type": "Consent",
      "subject": "KE-SHRP-170CDF0A-1363-4972-B36A",
      "outcome": "forwarded",
      "collectorStatus": "accepted"
    },
    {
      "entryIndex": 2,
      "type": "Observation",
      "outcome": "failed",
      "reason": "No patient reference found in Observation resource"
    }
  ]
}
```

### 5.4 Multi-entry failure policy

Every candidate bundle entry is adapted and forwarded independently by `SourceAdaptorService`/`InboundEventService` — one entry's facility-filter denial or failure never prevents a sibling entry in the same bundle from being attempted. This is deliberate: forwarding to the Collector is an irreversible side effect, so if entry A already forwarded successfully by the time entry B fails, there is no way to "undo" A. The response therefore reports exactly what happened to each entry, and the request as a whole is treated as a success (`202`, or `200 skipped`) as long as **at least one** entry forwarded or was cleanly filtered — reporting the whole request as failed in that situation would misrepresent something that already happened.

Only when **every** candidate entry ends in a genuine failure (none forwarded, none filtered) does `InboundEventService` instead re-throw — the first entry's original exception, letting it flow through the normal exception-handling path (API Reference §4) rather than inventing a separate all-failed response shape.

## 6. Metrics Reference

Registered in `InboundEventService` and `CollectorForwardingService` via constructor-injected `MeterRegistry`.

| Metric Name | Type | Tags | Registered In | Description |
|-------------|------|------|---------------|-------------|
| `tiberbu.cce.emitter.events.received` | Counter | `source`, `path` | `InboundEventService` | Inbound requests received (one per `POST /inbound` call, regardless of bundle size) |
| `tiberbu.cce.emitter.entries.received` | Counter | `source` | `InboundEventService` | Candidate bundle entries extracted, before any outcome is decided — the correct entry-level denominator |
| `tiberbu.cce.emitter.entries.forwarded` | Counter | `source` | `InboundEventService` | Events successfully forwarded to Collector |
| `tiberbu.cce.emitter.entries.duplicate` | Counter | — | `InboundEventService` | Duplicate events (Collector returned 200) |
| `tiberbu.cce.emitter.entries.rejected` | Counter | — | `CollectorForwardingService` | Events rejected by Collector (4xx) |
| `tiberbu.cce.emitter.entries.failed` | Counter | `source`, `reason` | `InboundEventService` | Entries that failed adaptation (FHIR parsing or missing patient identifier), before ever reaching the Collector. `reason` is the exception's simple class name. |
| `tiberbu.cce.emitter.collector.latency` | Timer | — | `CollectorForwardingService` | Collector forwarding round-trip latency |
| `tiberbu.cce.emitter.collector.retries` | Counter | — | `CollectorForwardingService` | Retry attempts exhausted |
| `tiberbu.cce.emitter.entries.filtered` | Counter | `source`, `facility`, `reason` | `FacilityFilter` | Events skipped by facility filter (not forwarded). `reason` value: `NOT_IN_ALLOWLIST`. |

## 7. MDC Context Fields

`InboundEventService` populates SLF4J MDC per-event with `try/finally` to ensure cleanup. These fields are included in all log output during event processing.

| MDC Key | Source | Description |
|---------|--------|-------------|
| `correlationId` | Adaptor-generated | Trace correlation ID |
| `source` | Resolved source key | Source system identifier (e.g., `"tiberbu"`) |
| `eventType` | FHIR `resourceType` | CloudEvents `type` field |
| `subject` | Patient identifier | Identifies the patient for the event |
