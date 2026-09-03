package org.openphc.tiberbu.cce.emitter.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.filter.FacilityFilterProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class EmitterStartupValidatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "cce.collector.url=http://localhost:5055",
                    "cce.collector.events-path=/v1/events");

    @EnableConfigurationProperties({
            EmitterProperties.class,
            CollectorProperties.class,
            FacilityFilterProperties.class,
    })
    @Import(EmitterStartupValidator.class)
    static class TestConfig {
    }

    @Test
    @DisplayName("the context starts when cce.emitter.source is configured")
    void startsWithSourceConfigured() {
        runner.withPropertyValues("cce.emitter.source=tiberbu")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmitterStartupValidator.class);
                });
    }

    @Test
    @DisplayName("the context refuses to start when cce.emitter.source is unset")
    void failsFastWhenSourceMissing() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cce.emitter.source must be configured"));
    }

    @Test
    @DisplayName("the context refuses to start when cce.emitter.source is blank")
    void failsFastWhenSourceBlank() {
        runner.withPropertyValues("cce.emitter.source=   ")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("cce.emitter.source must be configured"));
    }

    @Test
    @DisplayName("startup succeeds with no auth block at all")
    void startsWithoutAnyAuthConfiguration() {
        runner.withPropertyValues("cce.emitter.source=tiberbu")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
