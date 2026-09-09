package org.openphc.tiberbu.cce.emitter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.service.CollectorForwardingService;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Arrays;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for SUB-TASK E12: a real HTTP request through the
 * controller, forwarded to a WireMock-stubbed Collector (never mocked at the
 * service layer — same reasoning as {@link
 * org.openphc.tiberbu.cce.emitter.service.CollectorForwardingRetryTest}),
 * asserting every custom metric it exercises appears at {@code
 * /actuator/prometheus} with its documented tags, and that the MDC fields
 * populated during forwarding reach an actual log line.
 *
 * <p>One bundle, five Consent entries, each engineered to hit a different
 * outcome: {@code VCR-ACCEPTED-001} (allowed facility, Collector accepts),
 * {@code VCR-DUPLICATE-001} (allowed facility, Collector reports duplicate),
 * {@code VCR-FILTERED-001} (facility outside the allowlist — never reaches
 * the Collector at all), {@code VCR-REJECTED-001} (allowed facility,
 * Collector returns 400), {@code VCR-NOPATIENT-001} (allowed facility, no
 * {@code patient} reference — fails adaptation before ever reaching the
 * Collector). Three of the five forward-or-filter cleanly, so the request as
 * a whole still answers {@code 202} despite the other two failing — see
 * {@code InboundEventService}'s multi-entry failure policy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorMetricsIntegrationTest {

    private static final String PATIENT_ID = "KE-SHRP-170CDF0A-1363-4972-B36A";
    private static final String ALLOWED_FACILITY = "FAC-ALLOWED";
    private static final String BLOCKED_FACILITY = "FAC-BLOCKED";

    private static final WireMockServer collectorMock =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void collectorProperties(DynamicPropertyRegistry registry) {
        collectorMock.start();
        registry.add("cce.collector.url", () -> "http://localhost:" + collectorMock.port());
        registry.add("cce.collector.events-path", () -> "/v1/events");
        registry.add("cce.emitter.facility-filter.ids", () -> ALLOWED_FACILITY);
    }

    @AfterAll
    static void stopWireMock() {
        collectorMock.stop();
    }

    @BeforeAll
    static void stubCollectorResponses() {
        collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo("VCR-ACCEPTED-001")))
                .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"eventId": "evt-accepted", "status": "accepted", "correlationId": "corr-1", "timestamp": "2026-09-01T11:55:43Z"}}""")));

        collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo("VCR-DUPLICATE-001")))
                .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"eventId": "evt-duplicate", "status": "duplicate", "correlationId": "corr-1", "timestamp": "2026-09-01T11:55:43Z"}}""")));

        collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                .withRequestBody(matchingJsonPath("$.data.id", equalTo("VCR-REJECTED-001")))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json").withBody("""
                        {"error": {"code": "VALIDATION_ERROR", "message": "type is required"}}""")));
    }

    private static String consentEntry(String resourceId, String facilityId) {
        return """
                {
                  "request": {"method": "PUT", "url": "Consent/%s"},
                  "resource": {
                    "resourceType": "Consent", "id": "%s", "status": "active",
                    "patient": {"reference": "Patient/%s"},
                    "organization": [{"reference": "Organization/%s"}]
                  }
                }""".formatted(resourceId, resourceId, PATIENT_ID, facilityId);
    }

    /** No {@code patient} reference — {@code PatientIdExtractor} throws, an adaptation-stage failure. */
    private static String consentEntryWithoutPatient(String resourceId, String facilityId) {
        return """
                {
                  "request": {"method": "PUT", "url": "Consent/%s"},
                  "resource": {
                    "resourceType": "Consent", "id": "%s", "status": "active",
                    "organization": [{"reference": "Organization/%s"}]
                  }
                }""".formatted(resourceId, resourceId, facilityId);
    }

    private static String bundleEnvelope() {
        String entries = String.join(",",
                consentEntry("VCR-ACCEPTED-001", ALLOWED_FACILITY),
                consentEntry("VCR-DUPLICATE-001", ALLOWED_FACILITY),
                consentEntry("VCR-FILTERED-001", BLOCKED_FACILITY),
                consentEntry("VCR-REJECTED-001", ALLOWED_FACILITY),
                consentEntryWithoutPatient("VCR-NOPATIENT-001", ALLOWED_FACILITY));
        return """
                {"meta":{"bundleId":"E12-metrics-test","traceId":"trace-e12-001"},
                 "resource":{"resourceType":"Bundle","type":"transaction","entry":[%s]}}"""
                .formatted(entries);
    }

    /** Reads a Prometheus text-exposition line for {@code metricName} and returns its trailing value, tags aside. */
    private static double extractCounterValue(String metricsBody, String metricName) {
        return Arrays.stream(metricsBody.split("\n"))
                .filter(line -> line.startsWith(metricName + "{") || line.startsWith(metricName + " "))
                .findFirst()
                .map(line -> line.substring(line.lastIndexOf(' ') + 1))
                .map(Double::parseDouble)
                .orElseThrow(() -> new AssertionError("Metric not found in /actuator/prometheus output: " + metricName));
    }

    @Test
    @DisplayName("every custom metric appears at /actuator/prometheus, correctly tagged, after one mixed-outcome request")
    void everyCustomMetricAppearsAfterOneMixedOutcomeRequest() {
        Logger forwardingLogger = (Logger) LoggerFactory.getLogger(CollectorForwardingService.class);
        ListAppender<ILoggingEvent> capturedLogEvents = new ListAppender<>();
        capturedLogEvents.start();
        forwardingLogger.addAppender(capturedLogEvents);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> inboundResponse = restTemplate.postForEntity(
                "/inbound", new HttpEntity<>(bundleEnvelope(), headers), String.class);

        forwardingLogger.detachAppender(capturedLogEvents);

        // Mixed outcome: two forwarded, one filtered, one rejected — at least one
        // forwarded entry means the request as a whole is still 202, per the
        // multi-entry failure policy.
        assertThat(inboundResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> prometheusResponse = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String metrics = prometheusResponse.getBody();

        assertThat(metrics)
                .as("events.received — one inbound request, tagged with source and path")
                .contains("tiberbu_cce_emitter_events_received_total")
                .contains("source=\"tiberbu\"")
                .contains("path=\"/inbound\"");

        assertThat(metrics)
                .as("entries.forwarded — both the accepted and duplicate entries reached the Collector")
                .contains("tiberbu_cce_emitter_entries_forwarded_total");

        assertThat(metrics)
                .as("entries.duplicate — the Collector reported one of the two forwarded entries as a duplicate")
                .contains("tiberbu_cce_emitter_entries_duplicate_total");

        assertThat(metrics)
                .as("entries.filtered — the blocked-facility entry, tagged with the denied facility and reason")
                .contains("tiberbu_cce_emitter_entries_filtered_total")
                .contains("facility=\"" + BLOCKED_FACILITY + "\"")
                .contains("reason=\"NOT_IN_ALLOWLIST\"");

        assertThat(metrics)
                .as("entries.rejected — the Collector's 400 for the fourth entry")
                .contains("tiberbu_cce_emitter_entries_rejected_total");

        assertThat(metrics)
                .as("entries.failed — the fifth entry's adaptation-stage failure, tagged with the exception's simple name")
                .contains("tiberbu_cce_emitter_entries_failed_total")
                .contains("reason=\"PatientIdNotFoundException\"");

        assertThat(metrics)
                .as("collector.latency — timed round trip for every entry actually forwarded")
                .contains("tiberbu_cce_emitter_collector_latency_seconds_count");

        assertThat(extractCounterValue(metrics, "tiberbu_cce_emitter_entries_received_total"))
                .as("entries.received — every candidate entry in the bundle, regardless of outcome (all five)")
                .isEqualTo(5.0);

        assertThat(extractCounterValue(metrics, "tiberbu_cce_emitter_entries_failed_total"))
                .as("entries.failed — exactly the one adaptation-stage failure, not the filtered or rejected entries")
                .isEqualTo(1.0);

        assertThat(metrics)
                .as("common tag — every custom metric carries application=tiberbu-cce-emitter-adaptor")
                .contains("application=\"tiberbu-cce-emitter-adaptor\"");

        assertThat(capturedLogEvents.list)
                .as("MDC fields populated during forwarding reach an actual log line")
                .anySatisfy(event -> {
                    Map<String, String> mdc = event.getMDCPropertyMap();
                    assertThat(mdc.get("correlationId")).isNotBlank();
                    assertThat(mdc.get("source")).isEqualTo("tiberbu");
                    assertThat(mdc.get("eventType")).isEqualTo("Consent");
                    assertThat(mdc.get("subject")).isEqualTo(PATIENT_ID);
                });
    }
}
