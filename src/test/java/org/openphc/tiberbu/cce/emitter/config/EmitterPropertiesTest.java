package org.openphc.tiberbu.cce.emitter.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EmitterPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(EmitterProperties.class)
    static class TestConfig {
    }

    @Test
    void bindsSource() {
        runner.withPropertyValues("cce.emitter.source=tiberbu")
                .run(context -> assertThat(context.getBean(EmitterProperties.class).source())
                        .isEqualTo("tiberbu"));
    }

    @Test
    @DisplayName("the nested facility-filter key does not break binding")
    void ignoresTheNestedFacilityFilterBlock() {
        runner.withPropertyValues(
                "cce.emitter.source=tiberbu",
                "cce.emitter.facility-filter.ids=0030"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmitterProperties.class).source()).isEqualTo("tiberbu");
        });
    }
}
