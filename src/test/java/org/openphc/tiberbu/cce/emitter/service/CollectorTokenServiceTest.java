package org.openphc.tiberbu.cce.emitter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.config.CollectorProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Covers the three auth modes {@link CollectorTokenService} resolves between,
 * and OAuth2 token caching/error handling.
 *
 * <p>The service constructs its own {@link HttpClient} internally, so the
 * Keycloak HTTP call is exercised by replacing that field with a Mockito mock
 * via {@link ReflectionTestUtils} — no real network call, no WireMock needed
 * for this class. A real {@link ObjectMapper} is used so the {@code
 * TokenResponse} JSON binding (snake_case Keycloak fields → the record) is
 * genuinely verified, not just mocked away.
 */
class CollectorTokenServiceTest {

    private static final String TOKEN_JSON =
            "{\"access_token\":\"abc-123\",\"expires_in\":300,\"token_type\":\"Bearer\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
    }

    private CollectorTokenService collectorTokenServiceWith(CollectorProperties.AuthProperties auth) {
        CollectorProperties collectorProperties =
                new CollectorProperties("http://collector:5001", "/v1/events", 2000, null, auth);
        CollectorTokenService collectorTokenService = new CollectorTokenService(collectorProperties, objectMapper);
        ReflectionTestUtils.setField(collectorTokenService, "httpClient", httpClient);
        return collectorTokenService;
    }

    private static CollectorProperties.AuthProperties oAuth2Auth() {
        return new CollectorProperties.AuthProperties(
                null, "https://kc.example.org", "cce", "emitter-client", "s3cret");
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> keycloakResponse(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        lenient().when(response.statusCode()).thenReturn(status);
        lenient().when(response.body()).thenReturn(body);
        return response;
    }

    @Nested
    class StaticAndNoAuthModes {

        @Test
        void nullAuthBlockResolvesToNullToken() {
            CollectorTokenService collectorTokenService = collectorTokenServiceWith(null);

            assertThat(collectorTokenService.getToken()).isNull();
        }

        @Test
        void staticTokenIsReturnedWhenOAuth2IsNotConfigured() {
            CollectorProperties.AuthProperties staticAuth =
                    new CollectorProperties.AuthProperties("static-token", null, null, null, null);
            CollectorTokenService collectorTokenService = collectorTokenServiceWith(staticAuth);

            assertThat(collectorTokenService.getToken()).isEqualTo("static-token");
        }

        @Test
        void staticTokenModeNeverCallsKeycloak() throws Exception {
            CollectorProperties.AuthProperties staticAuth =
                    new CollectorProperties.AuthProperties("static-token", null, null, null, null);
            CollectorTokenService collectorTokenService = collectorTokenServiceWith(staticAuth);

            collectorTokenService.getToken();

            verify(httpClient, never()).send(any(), any());
        }
    }

    @Nested
    class OAuth2Mode {

        @Test
        void keycloak200ResponseReturnsTheAccessToken() throws Exception {
            CollectorTokenService collectorTokenService = collectorTokenServiceWith(oAuth2Auth());
            doReturn(keycloakResponse(200, TOKEN_JSON)).when(httpClient).send(any(HttpRequest.class), any());

            assertThat(collectorTokenService.getToken()).isEqualTo("abc-123");
        }

        @Test
        void tokenIsCachedAcrossCalls() throws Exception {
            CollectorTokenService collectorTokenService = collectorTokenServiceWith(oAuth2Auth());
            doReturn(keycloakResponse(200, TOKEN_JSON)).when(httpClient).send(any(HttpRequest.class), any());

            String firstCall = collectorTokenService.getToken();
            String secondCall = collectorTokenService.getToken();

            assertThat(firstCall).isEqualTo(secondCall).isEqualTo("abc-123");
            // expires_in=300 minus the 30s buffer is still well in the future, so the
            // second call reuses the cached token — Keycloak is hit only once.
            verify(httpClient, times(1)).send(any(HttpRequest.class), any());
        }

        @Test
        void keycloakNon200ResponseThrowsCollectorTokenException() throws Exception {
            CollectorTokenService collectorTokenService = collectorTokenServiceWith(oAuth2Auth());
            doReturn(keycloakResponse(401, "unauthorized")).when(httpClient).send(any(HttpRequest.class), any());

            assertThatThrownBy(collectorTokenService::getToken)
                    .isInstanceOf(CollectorTokenService.CollectorTokenException.class)
                    .hasMessageContaining("401");
        }

        @Test
        void ioExceptionDuringTokenFetchThrowsCollectorTokenExceptionWithCause() throws Exception {
            CollectorTokenService collectorTokenService = collectorTokenServiceWith(oAuth2Auth());
            doThrow(new IOException("connection reset")).when(httpClient).send(any(HttpRequest.class), any());

            assertThatThrownBy(collectorTokenService::getToken)
                    .isInstanceOf(CollectorTokenService.CollectorTokenException.class)
                    .hasCauseInstanceOf(IOException.class);
        }

        @Test
        void interruptedExceptionThrowsAndRestoresTheInterruptFlag() throws Exception {
            CollectorTokenService collectorTokenService = collectorTokenServiceWith(oAuth2Auth());
            doThrow(new InterruptedException("interrupted")).when(httpClient).send(any(HttpRequest.class), any());

            try {
                assertThatThrownBy(collectorTokenService::getToken)
                        .isInstanceOf(CollectorTokenService.CollectorTokenException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                // Clear the interrupt flag so it can't leak into unrelated tests.
                Thread.interrupted();
            }
        }
    }
}
