# tibERbu CCE Emitter Adaptor — Developer Setup Guide

## 1. Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| **Java** | 21 LTS (Eclipse Temurin recommended) | `java --version` → `21.x` |
| **Gradle** | 8.x (via wrapper) | `./gradlew --version` → `8.x` |
| **Docker** | 24.x+ | `docker --version` |
| **Docker Compose** | 2.x (plugin) | `docker compose version` |
| **Git** | 2.x+ | `git --version` |
| **IDE** | IntelliJ IDEA / VS Code with Java Extension Pack | — |

> **Note:** Gradle Wrapper (`gradlew`) is included in the project — no global Gradle install is required.

## 2. Clone & Build

```bash
# Clone the repository
git clone <repo-url>
cd cce-compliance-sub_system/emitter-adaptor

# Build (skipping tests for first-time setup)
./gradlew clean build -x test

# Build with tests
./gradlew clean build

# Run tests only
./gradlew test
```

## 3. Project Structure

```
emitter-adaptor/
├── build.gradle.kts              # Gradle build (Kotlin DSL)
├── settings.gradle.kts           # Project settings
├── gradlew                       # Gradle wrapper (Unix)
├── gradlew.bat                   # Gradle wrapper (Windows)
├── Dockerfile                    # Multi-stage build (JDK build → JRE runtime)
├── .dockerignore                 # Docker build exclusions
├── docker-compose.yml            # Local dev stack (WireMock Collector + adaptor)
├── .github/
│   └── workflows/
│       └── sonarqube.yml         # JaCoCo + SonarQube Cloud analysis
├── gradle/
│   └── wrapper/                  # Wrapper JAR + properties
├── wiremock/
│   ├── __files/                  # WireMock response body files (unused so far)
│   └── mappings/                 # WireMock stub mappings for Docker Compose
│       └── collector-events.json
├── src/
│   ├── main/
│   │   ├── java/org/openphc/tiberbu/cce/emitter/
│   │   │   ├── CceEmitterAdaptorApplication.java
│   │   │   ├── config/
│   │   │   ├── controller/
│   │   │   ├── adaptor/
│   │   │   ├── cloudevents/
│   │   │   ├── fhir/
│   │   │   ├── service/
│   │   │   ├── model/
│   │   │   └── exception/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       └── logback-spring.xml
│   └── test/
│       ├── java/org/openphc/tiberbu/cce/emitter/
│       │   ├── integration/            # Integration tests
│       │   └── ...                     # Unit test packages
│       └── resources/
│           ├── application-integration.yml
│           ├── fhir/                   # FHIR test fixtures
│           └── tiberbu/                # tibERbu test fixtures
├── docs/
└── .github/
    └── copilot-instructions.md
```

## 4. Gradle Build Configuration

### build.gradle.kts

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.openphc.tiberbu.cce"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

val hapiFhirVersion = "7.4.0"
val wiremockVersion = "3.9.2"

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Spring Retry — Collector forwarding backoff
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework:spring-aspects")

    // HAPI FHIR — R4 parsing of bundle entries
    implementation("ca.uhn.hapi.fhir:hapi-fhir-base:$hapiFhirVersion")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:$hapiFhirVersion")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.wiremock:wiremock-standalone:$wiremockVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName = "tiberbu-cce-emitter-adaptor"
}
```

### settings.gradle.kts

```kotlin
rootProject.name = "tiberbu-cce-emitter-adaptor"
```

## 5. Application Payload Contract

The TibERbu adaptor contract is bundle-based: every inbound request is expected to contain a FHIR `Bundle`, the first entry is the patient resource that should be ignored, and entries from the second position onward are forwarded as individual event payloads.

For local testing, create bundle fixtures in the following shape:

```json
{
  "resourceType": "Bundle",
  "type": "transaction",
  "entry": [
    { "resource": { "resourceType": "Patient", "id": "patient-001" } },
    { "resource": { "resourceType": "Consent", "status": "active" } },
    { "resource": { "resourceType": "Observation", "status": "final" } }
  ]
}
```

Only the Consent and Observation entries should be processed as events.

## 6. Application Configuration

### application.yml

```yaml
server:
  port: ${SERVER_PORT:8080}
  servlet:
    context-path: /

spring:
  application:
    name: tiberbu-cce-emitter-adaptor

# CCE Collector Configuration
cce:
  collector:
    url: http://localhost:5001
    events-path: /v1/events
    timeout: 5000
    retry:
      max-attempts: 3
      backoff-ms: 1000
    auth:
      token: local-dev-token
      keycloak-host:
      realm:
      client-id:
      client-secret:
  emitter:
    source: tiberbu
    patient-identifier-system: "http://openphc.org/identifier/upid"
    facility-filter:
      ids: ${FACILITY_FILTER_IDS:}  # Set via env var: FACILITY_FILTER_IDS=0234,0030 (comma-separated, empty = all pass)

# Actuator & Metrics
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true
    prometheus:
      enabled: true
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
  prometheus:
    metrics:
      export:
        enabled: true
  metrics:
    tags:
      application: tiberbu-cce-emitter-adaptor

# Logging
logging:
  level:
    org.openphc.tiberbu.cce: DEBUG
    org.springframework.web: INFO
  pattern:
    console: "%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n"
```

> **Note:** `logback-spring.xml` overrides the console pattern with MDC fields for dev (human-readable) and prod (JSON) profiles. The `logging.pattern.console` in YAML is a fallback.

### application-dev.yml

```yaml
cce:
  collector:
    url: ${CCE_COLLECTOR_URL:http://localhost:5055}   # the docker-compose stub
    auth:
      token: dev-token
  emitter:
    source: tiberbu
    patient-identifier-system: "http://openphc.org/identifier/upid"
    facility-filter:
      ids: ${FACILITY_FILTER_IDS:}

logging:
  level:
    org.openphc.tiberbu.cce: DEBUG
    org.springframework.web: DEBUG
```

> No `spring.profiles.active` is set in `application.yml` — select a profile
> explicitly with `SPRING_PROFILES_ACTIVE` or `--spring.profiles.active`.

### application-prod.yml

```yaml
server:
  port: ${SERVER_PORT:8080}

cce:
  collector:
    url: ${CCE_COLLECTOR_URL}
    events-path: ${CCE_COLLECTOR_EVENTS_PATH:/v1/events}
    timeout: ${CCE_COLLECTOR_TIMEOUT:5000}
    retry:
      max-attempts: ${CCE_COLLECTOR_RETRY_MAX_ATTEMPTS:3}
      backoff-ms: ${CCE_COLLECTOR_RETRY_BACKOFF_MS:1000}
    auth:
      token: ${CCE_COLLECTOR_AUTH_TOKEN:}
      keycloak-host: ${KEYCLOAK_HOST:}
      realm: ${KEYCLOAK_REALM:cce}
      client-id: ${KEYCLOAK_CLIENT_ID:}
      client-secret: ${KEYCLOAK_CLIENT_SECRET:}
  emitter:
    source: tiberbu

logging:
  level:
    org.openphc.tiberbu.cce: INFO
  pattern:
    console: '{"timestamp":"%d{ISO8601}","level":"%level","logger":"%logger","message":"%msg"}%n'

management:
  endpoint:
    health:
      show-details: never
```

## 7. Docker Compose (Local Development)

### docker-compose.yml

```yaml
services:
  # CCE Collector Stub (WireMock)
  collector-stub:
    image: wiremock/wiremock:3.9.2
    ports:
      - "5055:8080"
    volumes:
      - ./wiremock:/home/wiremock
    command: --verbose

  # tibERbu CCE Emitter Adaptor
  tiberbu-cce-emitter-adaptor:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "8081:8081"
    environment:
      - SPRING_PROFILES_ACTIVE=dev
      - SERVER_PORT=8081
      - CCE_COLLECTOR_URL=http://collector-stub:8080
      - FACILITY_FILTER_IDS=          # empty = filter inactive
    depends_on:
      collector-stub:
        condition: service_started
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health/liveness"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 30s
    restart: unless-stopped
```

> **Container names:** none are pinned. Compose derives them as
> `<project>-<service>-<n>`, which keeps the stack from colliding with the
> sibling emitter adaptors in the same workspace — they publish a Collector stub
> under the same name.

> **Facility filter tip:** `FACILITY_FILTER_IDS` is the only env var needed to control which facility IDs are admitted. Update it and do a rolling restart — no rebuild required. Skipped events return `200 OK` with `status: "skipped"` — a normal outcome, not an error, so callers should not retry.

### WireMock Stub for Collector

Create `wiremock/mappings/collector-events.json`:

```json
{
  "request": {
    "method": "POST",
    "urlPattern": "/v1/events"
  },
  "response": {
    "status": 202,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "data": {
        "eventId": "{{randomValue type='UUID'}}",
        "status": "accepted",
        "correlationId": "corr-{{randomValue type='UUID'}}",
        "timestamp": "{{now}}"
      }
    },
    "transformers": ["response-template"]
  }
}
```

## 8. Running the Application

### Option A: IDE (IntelliJ / VS Code)

1. Open the project root as a Gradle project
2. Run `CceEmitterAdaptorApplication.main()` with `--spring.profiles.active=dev`
3. Verify: `curl http://localhost:8080/actuator/health`

### Option B: Gradle CLI

```bash
# Start with dev profile
./gradlew bootRun --args='--spring.profiles.active=dev'

# Or build and run the JAR
./gradlew bootJar
java -jar build/libs/tiberbu-cce-emitter-adaptor-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
```

### Option C: Docker

```bash
# Build Docker image
docker build -t tiberbu-cce-emitter-adaptor:latest .

# Start local dev stack (WireMock Collector stub)
docker compose up -d

# Run application against the local stack
./gradlew bootRun --args='--spring.profiles.active=dev'

# Or run standalone container (production)
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e CCE_COLLECTOR_URL=http://collector:8081 \
  -e KEYCLOAK_HOST=https://keycloak.cce.mdtlabs.org \
  -e KEYCLOAK_REALM=cce \
  -e KEYCLOAK_CLIENT_ID=<client-id> \
  -e KEYCLOAK_CLIENT_SECRET=<client-secret> \
  tiberbu-cce-emitter-adaptor:latest
```

## 9. Verifying the Setup

### Health Check

```bash
curl -s http://localhost:8080/actuator/health | jq
# Expected: {"status":"UP","groups":["liveness","readiness"]}
```

### Metrics

```bash
curl -s http://localhost:8080/actuator/prometheus | grep cce_emitter
```

### Send a Test Event

```bash
curl -X POST http://localhost:8080/inbound \
  -H "Content-Type: application/json" \
  -H "X-Facility-Id: FAC-FOSA-001" \
  -H "X-Source-Event-Id: VCR-20260901-57098420" \
  -d '{
    "meta": {
      "resourceType": "Bundle",
      "resourceId": "VCR-20260901-57098420",
      "event": "upserted",
      "source": "shr-mediator",
      "timestamp": "2026-09-01 11:55:40.281292",
      "bundleId": "VCR-20260901-57098420",
      "version": "v1"
    },
    "resource": {
      "resourceType": "Bundle",
      "type": "transaction",
      "entry": [
        {
          "request": {"method": "PUT", "url": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"},
          "resource": {
            "resourceType": "Patient",
            "id": "KE-SHRP-170CDF0A-1363-4972-B36A",
            "gender": "male"
          }
        },
        {
          "request": {"method": "PUT", "url": "Consent/VCR-20260901-57098420"},
          "resource": {
            "resourceType": "Consent",
            "id": "VCR-20260901-57098420",
            "status": "active",
            "patient": {"reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"}
          }
        }
      ]
    }
  }'
```

Expected: `202 Accepted` with an `application/json` body of `{"status":"processed","eventsForwarded":1,...}` — one event, from the `Consent` at `entry[1]`. The `Patient` at `entry[0]` is skipped.

> A bare FHIR resource (no `meta` / `resource` envelope) is **not** the contract — it produces no events and returns `200 OK` with `status: "ignored"`. The same applies to a bundle holding only the patient entry; see API Reference §4.2 for every ignore scenario.

## 10. Useful Gradle Commands

| Command | Purpose |
|---------|---------|
| `./gradlew clean build` | Full build + tests |
| `./gradlew test` | Run tests only |
| `./gradlew bootRun` | Run application |
| `./gradlew bootJar` | Build executable JAR |
| `./gradlew dependencies` | Show dependency tree |
| `./gradlew dependencyInsight --dependency hapi-fhir-base` | Inspect specific dependency |
| `./gradlew tasks --all` | List all available tasks |
| `./gradlew jacocoTestReport` | Generate the JaCoCo coverage report (XML + HTML) |
| `./gradlew sonar` | Run SonarQube analysis (requires `SONAR_TOKEN`) |
| `./gradlew build jacocoTestReport sonar` | What CI runs — build, cover, analyse |

## 11. IDE Setup

### IntelliJ IDEA

1. File → Open → select `emitter-adaptor/` folder
2. IntelliJ auto-detects `build.gradle.kts` and imports
3. Enable **Annotation Processing** (for Lombok): Settings → Build → Compiler → Annotation Processors → Enable
4. Set Project SDK to Java 21

### VS Code

1. Install "Extension Pack for Java" and "Spring Boot Extension Pack"
2. Open `emitter-adaptor/` folder
3. Java Language Server will auto-detect Gradle project
4. Run/Debug via **Spring Boot Dashboard** panel

## 12. Troubleshooting

| Problem | Fix |
|---------|-----|
| `./gradlew: Permission denied` | `chmod +x gradlew` |
| `FhirContext.forR4()` slow first call | Normal — HAPI initializes models on first use (~2s). Subsequent calls are instant. |
| Events silently ignored (200 `"ignored"`) | The bundle produced no events: `resource` is not a FHIR `Bundle`, `resource.entry[]` is empty, or the bundle holds only the patient entry at `entry[0]`. There is no source-level filter, so this is never a source mismatch. |
| Collector 404 | Verify `cce.collector.url` and `cce.collector.events-path` |
| Collector 401/403 | Outbound auth mismatch — check which mode `cce.collector.auth.*` selects (Architecture Overview §11.1) against what the target expects. |
| Java 21 not found | Install Temurin 21: `sdk install java 21.0.5-tem` (SDKMAN) |

## 13. Testing

### Unit Tests

```bash
./gradlew test
```

Unit tests use JUnit 5 + Spring Boot Test. Located in `src/test/java/` mirroring the main package structure.

### Integration Tests

```bash
# Run integration tests only
./gradlew test --tests '*IntegrationTest'

# Run a specific integration test
./gradlew test --tests '*FullPipelineIntegrationTest'
```

Integration tests use `@ActiveProfiles("integration")` with `application-integration.yml`:
- Random `server.port: 0` for test isolation
- Programmatic `WireMockServer` + `@DynamicPropertySource` to inject Collector URL
- Fast retry (100ms backoff)

| Test Class | Description |
|------------|-------------|
| `FullPipelineIntegrationTest` | End-to-end: bundle → CloudEvent(s) → Collector WireMock (happy path, `entry[0]` skipped, multi-entry fan-out, ignored and skipped outcomes, duplicate handling, correlation ID) |
| `RetryIntegrationTest` | Retry behavior: 503→exhaustion→502, 422→no retry, retry→eventual success, 400→no retry |
| `ActuatorMetricsIntegrationTest` | Health probes, Prometheus scrape, custom metric registration (uses `TestRestTemplate`, not MockMvc) |

**FHIR Fixtures:** `src/test/resources/fhir/encounter-visit.json`, `observation-lab.json`

## 14. Code Quality — Coverage & SonarQube

Analysis runs against **SonarQube Cloud** in the `openphc` organization, using
JaCoCo for coverage.

| Property | Value |
|----------|-------|
| Organization | `openphc` |
| Project key | `openphc_tiberbu-cce-emitter-adaptor` |
| Scanner plugin | `org.sonarqube` 6.3.1.5724 |
| Coverage | JaCoCo XML at `build/reports/jacoco/test/jacocoTestReport.xml` |
| CI workflow | `.github/workflows/sonarqube.yml` |
| Required secret | `SONAR_TOKEN` (repository or organization scope) |

### Build configuration

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
    id("org.sonarqube") version "6.3.1.5724"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true      // the format Sonar ingests
        html.required = true     // for local browsing
    }
}

sonar {
    properties {
        property("sonar.organization", "openphc")
        property("sonar.projectKey", "openphc_tiberbu-cce-emitter-adaptor")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "${layout.buildDirectory.get()}/reports/jacoco/test/jacocoTestReport.xml",
        )
    }
}

tasks.named("sonar") { dependsOn(tasks.jacocoTestReport) }
```

### Running locally

```bash
# Coverage only — open build/reports/jacoco/test/html/index.html
./gradlew test jacocoTestReport

# Full analysis (uploads to SonarQube Cloud)
SONAR_TOKEN=<token> ./gradlew build jacocoTestReport sonar
```

### CI behaviour

The workflow triggers on pushes to `main` and `release-*` (a pattern, so future
release lines need no edit) and on pull requests.

Two details are easy to get wrong and are handled explicitly:

- **Branch identity must be passed to the scanner.** It is not inferred from the
  CI environment. Without `-Dsonar.branch.name` (or the `sonar.pullrequest.*`
  trio on a PR) every branch uploads as the project's main branch and
  overwrites the previous line's results.
- **Fork PRs receive no secrets**, so `SONAR_TOKEN` is empty there. Those runs
  still build and produce coverage but skip the upload; analysis lands on the
  push to the release branch after the PR is merged.

`fetch-depth: 0` on checkout is also required — a shallow clone degrades Sonar's
blame data and its new-code calculations.

### Troubleshooting

| Symptom | Cause |
|---------|-------|
| Coverage reported as 0% | `sonar.coverage.jacoco.xmlReportPaths` does not match the generated report path, or `jacocoTestReport` did not run before `sonar` |
| `Project not found` on upload | The project key is not registered in the `openphc` SonarQube Cloud organization yet |
| Every branch overwrites main | The branch identity flags are missing from the analysis command |
| Analysis skipped in CI | `SONAR_TOKEN` is unset — expected on fork PRs, otherwise check the repository secret |
