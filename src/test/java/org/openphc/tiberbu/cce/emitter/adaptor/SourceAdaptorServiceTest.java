package org.openphc.tiberbu.cce.emitter.adaptor;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.cloudevents.CloudEventEnvelopeBuilder;
import org.openphc.tiberbu.cce.emitter.cloudevents.EventIdGenerator;
import org.openphc.tiberbu.cce.emitter.config.EmitterProperties;
import org.openphc.tiberbu.cce.emitter.fhir.BundleEntryExtractor;
import org.openphc.tiberbu.cce.emitter.fhir.FacilityIdExtractor;
import org.openphc.tiberbu.cce.emitter.fhir.FhirResourceParser;
import org.openphc.tiberbu.cce.emitter.fhir.PatientIdExtractor;
import org.openphc.tiberbu.cce.emitter.filter.FacilityFilter;
import org.openphc.tiberbu.cce.emitter.filter.FacilityFilterProperties;
import org.openphc.tiberbu.cce.emitter.model.BundleEntryResult;
import org.openphc.tiberbu.cce.emitter.model.InboundRequest;
import org.openphc.tiberbu.cce.emitter.model.TransformationResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link SourceAdaptorService}'s per-entry orchestration: parsing,
 * patient/facility extraction, the facility filter, and CloudEvent building —
 * using real collaborators throughout (all cheap, already unit-tested
 * individually), so this genuinely exercises the wiring between them, not
 * just SourceAdaptorService's own logic in isolation.
 *
 * <p>The central theme: entries are independent. A facility-filtered or
 * failed entry never prevents a sibling entry in the same bundle from being
 * adapted normally.
 */
class SourceAdaptorServiceTest {

    private static final String PATIENT_ID = "KE-SHRP-170CDF0A-1363-4972-B36A";
    private static final String ALLOWED_ORGANIZATION_ID = "0030";

    private SourceAdaptorService sourceAdaptorServiceWithAllowlist(String... allowedFacilityIds) {
        return new SourceAdaptorService(
                new BundleEntryExtractor(new ObjectMapper()),
                new FhirResourceParser(FhirContext.forR4()),
                new PatientIdExtractor(),
                new FacilityIdExtractor(),
                new FacilityFilter(FacilityFilterProperties.of(List.of(allowedFacilityIds)), new SimpleMeterRegistry()),
                new CloudEventEnvelopeBuilder(new EventIdGenerator(), new ObjectMapper()),
                new ObjectMapper(),
                new EmitterProperties("tiberbu"));
    }

    private InboundRequest inboundRequestWithBody(String rawBody) {
        return InboundRequest.from(rawBody, null, "/inbound");
    }

    private static String envelope(String traceId, String... bundleEntriesJson) {
        return """
                {"meta":{"bundleId":"sample-evt-001","traceId":%s},
                 "resource":{"resourceType":"Bundle","type":"transaction","entry":[%s]}}"""
                .formatted(traceId == null ? "null" : "\"" + traceId + "\"", String.join(",", bundleEntriesJson));
    }

    private static final String PATIENT_ENTRY = """
            {"resource": {"resourceType": "Patient", "id": "%s", "gender": "male"}}""".formatted(PATIENT_ID);

    private static String consentEntry(String organizationId) {
        return """
                {"resource": {
                  "resourceType": "Consent", "id": "VCR-20260901-57098420", "status": "active",
                  "patient": {"reference": "Patient/%s"},
                  "organization": [{"reference": "Organization/%s"}]
                }}""".formatted(PATIENT_ID, organizationId);
    }

    private static final String OBSERVATION_ENTRY = """
            {"resource": {"resourceType": "Observation", "id": "obs-001", "status": "final",
              "subject": {"reference": "Patient/%s"}}}""".formatted(PATIENT_ID);

    @Nested
    @DisplayName("no candidate entries")
    class NoCandidateEntries {

        @Test
        @DisplayName("a Patient-only bundle adapts to an empty list")
        void patientOnlyBundleAdaptsToEmptyList() {
            SourceAdaptorService sourceAdaptorService = sourceAdaptorServiceWithAllowlist();

            List<BundleEntryResult> bundleEntryResults = sourceAdaptorService.processBundleEntries(
                    inboundRequestWithBody(envelope("trace-001", PATIENT_ENTRY)));

            assertThat(bundleEntryResults).isEmpty();
        }

        @Test
        @DisplayName("a non-JSON body adapts to an empty list")
        void nonJsonBodyAdaptsToEmptyList() {
            SourceAdaptorService sourceAdaptorService = sourceAdaptorServiceWithAllowlist();

            List<BundleEntryResult> bundleEntryResults = sourceAdaptorService.processBundleEntries(inboundRequestWithBody("not json at all"));

            assertThat(bundleEntryResults).isEmpty();
        }
    }

    @Nested
    @DisplayName("successful adaptation")
    class SuccessfulAdaptation {

        @Test
        @DisplayName("a single candidate entry is ready to forward, with a correctly built CloudEvent")
        void singleCandidateEntryIsReadyToForward() {
            SourceAdaptorService sourceAdaptorService = sourceAdaptorServiceWithAllowlist();

            List<BundleEntryResult> bundleEntryResults = sourceAdaptorService.processBundleEntries(
                    inboundRequestWithBody(envelope("trace-001", PATIENT_ENTRY, consentEntry(ALLOWED_ORGANIZATION_ID))));

            assertThat(bundleEntryResults).hasSize(1);
            BundleEntryResult bundleEntryResult = bundleEntryResults.get(0);
            assertThat(bundleEntryResult.isReadyToForward()).isTrue();
            assertThat(bundleEntryResult.bundleEntryIndex()).isEqualTo(1);
            assertThat(bundleEntryResult.cloudEventToForward().type()).isEqualTo("Consent");
            assertThat(bundleEntryResult.cloudEventToForward().subject()).isEqualTo(PATIENT_ID);
            assertThat(bundleEntryResult.cloudEventToForward().facilityid()).isEqualTo(ALLOWED_ORGANIZATION_ID);
            assertThat(bundleEntryResult.cloudEventToForward().source()).isEqualTo("tiberbu");
        }

        @Test
        @DisplayName("meta.traceId feeds the deterministic event id — the same envelope, adapted twice, produces the same id")
        void traceIdMakesTheEventIdDeterministic() {
            SourceAdaptorService sourceAdaptorService = sourceAdaptorServiceWithAllowlist();
            String body = envelope("trace-001", PATIENT_ENTRY, consentEntry(ALLOWED_ORGANIZATION_ID));

            String firstId = sourceAdaptorService.processBundleEntries(inboundRequestWithBody(body))
                    .get(0).cloudEventToForward().id();
            String secondId = sourceAdaptorService.processBundleEntries(inboundRequestWithBody(body))
                    .get(0).cloudEventToForward().id();

            assertThat(firstId).isEqualTo(secondId);
        }

        @Test
        @DisplayName("multiple candidate entries all process independently, in bundle order")
        void multipleCandidateEntriesAdaptInBundleOrder() {
            SourceAdaptorService sourceAdaptorService = sourceAdaptorServiceWithAllowlist();

            List<BundleEntryResult> bundleEntryResults = sourceAdaptorService.processBundleEntries(inboundRequestWithBody(
                    envelope("trace-001", PATIENT_ENTRY, consentEntry(ALLOWED_ORGANIZATION_ID), OBSERVATION_ENTRY)));

            assertThat(bundleEntryResults).hasSize(2);
            assertThat(bundleEntryResults.get(0).cloudEventToForward().type()).isEqualTo("Consent");
            assertThat(bundleEntryResults.get(0).bundleEntryIndex()).isEqualTo(1);
            assertThat(bundleEntryResults.get(1).cloudEventToForward().type()).isEqualTo("Observation");
            assertThat(bundleEntryResults.get(1).bundleEntryIndex()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("facility filter denial — a normal, terminal SKIPPED outcome")
    class FacilityFilterDenial {

        @Test
        @DisplayName("a denied entry is terminal and SKIPPED, not ready to forward")
        void deniedEntryIsTerminalAndSkipped() {
            SourceAdaptorService sourceAdaptorService = sourceAdaptorServiceWithAllowlist(ALLOWED_ORGANIZATION_ID);
            String deniedOrganizationId = "9999";

            List<BundleEntryResult> bundleEntryResults = sourceAdaptorService.processBundleEntries(
                    inboundRequestWithBody(envelope("trace-001", PATIENT_ENTRY, consentEntry(deniedOrganizationId))));

            assertThat(bundleEntryResults).hasSize(1);
            BundleEntryResult bundleEntryResult = bundleEntryResults.get(0);
            assertThat(bundleEntryResult.isReadyToForward()).isFalse();
            assertThat(bundleEntryResult.terminalResult().outcome()).isEqualTo(TransformationResult.OUTCOME_SKIPPED);
            assertThat(bundleEntryResult.terminalResult().patientSubject()).isEqualTo(PATIENT_ID);
            assertThat(bundleEntryResult.terminalResult().reason()).contains(deniedOrganizationId);
            assertThat(bundleEntryResult.failureCause()).isNull();
        }

        @Test
        @DisplayName("a denied entry never prevents a sibling entry in the same bundle from adapting normally")
        void deniedEntryDoesNotAffectSiblingEntries() {
            SourceAdaptorService sourceAdaptorService = sourceAdaptorServiceWithAllowlist(ALLOWED_ORGANIZATION_ID);

            List<BundleEntryResult> bundleEntryResults = sourceAdaptorService.processBundleEntries(inboundRequestWithBody(
                    envelope("trace-001", PATIENT_ENTRY, consentEntry("9999"), OBSERVATION_ENTRY)));

            assertThat(bundleEntryResults).hasSize(2);
            assertThat(bundleEntryResults.get(0).isReadyToForward()).isFalse();
            assertThat(bundleEntryResults.get(0).terminalResult().outcome()).isEqualTo(TransformationResult.OUTCOME_SKIPPED);
            assertThat(bundleEntryResults.get(1).isReadyToForward()).isTrue();
            assertThat(bundleEntryResults.get(1).cloudEventToForward().type()).isEqualTo("Observation");
        }
    }

    @Nested
    @DisplayName("adaptation failure — a terminal FAILED outcome")
    class AdaptationFailure {

        @Test
        @DisplayName("a resource with no usable patient reference is terminal and FAILED, with patientSubject unresolved")
        void missingPatientReferenceIsTerminalAndFailed() {
            SourceAdaptorService sourceAdaptorService = sourceAdaptorServiceWithAllowlist();
            String observationWithNoSubject = """
                    {"resource": {"resourceType": "Observation", "id": "obs-002", "status": "final"}}""";

            List<BundleEntryResult> bundleEntryResults = sourceAdaptorService.processBundleEntries(
                    inboundRequestWithBody(envelope("trace-001", PATIENT_ENTRY, observationWithNoSubject)));

            assertThat(bundleEntryResults).hasSize(1);
            BundleEntryResult bundleEntryResult = bundleEntryResults.get(0);
            assertThat(bundleEntryResult.isReadyToForward()).isFalse();
            assertThat(bundleEntryResult.terminalResult().outcome()).isEqualTo(TransformationResult.OUTCOME_FAILED);
            assertThat(bundleEntryResult.terminalResult().patientSubject()).isNull();
            assertThat(bundleEntryResult.terminalResult().resourceType()).isEqualTo("Observation");
            assertThat(bundleEntryResult.failureCause()).isNotNull();
        }

        @Test
        @DisplayName("a failed entry never prevents a sibling entry in the same bundle from adapting normally")
        void failedEntryDoesNotAffectSiblingEntries() {
            SourceAdaptorService sourceAdaptorService = sourceAdaptorServiceWithAllowlist();
            String observationWithNoSubject = """
                    {"resource": {"resourceType": "Observation", "id": "obs-002", "status": "final"}}""";

            List<BundleEntryResult> bundleEntryResults = sourceAdaptorService.processBundleEntries(inboundRequestWithBody(
                    envelope("trace-001", PATIENT_ENTRY, observationWithNoSubject, consentEntry(ALLOWED_ORGANIZATION_ID))));

            assertThat(bundleEntryResults).hasSize(2);
            assertThat(bundleEntryResults.get(0).terminalResult().outcome()).isEqualTo(TransformationResult.OUTCOME_FAILED);
            assertThat(bundleEntryResults.get(1).isReadyToForward()).isTrue();
            assertThat(bundleEntryResults.get(1).cloudEventToForward().type()).isEqualTo("Consent");
        }
    }
}
