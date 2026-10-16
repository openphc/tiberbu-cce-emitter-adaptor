package org.openphc.tiberbu.cce.emitter.service;

import org.hl7.fhir.r4.model.ResourceType;
import org.openphc.tiberbu.cce.emitter.fhir.ClinicalEventTimeExtractor;
import org.openphc.tiberbu.cce.emitter.model.BundleEntryResult;
import org.openphc.tiberbu.cce.emitter.model.CloudEventDto;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Decides the order a bundle's entries are forwarded to the Collector in.
 *
 * <p>tibERbu lists a bundle's children before their parents (e.g. a
 * ServiceRequest, then its Encounter, then the EpisodeOfCare). Downstream,
 * events are processed in the order they reach the Collector, so entries are
 * forwarded by when they happened clinically instead:
 * <ol>
 *   <li><b>clinical time</b>, earliest first ({@link ClinicalEventTimeExtractor}
 *       — the same fields the Collector and Matcher use);</li>
 *   <li>on a tie, <b>parent first</b>: EpisodeOfCare, then Encounter, then
 *       everything else — e.g. an EpisodeOfCare and its Encounter that both
 *       carry only the visit date;</li>
 *   <li>then <b>bundle order</b>.</li>
 * </ol>
 * An entry without a clinical time goes after every entry that has one.
 * Only entries that are forwarded are sorted here; {@code InboundEventService}
 * keeps the facility-filtered and failed ones aside.
 *
 * <p><b>Example</b> — a tibERbu bundle listing, in this order, a
 * ServiceRequest (authored 13:56), its Encounter (started 13:53) and the
 * EpisodeOfCare (started 13:51) is forwarded as EpisodeOfCare, Encounter,
 * ServiceRequest. If all three carried only the date {@code 2026-10-08},
 * their clinical times would tie and the parent rank would give the same
 * order.
 *
 * <p>Only the forwarding order changes: {@code InboundEventService} puts the
 * outcomes back in bundle order for the response.
 */
public class BundleEntryForwardingOrder {

    /** Tie-break rank: the episode every visit hangs under goes first. */
    private static final int EPISODE_OF_CARE_RANK = 0;

    /** Tie-break rank: the visit, which orders, results and diagnoses point to, goes next. */
    private static final int ENCOUNTER_RANK = 1;

    /** Tie-break rank: every other resource — they reference the two above, never the reverse. */
    private static final int OTHER_RESOURCE_RANK = 2;

    /** Reads each resource's clinical time — the same fields the Collector and Matcher use. */
    private final ClinicalEventTimeExtractor clinicalEventTimeExtractor;

    /**
     * @param clinicalEventTimeExtractor reads each entry's clinical time; the
     *                                   copy of cce-common-util's extractor
     */
    public BundleEntryForwardingOrder(ClinicalEventTimeExtractor clinicalEventTimeExtractor) {
        this.clinicalEventTimeExtractor = clinicalEventTimeExtractor;
    }

    /**
     * Sorts one bundle's entries to forward into the order they are sent to
     * the Collector: clinical time, then parent first on a tie, then bundle
     * order (see the class javadoc). The input list is not modified.
     *
     * @param entriesToForward one bundle's ready-to-forward entries ({@link
     *                         BundleEntryResult#isReadyToForward()}), in bundle order
     * @return the same entries, in the order to forward them
     */
    public List<BundleEntryResult> sortIntoForwardingOrder(List<BundleEntryResult> entriesToForward) {
        return entriesToForward.stream()
                // resolve each entry's clinical time once, not on every comparison
                .map(bundleEntryResult -> new SortKey(bundleEntryResult, clinicalTimeOf(bundleEntryResult)))
                .sorted(Comparator
                        // 1. earliest clinical time first; an entry with no clinical time goes after all timed ones
                        .comparing(SortKey::clinicalTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        // 2. same clinical time: EpisodeOfCare (0), then Encounter (1), then everything else (2)
                        .thenComparingInt(sortKey -> parentRankOf(sortKey.bundleEntryResult()))
                        // 3. still tied: keep the order the entries had in the bundle
                        .thenComparingInt(sortKey -> sortKey.bundleEntryResult().bundleEntryIndex()))
                // back to the entries themselves, now in forwarding order
                .map(SortKey::bundleEntryResult)
                .toList();
    }

    /**
     * The clinical time of an entry about to be forwarded.
     *
     * @param bundleEntryResult one ready-to-forward bundle entry
     * @return its clinical time in UTC; {@code null} when its resource carries
     *         no readable clinical time
     */
    private OffsetDateTime clinicalTimeOf(BundleEntryResult bundleEntryResult) {
        CloudEventDto cloudEvent = bundleEntryResult.cloudEventToForward();
        // the CloudEvent's data is the entry's FHIR resource, exactly as it is forwarded
        return clinicalEventTimeExtractor.extract(resourceTypeOf(cloudEvent.type()), cloudEvent.data());
    }

    /**
     * Converts the CloudEvent {@code type} — the FHIR resourceType verbatim,
     * e.g. {@code "Encounter"} — into the HAPI enum the extractor is keyed on.
     *
     * @param fhirResourceType the resource type name
     * @return the matching {@link ResourceType}, or {@code null} when HAPI does
     *         not know the name (the entry then has no clinical time)
     */
    private static ResourceType resourceTypeOf(String fhirResourceType) {
        try {
            return ResourceType.fromCode(fhirResourceType);
        } catch (Exception unknownResourceType) {
            return null; // the extractor returns null for a null type: no clinical time, sorted after timed entries
        }
    }

    /**
     * The tie-break rank of an entry: lower is forwarded first when clinical
     * times are equal.
     *
     * @param bundleEntryResult one ready-to-forward bundle entry
     * @return {@link #EPISODE_OF_CARE_RANK}, {@link #ENCOUNTER_RANK} or {@link #OTHER_RESOURCE_RANK}
     */
    private static int parentRankOf(BundleEntryResult bundleEntryResult) {
        return switch (bundleEntryResult.cloudEventToForward().type()) {
            case "EpisodeOfCare" -> EPISODE_OF_CARE_RANK;
            case "Encounter" -> ENCOUNTER_RANK;
            default -> OTHER_RESOURCE_RANK;
        };
    }

    /**
     * One entry paired with its clinical time, so the time is resolved once
     * per sort rather than on every comparison.
     *
     * @param bundleEntryResult the entry being sorted
     * @param clinicalTime      its clinical time in UTC, or {@code null} when it has none
     */
    private record SortKey(BundleEntryResult bundleEntryResult, OffsetDateTime clinicalTime) {
    }
}
