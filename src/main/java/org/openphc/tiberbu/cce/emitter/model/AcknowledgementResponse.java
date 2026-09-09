package org.openphc.tiberbu.cce.emitter.model;

/**
 * Response body for the {@code 200 OK, status: "ignored"} outcome — the
 * bundle produced no candidate entries at all (not JSON, not a Bundle, empty
 * {@code entry[]}, or every entry confirmed to be a Patient). Not an error,
 * and never retried.
 *
 * <p>A facility-filter denial is <em>not</em> represented by this type — see
 * {@link ProcessedEventsResponse#STATUS_SKIPPED} instead, which (unlike this
 * simple message-only body) can report per-entry detail alongside it.
 *
 * @param status  always {@link #STATUS_IGNORED}
 * @param message human-readable explanation of why nothing was forwarded
 */
public record AcknowledgementResponse(String status, String message) {

    /** The payload yielded no candidate event payloads to forward. */
    public static final String STATUS_IGNORED = "ignored";
}
