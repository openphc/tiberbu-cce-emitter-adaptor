package org.openphc.tiberbu.cce.emitter.config;

import jakarta.annotation.PostConstruct;
import org.openphc.tiberbu.cce.emitter.filter.FacilityFilterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fails the application startup on configuration that would silently corrupt
 * downstream data, and logs the resolved runtime identity.
 *
 * <p>Running as {@code @PostConstruct} means a misconfigured service never
 * reaches the point of accepting traffic — it is better to not start at all
 * than to emit events the Collector cannot attribute or deduplicate.
 */
@Component
public class EmitterStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(EmitterStartupValidator.class);

    private final EmitterProperties emitterProperties;
    private final CollectorProperties collectorProperties;
    private final FacilityFilterProperties facilityFilterProperties;

    public EmitterStartupValidator(
            EmitterProperties emitterProperties,
            CollectorProperties collectorProperties,
            FacilityFilterProperties facilityFilterProperties) {
        this.emitterProperties = emitterProperties;
        this.collectorProperties = collectorProperties;
        this.facilityFilterProperties = facilityFilterProperties;
    }

    @PostConstruct
    void validateAndReport() {
        String source = emitterProperties.source();
        if (source == null || source.isBlank()) {
            throw new IllegalStateException(
                    "cce.emitter.source must be configured — it is emitted as the CloudEvents "
                            + "'source' attribute and used downstream as part of the deduplication key");
        }

        log.info("Emitting events with source='{}'", source);
        log.info("Collector target: {}", collectorProperties.eventsUrl());
        log.info("Outbound auth mode: {}", describeAuthMode());
        log.info("Facility filter: {}", describeFilter());
    }

    private String describeAuthMode() {
        CollectorProperties.AuthProperties auth = collectorProperties.auth();
        if (auth == null) {
            return "none (no cce.collector.auth block)";
        }
        if (auth.isOAuth2Configured()) {
            return "OAuth2 client_credentials via " + auth.tokenEndpoint();
        }
        if (auth.token() != null && !auth.token().isBlank()) {
            return "static Bearer token";
        }
        return "none (no Authorization header will be sent)";
    }

    private String describeFilter() {
        return facilityFilterProperties.isActive()
                ? "active, admitting " + facilityFilterProperties.ids()
                : "inactive (empty allowlist — all facilities pass)";
    }
}
