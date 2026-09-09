package org.openphc.tiberbu.cce.emitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.cloudevents.EventIdGenerator;
import org.openphc.tiberbu.cce.emitter.model.SourceMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SUB-TASK E13 — full-pipeline integration tests: a real HTTP round trip
 * through {@code POST /inbound}, a real Spring context, and a WireMock-stubbed
 * Collector. Nothing internal is mocked; every scenario here exercises the
 * actual controller, {@code InboundEventService}, {@code SourceAdaptorService},
 * and {@code CollectorForwardingService} together, the same way tibERbu
 * traffic and the real Collector would.
 *
 * <p>One shared Spring context (static Collector Bearer token, facility
 * allowlist {@code FAC-0001}, short retry backoff) covers every scenario
 * below except the three outbound auth modes, which each need a different
 * {@code cce.collector.auth.*} binding and so get their own context in
 * {@link CollectorAuthModePipelineIntegrationTest}.
 *
 * <p>{@link #resetWireMockJournal()} clears WireMock's request log before
 * every test (stub mappings, registered once in {@link #stubCollectorResponses()},
 * survive) so each test's "N Collector calls" assertion counts only what that
 * test itself triggered, not what earlier tests in the same class left behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullPipelineIntegrationTest {

    private static final String ALLOWED_FACILITY = "FAC-0001";
    private static final String BLOCKED_FACILITY = "FAC-BLOCKED";
    private static final String STATIC_TOKEN = "integration-test-token";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final EventIdGenerator EVENT_ID_GENERATOR = new EventIdGenerator();

    private static final WireMockServer collectorMock =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void collectorProperties(DynamicPropertyRegistry registry) {
        collectorMock.start();
        registry.add("cce.collector.url", () -> "http://localhost:" + collectorMock.port());
        registry.add("cce.collector.events-path", () -> "/v1/events");
        registry.add("cce.collector.auth.token", () -> STATIC_TOKEN);
        registry.add("cce.collector.retry.max-attempts", () -> 3);
        registry.add("cce.collector.retry.backoff-ms", () -> 10);
        registry.add("cce.emitter.facility-filter.ids", () -> ALLOWED_FACILITY);
    }

    @AfterAll
    static void stopWireMock() {
        collectorMock.stop();
    }

    @BeforeEach
    void resetWireMockJournal() {
        collectorMock.resetRequests();
    }

    @BeforeAll
    static void stubCollectorResponses() {
        stubAccepted("VCR-20260901-57098420");
        stubAccepted("VCR-MULTI-0001");
        stubAccepted("OBS-MULTI-0001");
        stubAccepted("OBS-NOFAC-001");
        collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo("VCR-400-001")))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json").withBody("""
                        {"error": {"code": "VALIDATION_ERROR", "message": "type is required"}}""")));
        collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo("VCR-500-001")))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));
        collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo("VCR-DUP-001")))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"eventId": "evt-dup", "status": "duplicate", "correlationId": "corr-dup", "timestamp": "2026-09-01T11:55:43Z"}}""")));
    }

    private static void stubAccepted(String resourceId) {
        collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo(resourceId)))
                .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"eventId": "evt-%s", "status": "accepted", "correlationId": "corr-1", "timestamp": "2026-09-01T11:55:43Z"}}"""
                        .formatted(resourceId))));
    }

    // ---- shared helpers -----------------------------------------------------------------------

    private static String loadFixture(String fileName) {
        try (InputStream fixtureStream = FullPipelineIntegrationTest.class.getClassLoader()
                .getResourceAsStream("tiberbu/" + fileName)) {
            if (fixtureStream == null) {
                throw new IllegalStateException("Fixture not found on classpath: tiberbu/" + fileName);
            }
            return new String(fixtureStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException fixtureReadFailure) {
            throw new UncheckedIOException(fixtureReadFailure);
        }
    }

    private ResponseEntity<String> postInbound(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/inbound", new HttpEntity<>(body, headers), String.class);
    }

    private static JsonNode json(String body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (IOException malformedJson) {
            throw new UncheckedIOException(malformedJson);
        }
    }

    /** The single CloudEvent body actually POSTed to the Collector for the entry whose {@code resource.id} is {@code resourceId}. */
    private static JsonNode collectorRequestBodyFor(String resourceId) {
        List<ServeEvent> matches = collectorMock.getAllServeEvents(); // most-recent-first
        for (ServeEvent serveEvent : matches) {
            JsonNode body = json(serveEvent.getRequest().getBodyAsString());
            if (resourceId.equals(body.path("data").path("id").asText(null))) {
                return body;
            }
        }
        throw new AssertionError("No Collector request found carrying data.id=" + resourceId);
    }

    private static String deterministicEventId(String traceId, String entryResourceId) {
        SourceMetadata metadata = new SourceMetadata(null, null, null, null, null, traceId, 0);
        return EVENT_ID_GENERATOR.generate(metadata, entryResourceId);
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("a Consent bundle forwards exactly once, with every documented CloudEvent field correct")
        void consentBundleForwardsExactlyOnceWithDocumentedCloudEventFields() {
            ResponseEntity<String> response = postInbound(loadFixture("consent-bundle.json"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            JsonNode body = json(response.getBody());
            assertThat(body.path("status").asText()).isEqualTo("processed");
            assertThat(body.path("eventsForwarded").asInt()).isEqualTo(1);
            assertThat(body.path("events")).hasSize(1);
            JsonNode event = body.path("events").get(0);
            assertThat(event.path("entryIndex").asInt()).isEqualTo(1);
            assertThat(event.path("type").asText()).isEqualTo("Consent");
            assertThat(event.path("subject").asText()).isEqualTo("KE-SHRP-170CDF0A-1363-4972-B36A");
            assertThat(event.path("outcome").asText()).isEqualTo("forwarded");
            assertThat(event.path("collectorStatus").asText()).isEqualTo("accepted");

            String expectedEventId = deterministicEventId("ef1cb56375", "VCR-20260901-57098420");
            assertThat(event.path("eventId").asText()).isEqualTo(expectedEventId);

            collectorMock.verify(1, postRequestedFor(urlEqualTo("/v1/events"))
                    .withRequestBody(matchingJsonPath("$.data.id", equalTo("VCR-20260901-57098420"))));

            JsonNode cloudEvent = collectorRequestBodyFor("VCR-20260901-57098420");
            assertThat(cloudEvent.path("specversion").asText()).isEqualTo("1.0");
            assertThat(cloudEvent.path("id").asText()).isEqualTo(expectedEventId);
            assertThat(cloudEvent.path("source").asText()).isEqualTo("tiberbu");
            assertThat(cloudEvent.path("type").asText()).isEqualTo("Consent");
            assertThat(cloudEvent.path("subject").asText()).isEqualTo("KE-SHRP-170CDF0A-1363-4972-B36A");
            assertThat(cloudEvent.path("facilityid").asText()).isEqualTo(ALLOWED_FACILITY);
            assertThat(cloudEvent.path("datacontenttype").asText()).isEqualTo("application/fhir+json");
            assertThat(cloudEvent.has("sourceeventid")).as("sourceeventid is omitted, never null").isFalse();
            assertThat(cloudEvent.path("correlationid").asText())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            assertThat(cloudEvent.path("data").path("resourceType").asText()).isEqualTo("Consent");
        }

        @Test
        @DisplayName("correlationid is a fresh generated UUID on every request, even with no inbound headers at all")
        void correlationidIsAlwaysAFreshGeneratedUuid() {
            String fixture = loadFixture("consent-bundle.json");

            postInbound(fixture);
            JsonNode firstCall = collectorRequestBodyFor("VCR-20260901-57098420");
            postInbound(fixture);
            JsonNode secondCall = collectorRequestBodyFor("VCR-20260901-57098420");

            // Same traceId + resource.id both times -> the deterministic event id is identical,
            // but correlationid is minted fresh per request and must differ.
            assertThat(secondCall.path("id").asText()).isEqualTo(firstCall.path("id").asText());
            assertThat(secondCall.path("correlationid").asText()).isNotEqualTo(firstCall.path("correlationid").asText());
        }

        @Test
        @DisplayName("a multi-entry bundle forwards every non-Patient entry, distinct ids, bundle order preserved")
        void multiEntryBundleForwardsAllNonPatientEntriesWithDistinctIdsInBundleOrder() {
            ResponseEntity<String> response = postInbound(loadFixture("multi-entry-bundle.json"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            JsonNode body = json(response.getBody());
            assertThat(body.path("eventsForwarded").asInt()).isEqualTo(2);
            assertThat(body.path("events")).hasSize(2);

            JsonNode firstEvent = body.path("events").get(0);
            JsonNode secondEvent = body.path("events").get(1);
            assertThat(firstEvent.path("entryIndex").asInt()).isEqualTo(1);
            assertThat(firstEvent.path("type").asText()).isEqualTo("Consent");
            assertThat(secondEvent.path("entryIndex").asInt()).isEqualTo(2);
            assertThat(secondEvent.path("type").asText()).isEqualTo("Observation");

            String expectedConsentId = deterministicEventId("trace-multi-0001", "VCR-MULTI-0001");
            String expectedObservationId = deterministicEventId("trace-multi-0001", "OBS-MULTI-0001");
            assertThat(firstEvent.path("eventId").asText()).isEqualTo(expectedConsentId);
            assertThat(secondEvent.path("eventId").asText()).isEqualTo(expectedObservationId);
            assertThat(expectedConsentId).isNotEqualTo(expectedObservationId);

            collectorMock.verify(2, postRequestedFor(urlEqualTo("/v1/events")));
        }
    }

    @Nested
    @DisplayName("mixed outcome — one entry forwarded, a sibling entry fails, still 202")
    class MixedOutcome {

        @Test
        @DisplayName("the failed sibling's eventId/subject/collectorStatus are omitted, not null, per the documented body shape")
        void oneForwardedAndOneFailedEntryStillAnswersTwoOhTwo() {
            stubAccepted("VCR-MIXED-OK-001");
            String requestBody = """
                    {"meta": {"bundleId": "mixed-001", "traceId": "trace-mixed-001"},
                     "resource": {"resourceType": "Bundle", "type": "transaction", "entry": [
                       {"request": {"method": "PUT", "url": "Patient/patient-mixed"},
                        "resource": {"resourceType": "Patient", "id": "patient-mixed"}},
                       {"request": {"method": "PUT", "url": "Consent/VCR-MIXED-OK-001"},
                        "resource": {"resourceType": "Consent", "id": "VCR-MIXED-OK-001", "status": "active",
                          "patient": {"reference": "Patient/KE-SHRP-MIXED-0001"}}},
                       {"request": {"method": "PUT", "url": "Observation/OBS-MIXED-FAIL-001"},
                        "resource": {"resourceType": "Observation", "id": "OBS-MIXED-FAIL-001", "status": "final"}}
                     ]}}""";

            ResponseEntity<String> response = postInbound(requestBody);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            JsonNode body = json(response.getBody());
            assertThat(body.path("status").asText()).isEqualTo("processed");
            assertThat(body.path("eventsForwarded").asInt()).isEqualTo(1);
            assertThat(body.path("events")).hasSize(2);

            JsonNode forwardedEvent = body.path("events").get(0);
            assertThat(forwardedEvent.path("entryIndex").asInt()).isEqualTo(1);
            assertThat(forwardedEvent.path("outcome").asText()).isEqualTo("forwarded");

            JsonNode failedEvent = body.path("events").get(1);
            assertThat(failedEvent.path("entryIndex").asInt()).isEqualTo(2);
            assertThat(failedEvent.path("type").asText()).isEqualTo("Observation");
            assertThat(failedEvent.path("outcome").asText()).isEqualTo("failed");
            assertThat(failedEvent.path("reason").asText()).isEqualTo("No patient reference found in Observation resource");
            assertThat(failedEvent.has("eventId")).as("eventId omitted, not null, for a failed entry").isFalse();
            assertThat(failedEvent.has("subject")).as("subject omitted, not null, for a failed entry").isFalse();
            assertThat(failedEvent.has("collectorStatus")).as("collectorStatus omitted, not null, for a failed entry").isFalse();

            collectorMock.verify(1, postRequestedFor(urlEqualTo("/v1/events")));
        }
    }

    @Nested
    @DisplayName("ignored paths — 200, zero Collector calls")
    class IgnoredPaths {

        private void assertIgnoredWithNoCollectorCalls(String requestBody) {
            ResponseEntity<String> response = postInbound(requestBody);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = json(response.getBody());
            assertThat(body.path("status").asText()).isEqualTo("ignored");
            assertThat(body.path("message").asText()).isEqualTo("Non-processable payload");
            collectorMock.verify(0, postRequestedFor(urlEqualTo("/v1/events")));
        }

        @Test
        @DisplayName("an empty body is ignored")
        void emptyBodyIsIgnored() {
            assertIgnoredWithNoCollectorCalls("");
        }

        @Test
        @DisplayName("a non-JSON body is ignored")
        void malformedNonJsonBodyIsIgnored() {
            assertIgnoredWithNoCollectorCalls(loadFixture("malformed-envelope.json"));
        }

        @Test
        @DisplayName("a missing resource field is ignored")
        void missingResourceFieldIsIgnored() {
            assertIgnoredWithNoCollectorCalls("""
                    {"meta": {"bundleId": "no-resource-001"}}""");
        }

        @Test
        @DisplayName("a non-Bundle resource is ignored")
        void nonBundleResourceIsIgnored() {
            assertIgnoredWithNoCollectorCalls("""
                    {"meta": {}, "resource": {"resourceType": "Patient", "id": "p1"}}""");
        }

        @Test
        @DisplayName("an absent entry[] is ignored")
        void absentEntryArrayIsIgnored() {
            assertIgnoredWithNoCollectorCalls("""
                    {"meta": {}, "resource": {"resourceType": "Bundle", "type": "transaction"}}""");
        }

        @Test
        @DisplayName("an empty entry[] is ignored")
        void emptyEntryArrayIsIgnored() {
            assertIgnoredWithNoCollectorCalls("""
                    {"meta": {}, "resource": {"resourceType": "Bundle", "type": "transaction", "entry": []}}""");
        }

        @Test
        @DisplayName("a bundle carrying only a Patient entry is ignored")
        void patientOnlyBundleIsIgnored() {
            assertIgnoredWithNoCollectorCalls(loadFixture("patient-only-bundle.json"));
        }

        @Test
        @DisplayName("entries after index 0 with no resource object at all are ignored")
        void postIndexZeroEntriesWithNoResourceObjectAreIgnored() {
            assertIgnoredWithNoCollectorCalls("""
                    {"meta": {"bundleId": "no-resource-obj-001"},
                     "resource": {"resourceType": "Bundle", "type": "transaction", "entry": [
                       {"request": {"method": "PUT", "url": "Patient/patient-x"},
                        "resource": {"resourceType": "Patient", "id": "patient-x"}},
                       {"request": {"method": "PUT", "url": "Consent/consent-x"}}
                     ]}}""");
        }
    }

    @Nested
    @DisplayName("skipped path — facility filter")
    class SkippedPath {

        @Test
        @DisplayName("a facility outside the allowlist is skipped: 200, zero Collector calls, filtered counter incremented")
        void facilityNotInAllowlistIsSkippedWithZeroCollectorCalls() {
            String requestBody = """
                    {"meta": {"bundleId": "skip-001", "traceId": "trace-skip-001"},
                     "resource": {"resourceType": "Bundle", "type": "transaction", "entry": [
                       {"request": {"method": "PUT", "url": "Consent/VCR-SKIP-001"},
                        "resource": {"resourceType": "Consent", "id": "VCR-SKIP-001", "status": "active",
                          "patient": {"reference": "Patient/KE-SHRP-SKIP-0001"},
                          "organization": [{"reference": "Organization/%s"}]}}
                     ]}}""".formatted(BLOCKED_FACILITY);

            ResponseEntity<String> response = postInbound(requestBody);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = json(response.getBody());
            assertThat(body.path("status").asText()).isEqualTo("skipped");
            assertThat(body.path("eventsForwarded").asInt()).isZero();
            JsonNode event = body.path("events").get(0);
            assertThat(event.path("outcome").asText()).isEqualTo("skipped");
            assertThat(event.path("reason").asText())
                    .isEqualTo("Event skipped by facility filter: facilityId='" + BLOCKED_FACILITY + "' source='tiberbu'");
            collectorMock.verify(0, postRequestedFor(urlEqualTo("/v1/events")));

            String metrics = restTemplate.getForEntity("/actuator/prometheus", String.class).getBody();
            assertThat(metrics)
                    .contains("tiberbu_cce_emitter_entries_filtered_total")
                    .contains("facility=\"" + BLOCKED_FACILITY + "\"")
                    .contains("reason=\"NOT_IN_ALLOWLIST\"");
        }

        @Test
        @DisplayName("an entry with no resolvable facility ID still forwards, even while the filter is active")
        void entryWithNoFacilityIdStillForwardsWhenFilterIsActive() {
            String requestBody = """
                    {"meta": {"bundleId": "nofac-001", "traceId": "trace-nofac-001"},
                     "resource": {"resourceType": "Bundle", "type": "transaction", "entry": [
                       {"request": {"method": "PUT", "url": "Observation/OBS-NOFAC-001"},
                        "resource": {"resourceType": "Observation", "id": "OBS-NOFAC-001", "status": "final",
                          "subject": {"reference": "Patient/KE-SHRP-NOFAC-0001"}}}
                     ]}}""";

            ResponseEntity<String> response = postInbound(requestBody);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            JsonNode body = json(response.getBody());
            assertThat(body.path("eventsForwarded").asInt()).isEqualTo(1);
            assertThat(body.path("events").get(0).path("outcome").asText()).isEqualTo("forwarded");
            collectorMock.verify(1, postRequestedFor(urlEqualTo("/v1/events"))
                    .withRequestBody(matchingJsonPath("$.data.id", equalTo("OBS-NOFAC-001"))));
        }
    }

    @Nested
    @DisplayName("failure paths")
    class FailurePaths {

        @Test
        @DisplayName("a Collector 400 propagates as 400 COLLECTOR_CLIENT_ERROR")
        void collectorFourHundredPropagatesAsCollectorClientError() {
            String requestBody = singleConsentEntry("VCR-400-001", ALLOWED_FACILITY);

            ResponseEntity<String> response = postInbound(requestBody);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            JsonNode body = json(response.getBody());
            assertThat(body.path("error").path("code").asText()).isEqualTo("COLLECTOR_CLIENT_ERROR");
            assertThat(body.path("error").path("message").asText()).contains("Collector returned 400");
            assertThat(body.has("timestamp")).isTrue();
        }

        @Test
        @DisplayName("a Collector 500 on every attempt exhausts retries: 502, exactly maxAttempts Collector requests")
        void collectorFiveHundredExhaustsAllRetriesAndReturns502() {
            String requestBody = singleConsentEntry("VCR-500-001", ALLOWED_FACILITY);

            ResponseEntity<String> response = postInbound(requestBody);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            JsonNode body = json(response.getBody());
            assertThat(body.path("error").path("code").asText()).isEqualTo("COLLECTOR_FORWARDING_ERROR");
            collectorMock.verify(3, postRequestedFor(urlEqualTo("/v1/events"))
                    .withRequestBody(matchingJsonPath("$.data.id", equalTo("VCR-500-001"))));
        }

        @Test
        @DisplayName("a Collector duplicate (200) still answers 202, with collectorStatus duplicate")
        void collectorDuplicateStillAnswersTwoOhTwoWithDuplicateStatus() {
            String requestBody = singleConsentEntry("VCR-DUP-001", ALLOWED_FACILITY);

            ResponseEntity<String> response = postInbound(requestBody);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            JsonNode event = json(response.getBody()).path("events").get(0);
            assertThat(event.path("outcome").asText()).isEqualTo("forwarded");
            assertThat(event.path("collectorStatus").asText()).isEqualTo("duplicate");
        }

        @Test
        @DisplayName("an unparseable FHIR entry answers 422 FHIR_MAPPING_ERROR")
        void unparseableFhirEntryReturnsFourTwentyTwo() {
            String requestBody = """
                    {"meta": {"bundleId": "unparseable-001"},
                     "resource": {"resourceType": "Bundle", "type": "transaction", "entry": [
                       {"request": {"method": "PUT", "url": "Patient/patient-unparseable"},
                        "resource": {"resourceType": "Patient", "id": "patient-unparseable"}},
                       {"request": {"method": "PUT", "url": "NotARealResourceType/bad-001"},
                        "resource": {"resourceType": "NotARealResourceType", "id": "bad-001"}}
                     ]}}""";

            ResponseEntity<String> response = postInbound(requestBody);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(json(response.getBody()).path("error").path("code").asText()).isEqualTo("FHIR_MAPPING_ERROR");
            collectorMock.verify(0, postRequestedFor(urlEqualTo("/v1/events")));
        }

        @Test
        @DisplayName("a missing patient reference answers 400 PATIENT_ID_NOT_FOUND")
        void missingPatientReferenceReturnsFourHundred() {
            String requestBody = """
                    {"meta": {"bundleId": "nopatient-001"},
                     "resource": {"resourceType": "Bundle", "type": "transaction", "entry": [
                       {"request": {"method": "PUT", "url": "Observation/obs-nopatient"},
                        "resource": {"resourceType": "Observation", "id": "obs-nopatient", "status": "final"}}
                     ]}}""";

            ResponseEntity<String> response = postInbound(requestBody);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            JsonNode body = json(response.getBody());
            assertThat(body.path("error").path("code").asText()).isEqualTo("PATIENT_ID_NOT_FOUND");
            assertThat(body.path("error").path("message").asText()).isEqualTo("No patient reference found in Observation resource");
            collectorMock.verify(0, postRequestedFor(urlEqualTo("/v1/events")));
        }

        @Test
        @DisplayName("GET /inbound answers 405 METHOD_NOT_ALLOWED")
        void getOnInboundReturnsFourOhFive() {
            ResponseEntity<String> response = restTemplate.exchange("/inbound", HttpMethod.GET, null, String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(json(response.getBody()).path("error").path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        }

        private String singleConsentEntry(String resourceId, String facilityId) {
            return """
                    {"meta": {"bundleId": "%s"},
                     "resource": {"resourceType": "Bundle", "type": "transaction", "entry": [
                       {"request": {"method": "PUT", "url": "Consent/%s"},
                        "resource": {"resourceType": "Consent", "id": "%s", "status": "active",
                          "patient": {"reference": "Patient/KE-SHRP-FAIL-0001"},
                          "organization": [{"reference": "Organization/%s"}]}}
                     ]}}""".formatted(resourceId, resourceId, resourceId, facilityId);
        }
    }
}
