package org.openphc.tiberbu.cce.emitter.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response body whenever at least one candidate bundle entry was attempted
 * and none of them ended in a total failure — {@link #STATUS_PROCESSED}
 * ({@code 202}) when at least one entry reached the Collector, {@link
 * #STATUS_SKIPPED} ({@code 200}) when none did but at least one was cleanly
 * facility-filtered. Both are success outcomes: a facility-filter denial and
 * a Collector-reported duplicate are themselves normal, not errors, so a
 * single request forwarding one entry and skipping another is still {@code
 * 202} — see {@link #events()} for what actually happened to each one.
 *
 * <p>One inbound bundle can produce several events, so {@code eventsForwarded}
 * counts bundle entries forwarded, not requests received. A bundle entry
 * that failed outright (FHIR parsing, missing patient identifier, or
 * Collector rejection) still appears in {@link #events()} with {@code
 * outcome: "failed"} — as long as at least one other entry succeeded or was
 * skipped, the request as a whole is still reported this way, not as an error.
 * Only when every candidate entry fails does the request instead surface as
 * an error response (the first entry's failure, re-thrown) — see {@code
 * InboundEventService}.
 *
 * @param status          {@link #STATUS_PROCESSED} or {@link #STATUS_SKIPPED}
 * @param eventsForwarded how many CloudEvents actually reached the Collector
 * @param events          every candidate entry's outcome, in bundle order —
 *                        forwarded, skipped, and failed entries all included
 */
public record ProcessedEventsResponse(String status, int eventsForwarded, List<EventDetail> events) {

    /** At least one event was forwarded to the Collector. */
    public static final String STATUS_PROCESSED = "processed";

    /** No event was forwarded, but at least one was cleanly facility-filtered (not an error). */
    public static final String STATUS_SKIPPED = "skipped";

    /**
     * The wire representation of a single bundle entry's outcome.
     *
     * <p>The JSON keys for a forwarded entry ({@code eventId}, {@code type},
     * {@code subject}, {@code collectorStatus}) are fixed by the API contract,
     * so the descriptive Java names are mapped onto the shorter wire names
     * rather than renamed to match them. {@code @JsonInclude(NON_NULL)} drops
     * whichever of {@code eventId}/{@code collectorStatus}/{@code reason}
     * doesn't apply to this entry's outcome, rather than emitting it as
     * {@code null}.
     *
     * @param entryIndex      this entry's position in {@code resource.entry[]}
     *                        — the most reliable way to identify which entry
     *                        this is when {@code type} and {@code subject}
     *                        alone don't disambiguate (e.g. two failed
     *                        entries of the same resource type)
     * @param eventId         the CloudEvents {@code id}; {@code null} unless
     *                        {@code outcome} is {@code "forwarded"}
     * @param eventType       the FHIR resource type, serialized as {@code type}
     * @param patientSubject  the patient identifier, serialized as {@code
     *                        subject}; {@code null} when it was never resolved
     * @param outcome         {@link TransformationResult#OUTCOME_FORWARDED},
     *                        {@link TransformationResult#OUTCOME_SKIPPED}, or
     *                        {@link TransformationResult#OUTCOME_FAILED}
     * @param collectorStatus the Collector's reported status; {@code null}
     *                        unless {@code outcome} is {@code "forwarded"}
     * @param reason          why the entry was skipped or failed; {@code null}
     *                        when {@code outcome} is {@code "forwarded"}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventDetail(
            int entryIndex,
            String eventId,
            @JsonProperty("type") String eventType,
            @JsonProperty("subject") String patientSubject,
            String outcome,
            String collectorStatus,
            String reason) {
    }

    /**
     * @param transformationResults every candidate entry's final outcome, in bundle order
     * @return a response whose {@code eventsForwarded} count and {@code status}
     *         are both derived from the actual outcomes, never set independently
     */
    public static ProcessedEventsResponse from(List<TransformationResult> transformationResults) {
        List<EventDetail> eventDetails = transformationResults.stream()
                .map(transformationResult -> new EventDetail(
                        transformationResult.bundleEntryIndex(),
                        transformationResult.eventId(),
                        transformationResult.resourceType(),
                        transformationResult.patientSubject(),
                        transformationResult.outcome(),
                        transformationResult.collectorStatus(),
                        transformationResult.reason()))
                .toList();

        long forwardedCount = transformationResults.stream()
                .filter(transformationResult -> TransformationResult.OUTCOME_FORWARDED.equals(transformationResult.outcome()))
                .count();
        String status = forwardedCount > 0 ? STATUS_PROCESSED : STATUS_SKIPPED;

        return new ProcessedEventsResponse(status, (int) forwardedCount, eventDetails);
    }
}
