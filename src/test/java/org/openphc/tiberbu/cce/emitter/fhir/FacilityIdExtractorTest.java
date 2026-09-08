package org.openphc.tiberbu.cce.emitter.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link FacilityIdExtractor}'s organization-reference resolution
 * (based on real tibERbu data), and the "always resolves to something,
 * never throws" contract.
 */
class FacilityIdExtractorTest {

    private static final String FACILITY_ID = "KE-SHRF-D601602F-C9AC-4CC5-9347";

    private final FacilityIdExtractor facilityIdExtractor = new FacilityIdExtractor();

    @Nested
    @DisplayName("organization reference resolution")
    class OrganizationReferenceResolution {

        @Test
        @DisplayName("Consent.organization[0] resolves, with the Organization/ prefix stripped")
        void extractsFromConsentOrganizationList() {
            Consent consent = new Consent();
            consent.setOrganization(List.of(new Reference("Organization/" + FACILITY_ID)));

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo(FACILITY_ID);
        }

        @Test
        @DisplayName("the first populated entry in a multi-element organization list wins")
        void firstPopulatedEntryInTheListWins() {
            Consent consent = new Consent();
            consent.setOrganization(List.of(
                    new Reference("Organization/" + FACILITY_ID),
                    new Reference("Organization/should-not-be-used")));

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo(FACILITY_ID);
        }

        @Test
        @DisplayName("an empty entry ahead of a populated one is skipped, not treated as the answer")
        void emptyEntryAheadOfAPopulatedOneIsSkipped() {
            Consent consent = new Consent();
            consent.setOrganization(List.of(new Reference(), new Reference("Organization/" + FACILITY_ID)));

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo(FACILITY_ID);
        }
    }

    @Nested
    @DisplayName("prefix handling")
    class PrefixHandling {

        @Test
        @DisplayName("a reference with no Organization/ prefix passes through unchanged")
        void referenceWithNoPrefixPassesThroughUnchanged() {
            Consent consent = new Consent();
            consent.setOrganization(List.of(new Reference("0030")));

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo("0030");
        }

        @Test
        @DisplayName("a reference with no reference string falls back to identifier.value")
        void referenceWithNoReferenceStringFallsBackToIdentifierValue() {
            Consent consent = new Consent();
            Reference organizationByIdentifier = new Reference();
            organizationByIdentifier.getIdentifier().setValue(FACILITY_ID);
            consent.setOrganization(List.of(organizationByIdentifier));

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo(FACILITY_ID);
        }

        @Test
        @DisplayName("a reference string that strips to blank still falls back to identifier.value, not straight to null")
        void referenceThatStripsToBlankStillFallsBackToIdentifierValue() {
            Consent consent = new Consent();
            Reference organizationWithBlankReferenceAndAnIdentifier = new Reference("Organization/");
            organizationWithBlankReferenceAndAnIdentifier.getIdentifier().setValue(FACILITY_ID);
            consent.setOrganization(List.of(organizationWithBlankReferenceAndAnIdentifier));

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo(FACILITY_ID);
        }
    }

    @Nested
    @DisplayName("no organization info resolves to null, never throws")
    class NoOrganizationInfoResolvesToNull {

        @Test
        @DisplayName("a null resource resolves to null")
        void nullResourceResolvesToNull() {
            assertThat(facilityIdExtractor.extract(null)).isNull();
        }

        @Test
        @DisplayName("an empty organization list resolves to null")
        void emptyOrganizationListResolvesToNull() {
            Consent consent = new Consent();
            consent.setOrganization(List.of());

            assertThat(facilityIdExtractor.extract(consent)).isNull();
        }

        @Test
        @DisplayName("an organization list containing only an unpopulated reference resolves to null")
        void unpopulatedOrganizationReferenceResolvesToNull() {
            Consent consent = new Consent();
            consent.setOrganization(List.of(new Reference()));

            assertThat(facilityIdExtractor.extract(consent)).isNull();
        }

        @Test
        @DisplayName("a resource type with no organization field at all resolves to null")
        void resourceWithNoOrganizationFieldResolvesToNull() {
            assertThat(facilityIdExtractor.extract(new Observation())).isNull();
        }

        @Test
        @DisplayName("Patient — the entry resource, never actually reached in practice — still resolves to null safely")
        void patientResourceResolvesToNullSafely() {
            assertThat(facilityIdExtractor.extract(new Patient())).isNull();
        }
    }

    @Nested
    @DisplayName("a real tibERbu payload, parsed rather than hand-built")
    class RealTibErbuPayload {

        /**
         * The {@code Consent} resource from {@code entry[1]} of a real tibERbu
         * Verified Consent bundle, byte-for-byte — same fixture E5's
         * {@code PatientIdExtractorTest} uses, proving facility extraction is
         * unaffected by the resource's full real-world noise ({@code verification},
         * {@code performer}, {@code policyRule}, {@code provision}, ...) and by the
         * {@code performer} entry that references a {@code Patient}, not an
         * {@code Organization} — a different field this extractor must not confuse
         * with {@code organization}.
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
        @DisplayName("the real Consent resource, parsed by FhirResourceParser, still extracts the correct facility ID")
        void extractsFromTheRealParsedConsentResource() {
            IBaseResource parsedConsent = fhirResourceParser.parse(REAL_CONSENT_RESOURCE_JSON);

            assertThat(facilityIdExtractor.extract(parsedConsent))
                    .isEqualTo("KE-SHRF-D601602F-C9AC-4CC5-9347");
        }
    }
}
