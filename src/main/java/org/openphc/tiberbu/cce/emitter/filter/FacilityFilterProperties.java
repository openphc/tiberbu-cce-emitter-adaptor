package org.openphc.tiberbu.cce.emitter.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Facility allowlist configuration.
 *
 * <p>Binds to {@code cce.emitter.facility-filter.*}. Accepts either a YAML list
 * or the comma-separated {@code FACILITY_FILTER_IDS} environment variable —
 * Spring's relaxed binding resolves both into the same set.
 *
 * <p><b>Quote IDs in YAML.</b> {@code ids: [0030]} is parsed by SnakeYAML as a
 * number and reaches this record as {@code "30"} — the leading zero is gone
 * before binding can preserve it. Write {@code ids: ["0030"]} instead.
 *
 * @param ids allowed facility FOSA IDs. Normalized to an immutable, trimmed set
 *            for O(1) lookup, in configuration order so startup logs read
 *            predictably. An empty set means the filter is INACTIVE and every
 *            event passes
 */
@ConfigurationProperties(prefix = "cce.emitter.facility-filter")
public record FacilityFilterProperties(Set<String> ids) {

    public FacilityFilterProperties {
        ids = normalize(ids);
    }

    /**
     * @return an instance holding the given raw IDs, normalized
     */
    public static FacilityFilterProperties of(Collection<String> rawIds) {
        return new FacilityFilterProperties(rawIds == null ? null : new LinkedHashSet<>(rawIds));
    }

    /**
     * @return {@code true} when at least one ID is configured. An inactive filter
     *         admits every event, which is the default
     */
    public boolean isActive() {
        return !ids.isEmpty();
    }

    /**
     * @param facilityId the resolved facility ID, may be {@code null}
     * @return {@code true} when the filter is inactive, the facility is unknown
     *         (there is nothing to filter on), or the ID is on the allowlist
     */
    public boolean admits(String facilityId) {
        return !isActive() || facilityId == null || ids.contains(facilityId);
    }

    private static Set<String> normalize(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> trimmed = new LinkedHashSet<>();
        for (String id : raw) {
            if (id != null && !id.isBlank()) {
                trimmed.add(id.trim());
            }
        }
        return Collections.unmodifiableSet(trimmed);
    }
}
