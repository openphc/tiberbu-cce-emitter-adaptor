package org.openphc.tiberbu.cce.emitter.model;

/**
 * One bundle entry's final processing outcome — the pipeline's working type,
 * not the wire type. The response body shape lives in {@link
 * ProcessedEventsResponse.EventDetail}; keeping them apart lets {@code
 * InboundEventService} still reach an entry's own attributes (for metrics and
 * MDC) without re-deriving them from the response.
 *
 * <p>Every candidate bundle entry ends up as exactly one of three outcomes:
 * <ul>
 *   <li>{@link #OUTCOME_FORWARDED} — reached the Collector; {@link #eventId}
 *       and {@link #collectorStatus} are set, {@link #reason} is {@code null}</li>
 *   <li>{@link #OUTCOME_SKIPPED} — denied by the facility filter before a
 *       CloudEvent was even built; {@link #eventId} is {@code null} (never
 *       generated), {@link #reason} explains why</li>
 *   <li>{@link #OUTCOME_FAILED} — a FHIR parsing, patient-identifier, or
 *       Collector-forwarding failure; {@link #eventId} is {@code null} unless
 *       the failure happened after a CloudEvent was already built (a
 *       forwarding failure), {@link #reason} explains why</li>
 * </ul>
 * A single bundle can freely mix all three across its entries — see {@code
 * SourceAdaptorService} and {@code InboundEventService} for how each is
 * reached.
 *
 * @param bundleEntryIndex this entry's position in {@code resource.entry[]}
 * @param resourceType     the FHIR {@code resourceType}, always known — {@code
 *                         BundleEntryExtractor} reads it from the raw JSON
 *                         independently of whether HAPI parsing later succeeds
 * @param patientSubject   the patient identifier, or {@code null} when it was
 *                         never resolved (parsing or patient-extraction itself failed)
 * @param eventId          the CloudEvents {@code id}, or {@code null} when no
 *                         CloudEvent was ever built for this entry
 * @param outcome          one of {@link #OUTCOME_FORWARDED}, {@link #OUTCOME_SKIPPED},
 *                         {@link #OUTCOME_FAILED}
 * @param collectorStatus  the Collector's reported status ({@link
 *                         #COLLECTOR_STATUS_ACCEPTED} or {@link
 *                         #COLLECTOR_STATUS_DUPLICATE}); {@code null} unless
 *                         {@code outcome} is {@link #OUTCOME_FORWARDED}
 * @param reason           why the entry was skipped or failed; {@code null}
 *                         when {@code outcome} is {@link #OUTCOME_FORWARDED}
 */
public record TransformationResult(
        int bundleEntryIndex,
        String resourceType,
        String patientSubject,
        String eventId,
        String outcome,
        String collectorStatus,
        String reason) {

    /** Reached the Collector. */
    public static final String OUTCOME_FORWARDED = "forwarded";

    /** Denied by the facility filter — a normal outcome, not an error. */
    public static final String OUTCOME_SKIPPED = "skipped";

    /** A FHIR parsing, patient-identifier, or Collector-forwarding failure. */
    public static final String OUTCOME_FAILED = "failed";

    /** The Collector ingested the event. Also the default when it reports no status. */
    public static final String COLLECTOR_STATUS_ACCEPTED = "accepted";

    /** The Collector had already ingested this event id. */
    public static final String COLLECTOR_STATUS_DUPLICATE = "duplicate";

    /**
     * @param bundleEntryIndex the forwarded entry's position in {@code resource.entry[]}
     * @param cloudEvent       the CloudEvent that was forwarded
     * @param collectorResponse the Collector's response; a missing or incomplete
     *                          {@code data.status} defaults to {@link #COLLECTOR_STATUS_ACCEPTED}
     * @return an {@link #OUTCOME_FORWARDED} result
     */
    public static TransformationResult forwarded(
            int bundleEntryIndex, CloudEventDto cloudEvent, CollectorResponse collectorResponse) {
        String collectorStatus = COLLECTOR_STATUS_ACCEPTED;
        if (collectorResponse != null && collectorResponse.data() != null && collectorResponse.data().status() != null) {
            collectorStatus = collectorResponse.data().status();
        }
        return new TransformationResult(
                bundleEntryIndex, cloudEvent.type(), cloudEvent.subject(), cloudEvent.id(),
                OUTCOME_FORWARDED, collectorStatus, null);
    }

    /**
     * @param bundleEntryIndex the skipped entry's position in {@code resource.entry[]}
     * @param resourceType     the entry's FHIR {@code resourceType}
     * @param patientSubject   the patient identifier, already resolved by the
     *                         time the facility filter runs
     * @param reason           the facility filter's own denial message
     * @return an {@link #OUTCOME_SKIPPED} result
     */
    public static TransformationResult skipped(
            int bundleEntryIndex, String resourceType, String patientSubject, String reason) {
        return new TransformationResult(bundleEntryIndex, resourceType, patientSubject, null, OUTCOME_SKIPPED, null, reason);
    }

    /**
     * @param bundleEntryIndex the failed entry's position in {@code resource.entry[]}
     * @param resourceType     the entry's FHIR {@code resourceType}
     * @param patientSubject   the patient identifier, or {@code null} when the
     *                         failure happened before it could be resolved
     * @param reason           the failure's own message
     * @return an {@link #OUTCOME_FAILED} result
     */
    public static TransformationResult failed(
            int bundleEntryIndex, String resourceType, String patientSubject, String reason) {
        return new TransformationResult(bundleEntryIndex, resourceType, patientSubject, null, OUTCOME_FAILED, null, reason);
    }
}
