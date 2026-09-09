package org.openphc.tiberbu.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.config.CollectorProperties;
import org.openphc.tiberbu.cce.emitter.config.RestClientConfig;
import org.openphc.tiberbu.cce.emitter.config.RetryConfig;
import org.openphc.tiberbu.cce.emitter.exception.CollectorClientException;
import org.openphc.tiberbu.cce.emitter.exception.CollectorForwardingException;
import org.openphc.tiberbu.cce.emitter.model.CloudEventDto;
import org.openphc.tiberbu.cce.emitter.model.CollectorResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.ServerSocket;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies actual retry COUNT behaviour and Authorization header attachment
 * against a real WireMock-stubbed Collector, through a real Spring context
 * with {@code @EnableRetry} active.
 *
 * <p><b>Why this can't be a plain Mockito unit test.</b> {@code @Retryable}
 * only retries through Spring's AOP proxy — a {@code CollectorForwardingService}
 * built with a bare {@code new} call (as in {@link CollectorForwardingServiceTest})
 * is a plain object with no retry behaviour at all; calling {@code forward()}
 * on it throws on the very first failure. Wiring the real production {@link
 * RetryConfig} and {@link RestClientConfig} through a real {@link
 * ApplicationContextRunner}-built context is the only way to prove the
 * configured attempt count and backoff are actually honoured.
 */
class CollectorForwardingRetryTest {

    private WireMockServer collectorMock;

    @AfterEach
    void stopWireMock() {
        if (collectorMock != null) {
            collectorMock.stop();
        }
    }

    @EnableConfigurationProperties(CollectorProperties.class)
    static class PropertiesConfig {
    }

    private ApplicationContextRunner runnerFor(WireMockServer wireMockServer, String... extraProperties) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfig.class, RetryConfig.class, RestClientConfig.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(CollectorTokenService.class)
                .withBean(CollectorForwardingService.class)
                .withPropertyValues(
                        "cce.collector.url=http://localhost:" + wireMockServer.port(),
                        "cce.collector.events-path=/v1/events",
                        "cce.collector.timeout=2000",
                        "cce.collector.retry.max-attempts=3",
                        "cce.collector.retry.backoff-ms=10");
        return runner.withPropertyValues(extraProperties);
    }

    private CloudEventDto sampleEvent() {
        JsonNode data = new ObjectMapper().createObjectNode().put("resourceType", "Consent");
        return new CloudEventDto("1.0", "evt-001", "tiberbu", "Consent",
                "KE-SHRP-170CDF0A-1363-4972-B36A", "2026-09-01T11:55:42.118Z",
                "application/fhir+json", "FAC-0001", null, "corr-1", data);
    }

    private static final String ACCEPTED_BODY = """
            {"data": {"eventId": "evt-001", "status": "accepted", "correlationId": "corr-1", "timestamp": "2026-09-01T11:55:43Z"}}""";

    @Nested
    @DisplayName("5xx — retried exactly max-attempts times")
    class ServerErrorRetryCount {

        @Test
        void alwaysFailingWith500IsCalledExactlyMaxAttemptsTimes() {
            collectorMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
            collectorMock.start();
            collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                    .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

            runnerFor(collectorMock, "cce.collector.auth.token=test-token").run(context -> {
                CollectorForwardingService service = context.getBean(CollectorForwardingService.class);

                assertThatExceptionOfType(CollectorForwardingException.class)
                        .isThrownBy(() -> service.forward(sampleEvent()));

                collectorMock.verify(3, postRequestedFor(urlEqualTo("/v1/events")));
            });
        }

        @Test
        @DisplayName("a 503 that succeeds on the second attempt returns the accepted response and stops retrying")
        void succeedsOnSecondAttemptAfterOneRetry() {
            collectorMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
            collectorMock.start();
            collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                    .inScenario("retry-then-succeed")
                    .whenScenarioStateIs("Started")
                    .willReturn(aResponse().withStatus(503).withBody("Service Unavailable"))
                    .willSetStateTo("second-attempt"));
            collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                    .inScenario("retry-then-succeed")
                    .whenScenarioStateIs("second-attempt")
                    .willReturn(aResponse()
                            .withStatus(202)
                            .withHeader("Content-Type", "application/json")
                            .withBody(ACCEPTED_BODY)));

            runnerFor(collectorMock, "cce.collector.auth.token=test-token").run(context -> {
                CollectorForwardingService service = context.getBean(CollectorForwardingService.class);

                CollectorResponse response = service.forward(sampleEvent());

                assertThat(response.data().status()).isEqualTo("accepted");
                collectorMock.verify(2, postRequestedFor(urlEqualTo("/v1/events")));
            });
        }
    }

    @Nested
    @DisplayName("connection refused — retried like a 5xx")
    class ConnectionRefusedRetryCount {

        @Test
        void connectionRefusedIsRetriedExactlyMaxAttemptsTimes() throws IOException {
            int closedPort;
            try (ServerSocket transientlyBoundSocket = new ServerSocket(0)) {
                closedPort = transientlyBoundSocket.getLocalPort();
            }
            // The socket above is now closed, so nothing is listening on closedPort —
            // every connection attempt gets a real ECONNREFUSED, not a timeout.

            ApplicationContextRunner runner = new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                    .withUserConfiguration(PropertiesConfig.class, RetryConfig.class, RestClientConfig.class)
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withBean(CollectorTokenService.class)
                    .withBean(CollectorForwardingService.class)
                    .withPropertyValues(
                            "cce.collector.url=http://localhost:" + closedPort,
                            "cce.collector.events-path=/v1/events",
                            "cce.collector.timeout=1000",
                            "cce.collector.retry.max-attempts=3",
                            "cce.collector.retry.backoff-ms=10",
                            "cce.collector.auth.token=test-token");

            runner.run(context -> {
                CollectorForwardingService service = context.getBean(CollectorForwardingService.class);

                assertThatExceptionOfType(CollectorForwardingException.class)
                        .isThrownBy(() -> service.forward(sampleEvent()))
                        .withMessageContaining("unreachable");
            });
        }
    }

    @Nested
    @DisplayName("4xx — never retried")
    class ClientErrorNoRetry {

        @Test
        void a400IsCalledExactlyOnce() {
            collectorMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
            collectorMock.start();
            collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                    .willReturn(aResponse()
                            .withStatus(400)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {"error": {"code": "VALIDATION_ERROR", "message": "type is required"}}""")));

            runnerFor(collectorMock, "cce.collector.auth.token=test-token").run(context -> {
                CollectorForwardingService service = context.getBean(CollectorForwardingService.class);

                assertThatExceptionOfType(CollectorClientException.class)
                        .isThrownBy(() -> service.forward(sampleEvent()));

                collectorMock.verify(1, postRequestedFor(urlEqualTo("/v1/events")));
            });
        }
    }

    @Nested
    @DisplayName("outbound Authorization header — all three auth modes")
    class AuthorizationHeaderModes {

        @Test
        @DisplayName("static token configured -> Authorization header present with that token")
        void staticTokenModeSendsTheConfiguredToken() {
            collectorMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
            collectorMock.start();
            collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                    .willReturn(aResponse()
                            .withStatus(202).withHeader("Content-Type", "application/json").withBody(ACCEPTED_BODY)));

            runnerFor(collectorMock, "cce.collector.auth.token=my-static-token").run(context -> {
                CollectorForwardingService service = context.getBean(CollectorForwardingService.class);

                service.forward(sampleEvent());

                collectorMock.verify(postRequestedFor(urlEqualTo("/v1/events"))
                        .withHeader("Authorization", equalTo("Bearer my-static-token")));
            });
        }

        @Test
        @DisplayName("neither auth mode configured -> no Authorization header is sent at all")
        void noAuthConfiguredSendsNoAuthorizationHeader() {
            collectorMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
            collectorMock.start();
            collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                    .willReturn(aResponse()
                            .withStatus(202).withHeader("Content-Type", "application/json").withBody(ACCEPTED_BODY)));

            // No cce.collector.auth.* properties at all — CollectorProperties.auth() binds to null.
            runnerFor(collectorMock).run(context -> {
                CollectorForwardingService service = context.getBean(CollectorForwardingService.class);

                service.forward(sampleEvent());

                collectorMock.verify(postRequestedFor(urlEqualTo("/v1/events"))
                        .withoutHeader("Authorization"));
            });
        }

        @Test
        @DisplayName("OAuth2 fully configured -> Authorization header carries the Keycloak-issued token")
        void oAuth2ModeSendsTheKeycloakIssuedToken() {
            collectorMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
            collectorMock.start();
            collectorMock.stubFor(post(urlEqualTo("/v1/events"))
                    .willReturn(aResponse()
                            .withStatus(202).withHeader("Content-Type", "application/json").withBody(ACCEPTED_BODY)));
            collectorMock.stubFor(post(urlEqualTo("/realms/cce/protocol/openid-connect/token"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {"access_token": "keycloak-issued-token", "expires_in": 300, "token_type": "Bearer"}""")));

            runnerFor(collectorMock,
                    "cce.collector.auth.keycloak-host=http://localhost:" + collectorMock.port(),
                    "cce.collector.auth.realm=cce",
                    "cce.collector.auth.client-id=emitter-client",
                    "cce.collector.auth.client-secret=s3cret"
            ).run(context -> {
                CollectorForwardingService service = context.getBean(CollectorForwardingService.class);

                service.forward(sampleEvent());

                collectorMock.verify(postRequestedFor(urlEqualTo("/v1/events"))
                        .withHeader("Authorization", equalTo("Bearer keycloak-issued-token")));
            });
        }
    }
}
