package org.openphc.tiberbu.cce.emitter.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
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
 * <p>Matching is case-insensitive: IDs are lowercased on both sides of the
 * comparison, so a configured {@code abc-123} still passes an inbound
 * {@code ABC-123}. Numeric facility codes are unaffected.
 *
 * @param ids allowed facility IDs. Normalized to an immutable, trimmed,
 *            lowercased set for O(1) lookup, in configuration order so startup
 *            logs read predictably. An empty set means the filter is INACTIVE
 *            and every event passes
 */
@ConfigurationProperties(prefix = "cce.emitter.facility-filter")
public record FacilityFilterProperties(Set<String> ids) {

    public FacilityFilterProperties {
        ids = normalizeFacilityIds(ids);
    }

    /**
     * @return an instance holding the given raw IDs, normalized
     */
    public static FacilityFilterProperties of(Collection<String> rawFacilityIds) {
        return new FacilityFilterProperties(rawFacilityIds == null ? null : new LinkedHashSet<>(rawFacilityIds));
    }

    /**
     * @return {@code true} when at least one ID is configured. An inactive filter
     *         passes every event through, which is the default
     */
    public boolean isActive() {
        return !ids.isEmpty();
    }

    /**
     * @param facilityId the resolved facility ID, may be {@code null} or blank
     * @return {@code true} when the filter is inactive, the facility is unknown
     *         (there is nothing to filter on), or the ID is on the allowlist —
     *         i.e. this event should be let through, not denied
     */
    public boolean isFacilityAllowed(String facilityId) {
        if (!isActive() || facilityId == null || facilityId.isBlank()) {
            return true;
        }
        return ids.contains(normalizeFacilityId(facilityId));
    }

    private static Set<String> normalizeFacilityIds(Set<String> configuredFacilityIds) {
        if (configuredFacilityIds == null || configuredFacilityIds.isEmpty()) {
            return Set.of();
        }
        Set<String> normalizedFacilityIds = new LinkedHashSet<>();
        for (String configuredFacilityId : configuredFacilityIds) {
            if (configuredFacilityId != null && !configuredFacilityId.isBlank()) {
                normalizedFacilityIds.add(normalizeFacilityId(configuredFacilityId));
            }
        }
        return Collections.unmodifiableSet(normalizedFacilityIds);
    }

    private static String normalizeFacilityId(String facilityId) {
        return facilityId.trim().toLowerCase(Locale.ROOT);
    }
}
