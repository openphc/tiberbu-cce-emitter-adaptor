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
                        "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                        "Consent",
                        "KE-SHRP-170CDF0A-1363-4972-B36A",
                        TransformationResult.COLLECTOR_STATUS_ACCEPTED)));

        String expectedJson = """
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
    @DisplayName("200 skipped body matches the documented acknowledgement")
    void serializesTheSkippedAcknowledgement() throws Exception {
        InboundOutcome skippedOutcome = InboundOutcome.skipped(
                "Event skipped by facility filter: facilityId='9999' source='tiberbu'");

        JSONAssert.assertEquals(
                """
                {
                  "status": "skipped",
                  "message": "Event skipped by facility filter: facilityId='9999' source='tiberbu'"
                }""",
                objectMapper.writeValueAsString(skippedOutcome.responseBody()),
                JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("a Collector duplicate is reported verbatim in the receipt")
    void reportsDuplicateStatusFromTheCollector() throws Exception {
        ProcessedEventsResponse processedEvents = ProcessedEventsResponse.from(List.of(
                new TransformationResult("evt-1", "Consent", "KE-SHRP-001",
                        TransformationResult.COLLECTOR_STATUS_DUPLICATE)));

        JSONAssert.assertEquals(
                """
                {"status":"processed","eventsForwarded":1,"events":[
                  {"eventId":"evt-1","type":"Consent","subject":"KE-SHRP-001",
                   "collectorStatus":"duplicate"}]}""",
                objectMapper.writeValueAsString(processedEvents), JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("eventsForwarded always matches the number of events reported")
    void countAlwaysMatchesTheEventList() {
        List<TransformationResult> twoForwardedEvents = List.of(
                new TransformationResult("evt-1", "Consent", "PAT-1",
                        TransformationResult.COLLECTOR_STATUS_ACCEPTED),
                new TransformationResult("evt-2", "Observation", "PAT-1",
                        TransformationResult.COLLECTOR_STATUS_DUPLICATE));

        ProcessedEventsResponse processedEvents = ProcessedEventsResponse.from(twoForwardedEvents);

        assertThat(processedEvents.eventsForwarded()).isEqualTo(2);
        assertThat(processedEvents.events()).hasSize(2);
        assertThat(processedEvents.status()).isEqualTo("processed");
    }

    @Test
    @DisplayName("outcome factories carry the documented status codes")
    void factoriesUseTheDocumentedStatusCodes() {
        assertThat(InboundOutcome.accepted(ProcessedEventsResponse.from(List.of())).httpStatus().value())
                .isEqualTo(202);
        assertThat(InboundOutcome.ignored("x").httpStatus().value()).isEqualTo(200);
        assertThat(InboundOutcome.skipped("x").httpStatus().value()).isEqualTo(200);
    }
}
