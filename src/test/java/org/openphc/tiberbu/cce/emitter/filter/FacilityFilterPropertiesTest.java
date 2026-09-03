package org.openphc.tiberbu.cce.emitter.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FacilityFilterPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(FacilityFilterProperties.class)
    static class TestConfig {
    }

    @Test
    @DisplayName("an absent or empty allowlist leaves the filter inactive")
    void emptyAllowlistIsInactive() {
        runner.run(context -> {
            FacilityFilterProperties props = context.getBean(FacilityFilterProperties.class);
            assertThat(props.ids()).isEmpty();
            assertThat(props.isActive()).isFalse();
            assertThat(props.admits("anything")).isTrue();
        });

        runner.withPropertyValues("cce.emitter.facility-filter.ids=")
                .run(context -> assertThat(context.getBean(FacilityFilterProperties.class).isActive())
                        .isFalse());
    }

    @Test
    @DisplayName("a YAML list binds to the allowlist")
    void bindsFromYamlList() {
        runner.withPropertyValues(
                "cce.emitter.facility-filter.ids[0]=0030",
                "cce.emitter.facility-filter.ids[1]=1302"
        ).run(context -> {
            FacilityFilterProperties props = context.getBean(FacilityFilterProperties.class);
            assertThat(props.ids()).containsExactlyInAnyOrder("0030", "1302");
            assertThat(props.isActive()).isTrue();
        });
    }

    @Test
    @DisplayName("the comma-separated FACILITY_FILTER_IDS form binds to the same set")
    void bindsFromCommaSeparatedEnvVar() {
        runner.withPropertyValues("cce.emitter.facility-filter.ids=0234,0030,1302")
                .run(context -> assertThat(context.getBean(FacilityFilterProperties.class).ids())
                        .containsExactlyInAnyOrder("0234", "0030", "1302"));
    }

    @Test
    @DisplayName("leading zeros survive binding when the ID is quoted")
    void leadingZerosArePreserved() {
        runner.withPropertyValues("cce.emitter.facility-filter.ids=0030,0234")
                .run(context -> {
                    FacilityFilterProperties props = context.getBean(FacilityFilterProperties.class);
                    assertThat(props.ids()).contains("0030", "0234");
                    // The trap this guards: an unquoted YAML 0030 arrives as "30"
                    assertThat(props.ids()).doesNotContain("30", "234");
                    assertThat(props.admits("0030")).isTrue();
                    assertThat(props.admits("30")).isFalse();
                });
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed and blank entries dropped")
    void trimsWhitespaceAndDropsBlanks() {
        FacilityFilterProperties props =
                FacilityFilterProperties.of(Arrays.asList(" 0030 ", "1302", "   ", "", null));
        assertThat(props.ids()).containsExactly("0030", "1302");
    }

    @Test
    @DisplayName("the allowlist is immutable once bound")
    void allowlistIsImmutable() {
        FacilityFilterProperties props = FacilityFilterProperties.of(List.of("0030"));
        assertThat(props.ids()).isUnmodifiable();
    }

    @Test
    @DisplayName("an event with no resolvable facility passes even when the filter is active")
    void unknownFacilityAlwaysPasses() {
        FacilityFilterProperties props = FacilityFilterProperties.of(List.of("0030"));
        assertThat(props.isActive()).isTrue();
        assertThat(props.admits(null)).isTrue();
        assertThat(props.admits("0030")).isTrue();
        assertThat(props.admits("9999")).isFalse();
    }
}
