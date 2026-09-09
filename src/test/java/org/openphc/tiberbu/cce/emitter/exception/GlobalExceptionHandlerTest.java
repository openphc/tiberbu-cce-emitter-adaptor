package org.openphc.tiberbu.cce.emitter.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.model.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers every {@code @ExceptionHandler} in {@link GlobalExceptionHandler} —
 * status code, error code, message, and that the {@code timestamp} field is a
 * real, parseable ISO-8601 instant. Plain unit tests: the handler has no
 * Spring-managed dependencies, so no {@code @WebMvcTest}/{@code @SpringBootTest}
 * context is needed to exercise its logic directly.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Nested
    @DisplayName("FhirMappingException")
    class FhirMapping {

        @Test
        @DisplayName("maps to 422 FHIR_MAPPING_ERROR")
        void mapsToFhirMappingError() {
            FhirMappingException fhirMappingFailure = new FhirMappingException("Failed to parse FHIR JSON: unexpected token");

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleFhirMappingException(fhirMappingFailure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error().code()).isEqualTo("FHIR_MAPPING_ERROR");
            assertThat(response.getBody().error().message()).isEqualTo("Failed to parse FHIR JSON: unexpected token");
            assertParseableTimestamp(response.getBody().timestamp());
        }
    }

    @Nested
    @DisplayName("PatientIdNotFoundException")
    class PatientIdNotFound {

        @Test
        @DisplayName("maps to 400 PATIENT_ID_NOT_FOUND")
        void mapsToPatientIdNotFound() {
            PatientIdNotFoundException patientIdNotFound =
                    new PatientIdNotFoundException("No patient reference found in Observation resource");

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handlePatientIdNotFoundException(patientIdNotFound);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().error().code()).isEqualTo("PATIENT_ID_NOT_FOUND");
            assertThat(response.getBody().error().message()).isEqualTo("No patient reference found in Observation resource");
            assertParseableTimestamp(response.getBody().timestamp());
        }
    }

    @Nested
    @DisplayName("CollectorClientException")
    class CollectorClient {

        @Test
        @DisplayName("uses the Collector's own status code, not a hardcoded one")
        void usesTheCollectorsOwnStatusCode() {
            CollectorClientException collectorClientFailure = new CollectorClientException("Collector returned 404: not found", 404);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleCollectorClientException(collectorClientFailure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().error().code()).isEqualTo("COLLECTOR_CLIENT_ERROR");
            assertThat(response.getBody().error().message()).isEqualTo("Collector returned 404: not found");
        }

        @Test
        @DisplayName("falls back to 400 when the Collector's status code isn't a recognized HTTP status")
        void fallsBackTo400ForAnUnrecognizedStatusCode() {
            CollectorClientException collectorClientFailure = new CollectorClientException("Collector returned 999: nonsense", 999);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleCollectorClientException(collectorClientFailure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().error().code()).isEqualTo("COLLECTOR_CLIENT_ERROR");
        }
    }

    @Nested
    @DisplayName("CollectorForwardingException")
    class CollectorForwarding {

        @Test
        @DisplayName("maps to 502 COLLECTOR_FORWARDING_ERROR")
        void mapsToCollectorForwardingError() {
            CollectorForwardingException collectorForwardingFailure =
                    new CollectorForwardingException("All retries exhausted for event a1b2c3d4");

            ResponseEntity<ErrorResponse> response =
                    globalExceptionHandler.handleCollectorForwardingException(collectorForwardingFailure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody().error().code()).isEqualTo("COLLECTOR_FORWARDING_ERROR");
            assertThat(response.getBody().error().message()).isEqualTo("All retries exhausted for event a1b2c3d4");
        }
    }

    @Nested
    @DisplayName("HttpRequestMethodNotSupportedException")
    class MethodNotSupported {

        @Test
        @DisplayName("maps to 405 METHOD_NOT_ALLOWED, never 500")
        void mapsTo405NotAllowed() {
            HttpRequestMethodNotSupportedException methodNotSupported = new HttpRequestMethodNotSupportedException("GET");

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleMethodNotSupportedException(methodNotSupported);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(response.getBody().error().code()).isEqualTo("METHOD_NOT_ALLOWED");
        }
    }

    @Nested
    @DisplayName("unanticipated exceptions")
    class UnexpectedFailures {

        @Test
        @DisplayName("maps to 500 INTERNAL_ERROR with a fixed message — never the real exception's own message or class name")
        void mapsToInternalErrorWithoutLeakingDetails() {
            IllegalStateException unexpectedFailure = new IllegalStateException("connection pool exhausted at 10.0.4.12:5432");

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleUnexpectedException(unexpectedFailure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.getBody().error().message())
                    .isEqualTo("An unexpected error occurred")
                    .doesNotContain("IllegalStateException", "10.0.4.12");
        }
    }

    private static void assertParseableTimestamp(String timestamp) {
        assertThatCode(() -> OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME)).doesNotThrowAnyException();
    }
}
