package org.openphc.tiberbu.cce.emitter.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response body for {@code 202 Accepted} — at least one event reached the Collector.
 *
 * <p>One inbound bundle can produce several events, so {@code eventsForwarded}
 * counts bundle entries forwarded, not requests received.
 *
 * @param status          always {@link #STATUS_PROCESSED}
 * @param eventsForwarded how many CloudEvents were forwarded from this request
 * @param events          per-event detail, in bundle order
 */
public record ProcessedEventsResponse(
        String status,
        int eventsForwarded,
        List<EventDetail> events
) {

    /** At least one event was forwarded to the Collector. */
    public static final String STATUS_PROCESSED = "processed";

    /**
     * The wire representation of a single forwarded event.
     *
     * <p>The JSON keys are fixed by the API contract, so the descriptive Java names
     * are mapped onto the shorter wire names rather than renamed to match them.
     *
     * @param eventId         the CloudEvents {@code id}
     * @param eventType       the FHIR resource type, serialized as {@code type}
     * @param patientSubject  the patient identifier, serialized as {@code subject}
     * @param collectorStatus the Collector's reported status
     */
    public record EventDetail(
            String eventId,
            @JsonProperty("type") String eventType,
            @JsonProperty("subject") String patientSubject,
            String collectorStatus
    ) {
    }

    /**
     * @param transformationResults the per-event results, in bundle order
     * @return a response whose count always matches the list it reports
     */
    public static ProcessedEventsResponse from(List<TransformationResult> transformationResults) {
        List<EventDetail> eventDetails = transformationResults.stream()
                .map(transformationResult -> new EventDetail(
                        transformationResult.eventId(),
                        transformationResult.eventType(),
                        transformationResult.patientSubject(),
                        transformationResult.collectorStatus()))
                .toList();
        return new ProcessedEventsResponse(STATUS_PROCESSED, eventDetails.size(), eventDetails);
    }
}
