package org.openphc.tiberbu.cce.emitter.config;

import ca.uhn.fhir.context.FhirContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the shared {@link FhirContext} used for all FHIR R4 parsing.
 *
 * <p>Building a {@code FhirContext} scans and caches the R4 model classes,
 * which is expensive — exactly one instance must serve the whole application,
 * so it is a singleton {@code @Bean}.
 *
 * <p>A parser is a different matter. HAPI's {@code IParser} is cheap to create
 * but is documented as stateful and not safe to share across threads, so no
 * {@code IParser} bean is declared here — {@link
 * org.openphc.tiberbu.cce.emitter.fhir.FhirResourceParser} creates a fresh one
 * per call from this context instead of reusing a singleton.
 */
@Configuration
public class FhirConfig {

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();
    }
}
