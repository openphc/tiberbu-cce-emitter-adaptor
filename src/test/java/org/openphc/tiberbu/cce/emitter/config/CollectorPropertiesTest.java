package org.openphc.tiberbu.cce.emitter.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(CollectorProperties.class)
    static class TestConfig {
    }

    @Nested
    @DisplayName("binding")
    class Binding {

        @Test
        void bindsEveryPropertyFromConfiguration() {
            runner.withPropertyValues(
                    "cce.collector.url=http://cce-collector-service:8080",
                    "cce.collector.events-path=/v1/events",
                    "cce.collector.timeout=5000",
                    "cce.collector.retry.max-attempts=3",
                    "cce.collector.retry.backoff-ms=1000",
                    "cce.collector.auth.token=static-token"
            ).run(context -> {
                CollectorProperties props = context.getBean(CollectorProperties.class);
                assertThat(props.url()).isEqualTo("http://cce-collector-service:8080");
                assertThat(props.eventsPath()).isEqualTo("/v1/events");
                assertThat(props.timeout()).isEqualTo(5000);
                assertThat(props.retry().maxAttempts()).isEqualTo(3);
                assertThat(props.retry().backoffMs()).isEqualTo(1000L);
                assertThat(props.auth().token()).isEqualTo("static-token");
            });
        }

        @Test
        void eventsUrlConcatenatesBaseAndPath() {
            runner.withPropertyValues(
                    "cce.collector.url=http://localhost:5055",
                    "cce.collector.events-path=/v1/events"
            ).run(context -> assertThat(context.getBean(CollectorProperties.class).eventsUrl())
                    .isEqualTo("http://localhost:5055/v1/events"));
        }

        @Test
        @DisplayName("an absent auth block binds to null rather than failing")
        void absentAuthBlockBindsToNull() {
            runner.withPropertyValues(
                    "cce.collector.url=http://localhost:5055",
                    "cce.collector.events-path=/v1/events"
            ).run(context -> {
                CollectorProperties props = context.getBean(CollectorProperties.class);
                assertThat(props.auth()).isNull();
                assertThat(props.retry()).isNull();
            });
        }
    }

    @Nested
    @DisplayName("AuthProperties.isOAuth2Configured()")
    class OAuth2TruthTable {

        @ParameterizedTest(name = "host={0} realm={1} clientId={2} secret={3} -> {4}")
        @CsvSource(nullValues = "NULL", value = {
                // all four present -> OAuth2
                "https://kc, cce,  client, secret, true",
                // each one missing in turn -> not OAuth2
                "NULL,       cce,  client, secret, false",
                "https://kc, NULL, client, secret, false",
                "https://kc, cce,  NULL,   secret, false",
                "https://kc, cce,  client, NULL,   false",
                // blank is treated as missing, which is what the prod profile's
                // ${KEYCLOAK_CLIENT_ID:} default produces when the env var is unset
                "'',         cce,  client, secret, false",
                "https://kc, '',   client, secret, false",
                "https://kc, cce,  '',     secret, false",
                "https://kc, cce,  client, '',     false",
                "'   ',      cce,  client, secret, false",
                // nothing configured at all
                "NULL,       NULL, NULL,   NULL,   false",
        })
        void reportsOAuth2OnlyWhenFullyConfigured(
                String host, String realm, String clientId, String secret, boolean expected) {
            CollectorProperties.AuthProperties auth =
                    new CollectorProperties.AuthProperties("token", host, realm, clientId, secret);
            assertThat(auth.isOAuth2Configured()).isEqualTo(expected);
        }

        @Test
        void buildsTheKeycloakTokenEndpoint() {
            CollectorProperties.AuthProperties auth = new CollectorProperties.AuthProperties(
                    null, "https://keycloak.cce.mdtlabs.org", "cce", "emitter", "secret");
            assertThat(auth.tokenEndpoint())
                    .isEqualTo("https://keycloak.cce.mdtlabs.org/realms/cce/protocol/openid-connect/token");
        }
    }
}
