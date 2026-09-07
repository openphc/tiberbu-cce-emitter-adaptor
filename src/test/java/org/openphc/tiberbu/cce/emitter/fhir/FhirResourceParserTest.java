package org.openphc.tiberbu.cce.emitter.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openphc.tiberbu.cce.emitter.exception.FhirMappingException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FhirResourceParserTest {

    private final FhirResourceParser fhirResourceParser = new FhirResourceParser(FhirContext.forR4());

    @Test
    @DisplayName("parses a Consent resource")
    void parsesAConsentResource() {
        String consentJson = """
                {
                  "resourceType": "Consent", "id": "VCR-20260901-57098420", "status": "active",
                  "patient": {"reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"}
                }""";

        IBaseResource parsedResource = fhirResourceParser.parse(consentJson);

        assertThat(parsedResource).isInstanceOf(Consent.class);
        assertThat(parsedResource.fhirType()).isEqualTo("Consent");
        assertThat(((Consent) parsedResource).getIdPart()).isEqualTo("VCR-20260901-57098420");
    }

    @Test
    @DisplayName("parses an Observation resource")
    void parsesAnObservationResource() {
        IBaseResource parsedResource = fhirResourceParser.parse("""
                {"resourceType": "Observation", "id": "obs-001", "status": "final"}""");

        assertThat(parsedResource).isInstanceOf(Observation.class);
        assertThat(((Observation) parsedResource).getStatus().toCode()).isEqualTo("final");
    }

    @Test
    @DisplayName("parses a Patient resource")
    void parsesAPatientResource() {
        IBaseResource parsedResource = fhirResourceParser.parse("""
                {"resourceType": "Patient", "id": "KE-SHRP-170CDF0A-1363-4972-B36A", "gender": "male"}""");

        assertThat(parsedResource).isInstanceOf(Patient.class);
        assertThat(((Patient) parsedResource).getGender().toCode()).isEqualTo("male");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank input is rejected without reaching the HAPI parser")
    void blankInputThrowsFhirMappingException(String blankJson) {
        assertThatThrownBy(() -> fhirResourceParser.parse(blankJson))
                .isInstanceOf(FhirMappingException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    @DisplayName("null input is rejected without reaching the HAPI parser")
    void nullInputThrowsFhirMappingException() {
        assertThatThrownBy(() -> fhirResourceParser.parse(null))
                .isInstanceOf(FhirMappingException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    @DisplayName("malformed JSON is wrapped as FhirMappingException, not a raw HAPI exception")
    void malformedJsonThrowsFhirMappingException() {
        assertThatThrownBy(() -> fhirResourceParser.parse("not json at all"))
                .isInstanceOf(FhirMappingException.class)
                .hasMessageContaining("Failed to parse FHIR resource JSON");
    }

    @Test
    @DisplayName("valid JSON with no resourceType is wrapped as FhirMappingException")
    void jsonWithNoResourceTypeThrowsFhirMappingException() {
        assertThatThrownBy(() -> fhirResourceParser.parse("""
                {"id": "mystery-001"}"""))
                .isInstanceOf(FhirMappingException.class);
    }

    @Test
    @DisplayName("an unknown resourceType is wrapped as FhirMappingException")
    void unknownResourceTypeThrowsFhirMappingException() {
        assertThatThrownBy(() -> fhirResourceParser.parse("""
                {"resourceType": "NotARealFhirResource"}"""))
                .isInstanceOf(FhirMappingException.class);
    }
}
