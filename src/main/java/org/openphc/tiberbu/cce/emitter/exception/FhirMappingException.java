package org.openphc.tiberbu.cce.emitter.exception;

/**
 * Thrown when a bundle entry's FHIR resource cannot be parsed.
 *
 * <p>Mapped to {@code 422 FHIR_MAPPING_ERROR} by the global exception handler (E11).
 */
public class FhirMappingException extends RuntimeException {

    public FhirMappingException(String message) {
        super(message);
    }

    public FhirMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
