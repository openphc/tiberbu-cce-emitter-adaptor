package org.openphc.tiberbu.cce.emitter.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.tiberbu.cce.emitter.exception.FacilityFilterRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Enforces the facility allowlist configured in {@link FacilityFilterProperties}.
 *
 * <p>All the admission logic — inactive-filter passthrough, null/blank facility
 * passthrough, and the case-insensitive allowlist lookup itself — already
 * lives in {@link FacilityFilterProperties#isFacilityAllowed(String)}. This
 * class adds exactly one thing on top: what happens on denial — incrementing
 * a Micrometer counter and throwing {@link FacilityFilterRejectedException},
 * which the caller ({@code InboundEventService}, E10) catches and turns into
 * a normal {@code 200 OK} with {@code status: "skipped"}. A denial here is
 * never an error; it is a normal, expected outcome for an out-of-allowlist
 * facility.
 *
 * <p>Example — allowlist {@code {"0030"}}, event facility {@code "9999"}:
 * {@code enforceFilter("9999", "tiberbu")} increments {@code
 * tiberbu.cce.emitter.events.filtered} (tags {@code source=tiberbu},
 * {@code facility=9999}, {@code reason=NOT_IN_ALLOWLIST}) and throws {@link
 * FacilityFilterRejectedException}. The same allowlist with facility {@code
 * "0030"}, or a {@code null} facility (no facility context on the resource,
 * e.g. {@code Patient}), returns normally — nothing is counted.
 */
@Component
public class FacilityFilter {

    private static final Logger log = LoggerFactory.getLogger(FacilityFilter.class);

    private static final String FILTERED_EVENTS_COUNTER_NAME = "tiberbu.cce.emitter.events.filtered";
    private static final String NOT_IN_ALLOWLIST_REASON = "NOT_IN_ALLOWLIST";

    private final FacilityFilterProperties facilityFilterProperties;
    private final MeterRegistry meterRegistry;

    public FacilityFilter(FacilityFilterProperties facilityFilterProperties, MeterRegistry meterRegistry) {
        this.facilityFilterProperties = facilityFilterProperties;
        this.meterRegistry = meterRegistry;

        if (facilityFilterProperties.isActive()) {
            log.info("Facility filter: active — {} facility id(s) configured", facilityFilterProperties.ids().size());
        } else {
            log.info("Facility filter: inactive — no ids configured, all events pass through");
        }
    }

    /**
     * Admits or rejects one event based on its resolved facility ID.
     *
     * <p>Step 1: delegate the admission decision itself to {@link
     * FacilityFilterProperties#isFacilityAllowed(String)} — it already covers
     * every passthrough case (inactive filter, null/blank facility,
     * allowlisted facility). When it returns {@code true}, this method
     * simply returns.
     *
     * <p>Step 2: when it returns {@code false} — the filter is active AND a
     * non-blank facility ID was resolved AND that ID is not on the allowlist —
     * record the denial and throw.
     *
     * @param facilityId the resolved facility ID for this event; may be {@code null}
     * @param sourceKey  the configured source system (e.g. {@code "tiberbu"}),
     *                   used only as a metric tag and in the exception
     * @throws FacilityFilterRejectedException when the facility ID is resolved
     *                                          but not in the allowlist
     */
    public void enforceFilter(String facilityId, String sourceKey) {
        if (facilityFilterProperties.isFacilityAllowed(facilityId)) {
            return;
        }

        Counter.builder(FILTERED_EVENTS_COUNTER_NAME)
                .tag("source", sourceKey)
                .tag("facility", facilityId)
                .tag("reason", NOT_IN_ALLOWLIST_REASON)
                .register(meterRegistry)
                .increment();

        log.debug("Facility filter denied facilityId='{}' source='{}' reason={}",
                facilityId, sourceKey, NOT_IN_ALLOWLIST_REASON);
        throw new FacilityFilterRejectedException(facilityId, sourceKey, NOT_IN_ALLOWLIST_REASON);
    }
}
