package org.openphc.tiberbu.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.tiberbu.cce.emitter.config.CollectorProperties;
import org.openphc.tiberbu.cce.emitter.exception.CollectorClientException;
import org.openphc.tiberbu.cce.emitter.exception.CollectorForwardingException;
import org.openphc.tiberbu.cce.emitter.model.CloudEventDto;
import org.openphc.tiberbu.cce.emitter.model.CollectorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link CollectorForwardingService}'s response and error mapping with
 * a mocked {@link RestClient} — per-call behaviour only. It deliberately does
 * NOT exercise Spring Retry itself (a Mockito-constructed service is a plain
 * object, not the AOP-proxied bean {@code @Retryable} requires) — actual
 * retry-count behaviour against a real Collector is covered separately by a
 * WireMock-backed test built on a real Spring context.
 */
@ExtendWith(MockitoExtension.class)
class CollectorForwardingServiceTest {

    private static final String EVENTS_PATH = "/v1/events";

    @Mock
    private RestClient collectorRestClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private CollectorForwardingService collectorForwardingService;

    @BeforeEach
    void setUp() {
        CollectorProperties collectorProperties = new CollectorProperties(
                "http://localhost:5001", EVENTS_PATH, 5000,
                new CollectorProperties.RetryProperties(3, 1000L),
                new CollectorProperties.AuthProperties("test-token", null, null, null, null));

        collectorForwardingService =
                new CollectorForwardingService(collectorRestClient, collectorProperties, new SimpleMeterRegistry());
    }

    private void stubRestClientChain() {
        when(collectorRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(CloudEventDto.class))).thenReturn(requestBodySpec);
    }

    private CloudEventDto sampleEvent() {
        JsonNode data = new ObjectMapper().createObjectNode().put("resourceType", "Consent");
        return new CloudEventDto("1.0", "evt-001", "tiberbu", "Consent",
                "KE-SHRP-170CDF0A-1363-4972-B36A", "2026-09-01T11:55:42.118Z",
                "application/fhir+json", "FAC-0001", null, "corr-1", data);
    }

    @Nested
    @DisplayName("202 Accepted")
    class AcceptedResponse {

        @Test
        void parsesTheAcceptedResponseBody() {
            stubRestClientChain();
            CollectorResponse expected = new CollectorResponse(
                    new CollectorResponse.DataPayload("evt-001", "accepted", "corr-1", "2026-09-01T11:55:43Z"), null);
            when(requestBodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(CollectorResponse.class)).thenReturn(expected);

            CollectorResponse response = collectorForwardingService.forward(sampleEvent());

            assertThat(response.data()).isNotNull();
            assertThat(response.data().status()).isEqualTo("accepted");
            assertThat(response.data().eventId()).isEqualTo("evt-001");
        }

        @Test
        void postsToTheConfiguredEventsPath() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(CollectorResponse.class)).thenReturn(null);

            collectorForwardingService.forward(sampleEvent());

            verify(requestBodyUriSpec).uri(EVENTS_PATH);
        }

        @Test
        void sendsTheCloudEventAsTheRequestBody() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(CollectorResponse.class)).thenReturn(null);

            collectorForwardingService.forward(sampleEvent());

            verify(requestBodySpec).body(any(CloudEventDto.class));
        }

        @Test
        @DisplayName("a null response body is treated as an implicit accept")
        void nullResponseBodyIsTreatedAsAccepted() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(CollectorResponse.class)).thenReturn(null);

            CollectorResponse response = collectorForwardingService.forward(sampleEvent());

            assertThat(response.data()).isNotNull();
            assertThat(response.data().status()).isEqualTo("accepted");
        }
    }

    @Nested
    @DisplayName("200 Duplicate")
    class DuplicateResponse {

        @Test
        @DisplayName("\"duplicate\" is read from the response body, not inferred from the HTTP status")
        void duplicateStatusIsReadFromTheResponseBody() {
            stubRestClientChain();
            CollectorResponse expected = new CollectorResponse(
                    new CollectorResponse.DataPayload("evt-001", "duplicate", null, null), null);
            when(requestBodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(CollectorResponse.class)).thenReturn(expected);

            CollectorResponse response = collectorForwardingService.forward(sampleEvent());

            assertThat(response.data().status()).isEqualTo("duplicate");
        }
    }

    @Nested
    @DisplayName("4xx client error — never retried")
    class ClientErrorResponse {

        @Test
        void status400ThrowsCollectorClientExceptionWithTheStatusCode() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenThrow(HttpClientErrorException.create(
                    HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                    "{\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"type is required\"}}"
                            .getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8));

            assertThatThrownBy(() -> collectorForwardingService.forward(sampleEvent()))
                    .isInstanceOf(CollectorClientException.class)
                    .satisfies(ex -> assertThat(((CollectorClientException) ex).getStatusCode()).isEqualTo(400));
        }

        @Test
        void status422ThrowsCollectorClientExceptionWithTheStatusCode() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenThrow(HttpClientErrorException.create(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable", HttpHeaders.EMPTY,
                    "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

            assertThatThrownBy(() -> collectorForwardingService.forward(sampleEvent()))
                    .isInstanceOf(CollectorClientException.class)
                    .satisfies(ex -> assertThat(((CollectorClientException) ex).getStatusCode()).isEqualTo(422));
        }
    }

    @Nested
    @DisplayName("5xx server error — retryable")
    class ServerErrorResponse {

        @Test
        void status503ThrowsCollectorForwardingException() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenThrow(HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", HttpHeaders.EMPTY,
                    "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

            assertThatThrownBy(() -> collectorForwardingService.forward(sampleEvent()))
                    .isInstanceOf(CollectorForwardingException.class)
                    .hasMessageContaining("503");
        }

        @Test
        void status500ThrowsCollectorForwardingException() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenThrow(HttpServerErrorException.create(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", HttpHeaders.EMPTY,
                    "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

            assertThatThrownBy(() -> collectorForwardingService.forward(sampleEvent()))
                    .isInstanceOf(CollectorForwardingException.class)
                    .hasMessageContaining("500");
        }
    }

    @Nested
    @DisplayName("network failure — retryable")
    class NetworkFailure {

        @Test
        void connectionRefusedThrowsCollectorForwardingException() {
            stubRestClientChain();
            when(requestBodySpec.retrieve()).thenThrow(
                    new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

            assertThatThrownBy(() -> collectorForwardingService.forward(sampleEvent()))
                    .isInstanceOf(CollectorForwardingException.class)
                    .hasMessageContaining("unreachable");
        }
    }

    @Nested
    @DisplayName("recovery methods")
    class Recovery {

        @Test
        void forwardingRecoveryReThrowsAfterAllRetriesExhausted() {
            CollectorForwardingException exhausted = new CollectorForwardingException("Collector returned 503");

            assertThatThrownBy(() -> collectorForwardingService.recover(exhausted, sampleEvent()))
                    .isInstanceOf(CollectorForwardingException.class)
                    .hasMessageContaining("all retries");
        }

        @Test
        void forwardingRecoveryHandlesANullEventWithoutThrowingANullPointerException() {
            CollectorForwardingException exhausted = new CollectorForwardingException("timeout");

            assertThatThrownBy(() -> collectorForwardingService.recover(exhausted, null))
                    .isInstanceOf(CollectorForwardingException.class);
        }

        @Test
        void clientErrorRecoveryReThrowsTheOriginalException() {
            CollectorClientException original = new CollectorClientException("Collector returned 400", 400);

            assertThatThrownBy(() -> collectorForwardingService.recover(original, sampleEvent()))
                    .isSameAs(original);
        }
    }
}
