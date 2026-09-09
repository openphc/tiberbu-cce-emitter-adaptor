package org.openphc.tiberbu.cce.emitter.model;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Wire shape for every error response {@code GlobalExceptionHandler} produces
 * — {@code {"error": {"code", "message"}, "timestamp"}}, per api-reference.md
 * §4.3 onward. Never used for the {@code 200 ignored}/{@code 200 skipped}
 * outcomes; those are {@link AcknowledgementResponse}/{@link
 * ProcessedEventsResponse} instead, returned directly by {@code
 * InboundEventService}, not through this exception-handling path.
 *
 * @param error     the code/message pair
 * @param timestamp when this error response was built, ISO-8601 UTC
 */
public record ErrorResponse(ErrorDetail error, String timestamp) {

    /**
     * @param code    a short, stable machine-readable code (e.g. {@code
     *                "PATIENT_ID_NOT_FOUND"}) — never the raw exception class name
     * @param message a human-readable description, safe to expose to a caller
     */
    public record ErrorDetail(String code, String message) {
    }

    /**
     * @param code    the error code
     * @param message the error message
     * @return a new {@link ErrorResponse}, timestamped at the moment of the call
     */
    public static ErrorResponse of(String code, String message) {
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new ErrorResponse(new ErrorDetail(code, message), timestamp);
    }
}
