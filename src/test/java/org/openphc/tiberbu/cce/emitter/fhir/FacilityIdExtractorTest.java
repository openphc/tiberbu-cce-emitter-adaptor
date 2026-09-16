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
import org.hl7.fhir.r4.model.ServiceRequest;
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
 * production {@code inbound_event_log}), its {@code display}-field name
 * extraction, and the "always resolves to something, never throws" contract.
 *
 * <p>Four resource types carry an accessor {@link FacilityIdExtractor}
 * recognizes — {@code Consent.organization}, {@code Encounter.serviceProvider},
 * {@code EpisodeOfCare.managingOrganization}, {@code ServiceRequest.performer}.
 * {@code MedicationDispense} and {@code Procedure} carry a {@code location}
 * reference instead, which points at a {@code Location}, not an {@code
 * Organization} — deliberately not treated as equivalent; see {@link
 * DeliberatelyUnsupportedResourceTypes}. {@code Observation} also declares
 * {@code getPerformer()}, but real payloads populate it with {@code
 * Practitioner/} references, never {@code Organization/} — see {@link
 * ServiceRequestPerformerResolution} for the reference-type gate that keeps
 * this correctly resolving to {@code null} rather than misattributing a
 * practitioner as a facility.
 *
 * <p>Every hand-built {@link Reference} in this file below {@link
 * RealTibErbuPayload} leaves {@code display} unset on purpose, so {@link
 * #FACILITY_ID} alone identifies the expected result without repeating
 * {@code new FacilityDetails(FACILITY_ID, null)} everywhere; name extraction
 * itself is covered by {@link FacilityNameResolution} and by the real
 * payloads in {@link RealTibErbuPayload}.
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

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo(new FacilityDetails(FACILITY_ID, null));
        }

        @Test
        @DisplayName("the first populated entry in a multi-element organization list wins")
        void firstPopulatedEntryInTheListWins() {
            Consent consent = new Consent();
            consent.setOrganization(List.of(
                    new Reference("Organization/" + FACILITY_ID),
                    new Reference("Organization/should-not-be-used")));

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo(new FacilityDetails(FACILITY_ID, null));
        }

        @Test
        @DisplayName("an empty entry ahead of a populated one is skipped, not treated as the answer")
        void emptyEntryAheadOfAPopulatedOneIsSkipped() {
            Consent consent = new Consent();
            consent.setOrganization(List.of(new Reference(), new Reference("Organization/" + FACILITY_ID)));

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo(new FacilityDetails(FACILITY_ID, null));
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

            assertThat(facilityIdExtractor.extract(encounter)).isEqualTo(new FacilityDetails(FACILITY_ID, null));
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

            assertThat(facilityIdExtractor.extract(episodeOfCare)).isEqualTo(new FacilityDetails(FACILITY_ID, null));
        }

        @Test
        @DisplayName("an unpopulated EpisodeOfCare.managingOrganization resolves to null")
        void unpopulatedManagingOrganizationResolvesToNull() {
            assertThat(facilityIdExtractor.extract(new EpisodeOfCare())).isNull();
        }
    }

    @Nested
    @DisplayName("ServiceRequest.performer resolution — and the Organization-prefix gate it needs")
    class ServiceRequestPerformerResolution {

        @Test
        @DisplayName("ServiceRequest.performer[0] resolves when it is an Organization/ reference, with the prefix stripped")
        void extractsFromServiceRequestPerformerOrganizationReference() {
            ServiceRequest serviceRequest = new ServiceRequest();
            serviceRequest.setPerformer(List.of(new Reference("Organization/" + FACILITY_ID)));

            assertThat(facilityIdExtractor.extract(serviceRequest)).isEqualTo(new FacilityDetails(FACILITY_ID, null));
        }

        @Test
        @DisplayName("an unpopulated ServiceRequest.performer resolves to null")
        void unpopulatedPerformerResolvesToNull() {
            assertThat(facilityIdExtractor.extract(new ServiceRequest())).isNull();
        }

        @Test
        @DisplayName("performer[0] being a Practitioner/ reference resolves to null, never mistaken for a facility — "
                + "this is the exact real shape of Observation.performer in production, which must NOT resolve")
        void performerPractitionerReferenceResolvesToNullNotTheStrippedPractitionerId() {
            ServiceRequest serviceRequest = new ServiceRequest();
            serviceRequest.setPerformer(List.of(new Reference("Practitioner/KE-SHRPR-89552680-F575-4FD1-80F9")));

            assertThat(facilityIdExtractor.extract(serviceRequest)).isNull();
        }

        @Test
        @DisplayName("the same Practitioner/ performer shape on a real Observation — the resource type this gate actually "
                + "protects — also resolves to null, not the practitioner's own id")
        void observationPerformerPractitionerReferenceResolvesToNull() {
            Observation observation = new Observation();
            observation.setPerformer(List.of(new Reference("Practitioner/PUID-0000195-9")));

            assertThat(facilityIdExtractor.extract(observation)).isNull();
        }

        @Test
        @DisplayName("an Organization/ performer entry ahead of a Practitioner/ one wins — the first ORGANIZATION reference, "
                + "not merely the first populated entry")
        void firstOrganizationPerformerEntryWinsOverAnEarlierNonOrganizationOne() {
            ServiceRequest serviceRequest = new ServiceRequest();
            serviceRequest.setPerformer(List.of(
                    new Reference("Practitioner/KE-SHRPR-89552680-F575-4FD1-80F9"),
                    new Reference("Organization/" + FACILITY_ID)));

            assertThat(facilityIdExtractor.extract(serviceRequest)).isEqualTo(new FacilityDetails(FACILITY_ID, null));
        }
    }

    @Nested
    @DisplayName("facility display name resolution")
    class FacilityNameResolution {

        @Test
        @DisplayName("the display field on the same organization reference becomes facilityName")
        void displayFieldBecomesFacilityName() {
            Consent consent = new Consent();
            Reference organizationWithDisplay = new Reference("Organization/" + FACILITY_ID);
            organizationWithDisplay.setDisplay("Kamiriithu Health Centre");
            consent.setOrganization(List.of(organizationWithDisplay));

            assertThat(facilityIdExtractor.extract(consent))
                    .isEqualTo(new FacilityDetails(FACILITY_ID, "Kamiriithu Health Centre"));
        }

        @Test
        @DisplayName("no display field resolves to a null facilityName, not an extraction failure")
        void noDisplayFieldResolvesToNullFacilityName() {
            Consent consent = new Consent();
            consent.setOrganization(List.of(new Reference("Organization/" + FACILITY_ID)));

            assertThat(facilityIdExtractor.extract(consent))
                    .isEqualTo(new FacilityDetails(FACILITY_ID, null));
        }

        @Test
        @DisplayName("the display carried by the first POPULATED list entry wins, not the display of a skipped empty entry")
        void displayFromTheWinningListEntryIsUsed() {
            Consent consent = new Consent();
            Reference emptyEntry = new Reference();
            emptyEntry.setDisplay("should not be used");
            Reference winningEntry = new Reference("Organization/" + FACILITY_ID);
            winningEntry.setDisplay("Kamiriithu Health Centre");
            consent.setOrganization(List.of(emptyEntry, winningEntry));

            assertThat(facilityIdExtractor.extract(consent))
                    .isEqualTo(new FacilityDetails(FACILITY_ID, "Kamiriithu Health Centre"));
        }

        @Test
        @DisplayName("a display field with no resolvable ID at all is discarded, never surfaced on its own")
        void displayWithNoResolvableIdIsDiscarded() {
            Consent consent = new Consent();
            Reference displayOnly = new Reference();
            displayOnly.setDisplay("Kamiriithu Health Centre");
            consent.setOrganization(List.of(displayOnly));

            assertThat(facilityIdExtractor.extract(consent)).isNull();
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

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo(new FacilityDetails("0030", null));
        }

        @Test
        @DisplayName("a reference with no reference string falls back to identifier.value")
        void referenceWithNoReferenceStringFallsBackToIdentifierValue() {
            Consent consent = new Consent();
            Reference organizationByIdentifier = new Reference();
            organizationByIdentifier.getIdentifier().setValue(FACILITY_ID);
            consent.setOrganization(List.of(organizationByIdentifier));

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo(new FacilityDetails(FACILITY_ID, null));
        }

        @Test
        @DisplayName("a reference string that strips to blank still falls back to identifier.value, not straight to null")
        void referenceThatStripsToBlankStillFallsBackToIdentifierValue() {
            Consent consent = new Consent();
            Reference organizationWithBlankReferenceAndAnIdentifier = new Reference("Organization/");
            organizationWithBlankReferenceAndAnIdentifier.getIdentifier().setValue(FACILITY_ID);
            consent.setOrganization(List.of(organizationWithBlankReferenceAndAnIdentifier));

            assertThat(facilityIdExtractor.extract(consent)).isEqualTo(new FacilityDetails(FACILITY_ID, null));
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
         * with {@code organization}. This fixture's {@code organization[0]} also
         * carries a real {@code display}, so this doubles as the facility name
         * regression against real-world data.
         */
        @Test
        @DisplayName("the real Consent resource, parsed by FhirResourceParser, still extracts the correct facility ID and name")
        void extractsFromTheRealParsedConsentResource() {
            IBaseResource parsedConsent = fhirResourceParser.parse(loadFixture("consent.json"));

            assertThat(facilityIdExtractor.extract(parsedConsent)).isEqualTo(new FacilityDetails(
                    "KE-SHRF-D601602F-C9AC-4CC5-9347", "KAMIRITHU ST. CHARLES LWANGA CATHOLIC HEALTH CENTRE"));
        }

        /**
         * The {@code Encounter} resource from a real tibERbu triage encounter,
         * pulled from {@code inbound_event_log.raw_payload} (the {@code data}
         * field), byte-for-byte — proving {@code serviceProvider} extraction
         * against real-world noise: a {@code participant.individual} reference
         * to a {@code Practitioner}, and an {@code episodeOfCare} reference to
         * an {@code EpisodeOfCare}, neither of which this extractor must confuse
         * with {@code serviceProvider}. This fixture's {@code serviceProvider}
         * also carries a real {@code display}.
         */
        @Test
        @DisplayName("the real Encounter resource, parsed by FhirResourceParser, still extracts the correct facility ID and name")
        void extractsFromTheRealParsedEncounterResource() {
            IBaseResource parsedEncounter = fhirResourceParser.parse(loadFixture("encounter.json"));

            assertThat(facilityIdExtractor.extract(parsedEncounter)).isEqualTo(new FacilityDetails(
                    "KE-SHRF-F75DBB8A-E36C-44DE-95F4", "MBAGATHI COUNTY REFERRAL HOSPITAL"));
        }

        /**
         * The {@code EpisodeOfCare} resource from a real tibERbu inpatient
         * episode, pulled from {@code inbound_event_log.raw_payload} (the
         * {@code data} field), byte-for-byte — proving {@code
         * managingOrganization} extraction against real-world noise: a {@code
         * patient} reference and a {@code careManager} reference to a {@code
         * Practitioner}, neither of which this extractor must confuse with
         * {@code managingOrganization}. This fixture's {@code managingOrganization}
         * carries no {@code display} at all — a real, expected "ID known, name
         * unknown" case, not a fixture gap.
         */
        @Test
        @DisplayName("the real EpisodeOfCare resource, parsed by FhirResourceParser, extracts the facility ID with a null name (no display in this real payload)")
        void extractsFromTheRealParsedEpisodeOfCareResource() {
            IBaseResource parsedEpisodeOfCare = fhirResourceParser.parse(loadFixture("episode-of-care.json"));

            assertThat(facilityIdExtractor.extract(parsedEpisodeOfCare))
                    .isEqualTo(new FacilityDetails("KE-SHRF-86BD8E14-140B-4A55-8F6C", null));
        }

        /**
         * The {@code ServiceRequest} resource from a real tibERbu emergency
         * evacuation referral, byte-for-byte — proving {@code performer}
         * extraction against real-world noise: a {@code requester} reference
         * to a {@code Practitioner}, and two extensions ({@code
         * evacuation-origin} pointing at a {@code Location}, {@code
         * em-evacuation-destination} pointing at a DIFFERENT {@code
         * Organization} entirely) that this extractor must not read from —
         * only {@code performer} is consulted, not extensions. This
         * fixture's {@code performer[0]} carries no {@code display} — a
         * real "ID known, name unknown" case, same as the EpisodeOfCare
         * fixture above.
         */
        @Test
        @DisplayName("the real ServiceRequest resource, parsed by FhirResourceParser, extracts the facility ID from performer, with a null name")
        void extractsFromTheRealParsedServiceRequestResource() {
            IBaseResource parsedServiceRequest = fhirResourceParser.parse(loadFixture("service-request.json"));

            assertThat(facilityIdExtractor.extract(parsedServiceRequest))
                    .isEqualTo(new FacilityDetails("KE-SHRF-4E89A130-D458-4D7A-9C6D", null));
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
