package org.openphc.tiberbu.cce.emitter.cloudevents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openphc.tiberbu.cce.emitter.model.SourceMetadata;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the deterministic id contract: it must catch a genuine replay while
 * never colliding two genuinely distinct events. The two payload-derived
 * candidates ruled out by real tibERbu sample data are exercised directly as
 * regression tests (a real duplicate-payload pair, and two entries sharing one
 * bundle), alongside the traceId-absent and resource.id-absent fallbacks.
 */
class EventIdGeneratorTest {

    private static final String SOURCE = "tiberbu";

    private final EventIdGenerator eventIdGenerator = new EventIdGenerator();

    private SourceMetadata metadataWithTraceId(String traceId, int bundleEntryIndex) {
        return new SourceMetadata(SOURCE, "FAC-0001", "corr-id", OffsetDateTime.now(ZoneOffset.UTC),
                "/inbound", traceId, bundleEntryIndex);
    }

    @Nested
    @DisplayName("deterministic path — traceId present")
    class DeterministicPath {

        @Test
        @DisplayName("the same traceId + resource id, called twice, produce the same id — a genuine replay is caught")
        void sameInputsProduceSameId() {
            SourceMetadata metadata = metadataWithTraceId("ef1cb56375", 1);

            String firstCall = eventIdGenerator.generate(metadata, "VCR-20260901-57098420");
            String secondCall = eventIdGenerator.generate(metadata, "VCR-20260901-57098420");

            assertThat(firstCall).isEqualTo(secondCall);
        }

        @Test
        @DisplayName("REGRESSION: two real payloads that collide on bundleId+resourceId, but differ in traceId, get DIFFERENT ids")
        void realDuplicatePayloadPairNoLongerCollides() {
            // The two real tibERbu Consent samples that motivated this design: identical
            // bundleId ("VCR-20260901-57098420") and identical Consent.id
            // ("VCR-20260901-57098420") in both — genuinely distinct events (different
            // consent-capture flows, ~26 minutes apart), differing only in traceId.
            String sharedResourceId = "VCR-20260901-57098420";
            SourceMetadata firstSubmission = metadataWithTraceId("ef1cb56375", 1);
            SourceMetadata secondSubmission = metadataWithTraceId("8feb6c0fb2", 1);

            String firstEventId = eventIdGenerator.generate(firstSubmission, sharedResourceId);
            String secondEventId = eventIdGenerator.generate(secondSubmission, sharedResourceId);

            assertThat(firstEventId).isNotEqualTo(secondEventId);
        }

        @Test
        @DisplayName("REGRESSION: two entries in the SAME bundle (same traceId) get DIFFERENT ids")
        void twoEntriesInOneBundleGetDifferentIds() {
            SourceMetadata sameBundleMetadata = metadataWithTraceId("ef1cb56375", 1);

            String consentEventId = eventIdGenerator.generate(sameBundleMetadata, "VCR-20260901-57098420");
            String observationEventId = eventIdGenerator.generate(sameBundleMetadata, "obs-001");

            assertThat(consentEventId).isNotEqualTo(observationEventId);
        }

        @Test
        @DisplayName("a different traceId with everything else identical still produces a different id")
        void differentTraceIdAloneChangesTheId() {
            String eventId1 = eventIdGenerator.generate(metadataWithTraceId("trace-aaa", 1), "resource-001");
            String eventId2 = eventIdGenerator.generate(metadataWithTraceId("trace-bbb", 1), "resource-001");

            assertThat(eventId1).isNotEqualTo(eventId2);
        }

        @Test
        @DisplayName("the generated id is a well-formed UUID string")
        void generatedIdIsAWellFormedUuid() {
            String eventId = eventIdGenerator.generate(metadataWithTraceId("ef1cb56375", 0), "resource-001");

            assertThat(UUID.fromString(eventId)).isNotNull();
        }
    }

    @Nested
    @DisplayName("resource.id absent, traceId present — falls back to bundleEntryIndex")
    class ResourceIdAbsentFallback {

        @Test
        @DisplayName("a null resource id falls back to bundleEntryIndex, still deterministic")
        void nullResourceIdFallsBackToBundleEntryIndex() {
            SourceMetadata metadata = metadataWithTraceId("ef1cb56375", 2);

            String firstCall = eventIdGenerator.generate(metadata, null);
            String secondCall = eventIdGenerator.generate(metadata, null);

            assertThat(firstCall).isEqualTo(secondCall);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank resource id also falls back to bundleEntryIndex")
        void blankResourceIdFallsBackToBundleEntryIndex(String blankResourceId) {
            SourceMetadata metadata = metadataWithTraceId("ef1cb56375", 2);

            String firstCall = eventIdGenerator.generate(metadata, blankResourceId);
            String secondCall = eventIdGenerator.generate(metadata, blankResourceId);

            assertThat(firstCall).isEqualTo(secondCall);
        }

        @Test
        @DisplayName("two entries with no resource id, at different indices in the same bundle, still get different ids")
        void differentBundleEntryIndicesProduceDifferentIdsWhenResourceIdIsAbsent() {
            String traceId = "ef1cb56375";

            String firstEntryEventId = eventIdGenerator.generate(metadataWithTraceId(traceId, 0), null);
            String secondEntryEventId = eventIdGenerator.generate(metadataWithTraceId(traceId, 1), null);

            assertThat(firstEntryEventId).isNotEqualTo(secondEntryEventId);
        }

        @Test
        @DisplayName("a present resource id takes priority over bundleEntryIndex")
        void presentResourceIdTakesPriorityOverBundleEntryIndex() {
            SourceMetadata metadata = metadataWithTraceId("ef1cb56375", 5);

            String withResourceId = eventIdGenerator.generate(metadata, "resource-001");
            String withoutResourceId = eventIdGenerator.generate(metadata, null);

            assertThat(withResourceId).isNotEqualTo(withoutResourceId);
        }
    }

    @Nested
    @DisplayName("traceId absent — falls back to a random, non-deterministic id")
    class TraceIdAbsentFallback {

        @Test
        @DisplayName("a null traceId produces a different id on every call, even with identical resource id")
        void nullTraceIdProducesRandomIdEveryCall() {
            SourceMetadata metadata = metadataWithTraceId(null, 0);

            String firstCall = eventIdGenerator.generate(metadata, "resource-001");
            String secondCall = eventIdGenerator.generate(metadata, "resource-001");

            assertThat(firstCall).isNotEqualTo(secondCall);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank traceId is treated the same as a null one — random, non-deterministic id")
        void blankTraceIdAlsoProducesRandomId(String blankTraceId) {
            SourceMetadata metadata = metadataWithTraceId(blankTraceId, 0);

            String firstCall = eventIdGenerator.generate(metadata, "resource-001");
            String secondCall = eventIdGenerator.generate(metadata, "resource-001");

            assertThat(firstCall).isNotEqualTo(secondCall);
        }

        @Test
        @DisplayName("the random fallback id is still a well-formed UUID string")
        void randomFallbackIdIsAWellFormedUuid() {
            String eventId = eventIdGenerator.generate(metadataWithTraceId(null, 0), "resource-001");

            assertThat(UUID.fromString(eventId)).isNotNull();
        }
    }
}
