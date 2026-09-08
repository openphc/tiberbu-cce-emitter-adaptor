package org.openphc.tiberbu.cce.emitter.fhir;

/**
 * One candidate event payload extracted from a tibERbu bundle — everything
 * {@link BundleEntryExtractor} needs downstream to build a CloudEvent, without
 * re-parsing the original request body.
 *
 * <p>For the {@code entry[1]} shown in {@link BundleEntryExtractor}'s
 * class-level javadoc — a {@code Consent} — extraction produces:
 * <pre>{@code
 * new BundleEntry(
 *     1,
 *     "{\"resourceType\":\"Consent\",\"id\":\"VCR-20260901-57098420\",\"status\":\"active\"," +
 *         "\"patient\":{\"reference\":\"Patient/KE-SHRP-170CDF0A-1363-4972-B36A\"}}",
 *     "Consent")
 * }</pre>
 *
 * @param bundleEntryIndex the entry's position in {@code resource.entry[]} in the
 *                          original bundle
 * @param resourceJson     the entry's {@code resource} object, serialized back to
 *                          JSON exactly as received; the sibling {@code request}
 *                          object is never read and never appears here
 * @param resourceType     the resource's {@code resourceType} (e.g. {@code "Consent"}),
 *                          taken verbatim — there is no allowlist of accepted types
 */
public record BundleEntry(int bundleEntryIndex, String resourceJson, String resourceType) {
}
