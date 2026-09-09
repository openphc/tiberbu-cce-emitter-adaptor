package org.openphc.tiberbu.cce.emitter.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire format against the examples in docs/api-reference.md § 2.
 */
class ResponseContractSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("202 body matches the documented processed receipt")
    void serializesTheProcessedReceipt() throws Exception {
        ProcessedEventsResponse processedEvents = ProcessedEventsResponse.from(List.of(
                new TransformationResult(
                        1, "Consent", "KE-SHRP-170CDF0A-1363-4972-B36A",
                        "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                        TransformationResult.OUTCOME_FORWARDED, TransformationResult.COLLECTOR_STATUS_ACCEPTED, null)));

        String expectedJson = """
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
                }""";

        JSONAssert.assertEquals(expectedJson,
                objectMapper.writeValueAsString(processedEvents), JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("200 ignored body matches the documented acknowledgement")
    void serializesTheIgnoredAcknowledgement() throws Exception {
        InboundOutcome ignoredOutcome = InboundOutcome.ignored("Non-processable payload");

        JSONAssert.assertEquals(
                """
                {"status": "ignored", "message": "Non-processable payload"}""",
                objectMapper.writeValueAsString(ignoredOutcome.responseBody()),
                JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("200 skipped body matches the documented per-entry receipt")
    void serializesTheSkippedReceipt() throws Exception {
        String filterDenialReason = "Event skipped by facility filter: facilityId='9999' source='tiberbu'";
        ProcessedEventsResponse skippedEvents = ProcessedEventsResponse.from(List.of(
                TransformationResult.skipped(1, "Consent", "KE-SHRP-170CDF0A-1363-4972-B36A", filterDenialReason)));
        InboundOutcome skippedOutcome = InboundOutcome.skipped(skippedEvents);

        JSONAssert.assertEquals(
                """
                {
                  "status": "skipped",
                  "eventsForwarded": 0,
                  "events": [
                    {
                      "entryIndex": 1,
                      "type": "Consent",
                      "subject": "KE-SHRP-170CDF0A-1363-4972-B36A",
                      "outcome": "skipped",
                      "reason": "%s"
                    }
                  ]
                }""".formatted(filterDenialReason),
                objectMapper.writeValueAsString(skippedOutcome.responseBody()),
                JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("a Collector duplicate is reported verbatim in the receipt")
    void reportsDuplicateStatusFromTheCollector() throws Exception {
        ProcessedEventsResponse processedEvents = ProcessedEventsResponse.from(List.of(
                new TransformationResult(1, "Consent", "KE-SHRP-001", "evt-1",
                        TransformationResult.OUTCOME_FORWARDED, TransformationResult.COLLECTOR_STATUS_DUPLICATE, null)));

        JSONAssert.assertEquals(
                """
                {"status":"processed","eventsForwarded":1,"events":[
                  {"entryIndex":1,"eventId":"evt-1","type":"Consent","subject":"KE-SHRP-001",
                   "outcome":"forwarded","collectorStatus":"duplicate"}]}""",
                objectMapper.writeValueAsString(processedEvents), JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("eventsForwarded always matches the number of forwarded events, even alongside a failed entry")
    void countAlwaysMatchesForwardedEventsOnly() {
        List<TransformationResult> mixedResults = List.of(
                new TransformationResult(1, "Consent", "PAT-1", "evt-1",
                        TransformationResult.OUTCOME_FORWARDED, TransformationResult.COLLECTOR_STATUS_ACCEPTED, null),
                new TransformationResult(2, "Observation", "PAT-1", "evt-2",
                        TransformationResult.OUTCOME_FORWARDED, TransformationResult.COLLECTOR_STATUS_DUPLICATE, null),
                TransformationResult.failed(3, "Condition", "PAT-1", "missing required field"));

        ProcessedEventsResponse processedEvents = ProcessedEventsResponse.from(mixedResults);

        assertThat(processedEvents.eventsForwarded()).isEqualTo(2);
        assertThat(processedEvents.events()).hasSize(3);
        assertThat(processedEvents.status()).isEqualTo("processed");
    }

    @Test
    @DisplayName("outcome factories carry the documented status codes")
    void factoriesUseTheDocumentedStatusCodes() {
        assertThat(InboundOutcome.accepted(ProcessedEventsResponse.from(List.of())).httpStatus().value())
                .isEqualTo(202);
        assertThat(InboundOutcome.ignored("x").httpStatus().value()).isEqualTo(200);
        assertThat(InboundOutcome.skipped(ProcessedEventsResponse.from(List.of())).httpStatus().value())
                .isEqualTo(200);
    }
}
