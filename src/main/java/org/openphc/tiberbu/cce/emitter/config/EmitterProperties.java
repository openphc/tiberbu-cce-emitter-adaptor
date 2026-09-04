package org.openphc.tiberbu.cce.emitter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration for this adaptor's own identity.
 *
 * <p>Binds to the {@code cce.emitter.*} namespace. The facility allowlist lives
 * under the same prefix but binds separately — see
 * {@link org.openphc.tiberbu.cce.emitter.filter.FacilityFilterProperties}.
 *
 * @param source the CloudEvents {@code source} attribute stamped on every
 *               emitted event. Fixed by configuration — this adaptor serves a
 *               single source system and performs no per-request resolution.
 *               Validated at startup by {@link EmitterStartupValidator}
 */
@ConfigurationProperties(prefix = "cce.emitter")
public record EmitterProperties(String source) {
}
