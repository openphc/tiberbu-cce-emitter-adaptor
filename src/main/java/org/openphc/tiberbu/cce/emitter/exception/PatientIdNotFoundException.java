package org.openphc.tiberbu.cce.emitter.exception;

/**
 * Thrown when a bundle entry's FHIR resource carries no usable patient reference.
 *
 * <p>Mapped to {@code 400 PATIENT_ID_NOT_FOUND} by the global exception handler (E11).
 */
public class PatientIdNotFoundException extends RuntimeException {

    public PatientIdNotFoundException(String message) {
        super(message);
    }
}
