package org.openphc.tiberbu.cce.emitter.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.EpisodeOfCare;
import org.hl7.fhir.r4.model.MedicationDispense;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link FacilityIdExtractor}'s organization-reference resolution
 * (based on a real survey of every resource type actually seen in tibERbu's
 * production {@code inbound_event_log}), and the "always resolves to
 * something, never throws" contract.
 *
 * <p>Only three resource types carry an accessor {@link FacilityIdExtractor}
 * recognizes — {@code Consent.organization}, {@code Encounter.serviceProvider},
 * {@code EpisodeOfCare.managingOrganization}. {@code MedicationDispense} and
 * {@code Procedure} carry a {@code location} reference instead, which points
 * at a {@code Location}, not an {@code Organization} — deliberately not
 * treated as equivalent; see {@link DeliberatelyUnsupportedResourceTypes}.
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
    @DisplayName("Encounter.serviceProvider resolution")
    class EncounterServiceProviderResolution {

        @Test
        @DisplayName("Encounter.serviceProvider resolves, with the Organization/ prefix stripped")
        void extractsFromEncounterServiceProvider() {
            Encounter encounter = new Encounter();
            encounter.setServiceProvider(new Reference("Organization/" + FACILITY_ID));

            assertThat(facilityIdExtractor.extract(encounter)).isEqualTo(FACILITY_ID);
        }

        @Test
        @DisplayName("an unpopulated Encounter.serviceProvider resolves to null")
        void unpopulatedServiceProviderResolvesToNull() {
            assertThat(facilityIdExtractor.extract(new Encounter())).isNull();
        }
    }

    @Nested
    @DisplayName("EpisodeOfCare.managingOrganization resolution")
    class EpisodeOfCareManagingOrganizationResolution {

        @Test
        @DisplayName("EpisodeOfCare.managingOrganization resolves, with the Organization/ prefix stripped")
        void extractsFromEpisodeOfCareManagingOrganization() {
            EpisodeOfCare episodeOfCare = new EpisodeOfCare();
            episodeOfCare.setManagingOrganization(new Reference("Organization/" + FACILITY_ID));

            assertThat(facilityIdExtractor.extract(episodeOfCare)).isEqualTo(FACILITY_ID);
        }

        @Test
        @DisplayName("an unpopulated EpisodeOfCare.managingOrganization resolves to null")
        void unpopulatedManagingOrganizationResolvesToNull() {
            assertThat(facilityIdExtractor.extract(new EpisodeOfCare())).isNull();
        }
    }

    @Nested
    @DisplayName("resource types deliberately NOT supported, per the real production data survey")
    class DeliberatelyUnsupportedResourceTypes {

        @Test
        @DisplayName("MedicationDispense.location is a Location/ reference, not treated as a facility reference")
        void medicationDispenseLocationIsNotTreatedAsFacilityReference() {
            MedicationDispense medicationDispense = new MedicationDispense();
            medicationDispense.setLocation(new Reference("Location/some-location-id"));

            assertThat(facilityIdExtractor.extract(medicationDispense)).isNull();
        }

        @Test
        @DisplayName("Procedure.location is a Location/ reference, not treated as a facility reference")
        void procedureLocationIsNotTreatedAsFacilityReference() {
            Procedure procedure = new Procedure();
            procedure.setLocation(new Reference("Location/some-location-id"));

            assertThat(facilityIdExtractor.extract(procedure)).isNull();
        }

        @Test
        @DisplayName("AllergyIntolerance has no organization-equivalent field at all")
        void allergyIntoleranceResolvesToNull() {
            assertThat(facilityIdExtractor.extract(new AllergyIntolerance())).isNull();
        }

        @Test
        @DisplayName("Condition has no organization-equivalent field at all")
        void conditionResolvesToNull() {
            assertThat(facilityIdExtractor.extract(new Condition())).isNull();
        }

        @Test
        @DisplayName("MedicationRequest has no organization-equivalent field at all")
        void medicationRequestResolvesToNull() {
            assertThat(facilityIdExtractor.extract(new MedicationRequest())).isNull();
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
    @DisplayName("real tibERbu payloads, parsed rather than hand-built")
    class RealTibErbuPayload {

        private final FhirResourceParser fhirResourceParser = new FhirResourceParser(FhirContext.forR4());

        /**
         * The {@code Consent} resource from {@code entry[1]} of a real tibERbu
         * Verified Consent bundle, byte-for-byte — shared with {@code
         * PatientIdExtractorTest} (both load {@code fhir/consent.json} rather
         * than each carrying their own copy), proving facility extraction is
         * unaffected by the resource's full real-world noise ({@code verification},
         * {@code performer}, {@code policyRule}, {@code provision}, ...) and by the
         * {@code performer} entry that references a {@code Patient}, not an
         * {@code Organization} — a different field this extractor must not confuse
         * with {@code organization}.
         */
        @Test
        @DisplayName("the real Consent resource, parsed by FhirResourceParser, still extracts the correct facility ID")
        void extractsFromTheRealParsedConsentResource() {
            IBaseResource parsedConsent = fhirResourceParser.parse(loadFixture("consent.json"));

            assertThat(facilityIdExtractor.extract(parsedConsent))
                    .isEqualTo("KE-SHRF-D601602F-C9AC-4CC5-9347");
        }

        /**
         * The {@code Encounter} resource from a real tibERbu triage encounter,
         * pulled from {@code inbound_event_log.raw_payload} (the {@code data}
         * field), byte-for-byte — proving {@code serviceProvider} extraction
         * against real-world noise: a {@code participant.individual} reference
         * to a {@code Practitioner}, and an {@code episodeOfCare} reference to
         * an {@code EpisodeOfCare}, neither of which this extractor must confuse
         * with {@code serviceProvider}.
         */
        @Test
        @DisplayName("the real Encounter resource, parsed by FhirResourceParser, still extracts the correct facility ID")
        void extractsFromTheRealParsedEncounterResource() {
            IBaseResource parsedEncounter = fhirResourceParser.parse(loadFixture("encounter.json"));

            assertThat(facilityIdExtractor.extract(parsedEncounter))
                    .isEqualTo("KE-SHRF-F75DBB8A-E36C-44DE-95F4");
        }

        /**
         * The {@code EpisodeOfCare} resource from a real tibERbu inpatient
         * episode, pulled from {@code inbound_event_log.raw_payload} (the
         * {@code data} field), byte-for-byte — proving {@code
         * managingOrganization} extraction against real-world noise: a {@code
         * patient} reference and a {@code careManager} reference to a {@code
         * Practitioner}, neither of which this extractor must confuse with
         * {@code managingOrganization}.
         */
        @Test
        @DisplayName("the real EpisodeOfCare resource, parsed by FhirResourceParser, still extracts the correct facility ID")
        void extractsFromTheRealParsedEpisodeOfCareResource() {
            IBaseResource parsedEpisodeOfCare = fhirResourceParser.parse(loadFixture("episode-of-care.json"));

            assertThat(facilityIdExtractor.extract(parsedEpisodeOfCare))
                    .isEqualTo("KE-SHRF-86BD8E14-140B-4A55-8F6C");
        }

        /** Reads {@code src/test/resources/fhir/<fileName>} from the classpath — the fixture files themselves are shared with {@code PatientIdExtractorTest}, though each test class loads them independently. */
        private static String loadFixture(String fileName) {
            try (InputStream fixtureStream = RealTibErbuPayload.class.getClassLoader()
                    .getResourceAsStream("fhir/" + fileName)) {
                if (fixtureStream == null) {
                    throw new IllegalStateException("Fixture not found on classpath: fhir/" + fileName);
                }
                return new String(fixtureStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException fixtureReadFailure) {
                throw new UncheckedIOException(fixtureReadFailure);
            }
        }
    }
}
