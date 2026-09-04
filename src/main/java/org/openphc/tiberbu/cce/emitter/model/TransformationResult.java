package org.openphc.tiberbu.cce.emitter.model;

/**
 * Internal pairing of a forwarded event with what the Collector said about it.
 *
 * <p>This is the pipeline's working type, not the wire type — the response body
 * shape lives in {@link ProcessedEventsResponse.EventDetail}. Keeping them apart
 * lets {@code InboundEventService} still reach the event's own attributes after
 * forwarding (for metrics and MDC) without re-deriving them from the response.
 *
 * <p>In sub-task E8 the three identity fields collapse into the {@code CloudEventDto}
 * itself, together with a {@code from(cloudEvent, collectorResponse)} factory that
 * defaults a missing Collector status to {@link #COLLECTOR_STATUS_ACCEPTED}.
 *
 * @param eventId         the CloudEvents {@code id} that was forwarded
 * @param eventType       the FHIR resource type carried as the CloudEvents {@code type}
 * @param patientSubject  the patient identifier carried as the CloudEvents {@code subject}
 * @param collectorStatus the Collector's reported status, passed through verbatim from
 *                         {@code data.status}. {@link #COLLECTOR_STATUS_ACCEPTED} and
 *                         {@link #COLLECTOR_STATUS_DUPLICATE} are today's known values, not
 *                         an exhaustive set — a future third value requires no code change here
 */
public record TransformationResult(
        String eventId,
        String eventType,
        String patientSubject,
        String collectorStatus
) {

    /** The Collector ingested the event. Also the default when it reports no status. */
    public static final String COLLECTOR_STATUS_ACCEPTED = "accepted";

    /** The Collector had already ingested this event id. */
    public static final String COLLECTOR_STATUS_DUPLICATE = "duplicate";
}
