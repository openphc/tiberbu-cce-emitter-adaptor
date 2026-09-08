package org.openphc.tiberbu.cce.emitter.model;

import java.time.OffsetDateTime;

/**
 * Per-entry metadata gathered while adapting one bundle entry into a CloudEvent
 * — everything {@code CloudEventEnvelopeBuilder} and {@code EventIdGenerator}
 * need that isn't already carried on the entry's own FHIR resource.
 *
 * <p>Built once per bundle entry (not once per request), since {@link
 * #facilityId} and {@link #bundleEntryIndex} are entry-specific even though
 * {@link #sourceIdentifier}, {@link #correlationId}, {@link #eventTime}, {@link
 * #sourcePath} and {@link #traceId} are the same for every entry in one bundle.
 *
 * <p>There is deliberately no {@code sourceEventId} field. tibERbu sends no
 * per-event header, so the CloudEvents {@code id} is instead derived
 * from {@link #traceId} (the envelope's {@code meta.traceId}) together with
 * the entry's own {@code resource.id} — see {@code EventIdGenerator}. The
 * output CloudEvent's {@code sourceeventid} extension attribute stays {@code
 * null} and is omitted from the JSON, not backed by any field here.
 *
 * @param sourceIdentifier the configured {@code cce.emitter.source} (e.g.
 *                          {@code "tiberbu"}), stamped as the CloudEvents
 *                          {@code source} attribute
 * @param facilityId        this entry's resolved facility ID from {@code
 *                          FacilityIdExtractor}; {@code null} when the
 *                          resource has no {@code organization} reference
 * @param correlationId     an adaptor-generated UUID, never {@code null}
 * @param eventTime         the adaptor's own processing time in UTC — never
 *                          {@code meta.timestamp} from the inbound envelope
 * @param sourcePath        the inbound request URI path (e.g. {@code
 *                          "/inbound"}), used only for logging/metrics context
 * @param traceId           the inbound envelope's {@code meta.traceId}; {@code
 *                          null} or blank when absent. This is the primary
 *                          per-submission differentiator for the deterministic
 *                          CloudEvents {@code id} — see {@code
 *                          EventIdGenerator} for what happens when it's absent
 * @param bundleEntryIndex  this entry's position in {@code resource.entry[]}.
 *                          Used as the per-entry differentiator when the
 *                          entry's own {@code resource.id} is absent
 */
public record SourceMetadata(
        String sourceIdentifier,
        String facilityId,
        String correlationId,
        OffsetDateTime eventTime,
        String sourcePath,
        String traceId,
        int bundleEntryIndex) {
}
