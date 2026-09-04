package org.openphc.tiberbu.cce.emitter.model;

/**
 * Response body for the two non-forwarding outcomes, both answered with
 * {@code 200 OK}. Neither is an error and neither should be retried.
 *
 * @param status  {@link #STATUS_IGNORED} when the payload produced no events, or
 *                {@link #STATUS_SKIPPED} when the facility filter denied the event
 * @param message human-readable explanation of the outcome
 */
public record AcknowledgementResponse(String status, String message) {

    /** The payload yielded no event payloads to forward. */
    public static final String STATUS_IGNORED = "ignored";

    /** The facility filter denied the event. */
    public static final String STATUS_SKIPPED = "skipped";
}
