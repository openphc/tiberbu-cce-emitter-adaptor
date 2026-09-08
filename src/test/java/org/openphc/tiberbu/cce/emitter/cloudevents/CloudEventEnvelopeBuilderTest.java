package org.openphc.tiberbu.cce.emitter.cloudevents;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.exception.FhirMappingException;
import org.openphc.tiberbu.cce.emitter.model.CloudEventDto;
import org.openphc.tiberbu.cce.emitter.model.SourceMetadata;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Covers every CloudEvents attribute's mapping per api-reference.md § 3.1,
 * that {@code type} is the FHIR resourceType verbatim, and the event id
 * regression: two entries of one bundle never share an id, but the same
 * entry replayed does.
 */
class CloudEventEnvelopeBuilderTest {

    private static final String CONSENT_JSON = """
            {
              "resourceType": "Consent", "id": "VCR-20260901-57098420", "status": "active",
              "patient": {"reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"}
            }""";

    private static final OffsetDateTime FIXED_EVENT_TIME =
            OffsetDateTime.of(2026, 9, 1, 11, 55, 42, 118_000_000, ZoneOffset.UTC);

    private final CloudEventEnvelopeBuilder cloudEventEnvelopeBuilder =
            new CloudEventEnvelopeBuilder(new EventIdGenerator(), new ObjectMapper());

    private SourceMetadata sourceMetadata(String traceId, int bundleEntryIndex) {
        return new SourceMetadata("tiberbu", "FAC-0001", "7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d",
                FIXED_EVENT_TIME, "/inbound", traceId, bundleEntryIndex);
    }

    @Nested
    @DisplayName("attribute mapping")
    class AttributeMapping {

        @Test
        @DisplayName("every attribute is mapped from its documented source")
        void everyAttributeIsMappedFromItsDocumentedSource() {
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata("ef1cb56375", 1));

            assertThat(cloudEvent.specversion()).isEqualTo("1.0");
            assertThat(cloudEvent.source()).isEqualTo("tiberbu");
            assertThat(cloudEvent.type()).isEqualTo("Consent");
            assertThat(cloudEvent.subject()).isEqualTo("KE-SHRP-170CDF0A-1363-4972-B36A");
            assertThat(cloudEvent.time()).isEqualTo("2026-09-01T11:55:42.118Z");
            assertThat(cloudEvent.datacontenttype()).isEqualTo("application/fhir+json");
            assertThat(cloudEvent.facilityid()).isEqualTo("FAC-0001");
            assertThat(cloudEvent.correlationid()).isEqualTo("7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d");
            assertThat(cloudEvent.sourceeventid()).isNull();
            assertThat(cloudEvent.id()).isNotBlank();
        }

        @Test
        @DisplayName("data holds the entry's FHIR resource, verbatim")
        void dataHoldsTheEntryResourceVerbatim() throws Exception {
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata("ef1cb56375", 1));

            JSONAssert.assertEquals(CONSENT_JSON, cloudEvent.data().toString(), JSONCompareMode.STRICT);
        }

        @Test
        @DisplayName("type is the FHIR resourceType verbatim, no transformation")
        void typeIsResourceTypeVerbatim() {
            CloudEventDto observationEvent = cloudEventEnvelopeBuilder.build(
                    """
                    {"resourceType": "Observation", "id": "obs-001", "status": "final"}""",
                    "KE-SHRP-170CDF0A-1363-4972-B36A", "Observation", sourceMetadata("ef1cb56375", 2));

            assertThat(observationEvent.type()).isEqualTo("Observation");
        }

        @Test
        @DisplayName("source always comes from metadata.sourceIdentifier(), never from the payload")
        void sourceNeverComesFromThePayload() {
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata("ef1cb56375", 1));

            // CONSENT_JSON carries no "source" field at all — this asserts the value is
            // exactly the configured source identifier, not merely "not absent".
            assertThat(cloudEvent.source()).isEqualTo("tiberbu");
        }
    }

    @Nested
    @DisplayName("JSON serialization")
    class JsonSerialization {

        @Test
        @DisplayName("sourceeventid is entirely absent from the serialized JSON, not present as null")
        void sourceEventIdIsAbsentFromSerializedJson() throws Exception {
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata("ef1cb56375", 1));

            String serialized = new ObjectMapper().writeValueAsString(cloudEvent);

            assertThat(serialized).doesNotContain("sourceeventid");
        }

        @Test
        @DisplayName("every extension attribute name is lowercase")
        void extensionAttributeNamesAreLowercase() throws Exception {
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata("ef1cb56375", 1));

            String serialized = new ObjectMapper().writeValueAsString(cloudEvent);

            assertThat(serialized).contains(
                    "\"specversion\"", "\"datacontenttype\"", "\"facilityid\"", "\"correlationid\"");
        }
    }

    @Nested
    @DisplayName("event id regression — uniqueness")
    class EventIdRegression {

        @Test
        @DisplayName("the same source event replayed twice gets the same id")
        void sameSourceEventReplayedTwiceGetsSameId() {
            SourceMetadata metadata = sourceMetadata("ef1cb56375", 1);

            CloudEventDto firstDelivery = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent", metadata);
            CloudEventDto replayedDelivery = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent", metadata);

            assertThat(firstDelivery.id()).isEqualTo(replayedDelivery.id());
        }

        @Test
        @DisplayName("two entries in ONE bundle never share a CloudEvents id")
        void twoEntriesInOneBundleNeverShareAnId() {
            String observationJson = """
                    {"resourceType": "Observation", "id": "obs-001", "status": "final"}""";
            String sharedTraceId = "ef1cb56375";

            CloudEventDto consentEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata(sharedTraceId, 1));
            CloudEventDto observationEvent = cloudEventEnvelopeBuilder.build(
                    observationJson, "KE-SHRP-170CDF0A-1363-4972-B36A", "Observation",
                    sourceMetadata(sharedTraceId, 2));

            assertThat(consentEvent.id()).isNotEqualTo(observationEvent.id());
        }
    }

    @Nested
    @DisplayName("malformed FHIR JSON")
    class MalformedFhirJson {

        @Test
        @DisplayName("unparseable fhirJson throws FhirMappingException")
        void unparseableFhirJsonThrowsFhirMappingException() {
            assertThatExceptionOfType(FhirMappingException.class)
                    .isThrownBy(() -> cloudEventEnvelopeBuilder.build(
                            "not valid json", "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                            sourceMetadata("ef1cb56375", 1)));
        }
    }
}
