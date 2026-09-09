package org.openphc.tiberbu.cce.emitter.exception;

/**
 * Thrown when the CCE Collector rejects a forwarded event with a 4xx status —
 * a client error on our side (bad payload, validation failure), never a
 * transient condition. Explicitly excluded from retry: {@code
 * @Retryable(noRetryFor = CollectorClientException.class)} on {@code
 * CollectorForwardingService.forward}.
 *
 * <p>Mapped to the Collector's own status code (default {@code 400}), error
 * code {@code COLLECTOR_CLIENT_ERROR}, by the global exception handler.
 */
public class CollectorClientException extends RuntimeException {

    private final int statusCode;

    public CollectorClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public CollectorClientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** @return the Collector's own HTTP status code (4xx) */
    public int getStatusCode() {
        return statusCode;
    }
}
