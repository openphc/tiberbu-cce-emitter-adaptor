package org.openphc.tiberbu.cce.emitter.exception;

import org.openphc.tiberbu.cce.emitter.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps every exception that reaches the controller layer to the documented
 * status code and error body (api-reference.md §4.3 onward).
 *
 * <p>Deliberately does NOT handle {@code 200 ignored} or {@code 200 skipped}
 * — those are normal outcomes {@code InboundEventService} returns directly as
 * an {@code InboundOutcome}, never thrown. Nor does it handle {@code
 * FacilityFilterRejectedException} — despite its name, {@code
 * SourceAdaptorService} always catches that one itself, per bundle entry; it
 * never escapes to this class (see that exception's own javadoc).
 *
 * <p>Every response body is {@link ErrorResponse} — {@code code}/{@code
 * message} only, never a stack trace or an exception's fully-qualified class
 * name, so nothing internal leaks to the caller.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String CODE_FHIR_MAPPING_ERROR = "FHIR_MAPPING_ERROR";
    private static final String CODE_PATIENT_ID_NOT_FOUND = "PATIENT_ID_NOT_FOUND";
    private static final String CODE_COLLECTOR_CLIENT_ERROR = "COLLECTOR_CLIENT_ERROR";
    private static final String CODE_COLLECTOR_FORWARDING_ERROR = "COLLECTOR_FORWARDING_ERROR";
    private static final String CODE_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    private static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    /** Never the real exception's own message — that could leak internal details for an unanticipated failure. */
    private static final String GENERIC_INTERNAL_ERROR_MESSAGE = "An unexpected error occurred";

    /**
     * @param fhirMappingFailure the entry's FHIR resource could not be parsed
     * @return {@code 422 FHIR_MAPPING_ERROR}
     */
    @ExceptionHandler(FhirMappingException.class)
    public ResponseEntity<ErrorResponse> handleFhirMappingException(FhirMappingException fhirMappingFailure) {
        log.warn("FHIR mapping failure: {}", fhirMappingFailure.getMessage());
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, CODE_FHIR_MAPPING_ERROR, fhirMappingFailure.getMessage());
    }

    /**
     * @param patientIdNotFound the entry's FHIR resource carried no usable patient reference
     * @return {@code 400 PATIENT_ID_NOT_FOUND}
     */
    @ExceptionHandler(PatientIdNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePatientIdNotFoundException(PatientIdNotFoundException patientIdNotFound) {
        log.warn("Patient identifier not found: {}", patientIdNotFound.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, CODE_PATIENT_ID_NOT_FOUND, patientIdNotFound.getMessage());
    }

    /**
     * @param collectorClientFailure the Collector rejected the event with a 4xx
     * @return the Collector's own status code (falls back to {@code 400} if it
     *         isn't a status code Spring recognizes), {@code
     *         COLLECTOR_CLIENT_ERROR}
     */
    @ExceptionHandler(CollectorClientException.class)
    public ResponseEntity<ErrorResponse> handleCollectorClientException(CollectorClientException collectorClientFailure) {
        HttpStatus status = HttpStatus.resolve(collectorClientFailure.getStatusCode());
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }
        log.warn("Collector rejected the event: {}", collectorClientFailure.getMessage());
        return errorResponse(status, CODE_COLLECTOR_CLIENT_ERROR, collectorClientFailure.getMessage());
    }

    /**
     * @param collectorForwardingFailure every retry attempt to the Collector was exhausted
     * @return {@code 502 COLLECTOR_FORWARDING_ERROR}
     */
    @ExceptionHandler(CollectorForwardingException.class)
    public ResponseEntity<ErrorResponse> handleCollectorForwardingException(
            CollectorForwardingException collectorForwardingFailure) {
        log.error("Collector forwarding failed after all retries: {}", collectorForwardingFailure.getMessage());
        return errorResponse(HttpStatus.BAD_GATEWAY, CODE_COLLECTOR_FORWARDING_ERROR, collectorForwardingFailure.getMessage());
    }

    /**
     * Handled explicitly so an unsupported HTTP method is never reported as a
     * {@code 500} by the catch-all below — a client error must not read as a
     * server fault.
     *
     * @param methodNotSupported the request used a method {@code /inbound} doesn't map (only {@code POST} does)
     * @return {@code 405 METHOD_NOT_ALLOWED}
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupportedException(
            HttpRequestMethodNotSupportedException methodNotSupported) {
        log.debug("Unsupported HTTP method: {}", methodNotSupported.getMessage());
        return errorResponse(HttpStatus.METHOD_NOT_ALLOWED, CODE_METHOD_NOT_ALLOWED, methodNotSupported.getMessage());
    }

    /**
     * Catch-all for anything not covered by a more specific handler above —
     * Spring dispatches to whichever {@code @ExceptionHandler} matches most
     * specifically, so this only ever receives what nothing else claimed.
     *
     * @param unexpectedFailure the uncaught exception; logged in full server-side, never echoed back
     * @return {@code 500 INTERNAL_ERROR} with a fixed, generic message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception unexpectedFailure) {
        log.error("Unexpected error", unexpectedFailure);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, CODE_INTERNAL_ERROR, GENERIC_INTERNAL_ERROR_MESSAGE);
    }

    private static ResponseEntity<ErrorResponse> errorResponse(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
