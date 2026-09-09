package org.openphc.tiberbu.cce.emitter.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.openphc.tiberbu.cce.emitter.config.CollectorProperties;
import org.openphc.tiberbu.cce.emitter.exception.CollectorClientException;
import org.openphc.tiberbu.cce.emitter.exception.CollectorForwardingException;
import org.openphc.tiberbu.cce.emitter.model.CloudEventDto;
import org.openphc.tiberbu.cce.emitter.model.CollectorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

/**
 * Forwards one CloudEvent to the CCE Collector over HTTP, retrying transient
 * failures with exponential backoff.
 *
 * <p>Status handling: a 2xx response's {@code data.status} field — {@code
 * "accepted"} or {@code "duplicate"} — is read from the response BODY, never
 * inferred from the HTTP status code alone (both outcomes arrive as a 2xx).
 * A 4xx throws {@link CollectorClientException}, excluded from retry — it's a
 * problem with this specific event, retrying it would only fail the same way
 * again. A 5xx or a network failure (timeout, connection refused) throws
 * {@link CollectorForwardingException}, which IS retried.
 */
@Service
public class CollectorForwardingService {

    private static final Logger log = LoggerFactory.getLogger(CollectorForwardingService.class);

    /** What an empty (but non-error) response body is treated as — the Collector accepted the event. */
    private static final CollectorResponse DEFAULT_ACCEPTED_RESPONSE =
            new CollectorResponse(new CollectorResponse.DataPayload(null, "accepted", null, null), null);

    private final RestClient collectorRestClient;
    private final String eventsPath;
    private final Timer collectorLatencyTimer;
    private final Counter collectorRetriesExhaustedCounter;
    private final Counter eventsRejectedCounter;

    public CollectorForwardingService(
            @Qualifier("collectorRestClient") RestClient collectorRestClient,
            CollectorProperties collectorProperties,
            MeterRegistry meterRegistry) {
        this.collectorRestClient = collectorRestClient;
        this.eventsPath = collectorProperties.eventsPath();
        this.collectorLatencyTimer = Timer.builder("tiberbu.cce.emitter.collector.latency")
                .description("Collector forwarding round-trip latency")
                .register(meterRegistry);
        this.collectorRetriesExhaustedCounter = Counter.builder("tiberbu.cce.emitter.collector.retries")
                .description("Retry attempts exhausted (all retries failed)")
                .register(meterRegistry);
        this.eventsRejectedCounter = Counter.builder("tiberbu.cce.emitter.events.rejected")
                .description("Events rejected by Collector (4xx)")
                .register(meterRegistry);
    }

    /**
     * Forwards {@code cloudEvent} to the Collector, retrying on 5xx/timeout with
     * exponential backoff — attempt count and initial delay from {@code
     * cce.collector.retry.max-attempts}/{@code backoff-ms}, doubling each
     * retry.
     *
     * @param cloudEvent the CloudEvent to forward
     * @return the Collector's parsed response
     * @throws CollectorForwardingException once every retry attempt is exhausted (5xx/timeout)
     * @throws CollectorClientException immediately, never retried (4xx)
     */
    @Retryable(
            retryFor = CollectorForwardingException.class,
            noRetryFor = CollectorClientException.class,
            maxAttemptsExpression = "${cce.collector.retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${cce.collector.retry.backoff-ms:1000}",
                    multiplier = 2.0))
    public CollectorResponse forward(CloudEventDto cloudEvent) {
        log.debug("Forwarding CloudEvent id={} to Collector at {}", cloudEvent.id(), eventsPath);
        return collectorLatencyTimer.record(() -> doForward(cloudEvent));
    }

    private CollectorResponse doForward(CloudEventDto cloudEvent) {
        try {
            CollectorResponse response = collectorRestClient.post()
                    .uri(eventsPath)
                    .body(cloudEvent)
                    .retrieve()
                    .body(CollectorResponse.class);

            if (response == null) {
                response = DEFAULT_ACCEPTED_RESPONSE;
            }

            String status = response.data() != null ? response.data().status() : "accepted";
            log.info("Collector accepted CloudEvent id={} — status={}", cloudEvent.id(), status);
            return response;

        } catch (HttpClientErrorException clientError) {
            // 4xx — this specific event is the problem; retrying changes nothing.
            int statusCode = clientError.getStatusCode().value();
            String body = clientError.getResponseBodyAsString();
            log.warn("Collector rejected CloudEvent id={} — HTTP {} : {}", cloudEvent.id(), statusCode, body);
            eventsRejectedCounter.increment();
            throw new CollectorClientException("Collector returned " + statusCode + ": " + body, statusCode, clientError);

        } catch (HttpServerErrorException serverError) {
            // 5xx — transient, worth retrying.
            int statusCode = serverError.getStatusCode().value();
            log.warn("Collector server error for CloudEvent id={} — HTTP {} (will retry)", cloudEvent.id(), statusCode);
            throw new CollectorForwardingException("Collector returned " + statusCode, serverError);

        } catch (ResourceAccessException connectionFailure) {
            // Timeout / connection refused — transient, worth retrying. Also covers
            // SocketTimeoutException raised while establishing/reading the HTTP connection itself.
            log.warn("Collector unreachable for CloudEvent id={} — {} (will retry)",
                    cloudEvent.id(), connectionFailure.getMessage());
            throw new CollectorForwardingException("Collector unreachable: " + connectionFailure.getMessage(), connectionFailure);

        } catch (Exception unexpectedFailure) {
            // A SocketTimeoutException can occasionally escape ResourceAccessException's
            // wrapping when it happens while RestClient's message converters read the
            // response body, rather than in the HTTP client layer itself — still transient.
            if (unexpectedFailure instanceof SocketTimeoutException
                    || unexpectedFailure.getCause() instanceof SocketTimeoutException) {
                log.warn("Collector read timed out for CloudEvent id={} — {} (will retry)",
                        cloudEvent.id(), unexpectedFailure.getMessage());
                throw new CollectorForwardingException(
                        "Collector read timed out: " + unexpectedFailure.getMessage(), unexpectedFailure);
            }
            log.error("Unexpected error forwarding CloudEvent id={} — {}", cloudEvent.id(), unexpectedFailure.getMessage(), unexpectedFailure);
            throw new RuntimeException("Unexpected error forwarding to Collector: " + unexpectedFailure.getMessage(), unexpectedFailure);
        }
    }

    /**
     * Invoked once every retry attempt for {@link #forward} is exhausted.
     * Always re-throws — this method exists to record the exhausted-retries
     * counter at exactly the point retrying gives up, not to recover a value.
     */
    @Recover
    public CollectorResponse recover(CollectorForwardingException forwardingFailure, CloudEventDto cloudEvent) {
        log.error("All retry attempts exhausted for CloudEvent id={} — {}",
                cloudEvent != null ? cloudEvent.id() : "unknown", forwardingFailure.getMessage());
        collectorRetriesExhaustedCounter.increment();
        throw new CollectorForwardingException(
                "Collector forwarding failed after all retries: " + forwardingFailure.getMessage(), forwardingFailure);
    }

    /**
     * Spring Retry requires a {@code @Recover} method whose exception
     * parameter type matches every checked/unchecked type the {@code
     * @Retryable} method can throw when recovery might apply — this one just
     * re-throws {@link CollectorClientException} unchanged, since it was
     * never retried in the first place and needs no special handling here.
     */
    @Recover
    public CollectorResponse recover(CollectorClientException clientError, CloudEventDto cloudEvent) {
        log.warn("Non-retryable client error for CloudEvent id={} — {}",
                cloudEvent != null ? cloudEvent.id() : "unknown", clientError.getMessage());
        throw clientError;
    }
}
