# tiberbu-cce-emitter-adaptor

The TibERbu CCE emitter adaptor receives clinical events from TibERbu through the gateway, normalizes the incoming FHIR Bundle payload, applies facility filtering, and forwards each processable entry to the CCE Collector service as a CloudEvent.

## Scope

- Inbound clinical events originate from TibERbu and are posted through the gateway to the adaptor.
- The adaptor validates the payload, extracts a patient/facility context, and enforces configured facility allowlists.
- Processable events are transformed into CloudEvents and forwarded to the Collector service.
- Worked request/response examples, including the Bundle-based consent payloads that need careful handling, are inlined in [docs/api-reference.md](docs/api-reference.md).

## Architecture decisions

- **Single source.** This adaptor serves exactly one source system, fixed by `cce.emitter.source` (`tiberbu`). There is no per-request source header and no source-level routing or filtering — every request that reaches `/inbound` is processed the same way, regardless of what its `meta.source` field claims.
- **Stateless.** No database, no message broker, no in-memory queue. Every request is handled and forwarded (or acknowledged) within the same HTTP call; nothing here survives a restart, and nothing needs to.
- **Bundle-first.** The inbound contract is always a `meta` + `resource` envelope wrapping a FHIR `Bundle`; entries live at `resource.entry[]`. Any entry whose `resourceType` is `Patient` is skipped — every other entry, regardless of FHIR resource type, is treated as an independent event payload and forwarded on its own.
- **Ignored vs. skipped — two different `200 OK`s.** `status: "ignored"` means the payload produced zero candidate entries at all (not JSON, not a Bundle, empty `entry[]`, or every entry a confirmed `Patient`) — there was nothing to evaluate. `status: "skipped"` means at least one candidate entry existed but was denied by the facility allowlist before ever reaching the Collector. Neither is an error; callers should not retry either one. See [docs/api-reference.md §4](docs/api-reference.md#4-error-responses) for the full outcome matrix, including the multi-entry case where one entry forwards while a sibling is skipped or fails.

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21 LTS (Temurin recommended) |
| Docker + Docker Compose | 24.x+ / 2.x plugin |
| Git | 2.x+ |

Gradle itself is not required — use the wrapper (`./gradlew`). See [docs/developer-setup.md §1](docs/developer-setup.md) for the full toolchain list and IDE setup.

## Build

```bash
./gradlew clean build
```

## Run locally

The fastest path is the bundled Docker Compose stack — it starts the adaptor plus a WireMock stub standing in for the CCE Collector, so there's nothing external to wire up:

```bash
docker compose up -d
```

This publishes the adaptor on `http://localhost:8081` and the Collector stub on `http://localhost:5055`. `docker compose logs -f` follows both; `docker compose down` tears the stack down.

To run the adaptor against a real Collector instead, set `CCE_COLLECTOR_URL` (and, if it requires auth, `CCE_COLLECTOR_AUTH_TOKEN` or the `KEYCLOAK_*` OAuth2 properties) and run with the `dev` or `prod` Spring profile — see [Configuration reference](#configuration-reference) below, or [docs/developer-setup.md §8](docs/developer-setup.md) for every run option (IDE, Gradle CLI, standalone container).

## Try it

With the Compose stack running:

```bash
curl -X POST http://localhost:8081/inbound \
  -H "Content-Type: application/json" \
  -d '{
    "meta": {"bundleId": "VCR-20260901-57098420", "traceId": "ef1cb56375"},
    "resource": {
      "resourceType": "Bundle", "type": "transaction",
      "entry": [
        {"request": {"method": "PUT", "url": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"},
         "resource": {"resourceType": "Patient", "id": "KE-SHRP-170CDF0A-1363-4972-B36A", "gender": "male"}},
        {"request": {"method": "PUT", "url": "Consent/VCR-20260901-57098420"},
         "resource": {"resourceType": "Consent", "id": "VCR-20260901-57098420", "status": "active",
           "patient": {"reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"}}}
      ]
    }
  }'
```

Expected — `202 Accepted`, one event (the `Patient` entry is skipped, the `Consent` entry forwards):

```json
{"status":"processed","eventsForwarded":1,"events":[{"entryIndex":1,"eventId":"11c1fb08-c324-5f5e-9076-9f61e16d38d3","type":"Consent","subject":"KE-SHRP-170CDF0A-1363-4972-B36A","outcome":"forwarded","collectorStatus":"accepted"}]}
```

More worked examples — multi-entry bundles, every ignored/skipped/failure scenario — are in [docs/api-reference.md](docs/api-reference.md).

## Configuration reference

The full property list, defaults, and env-var names are in [docs/data-dictionary.md §3](docs/data-dictionary.md); the most commonly set ones:

| Env var | Property | Default | Description |
|---------|----------|---------|-------------|
| `CCE_COLLECTOR_URL` | `cce.collector.url` | *(required in prod)* | Collector base URL |
| `CCE_COLLECTOR_EVENTS_PATH` | `cce.collector.events-path` | `/v1/events` | Collector events endpoint path |
| `CCE_COLLECTOR_TIMEOUT` | `cce.collector.timeout` | `5000` | HTTP connect + read timeout (ms) |
| `CCE_COLLECTOR_RETRY_MAX_ATTEMPTS` | `cce.collector.retry.max-attempts` | `3` | Retry attempts for Collector 5xx/timeout |
| `CCE_COLLECTOR_RETRY_BACKOFF_MS` | `cce.collector.retry.backoff-ms` | `1000` | Initial retry backoff, doubling each attempt |
| `CCE_COLLECTOR_AUTH_TOKEN` | `cce.collector.auth.token` | — | Static Bearer token — fallback when OAuth2 isn't configured |
| `KEYCLOAK_HOST` / `KEYCLOAK_REALM` / `KEYCLOAK_CLIENT_ID` / `KEYCLOAK_CLIENT_SECRET` | `cce.collector.auth.keycloak-*` | `KEYCLOAK_REALM` defaults to `cce` | OAuth2 `client_credentials` — used only when all four are set |
| `EMITTER_SOURCE` | `cce.emitter.source` | `tiberbu` | The CloudEvents `source` attribute stamped on every event |
| `FACILITY_FILTER_IDS` | `cce.emitter.facility-filter.ids` | *(empty — filter inactive)* | Comma-separated facility-ID allowlist |

> `EMITTER_SOURCE`, not `CCE_EMITTER_SOURCE` — the one property here that doesn't follow the `CCE_`-prefixed pattern of its neighbors.

## Metrics

Prometheus metrics are exposed at `/actuator/prometheus`, tagged `application=tiberbu-cce-emitter-adaptor`:

```bash
curl -s http://localhost:8081/actuator/prometheus | grep tiberbu_cce_emitter
```

`events.received` counts inbound *requests*; `entries.received`, `entries.forwarded`, `entries.duplicate`, `entries.rejected`, `entries.failed`, and `entries.filtered` each count individual *bundle entries* — a multi-entry bundle routinely makes the entry-level counters run well above `events.received`. Full metric names, tags, alert rules, and Grafana panel recommendations are in [docs/monitoring-alerting.md](docs/monitoring-alerting.md).

## Documentation

- [docs/api-reference.md](docs/api-reference.md) — HTTP contract, request/response behavior, and handling notes
- [docs/architecture-overview.md](docs/architecture-overview.md) — system context, integration flow, and responsibilities
- [docs/flow-diagrams.md](docs/flow-diagrams.md) — request flow and retry logic
- [docs/data-dictionary.md](docs/data-dictionary.md) — CloudEvent and configuration data model
- [docs/developer-setup.md](docs/developer-setup.md) — local development, project structure, and testing
- [docs/monitoring-alerting.md](docs/monitoring-alerting.md) — operational monitoring and alerting guidance
