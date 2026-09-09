package org.openphc.tiberbu.cce.emitter.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.tiberbu.cce.emitter.config.CollectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Resolves the Bearer token used to authenticate outbound calls to the CCE
 * Collector — independent of any inbound authentication, which the CCE
 * Gateway handles before a request ever reaches this adaptor.
 *
 * <p>Three modes, selected purely by which {@code cce.collector.auth.*}
 * properties are set (see {@link CollectorProperties.AuthProperties}):
 * <ol>
 *   <li><b>OAuth2 client_credentials via Keycloak</b> — all four Keycloak
 *       properties present. The token is fetched once, cached, and
 *       automatically refreshed {@value #EXPIRY_BUFFER_SECONDS}s before it
 *       actually expires.</li>
 *   <li><b>Static Bearer token</b> — only {@code cce.collector.auth.token} is
 *       set.</li>
 *   <li><b>No authentication</b> — neither is configured; {@link #getToken()}
 *       returns {@code null}, and {@code RestClientConfig}'s interceptor skips
 *       the {@code Authorization} header entirely rather than sending a blank
 *       or {@code "Bearer null"} value.</li>
 * </ol>
 */
@Service
public class CollectorTokenService {

    private static final Logger log = LoggerFactory.getLogger(CollectorTokenService.class);

    /** Refresh the OAuth2 token this many seconds before its reported expiry, to avoid an edge-case 401. */
    private static final long EXPIRY_BUFFER_SECONDS = 30;

    private final CollectorProperties.AuthProperties authProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.MIN;

    public CollectorTokenService(CollectorProperties collectorProperties, ObjectMapper objectMapper) {
        this.authProperties = collectorProperties.auth();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().build();
    }

    /**
     * Resolves the Bearer token to send with the next outbound Collector call.
     *
     * <p>Step 1: no {@code cce.collector.auth} block at all, or one that isn't
     * fully configured for OAuth2 — fall through to the plain static token
     * (which is itself {@code null} when nothing is configured). Step 2:
     * OAuth2 is fully configured — fetch (or reuse the cached) Keycloak token.
     *
     * @return the Bearer token, or {@code null} when neither auth mode is configured
     * @throws CollectorTokenException if an OAuth2 token fetch fails
     */
    public String getToken() {
        if (authProperties == null || !authProperties.isOAuth2Configured()) {
            return authProperties != null ? authProperties.token() : null;
        }
        return getOAuth2Token();
    }

    private synchronized String getOAuth2Token() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }

        log.debug("Fetching new OAuth2 token from Keycloak: {}", authProperties.tokenEndpoint());

        String formBody = "grant_type=" + encode("client_credentials")
                + "&client_id=" + encode(authProperties.clientId())
                + "&client_secret=" + encode(authProperties.clientSecret());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(authProperties.tokenEndpoint()))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Keycloak token request failed: HTTP {} — {}", response.statusCode(), response.body());
                throw new CollectorTokenException(
                        "Keycloak token request failed with HTTP " + response.statusCode());
            }

            TokenResponse tokenResponse = objectMapper.readValue(response.body(), TokenResponse.class);
            cachedToken = tokenResponse.accessToken();
            tokenExpiry = Instant.now().plusSeconds(tokenResponse.expiresIn() - EXPIRY_BUFFER_SECONDS);

            log.info("OAuth2 token acquired, expires in {}s", tokenResponse.expiresIn());
            return cachedToken;

        } catch (IOException | InterruptedException tokenRequestFailure) {
            if (tokenRequestFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to fetch OAuth2 token from Keycloak: {}", tokenRequestFailure.getMessage());
            throw new CollectorTokenException(
                    "Failed to fetch OAuth2 token: " + tokenRequestFailure.getMessage(), tokenRequestFailure);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Keycloak's {@code client_credentials} token response — only the three fields this class needs. */
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("token_type") String tokenType) {
    }

    /** Thrown when an OAuth2 token cannot be obtained from Keycloak. */
    public static class CollectorTokenException extends RuntimeException {
        public CollectorTokenException(String message) {
            super(message);
        }

        public CollectorTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
