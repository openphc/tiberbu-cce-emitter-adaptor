package org.openphc.tiberbu.cce.emitter.fhir;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers every "ignored" scenario documented in api-reference.md § 4.2, plus
 * the positive extraction paths the E4 acceptance criteria require.
 */
class BundleEntryExtractorTest {

    private final BundleEntryExtractor bundleEntryExtractor = new BundleEntryExtractor(new ObjectMapper());

    private static final String PATIENT_ENTRY = """
            {
              "request": {"method": "PUT", "url": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"},
              "resource": {"resourceType": "Patient", "id": "KE-SHRP-170CDF0A-1363-4972-B36A", "gender": "male"}
            }""";

    private static final String CONSENT_ENTRY = """
            {
              "request": {"method": "PUT", "url": "Consent/VCR-20260901-57098420"},
              "resource": {
                "resourceType": "Consent", "id": "VCR-20260901-57098420", "status": "active",
                "patient": {"reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"}
              }
            }""";

    private static final String OBSERVATION_ENTRY = """
            {
              "request": {"method": "PUT", "url": "Observation/obs-001"},
              "resource": {"resourceType": "Observation", "id": "obs-001", "status": "final"}
            }""";

    private static String envelopeWithEntries(String... bundleEntriesJson) {
        return """
                {"meta":{"bundleId":"sample-evt-001"},"resource":{"resourceType":"Bundle","type":"transaction","entry":[%s]}}"""
                .formatted(String.join(",", bundleEntriesJson));
    }

    @Nested
    @DisplayName("scenario 1 — body is not JSON, or is empty")
    class NonJsonOrEmptyBody {

        @ParameterizedTest
        @ValueSource(strings = {"not json at all", "{", "[1, 2,", "\"unterminated"})
        void malformedJsonYieldsNoEvents(String malformedBody) {
            assertThat(bundleEntryExtractor.extract(malformedBody)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void blankBodyYieldsNoEvents(String blankBody) {
            assertThat(bundleEntryExtractor.extract(blankBody)).isEmpty();
        }

        @Test
        void nullBodyYieldsNoEvents() {
            assertThat(bundleEntryExtractor.extract(null)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"42", "\"just a string\"", "true", "null", "[1,2,3]"})
        void validButNonObjectJsonYieldsNoEvents(String nonObjectJson) {
            assertThat(bundleEntryExtractor.extract(nonObjectJson)).isEmpty();
        }
    }

    @Nested
    @DisplayName("scenario 2 — resource is absent, or is not a Bundle")
    class ResourceMissingOrWrongType {

        @Test
        void missingResourceFieldYieldsNoEvents() {
            assertThat(bundleEntryExtractor.extract("""
                    {"meta": {"bundleId": "sample-evt-001"}}""")).isEmpty();
        }

        @Test
        void resourceThatIsNotAnObjectYieldsNoEvents() {
            assertThat(bundleEntryExtractor.extract("""
                    {"meta": {}, "resource": [1, 2, 3]}""")).isEmpty();
        }

        @Test
        @DisplayName("a bare single FHIR resource (not the envelope contract) yields no events")
        void resourceTypeOtherThanBundleYieldsNoEvents() {
            assertThat(bundleEntryExtractor.extract("""
                    {"meta": {}, "resource": {"resourceType": "Patient", "id": "patient-001"}}"""))
                    .isEmpty();
        }

        @Test
        void resourceTypeIsCaseSensitiveAgainstBundle() {
            assertThat(bundleEntryExtractor.extract("""
                    {"meta": {}, "resource": {"resourceType": "bundle", "entry": [%s]}}"""
                    .formatted(PATIENT_ENTRY + "," + CONSENT_ENTRY)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("scenario 3 — entry[] is absent or empty")
    class EntriesAbsentOrEmpty {

        @Test
        void missingEntryFieldYieldsNoEvents() {
            assertThat(bundleEntryExtractor.extract("""
                    {"meta": {}, "resource": {"resourceType": "Bundle", "type": "transaction"}}"""))
                    .isEmpty();
        }

        @Test
        void emptyEntryArrayYieldsNoEvents() {
            assertThat(bundleEntryExtractor.extract(envelopeWithEntries())).isEmpty();
        }

        @Test
        void entryFieldThatIsNotAnArrayYieldsNoEvents() {
            assertThat(bundleEntryExtractor.extract("""
                    {"meta": {}, "resource": {"resourceType": "Bundle", "entry": "not-an-array"}}"""))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("scenario 4 — the bundle carries only the patient entry")
    class PatientOnlyBundle {

        @Test
        void singlePatientEntryYieldsNoEvents() {
            assertThat(bundleEntryExtractor.extract(envelopeWithEntries(PATIENT_ENTRY))).isEmpty();
        }
    }

    @Nested
    @DisplayName("scenario 5 — entries after entry[0] have no usable resource")
    class PostPatientEntriesWithNoResource {

        @Test
        @DisplayName("an entry carrying only 'request' (no 'resource') is skipped")
        void entryWithOnlyRequestIsSkipped() {
            String requestOnlyEntry = """
                    {"request": {"method": "PUT", "url": "Consent/VCR-20260901-57098420"}}""";

            assertThat(bundleEntryExtractor.extract(envelopeWithEntries(PATIENT_ENTRY, requestOnlyEntry)))
                    .isEmpty();
        }

        @Test
        @DisplayName("an entry whose resource is not a JSON object is skipped")
        void entryWhoseResourceIsNotAnObjectIsSkipped() {
            String malformedResourceEntry = """
                    {"resource": "not-an-object"}""";

            assertThat(bundleEntryExtractor.extract(envelopeWithEntries(PATIENT_ENTRY, malformedResourceEntry)))
                    .isEmpty();
        }

        @Test
        @DisplayName("an entry whose resource has no resourceType is skipped")
        void entryWhoseResourceHasNoResourceTypeIsSkipped() {
            String noResourceTypeEntry = """
                    {"resource": {"id": "mystery-001"}}""";

            assertThat(bundleEntryExtractor.extract(envelopeWithEntries(PATIENT_ENTRY, noResourceTypeEntry)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("positive extraction")
    class PositiveExtraction {

        @Test
        @DisplayName("a Patient + Consent bundle yields exactly one event")
        void consentBundleYieldsExactlyOneEvent() {
            List<BundleEntry> extractedEntries =
                    bundleEntryExtractor.extract(envelopeWithEntries(PATIENT_ENTRY, CONSENT_ENTRY));

            assertThat(extractedEntries).hasSize(1);
            assertThat(extractedEntries.get(0).resourceType()).isEqualTo("Consent");
            assertThat(extractedEntries.get(0).bundleEntryIndex()).isEqualTo(1);
        }

        @Test
        @DisplayName("a 3-entry bundle yields exactly 2 events, in bundle order")
        void threeEntryBundleYieldsTwoEventsInOrder() {
            List<BundleEntry> extractedEntries = bundleEntryExtractor.extract(
                    envelopeWithEntries(PATIENT_ENTRY, CONSENT_ENTRY, OBSERVATION_ENTRY));

            assertThat(extractedEntries).hasSize(2);
            assertThat(extractedEntries.get(0).resourceType()).isEqualTo("Consent");
            assertThat(extractedEntries.get(0).bundleEntryIndex()).isEqualTo(1);
            assertThat(extractedEntries.get(1).resourceType()).isEqualTo("Observation");
            assertThat(extractedEntries.get(1).bundleEntryIndex()).isEqualTo(2);
        }

        @Test
        @DisplayName("entry[].request is never read — resourceJson holds only the resource object")
        void requestObjectIsNeverIncludedInTheExtractedJson() throws Exception {
            List<BundleEntry> extractedEntries =
                    bundleEntryExtractor.extract(envelopeWithEntries(PATIENT_ENTRY, CONSENT_ENTRY));

            String extractedResourceJson = extractedEntries.get(0).resourceJson();

            assertThat(extractedResourceJson).doesNotContain("\"request\"", "\"method\"", "\"url\"");
            JSONAssert.assertEquals(
                    """
                    {
                      "resourceType": "Consent", "id": "VCR-20260901-57098420", "status": "active",
                      "patient": {"reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"}
                    }""",
                    extractedResourceJson, JSONCompareMode.STRICT);
        }

        @Test
        @DisplayName("entry[0] is skipped unconditionally, even when it is not a Patient")
        void entryZeroIsSkippedRegardlessOfItsResourceType() {
            List<BundleEntry> extractedEntries =
                    bundleEntryExtractor.extract(envelopeWithEntries(CONSENT_ENTRY, OBSERVATION_ENTRY));

            assertThat(extractedEntries).hasSize(1);
            assertThat(extractedEntries.get(0).resourceType()).isEqualTo("Observation");
        }

        @Test
        @DisplayName("no resource-type allowlist — an uncommon resource type is still extracted")
        void noResourceTypeAllowlistIsEnforced() {
            String medicationRequestEntry = """
                    {"resource": {"resourceType": "MedicationRequest", "id": "medreq-001", "status": "active"}}""";

            List<BundleEntry> extractedEntries =
                    bundleEntryExtractor.extract(envelopeWithEntries(PATIENT_ENTRY, medicationRequestEntry));

            assertThat(extractedEntries).hasSize(1);
            assertThat(extractedEntries.get(0).resourceType()).isEqualTo("MedicationRequest");
        }
    }

    @Nested
    @DisplayName("robustness — the extractor never throws")
    class NeverThrows {

        @ParameterizedTest
        @ValueSource(strings = {
                "{", "not json", "[]", "null", "42", "\"str\"",
                "{\"resource\":null}",
                "{\"resource\":{}}",
                "{\"resource\":{\"resourceType\":\"Bundle\"}}",
                "{\"resource\":{\"resourceType\":\"Bundle\",\"entry\":null}}",
                "{\"resource\":{\"resourceType\":\"Bundle\",\"entry\":[null,null]}}",
                "{\"resource\":{\"resourceType\":\"Bundle\",\"entry\":[{},\"oops\",42,null]}}",
        })
        void neverThrowsRegardlessOfInputShape(String hostileInput) {
            assertThatCode(() -> bundleEntryExtractor.extract(hostileInput)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an entry that itself is not a JSON object is skipped, not fatal")
        void nonObjectBundleEntryIsSkipped() {
            String hostileEnvelope = """
                    {"resource":{"resourceType":"Bundle","entry":[%s,"oops",42,null]}}"""
                    .formatted(PATIENT_ENTRY);

            assertThat(bundleEntryExtractor.extract(hostileEnvelope)).isEmpty();
        }
    }
}
