package org.openphc.tiberbu.cce.emitter.config;

import ca.uhn.fhir.context.FhirContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class FhirConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    /** Two independent consumers, to prove they receive the identical FhirContext instance. */
    static class FirstFhirContextConsumer {
        final FhirContext fhirContext;
        FirstFhirContextConsumer(FhirContext fhirContext) {
            this.fhirContext = fhirContext;
        }
    }

    static class SecondFhirContextConsumer {
        final FhirContext fhirContext;
        SecondFhirContextConsumer(FhirContext fhirContext) {
            this.fhirContext = fhirContext;
        }
    }

    @Import(FhirConfig.class)
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        FirstFhirContextConsumer firstFhirContextConsumer(FhirContext fhirContext) {
            return new FirstFhirContextConsumer(fhirContext);
        }

        @org.springframework.context.annotation.Bean
        SecondFhirContextConsumer secondFhirContextConsumer(FhirContext fhirContext) {
            return new SecondFhirContextConsumer(fhirContext);
        }
    }

    @Test
    @DisplayName("fhirContext() is registered as a bean and configured for R4")
    void fhirContextBeanIsRegisteredForR4() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(FhirContext.class);
            assertThat(context.getBean(FhirContext.class).getVersion().getVersion().name())
                    .isEqualTo("R4");
        });
    }

    @Test
    @DisplayName("repeated lookups return the same instance, per default Spring singleton scope")
    void repeatedLookupsReturnTheSameInstance() {
        runner.run(context -> assertThat(context.getBean(FhirContext.class))
                .isSameAs(context.getBean(FhirContext.class)));
    }

    @Test
    @DisplayName("two independent consumers share the identical FhirContext — no per-consumer instance")
    void allConsumersShareTheSameFhirContextInstance() {
        runner.run(context -> {
            FhirContext contextSeenByFirstConsumer =
                    context.getBean(FirstFhirContextConsumer.class).fhirContext;
            FhirContext contextSeenBySecondConsumer =
                    context.getBean(SecondFhirContextConsumer.class).fhirContext;

            assertThat(contextSeenByFirstConsumer).isSameAs(contextSeenBySecondConsumer);
        });
    }
}
