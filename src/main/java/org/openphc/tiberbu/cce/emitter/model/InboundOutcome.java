package org.openphc.tiberbu.cce.emitter.model;

import org.springframework.http.HttpStatus;

/**
 * What the service layer decided, before it becomes an HTTP response.
 *
 * <p>Keeping the outcome as a value lets the pipeline express "nothing to
 * forward" (or "some entries skipped, none failed") without throwing —
 * {@code ignored}, {@code skipped}, and {@code accepted} are all normal
 * results, not failures, and must not travel through the exception handler.
 * Only when every candidate entry in a bundle genuinely fails does {@code
 * InboundEventService} re-throw instead of returning an {@code
 * InboundOutcome} at all, letting that one case flow through the normal
 * exception-handling path.
 *
 * @param httpStatus   the status to return
 * @param responseBody the body to serialize: a {@link ProcessedEventsResponse}
 *                     for {@code 202}/{@code 200 skipped}, an {@link
 *                     AcknowledgementResponse} for {@code 200 ignored}
 */
public record InboundOutcome(HttpStatus httpStatus, Object responseBody) {

    /**
     * @param processedEvents at least one entry reached the Collector; the
     *                        body still reports every entry's own outcome,
     *                        including any that were skipped or failed
     * @return a {@code 202 Accepted} outcome
     */
    public static InboundOutcome accepted(ProcessedEventsResponse processedEvents) {
        return new InboundOutcome(HttpStatus.ACCEPTED, processedEvents);
    }

    /**
     * @param processedEvents no entry reached the Collector, but at least one
     *                        was cleanly facility-filtered — a normal
     *                        outcome, not an error
     * @return a {@code 200 OK} outcome carrying {@link
     *         ProcessedEventsResponse#STATUS_SKIPPED} and per-entry detail
     */
    public static InboundOutcome skipped(ProcessedEventsResponse processedEvents) {
        return new InboundOutcome(HttpStatus.OK, processedEvents);
    }

    /**
     * @param message why nothing was forwarded — the bundle produced no
     *                candidate entries at all
     * @return a {@code 200 OK} outcome marked {@code ignored}
     */
    public static InboundOutcome ignored(String message) {
        return new InboundOutcome(
                HttpStatus.OK,
                new AcknowledgementResponse(AcknowledgementResponse.STATUS_IGNORED, message));
    }
}
