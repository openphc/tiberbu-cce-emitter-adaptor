package org.openphc.tiberbu.cce.emitter.filter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.exception.FacilityFilterRejectedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class FacilityFilterTest {

    private static final String SOURCE = "tiberbu";

    private FacilityFilter facilityFilter(String... allowedIds) {
        return new FacilityFilter(FacilityFilterProperties.of(List.of(allowedIds)), new SimpleMeterRegistry());
    }

    @Nested
    @DisplayName("empty allowlist — filter inactive")
    class InactiveFilter {

        @Test
        @DisplayName("any facility ID passes")
        void anyFacilityIdPasses() {
            assertThatCode(() -> facilityFilter().enforceFilter("9999", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a null facility ID passes")
        void nullFacilityIdPasses() {
            assertThatCode(() -> facilityFilter().enforceFilter(null, SOURCE))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("non-empty allowlist — filter active")
    class ActiveFilter {

        @Test
        @DisplayName("an allowed facility ID passes")
        void allowedFacilityIdPasses() {
            assertThatCode(() -> facilityFilter("0030").enforceFilter("0030", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a facility ID outside the allowlist is rejected")
        void deniedFacilityIdIsRejected() {
            assertThatExceptionOfType(FacilityFilterRejectedException.class)
                    .isThrownBy(() -> facilityFilter("0030").enforceFilter("9999", SOURCE))
                    .withMessage("Event skipped by facility filter: facilityId='9999' source='tiberbu'");
        }

        @Test
        @DisplayName("a null facility ID (no facility context on the resource) passes")
        void nullFacilityIdStillPasses() {
            assertThatCode(() -> facilityFilter("0030").enforceFilter(null, SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a blank facility ID passes, same as null")
        void blankFacilityIdStillPasses() {
            assertThatCode(() -> facilityFilter("0030").enforceFilter("   ", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("matching is case-insensitive: uppercase config, lowercase inbound ID")
        void matchingIsCaseInsensitiveUpperConfigLowerInbound() {
            assertThatCode(() -> facilityFilter("ABC-123").enforceFilter("abc-123", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("matching is case-insensitive: lowercase config, uppercase inbound ID")
        void matchingIsCaseInsensitiveLowerConfigUpperInbound() {
            assertThatCode(() -> facilityFilter("abc-123").enforceFilter("ABC-123", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a configured ID with surrounding whitespace is trimmed before matching")
        void configuredIdWithWhitespaceIsTrimmedBeforeMatch() {
            assertThatCode(() -> facilityFilter("  0030  ").enforceFilter("0030", SOURCE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("leading-zero IDs compare as strings, not numbers — '0030' and '30' are different facilities")
        void leadingZeroIdsCompareAsStrings() {
            FacilityFilter facilityFilter = facilityFilter("0030");

            assertThatCode(() -> facilityFilter.enforceFilter("0030", SOURCE)).doesNotThrowAnyException();
            assertThatExceptionOfType(FacilityFilterRejectedException.class)
                    .isThrownBy(() -> facilityFilter.enforceFilter("30", SOURCE));
        }

        @Test
        @DisplayName("the thrown exception carries facilityId, sourceKey and reason")
        void exceptionCarriesFacilityIdSourceKeyAndReason() {
            assertThatExceptionOfType(FacilityFilterRejectedException.class)
                    .isThrownBy(() -> facilityFilter("0030").enforceFilter("9999", SOURCE))
                    .satisfies(rejection -> {
                        assertThat(rejection.getFacilityId()).isEqualTo("9999");
                        assertThat(rejection.getSourceKey()).isEqualTo(SOURCE);
                        assertThat(rejection.getReason()).isEqualTo("NOT_IN_ALLOWLIST");
                    });
        }
    }

    @Nested
    @DisplayName("tiberbu.cce.emitter.entries.filtered counter")
    class FilteredEventsCounter {

        @Test
        @DisplayName("incremented, with source/facility/reason tags, only on denial")
        void incrementedOnlyOnDenial() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            FacilityFilter facilityFilter =
                    new FacilityFilter(FacilityFilterProperties.of(List.of("0030")), meterRegistry);

            assertThatExceptionOfType(FacilityFilterRejectedException.class)
                    .isThrownBy(() -> facilityFilter.enforceFilter("9999", SOURCE));

            double filteredCount = meterRegistry.counter("tiberbu.cce.emitter.entries.filtered",
                    "source", SOURCE, "facility", "9999", "reason", "NOT_IN_ALLOWLIST").count();
            assertThat(filteredCount).isEqualTo(1.0);
        }

        @Test
        @DisplayName("not incremented, and no meter registered at all, when the event is admitted")
        void notIncrementedOnAdmission() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            FacilityFilter facilityFilter =
                    new FacilityFilter(FacilityFilterProperties.of(List.of("0030")), meterRegistry);

            facilityFilter.enforceFilter("0030", SOURCE);

            assertThat(meterRegistry.getMeters()).isEmpty();
        }

        @Test
        @DisplayName("not incremented when a null facility ID passes through an active filter")
        void notIncrementedOnNullFacilityPassthrough() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            FacilityFilter facilityFilter =
                    new FacilityFilter(FacilityFilterProperties.of(List.of("0030")), meterRegistry);

            facilityFilter.enforceFilter(null, SOURCE);

            assertThat(meterRegistry.getMeters()).isEmpty();
        }

        @Test
        @DisplayName("repeated denials for the same facility accumulate on the same counter")
        void repeatedDenialsAccumulate() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            FacilityFilter facilityFilter =
                    new FacilityFilter(FacilityFilterProperties.of(List.of("0030")), meterRegistry);

            for (int denialAttempt = 0; denialAttempt < 3; denialAttempt++) {
                assertThatExceptionOfType(FacilityFilterRejectedException.class)
                        .isThrownBy(() -> facilityFilter.enforceFilter("9999", SOURCE));
            }

            double filteredCount = meterRegistry.counter("tiberbu.cce.emitter.entries.filtered",
                    "source", SOURCE, "facility", "9999", "reason", "NOT_IN_ALLOWLIST").count();
            assertThat(filteredCount).isEqualTo(3.0);
        }
    }
}
