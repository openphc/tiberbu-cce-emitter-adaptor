package org.openphc.tiberbu.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.openphc.tiberbu.cce.emitter.fhir.ClinicalEventTimeExtractor;
import org.openphc.tiberbu.cce.emitter.model.BundleEntryResult;
import org.openphc.tiberbu.cce.emitter.model.CloudEventDto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the forwarding order rule: clinical time first, then parent first
 * (EpisodeOfCare, Encounter, everything else) on a tie, then bundle order.
 */
class BundleEntryForwardingOrderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BundleEntryForwardingOrder bundleEntryForwardingOrder =
            new BundleEntryForwardingOrder(new ClinicalEventTimeExtractor(new SimpleMeterRegistry()));

    private static BundleEntryResult entry(int bundleEntryIndex, String resourceJson) {
        try {
            JsonNode resource = OBJECT_MAPPER.readTree(resourceJson);
            CloudEventDto cloudEvent = new CloudEventDto("1.0", "evt-" + bundleEntryIndex, "tiberbu",
                    resource.path("resourceType").asText(), "KE-SHRP-B4495158-2C21-4FD1-8531",
                    "2026-10-08T10:56:47Z", "application/fhir+json", null, null, null, "trace-001", resource);
            return BundleEntryResult.readyToForward(bundleEntryIndex, cloudEvent);
        } catch (Exception invalidTestJson) {
            throw new IllegalArgumentException(invalidTestJson);
        }
    }

    private static List<Integer> bundleEntryIndices(List<BundleEntryResult> bundleEntryResults) {
        return bundleEntryResults.stream().map(BundleEntryResult::bundleEntryIndex).toList();
    }

    @Test
    @DisplayName("tibERbu's child-first bundle (ServiceRequest, Encounter, EpisodeOfCare) is forwarded by clinical time")
    void childFirstBundleIsForwardedByClinicalTime() {
        List<BundleEntryResult> tiberbuOrder = List.of(
                entry(1, """
                        {"resourceType":"ServiceRequest","id":"8d465b7b","authoredOn":"2026-10-08T13:56:40+03:00"}"""),
                entry(2, """
                        {"resourceType":"Encounter","id":"d7f3f931","period":{"start":"2026-10-08T13:53:00+03:00"}}"""),
                entry(3, """
                        {"resourceType":"EpisodeOfCare","id":"d891cc12","period":{"start":"2026-10-08T13:51:30+03:00"}}"""));

        assertThat(bundleEntryIndices(bundleEntryForwardingOrder.sortIntoForwardingOrder(tiberbuOrder))).containsExactly(3, 2, 1);
    }

    @Test
    @DisplayName("same clinical time — EpisodeOfCare, then Encounter, then the rest")
    void sameClinicalTimeForwardsParentsFirst() {
        List<BundleEntryResult> sameDayBundle = List.of(
                entry(1, """
                        {"resourceType":"ServiceRequest","id":"sr","authoredOn":"2026-10-08"}"""),
                entry(2, """
                        {"resourceType":"Encounter","id":"enc","period":{"start":"2026-10-08"}}"""),
                entry(3, """
                        {"resourceType":"EpisodeOfCare","id":"eoc","period":{"start":"2026-10-08"}}"""));

        assertThat(bundleEntryIndices(bundleEntryForwardingOrder.sortIntoForwardingOrder(sameDayBundle))).containsExactly(3, 2, 1);
    }

    @Test
    @DisplayName("same clinical time and same rank — bundle order is kept")
    void sameClinicalTimeAndRankKeepsBundleOrder() {
        List<BundleEntryResult> vitals = List.of(
                entry(1, """
                        {"resourceType":"Observation","id":"temperature","effectiveDateTime":"2026-10-08T13:53:51+03:00"}"""),
                entry(2, """
                        {"resourceType":"Observation","id":"heart-rate","effectiveDateTime":"2026-10-08T13:53:51+03:00"}"""),
                entry(3, """
                        {"resourceType":"Observation","id":"weight","effectiveDateTime":"2026-10-08T13:53:51+03:00"}"""));

        assertThat(bundleEntryIndices(bundleEntryForwardingOrder.sortIntoForwardingOrder(vitals))).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("an entry without a clinical time is forwarded after every entry that has one")
    void entryWithoutClinicalTimeGoesLast() {
        List<BundleEntryResult> bundle = List.of(
                entry(1, """
                        {"resourceType":"Observation","id":"untimed","status":"final"}"""),
                entry(2, """
                        {"resourceType":"Observation","id":"timed","effectiveDateTime":"2026-10-08T13:53:51+03:00"}"""));

        assertThat(bundleEntryIndices(bundleEntryForwardingOrder.sortIntoForwardingOrder(bundle))).containsExactly(2, 1);
    }
}
