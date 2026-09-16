package org.openphc.tiberbu.cce.emitter.fhir;

/**
 * The facility ID and display name resolved together from a single FHIR
 * {@code Reference} node by {@link FacilityIdExtractor} — both values sit on
 * the same node ({@code reference}/{@code identifier} for the ID, {@code
 * display} for the name), so one resolution pass covers both. Mirrors the
 * equivalent {@code FacilityDetails} in cce-compliance-service's {@code
 * FacilityService.upsertFacility()}, which extracts the same two values the
 * same way.
 *
 * @param facilityId   never {@code null} when this record itself is non-null
 *                      — {@link FacilityIdExtractor#extract} returns {@code
 *                      null} outright rather than a record with a null id
 * @param facilityName the reference's {@code display} field, verbatim;
 *                      {@code null} when absent — a known facility ID with no
 *                      display name is a normal, expected outcome, not an
 *                      extraction failure
 */
public record FacilityDetails(String facilityId, String facilityName) {
}
