# tibERbu CCE Emitter Adaptor — API Reference

## 1. Overview

| Property | Value |
|----------|-------|
| **Ingress path** | TibERbu clinical events arrive through the gateway and are forwarded to the adaptor service |
| **Base URL** | `http://<host>:8080` |
| **Protocol** | HTTP (HTTPS with Spring Boot `server.ssl.*`) |
| **Content Type** | `application/json` |
| **Response Format** | `application/json` |

> The source system is TibERbu, and the inbound call is authenticated and routed by the CCE Gateway before it reaches the adaptor. The adaptor is the normalization and filtering layer in front of the CCE Collector service, which it calls directly from inside the CCE ecosystem.

## 2. Inbound Event Endpoint

The adaptor exposes a single inbound endpoint for TibERbu payloads that are routed through the gateway.

### 2.1 POST /inbound

Generic inbound endpoint. Only `POST` is mapped — any other method returns `405 Method Not Allowed`.

**Source attribution:** this adaptor serves a single source system, named by
`cce.emitter.source`. Every event is stamped with that value as the CloudEvents
`source` attribute — no per-request routing headers are read.

```
POST /inbound
```

---

### Request Format

**Headers:**

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes | `application/json` |

> **Note:** Facility ID is resolved from the FHIR resource (`FacilityIdExtractor`). The CloudEvent
> `correlationid` is adaptor-generated. The source is fixed by `cce.emitter.source`.

**Body:** TibERbu payloads always arrive as a wrapper object with two top-level keys — `meta` (transport envelope) and `resource` (the FHIR Bundle itself, `resourceType: "Bundle"`). Bundle entries live at `resource.entry[]`. Any entry whose `resourceType` is `Patient` is ignored for downstream event processing. Every other entry is treated as an independent event payload and forwarded individually after the adaptor resolves facility context and applies any configured filter rules.

**Bundle entry behavior:** the adaptor does not maintain a hardcoded allowlist of FHIR resource types. Any bundle entry that is not a confirmed `Patient` is treated as a candidate event payload and processed according to the normal event extraction and facility-filter rules. Only a confirmed Patient entry is skipped, no matter where in the bundle it appears.

**Transaction metadata:** real TibERbu bundles are `type: "transaction"`, so every entry carries a sibling `request` object (`{"method": "PUT", "url": "Consent/VCR-20260901-57098420"}`). The adaptor reads only `entry[].resource`; `entry[].request` is ignored.

**`meta.source`:** the envelope's `meta.source` (e.g. `shr-mediator`) is descriptive only. It is never used for routing or attribution — the CloudEvents `source` attribute always comes from `cce.emitter.source`.

> In other words, the current contract is: outer envelope with `meta` + `resource` (the Bundle itself); entries at `resource.entry[]`; any entry whose `resourceType` is `Patient` is ignored; every other entry = an event payload to process, regardless of its specific FHIR resource type.

### Response Format

**Status:** `202 Accepted` when at least one entry reached the Collector, `200 OK` when none did — either because the payload produced no candidate entries at all (`status: "ignored"`), or because at least one entry was cleanly facility-filtered and none reached the Collector (`status: "skipped"`).

**Content-Type:** `application/json`

Every candidate bundle entry is processed independently — one entry's facility-filter denial or failure never prevents a sibling entry in the same bundle from being forwarded. Forwarding is an irreversible side effect, so as long as **at least one** entry reached the Collector or was cleanly filtered, the whole response is a success (`202` or `200 skipped`) — the body lists every entry's own outcome, including any that failed, rather than the top-level status hiding what actually happened. Only when **every** candidate entry fails outright does the request answer with an error status instead (see [§4](#4-error-responses)).

**Body (202 — at least one entry forwarded):**

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

`collectorStatus` is `accepted` normally, or `duplicate` when the Collector reports the event was already ingested — either way `outcome` is `"forwarded"`. `entryIndex` is the entry's position in `resource.entry[]`, included so a caller can tell entries apart even when `type`/`subject` alone don't disambiguate (e.g. two failed entries of the same resource type).

**Body (202 — one entry forwarded, one entry in the same bundle failed):**

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

The overall status is still `202` — entry 1's successful forward is real and irreversible, so the request as a whole is reported as a success even though entry 2 failed. `eventId`, `subject`, and `collectorStatus` are all omitted (not `null`) for a `failed` or `skipped` entry, since they were never resolved.

**Body (200 — nothing forwarded, nothing failed):**

```json
{
  "status": "ignored",
  "message": "Non-processable payload"
}
```

There is no source-level matching step on this adaptor — the source is fixed by `cce.emitter.source`. `ignored` means only that the request produced zero candidate entries; see [§4.2](#42-non-processable-payload-200--silently-ignored) for the exact scenarios. See [§4.1](#41-facility-filter-skipped-200) for `status: "skipped"`, the other `200` outcome.

---

## 3. Request & Response Examples

### 3.1 TibERbu bundle with patient + clinical event entries

**Request:**

```bash
curl -X POST http://localhost:8080/inbound \
  -H "Content-Type: application/json" \
  -d '{
    "meta": {
      "resourceType": "Bundle",
      "resourceId": "VCR-20260901-57098420",
      "event": "upserted",
      "source": "shr-mediator",
      "timestamp": "2026-09-01 11:55:40.281292",
      "traceId": "ef1cb56375",
      "bundleId": "VCR-20260901-57098420",
      "agentId": "DHABP00018",
      "version": "v1"
    },
    "resource": {
      "resourceType": "Bundle",
      "type": "transaction",
      "entry": [
        {
          "request": {
            "method": "PUT",
            "url": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"
          },
          "resource": {
            "resourceType": "Patient",
            "id": "KE-SHRP-170CDF0A-1363-4972-B36A",
            "gender": "male"
          }
        },
        {
          "request": {
            "method": "PUT",
            "url": "Consent/VCR-20260901-57098420"
          },
          "resource": {
            "resourceType": "Consent",
            "id": "VCR-20260901-57098420",
            "status": "active",
            "patient": {
              "reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"
            }
          }
        }
      ]
    }
  }'
```

**Generated CloudEvent (sent to Collector):**

`entry[0]`'s `resourceType` is `Patient`, so it is skipped; this bundle produces exactly one CloudEvent — from `entry[1]`, the `Consent`.

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
    "patient": {
      "reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"
    }
  }
}
```

How each attribute is derived:

| Attribute | Value | Source |
|-----------|-------|--------|
| `source` | `tiberbu` | `cce.emitter.source` — never `meta.source` |
| `type` | `Consent` | `entry[1].resource.resourceType`, verbatim |
| `subject` | `KE-SHRP-170CDF0A-1363-4972-B36A` | `Consent.patient.reference`, `Patient/` prefix stripped |
| `time` | `2026-09-01T11:55:42.118Z` | Adaptor processing time (UTC) — not `meta.timestamp` |
| `facilityid` | `FAC-0001` | Extracted from the FHIR resource (`FacilityIdExtractor`) |
| `sourceeventid` | *(not populated)* | Not populated |
| `correlationid` | `7f3c9b12-…` | Adaptor-generated |
| `data` | the `Consent` resource | `entry[1].resource`, verbatim |

**Adaptor response:** `202 Accepted`

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

### 3.2 Patient entries are ignored; other bundle entries are forwarded

Any bundle entry whose `resourceType` is `Patient` is intentionally skipped; the meaningful event payloads are every other entry.

**Example packet layout:**

```json
{
  "meta": {
    "resourceType": "Bundle",
    "source": "shr-mediator"
  },
  "resource": {
    "resourceType": "Bundle",
    "type": "transaction",
    "entry": [
      {
        "request": { "method": "PUT", "url": "Patient/patient-001" },
        "resource": { "resourceType": "Patient", "id": "patient-001" }
      },
      {
        "request": { "method": "PUT", "url": "Consent/consent-001" },
        "resource": { "resourceType": "Consent", "status": "active" }
      },
      {
        "request": { "method": "PUT", "url": "Observation/obs-001" },
        "resource": { "resourceType": "Observation", "status": "final" }
      }
    ]
  }
}
```

The adaptor skips the patient entry and forwards the Consent and Observation entries as individual events. The per-entry `request` block is transaction metadata and is not read.

### 3.3 TibERbu bundle processing contract

The contract is explicit and has three rules:

| # | Rule |
|---|------|
| 1 | `resource` is **always** a FHIR `Bundle` — no other top-level resource type is expected |
| 2 | Any entry whose `resourceType` is `Patient` is ignored |
| 3 | Every other entry is an event payload, each forwarded to the Collector as an individual CloudEvent |

Rule 3 has no resource-type allowlist: whatever FHIR resource sits at these entries — `Consent`, `Observation`, `Encounter`, `ServiceRequest` — is processed the same way.

> **Not the contract:** a bare top-level Bundle (no `meta` / `resource` wrapper) is not what TibERbu sends. Such a body has no `resource.entry[]` and falls into scenario 2 of [§4.2](#42-non-processable-payload-200--silently-ignored) — `200 ignored`.

---

## 4. Error Responses

Error responses are plain JSON, produced by `GlobalExceptionHandler`. Sections 4.1 and 4.2 are **not** errors — they are normal `200 OK` outcomes returned directly by `InboundEventService`, documented here so callers can tell them apart from failures. Neither should be retried.

The sections below (4.3 onward) only apply to a bundle when **every** candidate entry fails — as long as at least one entry reaches the Collector or is cleanly filtered, the request is `202`/`200 skipped` instead (see [§2](#response-format)), with the failed entry's own reason still visible in that response's `events[]`. When every entry does fail, the first one's failure is what determines the error status/code below — the other entries' failures aren't individually surfaced in the error body.

### 4.1 Facility Filter Skipped (200)

When `FACILITY_FILTER_IDS` is configured (non-empty) and an entry's resolved facility ID is not in the allowlist, that entry is denied by the facility filter and never forwarded to the Collector. A denial is a normal outcome, not an error, so callers should not retry. Entries with no resolvable facility ID (e.g. `Patient`, `Observation`) always pass through unconditionally, since there's nothing for the filter to check.

The whole request answers `status: "skipped"` only when **no** entry in the bundle reached the Collector, but **at least one** was cleanly filtered:

```json
{
  "status": "skipped",
  "eventsForwarded": 0,
  "events": [
    {
      "entryIndex": 1,
      "type": "Consent",
      "subject": "KE-SHRP-170CDF0A-1363-4972-B36A",
      "outcome": "skipped",
      "reason": "Event skipped by facility filter: facilityId='9999' source='tiberbu'"
    }
  ]
}
```

If a bundle has multiple entries and at least one of them *does* reach the Collector, the request is `202` instead — a filtered sibling entry still shows up in `events` with `outcome: "skipped"`, it just doesn't change the overall status. See [§2 Response Format](#response-format) for that mixed case.

> **Note:** Configure `ids` with bare ID values only (e.g. `0030`, `1302`). `FacilityIdExtractor` strips a `ResourceType/` prefix during extraction — `Organization/1302` resolves to `1302` before reaching the filter.

### 4.2 Non-processable Payload (200 — Silently Ignored)

This adaptor serves a single source system and applies **no source-level filter**: every request that reaches `/inbound` is accepted for processing. `ignored` therefore means one thing only — *the payload yielded no event payloads to forward*. That happens in these scenarios:

| # | Scenario | Example |
|---|----------|---------|
| 1 | Body is not JSON, or is not the TibERbu envelope | empty body, plain text, form-encoded data |
| 2 | `resource` is absent, or is not a FHIR `Bundle` | `{"meta": {...}}` with no `resource` |
| 3 | `resource.entry[]` is absent or empty | `{"meta": {...}, "resource": {"resourceType": "Bundle", "entry": []}}` |
| 4 | The bundle carries **only** confirmed Patient entries | every entry's `resourceType` is `Patient`, so nothing remains to forward |
| 5 | Every remaining entry is missing its `resource` object | entries that carry only `request` |

Scenario 4 is the one seen most often in practice — a well-formed envelope whose bundle carries only the patient entry:

```json
{
  "meta": {
    "resourceType": "",
    "resourceId": "",
    "event": "",
    "source": "",
    "timestamp": "",
    "traceId": "",
    "bundleId": "",
    "agentId": "",
    "version": ""
  },
  "resource": {
    "resourceType": "Bundle",
    "type": "transaction",
    "entry": [
      {
        "request": {
          "method": "PUT",
          "url": "Patient/KE-SHRP-7E93454F-6D34-47C5-A6C2"
        },
        "resource": {
          "resourceType": "Patient",
          "id": "KE-SHRP-7E93454F-6D34-47C5-A6C2",
          "gender": "male",
          "birthDate": "1982-08-17",
          "deceasedBoolean": false
        }
      }
    ]
  }
}
```

`entry[0]`'s `resourceType` is `Patient`, so it is skipped; nothing follows it, so no CloudEvent is produced and the adaptor answers `200 ignored`. Note that an empty `meta` block has no bearing on the outcome — `meta` is never read for processing decisions.

```json
{
  "status": "ignored",
  "message": "Non-processable payload"
}
```

No error is raised, nothing is forwarded to the Collector, and the caller should not retry.

### 4.3 Patient ID Not Found (400)

```json
{
  "error": {
    "code": "PATIENT_ID_NOT_FOUND",
    "message": "No patient reference found in Observation resource"
  },
  "timestamp": "2026-02-25T08:00:05Z"
}
```

### 4.4 FHIR Mapping Error (422)

The entry's `resource` object could not be parsed as a FHIR R4 resource at all — malformed JSON reaching HAPI FHIR, not a missing field HAPI can tolerate.

```json
{
  "error": {
    "code": "FHIR_MAPPING_ERROR",
    "message": "Failed to parse FHIR JSON for entry: unexpected token at line 1"
  },
  "timestamp": "2026-02-25T08:00:05Z"
}
```

### 4.5 Collector Client Error (4xx)

The Collector itself rejected the forwarded CloudEvent — never retried, since retrying an event the Collector already rejected as invalid would fail identically every time. The HTTP status returned here is always the Collector's own status code, not a fixed one.

```json
{
  "error": {
    "code": "COLLECTOR_CLIENT_ERROR",
    "message": "Collector returned 400: {\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"...\"}}"
  },
  "timestamp": "2026-02-25T08:00:05Z"
}
```

### 4.6 Method Not Allowed (405)

`/inbound` only maps `POST` — any other method (`GET`, `PUT`, `DELETE`, ...) is rejected explicitly as `405`, never as `500`.

```json
{
  "error": {
    "code": "METHOD_NOT_ALLOWED",
    "message": "Request method 'GET' is not supported"
  },
  "timestamp": "2026-02-25T08:00:05Z"
}
```

### 4.7 Internal Server Error (500)

Caught by the global catch-all exception handler for any unexpected error not covered by a more specific handler above. The message is always this fixed, generic text — never the real exception's own message or class name, so nothing internal ever leaks into the response.

```json
{
  "error": {
    "code": "INTERNAL_ERROR",
    "message": "An unexpected error occurred"
  },
  "timestamp": "2026-02-25T08:00:05Z"
}
```

### 4.8 Collector Forwarding Failure (502)

Every retry attempt against the Collector was exhausted (5xx responses, or the Collector being unreachable — timeout/connection refused).

```json
{
  "error": {
    "code": "COLLECTOR_FORWARDING_ERROR",
    "message": "All retries exhausted for event a1b2c3d4"
  },
  "timestamp": "2026-02-25T08:00:10Z"
}
```

---

## 5. Actuator Endpoints

Spring Boot Actuator endpoints exposed for operations.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/actuator/health` | GET | Overall health status |
| `/actuator/health/liveness` | GET | Kubernetes liveness probe |
| `/actuator/health/readiness` | GET | Kubernetes readiness probe |
| `/actuator/info` | GET | Application info (name, version) |
| `/actuator/prometheus` | GET | Prometheus metrics scrape endpoint |
| `/actuator/metrics` | GET | All available metrics list |
| `/actuator/metrics/{metricName}` | GET | Specific metric detail |

### Health Response

```json
{
  "status": "UP",
  "groups": ["liveness", "readiness"]
}
```

### Prometheus Metrics (excerpt)

```
# HELP tiberbu_cce_emitter_events_received_total Inbound requests received
# TYPE tiberbu_cce_emitter_events_received_total counter
tiberbu_cce_emitter_events_received_total{source="tiberbu",path="/inbound"} 42.0

# HELP tiberbu_cce_emitter_entries_received_total Candidate bundle entries extracted, before any outcome is decided
# TYPE tiberbu_cce_emitter_entries_received_total counter
tiberbu_cce_emitter_entries_received_total{source="tiberbu"} 45.0

# HELP tiberbu_cce_emitter_entries_forwarded_total Events forwarded to Collector
# TYPE tiberbu_cce_emitter_entries_forwarded_total counter
tiberbu_cce_emitter_entries_forwarded_total{source="tiberbu"} 40.0

# HELP tiberbu_cce_emitter_entries_duplicate_total Duplicate events
# TYPE tiberbu_cce_emitter_entries_duplicate_total counter
tiberbu_cce_emitter_entries_duplicate_total 2.0

# HELP tiberbu_cce_emitter_entries_failed_total Entries that failed adaptation before ever reaching the Collector
# TYPE tiberbu_cce_emitter_entries_failed_total counter
tiberbu_cce_emitter_entries_failed_total{source="tiberbu",reason="PatientIdNotFoundException"} 2.0

# HELP tiberbu_cce_emitter_collector_latency_seconds Collector forwarding latency
# TYPE tiberbu_cce_emitter_collector_latency_seconds summary
tiberbu_cce_emitter_collector_latency_seconds_count 42.0
tiberbu_cce_emitter_collector_latency_seconds_sum 8.456

# HELP tiberbu_cce_emitter_entries_filtered_total Events denied by facility filter
# TYPE tiberbu_cce_emitter_entries_filtered_total counter
tiberbu_cce_emitter_entries_filtered_total{source="tiberbu",facility="9999",reason="NOT_IN_ALLOWLIST"} 3.0
```

---

## 6. Downstream Call (Outbound)

### Forward to CCE Collector

```
POST <collector-url>/v1/events
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "specversion": "1.0",
  "id": "...",
  "source": "...",
  "type": "Consent",
  ...
}
```

> **Authentication:** resolved by `CollectorTokenService` and attached as `Authorization: Bearer <token>`.
> OAuth2 `client_credentials` via Keycloak when `cce.collector.auth.keycloak-host`/`realm`/`client-id`/`client-secret`
> are all set (tokens cached and refreshed automatically); otherwise the static token from
> `cce.collector.auth.token`; otherwise no `Authorization` header at all. See Architecture Overview §11.1.
> This is independent of any authentication on the inbound endpoint, which the gateway handles.

**Success:** `202 Accepted`
**Duplicate:** `200 OK`
**Validation Error:** `400 Bad Request` (missing `type`)
**Server Error:** `5xx` → retried with exponential backoff (max 3 attempts)
