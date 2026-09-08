package org.openphc.tiberbu.cce.emitter.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The CloudEvents v1.0 envelope forwarded to the CCE Collector — one per
 * bundle entry.
 *
 * <p>Field names are the exact wire attribute names, lowercase, matching the
 * CloudEvents spec's extension-attribute naming rule; Jackson serializes a
 * record's own component names as-is, so no {@code @JsonProperty} renaming is
 * needed here. {@code @JsonInclude(NON_NULL)} on the class drops any null
 * field from the JSON entirely, rather than emitting {@code "field": null} —
 * this is how {@link #sourceeventid} (always {@code null}; tibERbu sends no
 * per-event header) disappears from the output instead of being serialized.
 *
 * <p>Example — for the {@code Consent} entry shown in api-reference.md § 3.1,
 * this serializes to:
 * <pre>{@code
 * {
 *   "specversion": "1.0",
 *   "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
 *   "source": "tiberbu",
 *   "type": "Consent",
 *   "subject": "KE-SHRP-170CDF0A-1363-4972-B36A",
 *   "time": "2026-09-01T11:55:42.118Z",
 *   "datacontenttype": "application/fhir+json",
 *   "facilityid": "FAC-0001",
 *   "correlationid": "7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d",
 *   "data": { "resourceType": "Consent", ... }
 * }
 * }</pre>
 * — {@code sourceeventid} is entirely absent, not present with a null value.
 *
 * @param specversion     always {@code "1.0"}
 * @param id              the deterministic event ID from {@code EventIdGenerator}
 * @param source          {@code cce.emitter.source} — never {@code meta.source}
 *                         from the inbound envelope
 * @param type            the FHIR {@code resourceType}, verbatim (e.g. {@code
 *                         "Consent"}); no transformation
 * @param subject         the patient identifier from {@code PatientIdExtractor}
 * @param time             the adaptor's processing time, ISO-8601 UTC — never
 *                         {@code meta.timestamp}
 * @param datacontenttype  always {@code "application/fhir+json"}
 * @param facilityid       from {@code FacilityIdExtractor}; {@code null} when
 *                         the resource has no {@code organization} reference
 * @param sourceeventid    always {@code null} — tibERbu sends no per-event
 *                         header; kept as a field only so the attribute name
 *                         exists in the schema, never populated
 * @param correlationid    an adaptor-generated UUID, never {@code null}
 * @param data             the entry's FHIR resource, verbatim
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloudEventDto(
        String specversion,
        String id,
        String source,
        String type,
        String subject,
        String time,
        String datacontenttype,
        String facilityid,
        String sourceeventid,
        String correlationid,
        JsonNode data) {
}
