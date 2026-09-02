# tiberbu-cce-emitter-adaptor

This repository documents the TibERbu CCE emitter adaptor used to receive clinical events from TibERbu through the gateway, normalize the incoming FHIR payload, apply facility filtering, and forward relevant events to the CCE collector service.

## Scope

- Inbound clinical events originate from TibERbu and are posted through the gateway to the adaptor.
- The adaptor validates the payload, extracts a patient/facility context, and enforces configured facility allowlists.
- Processable events are transformed into CloudEvents and forwarded to the collector service.
- Worked request/response examples, including the Bundle-based consent payloads that need careful handling, are inlined in [docs/api-reference.md](docs/api-reference.md).

## Documentation

- [docs/api-reference.md](docs/api-reference.md) — HTTP contract, request/response behavior, and handling notes
- [docs/architecture-overview.md](docs/architecture-overview.md) — system context, integration flow, and responsibilities
- [docs/flow-diagrams.md](docs/flow-diagrams.md) — request flow and retry logic
- [docs/data-dictionary.md](docs/data-dictionary.md) — CloudEvent and configuration data model
- [docs/developer-setup.md](docs/developer-setup.md) — local development and runtime configuration
- [docs/monitoring-alerting.md](docs/monitoring-alerting.md) — operational monitoring and alerting guidance

## Source payload note

The source payload contract for this adaptor is a Bundle-based event message. The body is a wrapper object with `meta` (transport envelope) and `resource` (the FHIR Bundle itself); entries live at `resource.entry[]`. `entry[0]` is always the patient record and is ignored, and every entry from `entry[1]` onward is treated as an individual event payload that is forwarded downstream after filtering and normalization. A payload that yields no such entries is acknowledged with `200 OK` and `status: "ignored"` — there is no source-level filter.