package org.openphc.tiberbu.cce.emitter.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.exception.PatientIdNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers every resolution path in {@link PatientIdExtractor}: the
 * {@code subject.reference} family, the {@code patient.reference} family, prefix
 * stripping, and the failure modes documented in api-reference.md § 4.3.
 */
class PatientIdExtractorTest {

    private static final String PATIENT_IDENTIFIER = "KE-SHRP-170CDF0A-1363-4972-B36A";

    private final PatientIdExtractor patientIdExtractor = new PatientIdExtractor();

    @Nested
    @DisplayName("patient.reference family (Consent, Immunization, NutritionOrder, ...)")
    class PatientReferenceFamily {

        @Test
        @DisplayName("Consent.patient.reference resolves, with the Patient/ prefix stripped")
        void extractsFromConsentPatientReference() {
            Consent consent = new Consent();
            consent.setPatient(new Reference("Patient/" + PATIENT_IDENTIFIER));

            assertThat(patientIdExtractor.extract(consent)).isEqualTo(PATIENT_IDENTIFIER);
        }

        @Test
        @DisplayName("Immunization.patient.reference resolves — no hardcoded allowlist of resource types")
        void extractsFromImmunizationPatientReference() {
            Immunization immunization = new Immunization();
            immunization.setPatient(new Reference("Patient/" + PATIENT_IDENTIFIER));

            assertThat(patientIdExtractor.extract(immunization)).isEqualTo(PATIENT_IDENTIFIER);
        }

        @Test
        @DisplayName("NutritionOrder.patient.reference resolves too")
        void extractsFromNutritionOrderPatientReference() {
            NutritionOrder nutritionOrder = new NutritionOrder();
            nutritionOrder.setPatient(new Reference("Patient/" + PATIENT_IDENTIFIER));

            assertThat(patientIdExtractor.extract(nutritionOrder)).isEqualTo(PATIENT_IDENTIFIER);
        }
    }

    @Nested
    @DisplayName("subject.reference family (Encounter, Observation, ...)")
    class SubjectReferenceFamily {

        @Test
        @DisplayName("Encounter.subject.reference resolves, with the Patient/ prefix stripped")
        void extractsFromEncounterSubjectReference() {
            Encounter encounter = new Encounter();
            encounter.setSubject(new Reference("Patient/" + PATIENT_IDENTIFIER));

            assertThat(patientIdExtractor.extract(encounter)).isEqualTo(PATIENT_IDENTIFIER);
        }

        @Test
        @DisplayName("Observation.subject.reference resolves")
        void extractsFromObservationSubjectReference() {
            Observation observation = new Observation();
            observation.setSubject(new Reference("Patient/" + PATIENT_IDENTIFIER));

            assertThat(patientIdExtractor.extract(observation)).isEqualTo(PATIENT_IDENTIFIER);
        }
    }

    @Nested
    @DisplayName("prefix handling")
    class PrefixHandling {

        @Test
        @DisplayName("a reference with no Patient/ prefix passes through unchanged")
        void referenceWithNoPrefixPassesThroughUnchanged() {
            Consent consent = new Consent();
            consent.setPatient(new Reference("260225-0002-5501"));

            assertThat(patientIdExtractor.extract(consent)).isEqualTo("260225-0002-5501");
        }
    }

    @Nested
    @DisplayName("failure modes")
    class FailureModes {

        @Test
        @DisplayName("a null resource is rejected")
        void nullResourceIsRejected() {
            assertThatThrownBy(() -> patientIdExtractor.extract(null))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("null resource");
        }

        @Test
        @DisplayName("an unset reference (present accessor, empty value) is rejected, naming the resource type")
        void unsetReferenceIsRejected() {
            assertThatThrownBy(() -> patientIdExtractor.extract(new Encounter()))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("Encounter");
        }

        @Test
        @DisplayName("a reference that is only the 'Patient/' prefix, with nothing after it, is rejected as blank")
        void referenceThatIsOnlyThePrefixIsRejectedAsBlank() {
            Consent consent = new Consent();
            consent.setPatient(new Reference("Patient/"));

            assertThatThrownBy(() -> patientIdExtractor.extract(consent))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("blank")
                    .hasMessageContaining("Consent");
        }

        @Test
        @DisplayName("a resource with neither getSubject() nor getPatient() is rejected")
        void resourceWithNeitherAccessorIsRejected() {
            assertThatThrownBy(() -> patientIdExtractor.extract(new Organization()))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("Organization");
        }

        @Test
        @DisplayName("a Patient resource is defensively rejected, never processed as an event")
        void patientResourceIsDefensivelyRejected() {
            Patient patient = new Patient();
            patient.setId(PATIENT_IDENTIFIER);

            assertThatThrownBy(() -> patientIdExtractor.extract(patient))
                    .isInstanceOf(PatientIdNotFoundException.class)
                    .hasMessageContaining("BundleEntryExtractor");
        }
    }

    @Test
    @DisplayName("extract() accepts the plain HAPI resource type, no adaptor-specific wrapper needed")
    void acceptsAnyIBaseResource() {
        IBaseResource resource = new Consent();
        ((Consent) resource).setPatient(new Reference("Patient/" + PATIENT_IDENTIFIER));

        assertThat(patientIdExtractor.extract(resource)).isEqualTo(PATIENT_IDENTIFIER);
    }

    @Nested
    @DisplayName("a real tibERbu payload, parsed rather than hand-built")
    class RealTibErbuPayload {

        /**
         * The {@code Consent} resource from {@code entry[1]} of a real tibERbu
         * Verified Consent bundle, byte-for-byte — every other test above builds a
         * minimal {@code Consent} with only {@code patient} set, so this one proves
         * extraction still lands on the right field once HAPI parses the real
         * resource's full noise: {@code verification}, {@code scope},
         * {@code category}, a {@code performer} entry that ALSO happens to
         * reference {@code Patient/KE-SHRP-170CDF0A-1363-4972-B36A} (a different
         * field extraction must not be confused by), {@code organization},
         * {@code policyRule}, and {@code provision}.
         */
        private static final String REAL_CONSENT_RESOURCE_JSON = """
                {
                  "resourceType": "Consent",
                  "id": "VCR-20260901-57098420",
                  "meta": {
                    "profile": ["https://nshr-uat.sha.go.ke/fhir/StructureDefinition/ke-consent"],
                    "lastUpdated": "2026-09-01T12:21:18.706261+00:00",
                    "security": [{"system": "http://terminology.hl7.org/CodeSystem/v3-Confidentiality", "code": "N", "display": "Normal"}]
                  },
                  "text": {"status": "generated", "div": "<div xmlns=\\"http://www.w3.org/1999/xhtml\\">Consent to access patient records</div>"},
                  "verification": [{
                    "verified": true,
                    "extension": [{
                      "url": "https://nshr-uat.sha.go.ke/fhir/StructureDefinition/consent-verification-channel",
                      "valueCodeableConcept": {"coding": [{"system": "https://nshr-uat.sha.go.ke/fhir/CodeSystem/consent-verification-channel", "code": "otp", "display": "OTP"}]}
                    }],
                    "verificationDate": "2026-09-01T12:21:18.706268+00:00"
                  }],
                  "status": "active",
                  "scope": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/consentscope", "code": "patient-privacy", "display": "Privacy Consent"}]},
                  "category": [{"coding": [{"system": "http://loinc.org", "code": "59284-0", "display": "Patient Consent"}]}],
                  "patient": {"reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A", "display": "TIMOTHY NJIBU"},
                  "dateTime": "2026-09-01T11:55:32.412581+00:00",
                  "performer": [{"reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"}],
                  "organization": [{"reference": "Organization/KE-SHRF-D601602F-C9AC-4CC5-9347", "display": "KAMIRITHU ST. CHARLES LWANGA CATHOLIC HEALTH CENTRE"}],
                  "policyRule": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/consentpolicycodes", "code": "hipaa-auth", "display": "HIPAA Authorization"}]},
                  "provision": {
                    "type": "permit",
                    "period": {"start": "2026-09-01"},
                    "action": [
                      {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/consentaction", "code": "access", "display": "Access"}]},
                      {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/consentaction", "code": "collect", "display": "Collect"}]},
                      {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/consentaction", "code": "use", "display": "Use"}]}
                    ],
                    "purpose": [
                      {"system": "http://terminology.hl7.org/CodeSystem/v3-ActReason", "code": "TREAT", "display": "Treatment"},
                      {"system": "http://terminology.hl7.org/CodeSystem/v3-ActReason", "code": "HPAYMT", "display": "Healthcare Payment"},
                      {"system": "http://terminology.hl7.org/CodeSystem/v3-ActReason", "code": "HOPERAT", "display": "Healthcare Operations"}
                    ]
                  }
                }""";

        private final FhirResourceParser fhirResourceParser = new FhirResourceParser(FhirContext.forR4());

        @Test
        @DisplayName("the real Consent resource, parsed by FhirResourceParser, still extracts the correct patient identifier")
        void extractsFromTheRealParsedConsentResource() {
            IBaseResource parsedConsent = fhirResourceParser.parse(REAL_CONSENT_RESOURCE_JSON);

            assertThat(patientIdExtractor.extract(parsedConsent))
                    .isEqualTo("KE-SHRP-170CDF0A-1363-4972-B36A");
        }
    }
}
