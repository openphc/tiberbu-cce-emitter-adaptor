package org.openphc.tiberbu.cce.emitter.model;

import org.springframework.http.HttpStatus;

/**
 * What the service layer decided, before it becomes an HTTP response.
 *
 * <p>Keeping the outcome as a value lets the pipeline express "nothing to
 * forward" without throwing — {@code ignored} and {@code skipped} are normal
 * results, not failures, and must not travel through the exception handler.
 *
 * @param httpStatus   the status to return
 * @param responseBody the body to serialize: a {@link ProcessedEventsResponse}
 *                     for {@code 202}, an {@link AcknowledgementResponse} for {@code 200}
 */
public record InboundOutcome(HttpStatus httpStatus, Object responseBody) {

    /**
     * @param processedEvents the events that reached the Collector
     * @return a {@code 202 Accepted} outcome
     */
    public static InboundOutcome accepted(ProcessedEventsResponse processedEvents) {
        return new InboundOutcome(HttpStatus.ACCEPTED, processedEvents);
    }

    /**
     * @param message why nothing was forwarded
     * @return a {@code 200 OK} outcome marked {@code ignored}
     */
    public static InboundOutcome ignored(String message) {
        return new InboundOutcome(
                HttpStatus.OK,
                new AcknowledgementResponse(AcknowledgementResponse.STATUS_IGNORED, message));
    }

    /**
     * @param message which facility was denied, and by which source
     * @return a {@code 200 OK} outcome marked {@code skipped}
     */
    public static InboundOutcome skipped(String message) {
        return new InboundOutcome(
                HttpStatus.OK,
                new AcknowledgementResponse(AcknowledgementResponse.STATUS_SKIPPED, message));
    }
}
