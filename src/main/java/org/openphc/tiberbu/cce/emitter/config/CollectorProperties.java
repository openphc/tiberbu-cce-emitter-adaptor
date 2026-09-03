package org.openphc.tiberbu.cce.emitter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration for the CCE Collector target.
 *
 * <p>Binds to the {@code cce.collector.*} namespace.
 *
 * @param url        Collector base URL (e.g. {@code http://cce-collector-service:8080})
 * @param eventsPath events endpoint path (e.g. {@code /v1/events})
 * @param timeout    HTTP connect + read timeout in milliseconds
 * @param retry      retry behaviour for transient Collector failures
 * @param auth       outbound authentication; {@code null} when the whole
 *                   {@code cce.collector.auth} block is absent
 */
@ConfigurationProperties(prefix = "cce.collector")
public record CollectorProperties(
        String url,
        String eventsPath,
        int timeout,
        RetryProperties retry,
        AuthProperties auth
) {

    /**
     * Retry configuration for Collector forwarding (5xx / timeout errors).
     *
     * @param maxAttempts maximum number of attempts, the first one included
     * @param backoffMs   initial backoff delay in milliseconds; doubles per retry
     */
    public record RetryProperties(
            int maxAttempts,
            long backoffMs
    ) {
    }

    /**
     * Outbound authentication for Collector calls. Independent of any inbound
     * authentication, which the CCE Gateway handles before the request arrives.
     *
     * <p>Three modes, selected purely by which properties are set:
     * <ul>
     *   <li><b>OAuth2 client credentials</b> — all four Keycloak properties present;
     *       takes precedence over a static token</li>
     *   <li><b>Static Bearer token</b> — only {@code token} present</li>
     *   <li><b>No authentication</b> — neither; no {@code Authorization} header is sent</li>
     * </ul>
     *
     * @param token        static Bearer token
     * @param keycloakHost Keycloak base URL (e.g. {@code https://keycloak.cce.mdtlabs.org})
     * @param realm        Keycloak realm name
     * @param clientId     OAuth2 client ID for the {@code client_credentials} grant
     * @param clientSecret OAuth2 client secret
     */
    public record AuthProperties(
            String token,
            String keycloakHost,
            String realm,
            String clientId,
            String clientSecret
    ) {

        /**
         * @return {@code true} only when every Keycloak property is present and non-blank.
         *         A partially configured block is NOT treated as OAuth2 — it would fail at
         *         token-fetch time, so it falls back to the static token instead.
         */
        public boolean isOAuth2Configured() {
            return isSet(keycloakHost) && isSet(realm) && isSet(clientId) && isSet(clientSecret);
        }

        /**
         * @return the Keycloak {@code client_credentials} token endpoint
         */
        public String tokenEndpoint() {
            return keycloakHost + "/realms/" + realm + "/protocol/openid-connect/token";
        }

        private static boolean isSet(String value) {
            return value != null && !value.isBlank();
        }
    }

    /**
     * @return the fully qualified events endpoint, {@code url + eventsPath}
     */
    public String eventsUrl() {
        return url + eventsPath;
    }
}
