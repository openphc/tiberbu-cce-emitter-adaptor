package org.openphc.tiberbu.cce.emitter.exception;

/**
 * Thrown when forwarding a CloudEvent to the CCE Collector fails with a
 * transient condition — a 5xx response, or a network timeout/connection
 * refused. This is the retry trigger for {@code CollectorForwardingService
 * .forward}'s {@code @Retryable}; once every retry attempt is exhausted, the
 * {@code @Recover} method re-throws it and the global exception handler maps
 * it to {@code 502 COLLECTOR_FORWARDING_ERROR}.
 */
public class CollectorForwardingException extends RuntimeException {

    public CollectorForwardingException(String message) {
        super(message);
    }

    public CollectorForwardingException(String message, Throwable cause) {
        super(message, cause);
    }
}
