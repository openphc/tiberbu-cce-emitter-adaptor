package org.openphc.tiberbu.cce.emitter.exception;

/**
 * Thrown by {@code FacilityFilter} when an event's facility ID is not in the
 * configured allowlist.
 *
 * <p>Despite the name, this is not surfaced as an HTTP error. It is caught by
 * {@code InboundEventService} (E10) and converted into a normal {@code 200 OK}
 * with {@code status: "skipped"} — never reaches {@code GlobalExceptionHandler}
 * (E11). Callers must not retry a skip.
 *
 * <p>Example: for {@code facilityId="9999"}, {@code sourceKey="tiberbu"},
 * {@code reason="NOT_IN_ALLOWLIST"}, {@link #getMessage()} returns {@code
 * "Event skipped by facility filter: facilityId='9999' source='tiberbu'"} —
 * the exact string documented in api-reference.md § 4.1, so the service layer
 * that catches this can reuse it verbatim in the {@code 200} response body
 * rather than rebuilding it from the individual fields.
 */
public class FacilityFilterRejectedException extends RuntimeException {

    private final String facilityId;
    private final String sourceKey;
    private final String reason;

    public FacilityFilterRejectedException(String facilityId, String sourceKey, String reason) {
        super("Event skipped by facility filter: facilityId='" + facilityId + "' source='" + sourceKey + "'");
        this.facilityId = facilityId;
        this.sourceKey = sourceKey;
        this.reason = reason;
    }

    /** @return the facility ID that failed the allowlist check */
    public String getFacilityId() {
        return facilityId;
    }

    /** @return the source system key the event was attributed to */
    public String getSourceKey() {
        return sourceKey;
    }

    /** @return the short reason code (e.g. {@code "NOT_IN_ALLOWLIST"}), also used as the Micrometer counter tag */
    public String getReason() {
        return reason;
    }
}
