# tibERbu CCE Emitter Adaptor — Monitoring & Alerting Guide

Prometheus alert rules, Grafana dashboard recommendations, and log-based monitoring for the tibERbu CCE Emitter Adaptor in production.

---

## 1. Metrics Inventory

All custom metrics are exposed at `GET /actuator/prometheus` and use the `cce_emitter_` prefix (Prometheus naming convention: dots → underscores).

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `tiberbu_cce_emitter_events_received_total` | Counter | `source`, `path` | Total inbound event entries processed by the adaptor. The first patient bundle entry is ignored; all subsequent bundle entries count as received events. |
| `tiberbu_cce_emitter_events_forwarded_total` | Counter | `source` | Individual event entries successfully forwarded to Collector |
| `tiberbu_cce_emitter_events_duplicate_total` | Counter | — | Duplicate individual events (Collector returned 200) |
| `tiberbu_cce_emitter_events_rejected_total` | Counter | — | Individual events rejected by Collector (4xx) |
| `tiberbu_cce_emitter_collector_latency_seconds` | Timer | — | Collector forwarding round-trip latency for each individual event payload |
| `tiberbu_cce_emitter_collector_retries_total` | Counter | — | Retry attempts exhausted (all retries failed) |
| `tiberbu_cce_emitter_events_filtered_total` | Counter | `source`, `facility`, `reason` | Events skipped by facility filter (not forwarded; response is 200 OK). `reason`: `NOT_IN_ALLOWLIST`. Events with no facility ID pass through and are not counted. |

### JVM & Spring Boot Metrics (auto-registered)

| Metric | Description |
|--------|-------------|
| `jvm_memory_used_bytes` | JVM heap/non-heap memory |
| `jvm_threads_live_threads` | Active thread count |
| `http_server_requests_seconds` | Inbound HTTP request latency (by status, method, URI) |
| `process_cpu_usage` | Process CPU utilization |
| `system_cpu_usage` | System CPU utilization |

## 2. Prometheus Scrape Configuration

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'tiberbu-cce-emitter-adaptor'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['<emitter-adaptor-host>:8080']
        labels:
          service: 'tiberbu-cce-emitter-adaptor'
          environment: 'production'
```

## 3. Alert Rules

### 3.1 Critical Alerts

```yaml
# prometheus-alerts.yml
groups:
  - name: tiberbu-cce-emitter-adaptor-critical
    rules:

      # Service is down — liveness probe failing
      - alert: TiberbuCceEmitterDown
        expr: up{job="tiberbu-cce-emitter-adaptor"} == 0
        for: 1m
        labels:
          severity: critical
          service: tiberbu-cce-emitter-adaptor
        annotations:
          summary: "tibERbu CCE Emitter Adaptor is down"
          description: "Prometheus cannot scrape the emitter adaptor for >1 minute. Check container health."
          runbook: "Check `docker ps`, restart container, check logs for startup errors."

      # All retries exhausted — Collector unreachable or failing
      - alert: TiberbuCceEmitterRetriesExhausted
        expr: rate(tiberbu_cce_emitter_collector_retries_total[5m]) > 0
        for: 2m
        labels:
          severity: critical
          service: tiberbu-cce-emitter-adaptor
        annotations:
          summary: "tibERbu CCE Emitter Adaptor retries exhausted"
          description: "Collector forwarding retries are being exhausted (rate > 0 for 2+ minutes). Events are being dropped."
          runbook: "Check CCE Collector health, network connectivity, and CCE_COLLECTOR_URL configuration."
```

### 3.2 Warning Alerts

```yaml
      # High rejection rate — Collector returning 4xx
      - alert: TiberbuCceEmitterHighRejections
        expr: rate(tiberbu_cce_emitter_events_rejected_total[5m]) / rate(tiberbu_cce_emitter_events_received_total[5m]) > 0.05
        for: 5m
        labels:
          severity: warning
          service: tiberbu-cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter >5% event rejection rate"
          description: "More than 5% of events are being rejected by the Collector (4xx) for >5 minutes."
          runbook: "Check Collector logs for validation errors. Likely a payload format issue."

      # High duplicate rate
      - alert: TiberbuCceEmitterHighDuplicates
        expr: rate(tiberbu_cce_emitter_events_duplicate_total[5m]) / rate(tiberbu_cce_emitter_events_received_total[5m]) > 0.20
        for: 10m
        labels:
          severity: warning
          service: tiberbu-cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter >20% duplicate event rate"
          description: "More than 20% of events are duplicates (Collector returned 200). May indicate a retry storm or repeated source submissions."
          runbook: "Check if source system is resending events. Check for network-level retries between the source system and the adaptor."

      # High latency — Collector response time degraded
      - alert: TiberbuCceEmitterHighLatency
        expr: histogram_quantile(0.95, rate(tiberbu_cce_emitter_collector_latency_seconds_bucket[5m])) > 2.0
        for: 5m
        labels:
          severity: warning
          service: tiberbu-cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter Collector latency p95 > 2s"
          description: "95th percentile Collector forwarding latency exceeds 2 seconds for >5 minutes."
          runbook: "Check Collector health, Kafka broker connectivity, network latency between adaptor and Collector."

      # High facility filter denial rate (may indicate misconfigured allowlist)
      - alert: TiberbuCceEmitterHighFilterDenialRate
        expr: rate(tiberbu_cce_emitter_events_filtered_total[5m]) / rate(tiberbu_cce_emitter_events_received_total[5m]) > 0.50
        for: 5m
        labels:
          severity: warning
          service: tiberbu-cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter facility filter denying >50% of events"
          description: "More than 50% of events are being skipped by the facility filter for >5 minutes. May indicate a misconfigured allowlist or missing facility IDs."
          runbook: "Check FACILITY_FILTER_IDS env var. Use topk Prometheus query to identify which facilities are being skipped. Verify tibERbu is sending the X-Facility-Id header."

      # No events received for extended period (during business hours)
      - alert: TiberbuCceEmitterNoEventsReceived
        expr: increase(tiberbu_cce_emitter_events_received_total[30m]) == 0
        for: 30m
        labels:
          severity: warning
          service: tiberbu-cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter no events received for 30 minutes"
          description: "No inbound events received for 30+ minutes. May indicate a source system outage or a networking issue on the path to /inbound."
          runbook: "Check source system status and network connectivity to the adaptor's /inbound endpoint."
```

### 3.3 Informational Alerts

```yaml
      # High memory usage
      - alert: TiberbuCceEmitterHighMemory
        expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85
        for: 10m
        labels:
          severity: info
          service: tiberbu-cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter heap usage > 85%"
          description: "JVM heap usage exceeds 85% for >10 minutes. Consider increasing container memory limits."
```

## 4. Grafana Dashboard

### 4.1 Recommended Dashboard Panels

#### Row 1: Overview

| Panel | Type | Query | Description |
|-------|------|-------|-------------|
| **Events Received Rate** | Stat | `rate(tiberbu_cce_emitter_events_received_total[5m])` | Current inbound event rate (events/sec) |
| **Events Forwarded Rate** | Stat | `rate(tiberbu_cce_emitter_events_forwarded_total[5m])` | Current forwarding rate |
| **Success Rate** | Gauge | `rate(tiberbu_cce_emitter_events_forwarded_total[5m]) / rate(tiberbu_cce_emitter_events_received_total[5m]) * 100` | % of events successfully forwarded |
| **Service Status** | Stat | `up{job="tiberbu-cce-emitter-adaptor"}` | 1 = UP, 0 = DOWN |

#### Row 2: Event Throughput (Time Series)

| Panel | Type | Queries |
|-------|------|---------|
| **Event Throughput** | Time series (stacked) | `rate(tiberbu_cce_emitter_events_forwarded_total[5m])` — Forwarded |
| | | `rate(tiberbu_cce_emitter_events_duplicate_total[5m])` — Duplicates |
| | | `rate(tiberbu_cce_emitter_events_rejected_total[5m])` — Rejected |
| | | `rate(tiberbu_cce_emitter_collector_retries_total[5m])` — Retries Exhausted |

#### Row 3: Latency

| Panel | Type | Query | Description |
|-------|------|-------|-------------|
| **Collector Latency p50** | Time series | `histogram_quantile(0.50, rate(tiberbu_cce_emitter_collector_latency_seconds_bucket[5m]))` | Median latency |
| **Collector Latency p95** | Time series | `histogram_quantile(0.95, rate(tiberbu_cce_emitter_collector_latency_seconds_bucket[5m]))` | 95th percentile |
| **Collector Latency p99** | Time series | `histogram_quantile(0.99, rate(tiberbu_cce_emitter_collector_latency_seconds_bucket[5m]))` | 99th percentile |

#### Row 4: JVM Health

| Panel | Type | Query |
|-------|------|-------|
| **Heap Usage** | Time series + threshold | `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}` |
| **CPU Usage** | Time series | `process_cpu_usage{job="tiberbu-cce-emitter-adaptor"}` |
| **Live Threads** | Time series | `jvm_threads_live_threads{job="tiberbu-cce-emitter-adaptor"}` |

#### Row 5: HTTP Server

| Panel | Type | Query |
|-------|------|-------|
| **HTTP Request Rate** | Time series by status | `rate(http_server_requests_seconds_count{uri="/inbound"}[5m])` |
| **HTTP Latency p95** | Time series | `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{uri="/inbound"}[5m]))` |
| **HTTP Error Rate** | Time series | `rate(http_server_requests_seconds_count{uri="/inbound",status=~"4..|5.."}[5m])` |

### 4.2 Dashboard Variables

| Variable | Label | Query |
|----------|-------|-------|
| `$source` | Source | `label_values(tiberbu_cce_emitter_events_received_total, source)` |
| `$instance` | Instance | `label_values(up{job="tiberbu-cce-emitter-adaptor"}, instance)` |

## 5. Log-Based Monitoring

### 5.1 Production Log Format

Production profile outputs structured JSON to stdout:

```json
{"timestamp":"2026-09-01T11:55:42.118Z","level":"INFO","logger":"org.openphc.tiberbu.cce.emitter.service.InboundEventService","thread":"http-nio-8080-exec-1","correlationId":"7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d","source":"tiberbu","eventType":"Consent","subject":"KE-SHRP-170CDF0A-1363-4972-B36A","message":"Event forwarded to Collector"}
```

### 5.2 Key Log Queries

For log aggregation tools (ELK, Loki, CloudWatch):

| Query Purpose | Filter |
|--------------|--------|
| All errors | `level: "ERROR"` |
| Collector failures | `message: *"retry"* OR message: *"exhausted"*` |
| Events for a patient | `subject: "KE-SHRP-170CDF0A-1363-4972-B36A"` |
| Events by source | `source: "tiberbu"` |
| Trace a request | `correlationId: "7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d"` |
| Rejected events | `message: *"rejected"* OR message: *"ClientException"*` |

### 5.3 MDC Fields for Filtering

Every log line during event processing includes these MDC fields:

| Field | Example | Use Case |
|-------|---------|----------|
| `correlationId` | `7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d` | Trace a single request across services |
| `source` | `tiberbu` | Filter by source system |
| `eventType` | `Consent` | Filter by FHIR resource type |
| `subject` | `KE-SHRP-170CDF0A-1363-4972-B36A` | Filter by patient UPID |

## 6. Health Check Monitoring

### Endpoints

| Endpoint | Expected | When to Alert |
|----------|----------|---------------|
| `GET /actuator/health/liveness` | `{"status":"UP"}` | Alert if returns non-200 for >30s |
| `GET /actuator/health/readiness` | `{"status":"UP"}` | Alert if returns non-200 for >1m |

### Docker HEALTHCHECK

The Dockerfile includes a built-in health check:

```
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1
```

Docker marks the container as `unhealthy` after 3 consecutive failures. Container orchestrators (Kubernetes, ECS) should be configured to restart unhealthy containers.

## 7. Recommended Thresholds Summary

| Metric | Warning | Critical |
|--------|---------|----------|
| Service down | — | >1 minute |
| Retries exhausted rate | — | Any sustained rate > 0 for 2 minutes |
| Rejection rate | >5% for 5 minutes | >20% for 5 minutes |
| Duplicate rate | >20% for 10 minutes | >50% for 10 minutes |
| Collector latency p95 | >2 seconds for 5 minutes | >5 seconds for 5 minutes |
| No events received | >30 minutes (business hours) | >60 minutes (business hours) |
| Heap usage | >85% for 10 minutes | >95% for 5 minutes |
