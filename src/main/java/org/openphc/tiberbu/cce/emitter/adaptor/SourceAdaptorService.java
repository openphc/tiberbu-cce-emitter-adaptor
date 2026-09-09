package org.openphc.tiberbu.cce.emitter.adaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.openphc.tiberbu.cce.emitter.cloudevents.CloudEventEnvelopeBuilder;
import org.openphc.tiberbu.cce.emitter.config.EmitterProperties;
import org.openphc.tiberbu.cce.emitter.exception.FhirMappingException;
import org.openphc.tiberbu.cce.emitter.exception.PatientIdNotFoundException;
import org.openphc.tiberbu.cce.emitter.exception.FacilityFilterRejectedException;
import org.openphc.tiberbu.cce.emitter.fhir.BundleEntry;
import org.openphc.tiberbu.cce.emitter.fhir.BundleEntryExtractor;
import org.openphc.tiberbu.cce.emitter.fhir.FacilityIdExtractor;
import org.openphc.tiberbu.cce.emitter.fhir.FhirResourceParser;
import org.openphc.tiberbu.cce.emitter.fhir.PatientIdExtractor;
import org.openphc.tiberbu.cce.emitter.filter.FacilityFilter;
import org.openphc.tiberbu.cce.emitter.model.BundleEntryResult;
import org.openphc.tiberbu.cce.emitter.model.CloudEventDto;
import org.openphc.tiberbu.cce.emitter.model.InboundRequest;
import org.openphc.tiberbu.cce.emitter.model.SourceMetadata;
import org.openphc.tiberbu.cce.emitter.model.TransformationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transforms one inbound tibERbu request into a list of per-entry outcomes —
 * every candidate bundle entry either ready to forward, or already terminal
 * (facility-filtered, or failed).
 *
 * <p>Per entry: parse the FHIR resource (E4), extract the patient identifier
 * (E5), resolve the facility ID and build {@link SourceMetadata} (E6/E8),
 * enforce the facility filter (E7), then build the CloudEvent (E8). Entries
 * are independent of each other — one entry's failure or filtering never
 * prevents the rest of the bundle from being attempted; see {@link
 * BundleEntryResult} and {@code InboundEventService} for how each entry's fate is
 * carried forward and eventually reported.
 *
 * <p>Forwarding to the Collector (E9) deliberately does NOT happen here —
 * {@link #processBundleEntries} only builds CloudEvents; {@code InboundEventService} calls
 * {@code CollectorForwardingService} itself, so it can populate MDC and
 * collect the final {@link TransformationResult} per entry in one place.
 */
@Component
public class SourceAdaptorService {

    private static final Logger log = LoggerFactory.getLogger(SourceAdaptorService.class);

    private static final String META_FIELD = "meta";
    private static final String TRACE_ID_FIELD = "traceId";

    private final BundleEntryExtractor bundleEntryExtractor;
    private final FhirResourceParser fhirResourceParser;
    private final PatientIdExtractor patientIdExtractor;
    private final FacilityIdExtractor facilityIdExtractor;
    private final FacilityFilter facilityFilter;
    private final CloudEventEnvelopeBuilder cloudEventEnvelopeBuilder;
    private final ObjectMapper objectMapper;
    private final String sourceIdentifier;

    public SourceAdaptorService(
            BundleEntryExtractor bundleEntryExtractor,
            FhirResourceParser fhirResourceParser,
            PatientIdExtractor patientIdExtractor,
            FacilityIdExtractor facilityIdExtractor,
            FacilityFilter facilityFilter,
            CloudEventEnvelopeBuilder cloudEventEnvelopeBuilder,
            ObjectMapper objectMapper,
            EmitterProperties emitterProperties) {
        this.bundleEntryExtractor = bundleEntryExtractor;
        this.fhirResourceParser = fhirResourceParser;
        this.patientIdExtractor = patientIdExtractor;
        this.facilityIdExtractor = facilityIdExtractor;
        this.facilityFilter = facilityFilter;
        this.cloudEventEnvelopeBuilder = cloudEventEnvelopeBuilder;
        this.objectMapper = objectMapper;
        this.sourceIdentifier = emitterProperties.source();
    }

    /**
     * Adapts every candidate entry in one inbound request.
     *
     * <p>Request-level metadata (source, correlation id, processing time,
     * request path, trace id) is resolved once, before the loop — it's the
     * same for every entry in the bundle. Entry-level metadata (facility id,
     * bundle entry index) is resolved fresh per entry inside {@link
     * #processBundleEntry}.
     *
     * @param inboundRequest the normalized inbound request
     * @return one {@link BundleEntryResult} per candidate bundle entry, in bundle
     *         order; empty when the body matches none of the bundle contract
     *         (not JSON, not a Bundle, empty {@code entry[]}, or every entry
     *         confirmed to be a Patient)
     */
    public List<BundleEntryResult> processBundleEntries(InboundRequest inboundRequest) {
        String rawRequestBody = inboundRequest.getRawBody();
        // skips any entry confirmed to be a Patient; every other entry comes back as a candidate
        List<BundleEntry> bundleEntries = bundleEntryExtractor.extract(rawRequestBody);
        if (bundleEntries.isEmpty()) {
            return List.of(); // no candidates at all — caller reports this as "ignored"
        }

        String correlationId = UUID.randomUUID().toString(); // same value stamped on every entry in this bundle
        OffsetDateTime eventTime = OffsetDateTime.now(ZoneOffset.UTC); // adaptor's own processing time, never meta.timestamp
        String traceId = extractTraceId(rawRequestBody); // meta.traceId — primary key for the deterministic event id
        String sourcePath = inboundRequest.getRequestPath();

        List<BundleEntryResult> bundleEntryResults = new ArrayList<>(bundleEntries.size());
        for (BundleEntry bundleEntry : bundleEntries) {
            // each entry is processed independently — one entry's outcome never affects another
            bundleEntryResults.add(processBundleEntry(bundleEntry, correlationId, eventTime, traceId, sourcePath));
        }
        return bundleEntryResults;
    }

    /**
     * Processes a single bundle entry through parsing, patient/facility
     * extraction, the facility filter, and CloudEvent building.
     *
     * <p>{@code patientId} is declared outside the try block on purpose:
     * if patient extraction itself throws, it's never assigned and stays
     * {@code null} in the resulting {@link TransformationResult#failed}; if
     * parsing succeeds and patient extraction does too, it survives into the
     * facility-filter and CloudEvent-building steps below it.
     */
    private BundleEntryResult processBundleEntry(
            BundleEntry bundleEntry, String correlationId, OffsetDateTime eventTime, String traceId, String sourcePath) {
        String patientId = null; // declared here so it still reaches the FAILED result even if extraction itself throws
        try {
            IBaseResource resource = fhirResourceParser.parse(bundleEntry.resourceJson());
            patientId = patientIdExtractor.extract(resource);
            String facilityId = facilityIdExtractor.extract(resource);

            SourceMetadata sourceMetadata = new SourceMetadata(
                    sourceIdentifier, facilityId, correlationId, eventTime, sourcePath, traceId, bundleEntry.bundleEntryIndex());

            try {
                facilityFilter.enforceFilter(facilityId, sourceIdentifier);
            } catch (FacilityFilterRejectedException filterDenied) {
                // a denial is a terminal SKIPPED outcome for this entry only, not an aborting exception
                return BundleEntryResult.skipped(TransformationResult.skipped(
                        bundleEntry.bundleEntryIndex(), bundleEntry.resourceType(), patientId, filterDenied.getMessage()));
            }

            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    bundleEntry.resourceJson(), patientId, bundleEntry.resourceType(), sourceMetadata);
            return BundleEntryResult.readyToForward(bundleEntry.bundleEntryIndex(), cloudEvent);

        } catch (FhirMappingException | PatientIdNotFoundException processingFailure) {
            log.warn("Entry[{}] ({}) failed to process: {}",
                    bundleEntry.bundleEntryIndex(), bundleEntry.resourceType(), processingFailure.getMessage());
            // this entry is FAILED, but the caller's loop still attempts every remaining sibling entry
            return BundleEntryResult.failed(
                    TransformationResult.failed(
                            bundleEntry.bundleEntryIndex(), bundleEntry.resourceType(), patientId, processingFailure.getMessage()),
                    processingFailure);
        }
    }

    /**
     * Reads {@code meta.traceId} from the inbound envelope — the primary
     * input to {@code EventIdGenerator}'s deterministic CloudEvents id.
     *
     * <p>Re-parses {@code rawRequestBody} independently of {@link
     * #bundleEntryExtractor}, which deliberately never reads {@code meta} at
     * all (see its own class javadoc) — keeping that extractor's
     * single-purpose contract intact was judged worth one extra cheap JSON
     * parse here, done once per request rather than per entry.
     *
     * @return {@code meta.traceId}, or {@code null} when the body isn't valid
     *         JSON, has no {@code meta} object, or {@code traceId} is absent/blank
     */
    private String extractTraceId(String rawRequestBody) {
        if (rawRequestBody == null || rawRequestBody.isBlank()) {
            return null;
        }
        try {
            JsonNode envelopeNode = objectMapper.readTree(rawRequestBody);
            String traceId = envelopeNode.path(META_FIELD).path(TRACE_ID_FIELD).asText(null);
            return (traceId != null && !traceId.isBlank()) ? traceId : null;
        } catch (Exception malformedOrAbsent) {
            return null; // malformed JSON or a missing meta/traceId — treated the same as "absent"
        }
    }
}
