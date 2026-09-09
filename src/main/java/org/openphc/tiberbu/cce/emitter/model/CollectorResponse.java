package org.openphc.tiberbu.cce.emitter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parses the CCE Collector's response body — a different shape for a
 * success/duplicate outcome than for an error.
 *
 * <p>Success (202) or duplicate (200): {@code {"data": {"eventId": "...",
 * "status": "accepted"|"duplicate", "correlationId": "...", "timestamp":
 * "..."}}}. Error (4xx): {@code {"error": {"code": "...", "message":
 * "..."}}}. {@code @JsonIgnoreProperties(ignoreUnknown = true)} at every
 * level means an unrecognized field the Collector adds later never breaks
 * deserialization.
 *
 * <p><b>Why {@link #data()}'s {@code status} matters.</b> "duplicate" is read
 * from here, this response body field — never inferred from the HTTP status
 * code alone, since both {@code accepted} and {@code duplicate} arrive as a
 * 2xx. See {@code CollectorForwardingService#doForward}.
 *
 * @param data  populated on a success/duplicate (2xx) response; {@code null} on error
 * @param error populated on an error (4xx/5xx) response; {@code null} on success
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollectorResponse(DataPayload data, ErrorPayload error) {

    /**
     * @param eventId       the Collector-assigned event identifier
     * @param status        {@code "accepted"} or {@code "duplicate"} — the
     *                      authoritative outcome, distinct from the HTTP status
     * @param correlationId the Collector's own trace correlation ID
     * @param timestamp     when the Collector processed the event
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataPayload(String eventId, String status, String correlationId, String timestamp) {
    }

    /**
     * @param code    a short error code (e.g. {@code "VALIDATION_ERROR"})
     * @param message a human-readable description
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorPayload(String code, String message) {
    }
}
