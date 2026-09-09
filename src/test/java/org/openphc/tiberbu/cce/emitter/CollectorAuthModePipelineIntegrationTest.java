package org.openphc.tiberbu.cce.emitter;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SUB-TASK E13 deliverable 6 — the three outbound Collector auth modes,
 * proven through the real HTTP pipeline ({@code POST /inbound}), each against
 * a real WireMock Collector. Each mode needs a different {@code
 * cce.collector.auth.*} binding fixed at context-creation time, so each gets
 * its own {@code @SpringBootTest} context — see each nested class's own
 * {@code @DynamicPropertySource}. {@link FullPipelineIntegrationTest} covers
 * every other scenario (happy path, ignored/skipped/failure paths) against a
 * single shared static-token context; this class exists only to isolate the
 * auth-mode variations that context can't also hold.
 */
class CollectorAuthModePipelineIntegrationTest {

    private static final String ACCEPTED_BODY = """
            {"data": {"eventId": "evt-auth", "status": "accepted", "correlationId": "corr-auth", "timestamp": "2026-09-01T11:55:43Z"}}""";

    private static String consentBundle(String resourceId) {
        return """
                {"meta": {"bundleId": "%s"},
                 "resource": {"resourceType": "Bundle", "type": "transaction", "entry": [
                   {"request": {"method": "PUT", "url": "Consent/%s"},
                    "resource": {"resourceType": "Consent", "id": "%s", "status": "active",
                      "patient": {"reference": "Patient/KE-SHRP-AUTH-0001"}}}
                 ]}}""".formatted(resourceId, resourceId, resourceId);
    }

    private static ResponseEntity<String> postInbound(TestRestTemplate restTemplate, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/inbound", new HttpEntity<>(body, headers), String.class);
    }

    @Nested
    @DisplayName("static token configured")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class StaticToken {

        private static final WireMockServer collectorMock =
                new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

        @Autowired
        private TestRestTemplate restTemplate;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            collectorMock.start();
            registry.add("cce.collector.url", () -> "http://localhost:" + collectorMock.port());
            registry.add("cce.collector.events-path", () -> "/v1/events");
            registry.add("cce.collector.auth.token", () -> "my-static-token");
        }

        @AfterAll
        static void stopWireMock() {
            collectorMock.stop();
        }

        @Test
        @DisplayName("the configured static token reaches the Collector verbatim as a Bearer header")
        void staticTokenReachesCollectorAsAuthorizationHeader() {
            collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                    .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody(ACCEPTED_BODY)));

            ResponseEntity<String> response = postInbound(restTemplate, consentBundle("VCR-AUTH-STATIC-001"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            collectorMock.verify(postRequestedFor(urlEqualTo("/v1/events"))
                    .withHeader("Authorization", equalTo("Bearer my-static-token")));
        }
    }

    @Nested
    @DisplayName("neither auth mode configured")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class NoAuth {

        private static final WireMockServer collectorMock =
                new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

        @Autowired
        private TestRestTemplate restTemplate;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            collectorMock.start();
            registry.add("cce.collector.url", () -> "http://localhost:" + collectorMock.port());
            registry.add("cce.collector.events-path", () -> "/v1/events");
            // Overrides application.yml's local-dev default — blank is treated identically to
            // absent by RestClientConfig's interceptor (token != null && !token.isBlank()).
            registry.add("cce.collector.auth.token", () -> "");
        }

        @AfterAll
        static void stopWireMock() {
            collectorMock.stop();
        }

        @Test
        @DisplayName("no Authorization header is sent to the Collector at all")
        void noAuthConfiguredSendsNoAuthorizationHeaderToCollector() {
            collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                    .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody(ACCEPTED_BODY)));

            ResponseEntity<String> response = postInbound(restTemplate, consentBundle("VCR-AUTH-NONE-001"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            collectorMock.verify(postRequestedFor(urlEqualTo("/v1/events")).withoutHeader("Authorization"));
        }
    }

    @Nested
    @DisplayName("OAuth2 configured")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class OAuth2 {

        private static final WireMockServer collectorMock =
                new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

        @Autowired
        private TestRestTemplate restTemplate;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            collectorMock.start();
            registry.add("cce.collector.url", () -> "http://localhost:" + collectorMock.port());
            registry.add("cce.collector.events-path", () -> "/v1/events");
            registry.add("cce.collector.auth.keycloak-host", () -> "http://localhost:" + collectorMock.port());
            registry.add("cce.collector.auth.realm", () -> "cce");
            registry.add("cce.collector.auth.client-id", () -> "emitter-client");
            registry.add("cce.collector.auth.client-secret", () -> "s3cret");
        }

        @AfterAll
        static void stopWireMock() {
            collectorMock.stop();
        }

        @Test
        @DisplayName("the Keycloak token endpoint is hit exactly once for two events, both carrying the cached token")
        void oAuth2TokenIsFetchedOnceAndCachedAcrossTwoEvents() {
            collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                    .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody(ACCEPTED_BODY)));
            collectorMock.stubFor(post(urlEqualTo("/realms/cce/protocol/openid-connect/token"))
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                            {"access_token": "keycloak-issued-token", "expires_in": 300, "token_type": "Bearer"}""")));

            String multiEntryBundle = """
                    {"meta": {"bundleId": "auth-oauth2-001", "traceId": "trace-auth-oauth2"},
                     "resource": {"resourceType": "Bundle", "type": "transaction", "entry": [
                       {"request": {"method": "PUT", "url": "Consent/VCR-AUTH-OAUTH-001"},
                        "resource": {"resourceType": "Consent", "id": "VCR-AUTH-OAUTH-001", "status": "active",
                          "patient": {"reference": "Patient/KE-SHRP-AUTH-0002"}}},
                       {"request": {"method": "PUT", "url": "Observation/OBS-AUTH-OAUTH-001"},
                        "resource": {"resourceType": "Observation", "id": "OBS-AUTH-OAUTH-001", "status": "final",
                          "subject": {"reference": "Patient/KE-SHRP-AUTH-0002"}}}
                     ]}}""";

            ResponseEntity<String> response = postInbound(restTemplate, multiEntryBundle);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            collectorMock.verify(2, postRequestedFor(urlEqualTo("/v1/events"))
                    .withHeader("Authorization", equalTo("Bearer keycloak-issued-token")));
            collectorMock.verify(1, postRequestedFor(urlEqualTo("/realms/cce/protocol/openid-connect/token")));
        }
    }
}
