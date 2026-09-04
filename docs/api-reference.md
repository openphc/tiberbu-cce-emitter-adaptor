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

**Body:** TibERbu payloads always arrive as a wrapper object with two top-level keys — `meta` (transport envelope) and `resource` (the FHIR Bundle itself, `resourceType: "Bundle"`). Bundle entries live at `resource.entry[]`. The first entry, `resource.entry[0]`, is always the patient resource and is ignored for downstream event processing. Every subsequent entry is treated as an independent event payload and forwarded individually after the adaptor resolves facility context and applies any configured filter rules.

**Bundle entry behavior:** the adaptor does not maintain a hardcoded allowlist of FHIR resource types. Any bundle entry after the first patient entry is treated as a candidate event payload and processed according to the normal event extraction and facility-filter rules. The patient entry itself is skipped.

**Transaction metadata:** real TibERbu bundles are `type: "transaction"`, so every entry carries a sibling `request` object (`{"method": "PUT", "url": "Consent/VCR-20260901-57098420"}`). The adaptor reads only `entry[].resource`; `entry[].request` is ignored.

**`meta.source`:** the envelope's `meta.source` (e.g. `shr-mediator`) is descriptive only. It is never used for routing or attribution — the CloudEvents `source` attribute always comes from `cce.emitter.source`.

> In other words, the current contract is: outer envelope with `meta` + `resource` (the Bundle itself); entries at `resource.entry[]`; `entry[0]` = patient metadata, ignore it; all later entries = event payloads to process, regardless of their specific FHIR resource type.

### Response Format

**Status:** `202 Accepted` when at least one event was forwarded, `200 OK` when the payload produced no events.

**Content-Type:** `application/json`

**Body (202 — events forwarded):**

```json
{
  "status": "processed",
  "eventsForwarded": 1,
  "events": [
    {
      "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "type": "Consent",
      "subject": "KE-SHRP-170CDF0A-1363-4972-B36A",
      "collectorStatus": "accepted"
    }
  ]
}
```

`collectorStatus` is `accepted` normally, or `duplicate` when the Collector reports the event was already ingested.

**Body (200 — nothing forwarded):**

```json
{
  "status": "ignored",
  "message": "Non-processable payload"
}
```

There is no source-level matching step on this adaptor — the source is fixed by `cce.emitter.source`. `ignored` means only that the request produced zero event payloads; see [§4.2](#42-non-processable-payload-200--silently-ignored) for the exact scenarios.

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

`entry[0]` (the `Patient`) is skipped, so this bundle produces exactly one CloudEvent — from `entry[1]`, the `Consent`.

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
      "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "type": "Consent",
      "subject": "KE-SHRP-170CDF0A-1363-4972-B36A",
      "collectorStatus": "accepted"
    }
  ]
}
```

### 3.2 Patient entry is ignored; only later bundle entries are forwarded

The first bundle entry is always the patient record and is intentionally skipped. TibERbu places patient metadata at `entry[0]`; the meaningful event payloads start at `entry[1]`.

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
| 2 | `resource.entry[0]` is **always** the patient resource, and is ignored |
| 3 | `resource.entry[1..n]` are event payloads, each forwarded to the Collector as an individual CloudEvent |

Rule 3 has no resource-type allowlist: whatever FHIR resource sits at `entry[1..n]` — `Consent`, `Observation`, `Encounter`, `ServiceRequest` — is processed the same way.

> **Not the contract:** a bare top-level Bundle (no `meta` / `resource` wrapper) is not what TibERbu sends. Such a body has no `resource.entry[]` and falls into scenario 2 of [§4.2](#42-non-processable-payload-200--silently-ignored) — `200 ignored`.

---

## 4. Error Responses

Error responses are plain JSON, produced by `GlobalExceptionHandler`. Sections 4.1 and 4.2 are **not** errors — they are normal `200 OK` outcomes returned directly by `InboundEventService`, documented here so callers can tell them apart from failures. Neither should be retried.

### 4.1 Facility Filter Skipped (200)

When `FACILITY_FILTER_IDS` is configured (non-empty) and the event's resolved facility ID is not in the allowlist, the adaptor returns `200 OK` with `status: "skipped"` — the event is **not** forwarded to the Collector. A skip is a normal outcome, not an error, so callers should not retry. Events with no resolvable facility ID (e.g. `Patient`, `Observation`) always pass through unconditionally.

```json
{
  "status": "skipped",
  "message": "Event skipped by facility filter: facilityId='9999' source='tiberbu'"
}
```

Events with no facility ID (e.g. `Patient`, `RelatedPerson`) are always forwarded and never reach the filter.

> **Note:** Configure `ids` with bare ID values only (e.g. `0030`, `1302`). `FacilityIdExtractor` strips any `ResourceType/` prefix generically during extraction — both `Location/1302` and `Organization/1302` resolve to `1302` before reaching the filter.

### 4.2 Non-processable Payload (200 — Silently Ignored)

This adaptor serves a single source system and applies **no source-level filter**: every request that reaches `/inbound` is accepted for processing. `ignored` therefore means one thing only — *the payload yielded no event payloads to forward*. That happens in these scenarios:

| # | Scenario | Example |
|---|----------|---------|
| 1 | Body is not JSON, or is not the TibERbu envelope | empty body, plain text, form-encoded data |
| 2 | `resource` is absent, or is not a FHIR `Bundle` | `{"meta": {...}}` with no `resource` |
| 3 | `resource.entry[]` is absent or empty | `{"meta": {...}, "resource": {"resourceType": "Bundle", "entry": []}}` |
| 4 | The bundle carries **only** the patient entry | `entry[0]` is `Patient` and there is nothing after it |
| 5 | Every entry after `entry[0]` is missing its `resource` object | entries that carry only `request` |

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

`entry[0]` is skipped, nothing follows it, so no CloudEvent is produced and the adaptor answers `200 ignored`. Note that an empty `meta` block has no bearing on the outcome — `meta` is never read for processing decisions.

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

### 4.4 Internal Server Error (500)

Caught by the global catch-all exception handler for any unexpected errors not covered by specific handlers.

```json
{
  "error": {
    "code": "INTERNAL_ERROR",
    "message": "Unexpected error details"
  },
  "timestamp": "2026-02-25T08:00:05Z"
}
```

### 4.5 Collector Forwarding Failure (502)

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
# HELP tiberbu_cce_emitter_events_received_total Total inbound events received
# TYPE tiberbu_cce_emitter_events_received_total counter
tiberbu_cce_emitter_events_received_total{source="tiberbu",path="/inbound"} 42.0

# HELP tiberbu_cce_emitter_events_forwarded_total Events forwarded to Collector
# TYPE tiberbu_cce_emitter_events_forwarded_total counter
tiberbu_cce_emitter_events_forwarded_total{source="tiberbu"} 40.0

# HELP tiberbu_cce_emitter_events_duplicate_total Duplicate events
# TYPE tiberbu_cce_emitter_events_duplicate_total counter
tiberbu_cce_emitter_events_duplicate_total 2.0

# HELP tiberbu_cce_emitter_collector_latency_seconds Collector forwarding latency
# TYPE tiberbu_cce_emitter_collector_latency_seconds summary
tiberbu_cce_emitter_collector_latency_seconds_count 42.0
tiberbu_cce_emitter_collector_latency_seconds_sum 8.456

# HELP tiberbu_cce_emitter_events_filtered_total Events denied by facility filter
# TYPE tiberbu_cce_emitter_events_filtered_total counter
tiberbu_cce_emitter_events_filtered_total{source="tiberbu",facility="9999",reason="NOT_IN_ALLOWLIST"} 3.0
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
