package org.openphc.tiberbu.cce.emitter.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.tiberbu.cce.emitter.adaptor.SourceAdaptorService;
import org.openphc.tiberbu.cce.emitter.config.EmitterProperties;
import org.openphc.tiberbu.cce.emitter.exception.CollectorClientException;
import org.openphc.tiberbu.cce.emitter.exception.CollectorForwardingException;
import org.openphc.tiberbu.cce.emitter.model.BundleEntryResult;
import org.openphc.tiberbu.cce.emitter.model.CloudEventDto;
import org.openphc.tiberbu.cce.emitter.model.CollectorResponse;
import org.openphc.tiberbu.cce.emitter.model.InboundOutcome;
import org.openphc.tiberbu.cce.emitter.model.InboundRequest;
import org.openphc.tiberbu.cce.emitter.model.ProcessedEventsResponse;
import org.openphc.tiberbu.cce.emitter.model.TransformationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates one inbound request: bundle extraction and per-entry
 * adaptation ({@code SourceAdaptorService}), Collector forwarding ({@code
 * CollectorForwardingService}), and deciding between the three response
 * outcomes.
 *
 * <h2>Multi-entry failure policy</h2>
 * Every candidate bundle entry is processed independently — one entry's
 * facility-filter denial or failure never prevents the rest of the bundle
 * from being attempted. This is deliberate, not an oversight: forwarding to
 * the Collector is an irreversible side effect. If entry A forwards
 * successfully and entry B then fails, there is no way to "undo" A — so the
 * response reports exactly what happened to each entry, and the request as a
 * whole is treated as a success ({@code 202}, or {@code 200 skipped}) as long
 * as at least one entry forwarded or was cleanly filtered. Reporting the
 * whole request as failed in that situation would misrepresent something
 * that already, irreversibly, happened.
 *
 * <p>Only when EVERY candidate entry ends in a genuine failure (none
 * forwarded, none filtered) does this class re-throw — the first entry's
 * original exception, letting it flow through the normal exception-handling
 * path (E11) instead of inventing a separate all-failed response shape.
 *
 * <p><b>Example.</b> A 3-entry bundle where entry 0 ({@code Consent}) forwards
 * successfully, entry 1 ({@code Observation}) is denied by the facility
 * filter, and entry 2 ({@code Encounter}) fails to parse: the response is
 * still {@code 202 processed} (one entry forwarded), with all three outcomes
 * listed in {@code events[]} — {@code forwarded}, {@code skipped}, and {@code
 * failed} respectively. Only if entry 0 had also failed or been filtered
 * (zero entries forwarded or skipped) would this instead re-throw entry 0's
 * own exception.
 */
@Service
public class InboundEventService {

    private static final Logger log = LoggerFactory.getLogger(InboundEventService.class);

    static final String NON_PROCESSABLE_PAYLOAD = "Non-processable payload";

    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_SOURCE = "source";
    private static final String MDC_EVENT_TYPE = "eventType";
    private static final String MDC_SUBJECT = "subject";

    private final SourceAdaptorService sourceAdaptorService;
    private final CollectorForwardingService collectorForwardingService;
    private final MeterRegistry meterRegistry;
    private final Counter entriesForwardedCounter;
    private final Counter entriesDuplicateCounter;
    private final Counter entriesReceivedCounter;
    private final String sourceIdentifier;

    public InboundEventService(
            SourceAdaptorService sourceAdaptorService,
            CollectorForwardingService collectorForwardingService,
            MeterRegistry meterRegistry,
            EmitterProperties emitterProperties) {
        this.sourceAdaptorService = sourceAdaptorService;
        this.collectorForwardingService = collectorForwardingService;
        this.meterRegistry = meterRegistry;
        this.sourceIdentifier = emitterProperties.source();
        this.entriesForwardedCounter = Counter.builder("tiberbu.cce.emitter.entries.forwarded")
                .description("CloudEvents that reached the Collector (accepted or duplicate)")
                .tag("source", sourceIdentifier)
                .register(meterRegistry);
        this.entriesDuplicateCounter = Counter.builder("tiberbu.cce.emitter.entries.duplicate")
                .description("Forwarded CloudEvents the Collector reported as already-ingested duplicates")
                .register(meterRegistry);
        this.entriesReceivedCounter = Counter.builder("tiberbu.cce.emitter.entries.received")
                .description("Candidate bundle entries extracted from an inbound request, before any outcome is decided — "
                        + "the true entry-level denominator for entries.forwarded/duplicate/rejected/filtered/failed ratios")
                .tag("source", sourceIdentifier)
                .register(meterRegistry);
    }

    /**
     * @param inboundRequest the normalized inbound request
     * @return the outcome — HTTP status plus the body to serialize
     */
    public InboundOutcome process(InboundRequest inboundRequest) {
        // source/path are both fixed per adaptor instance, but registered per-call rather than cached
        // as a field — same reason FacilityFilter does this — Micrometer looks up-or-creates by
        // name+tags, so this is cheap and keeps the tagging logic next to where the values are known.
        // NOTE: one bundle can hold several candidate entries, so entries.forwarded routinely exceeds
        // events.received (request-level) — they are never meant to move 1:1. entries.received, set
        // below, is the correct entry-level denominator for forwarded/duplicate/rejected/filtered/failed
        // ratios; see forwardEach's own javadoc.
        Counter.builder("tiberbu.cce.emitter.events.received")
                .description("Inbound requests received")
                .tag("source", sourceIdentifier)
                .tag("path", inboundRequest.getRequestPath())
                .register(meterRegistry)
                .increment();

        // parses the Bundle, skips any Patient entry, and builds a CloudEvent per remaining candidate
        List<BundleEntryResult> bundleEntryResults = sourceAdaptorService.processBundleEntries(inboundRequest);
        entriesReceivedCounter.increment(bundleEntryResults.size());
        if (bundleEntryResults.isEmpty()) {
            // e.g. the body wasn't a Bundle, entry[] was empty, or every entry was a Patient
            log.debug("Request on path '{}' produced no candidate entries — 200 ignored", inboundRequest.getRequestPath());
            return InboundOutcome.ignored(NON_PROCESSABLE_PAYLOAD);
        }

        List<TransformationResult> transformationResults = new ArrayList<>(bundleEntryResults.size());
        RuntimeException firstFailureCause = forwardEach(bundleEntryResults, transformationResults); // fills transformationResults, in bundle order

        long forwardedCount = countByOutcome(transformationResults, TransformationResult.OUTCOME_FORWARDED);
        if (forwardedCount > 0) {
            // at least one entry reached the Collector — success, even alongside a failed or skipped sibling entry
            return InboundOutcome.accepted(ProcessedEventsResponse.from(transformationResults));
        }

        boolean anySkipped = countByOutcome(transformationResults, TransformationResult.OUTCOME_SKIPPED) > 0;
        if (anySkipped) {
            // none forwarded, but at least one was cleanly facility-filtered — still a success, just nothing left the building
            return InboundOutcome.skipped(ProcessedEventsResponse.from(transformationResults));
        }

        // Every entry failed — surface the first one through the normal exception
        // path (E11) rather than inventing a separate all-failed response shape.
        // firstFailureCause is guaranteed non-null here: bundleEntryResults was
        // non-empty, and every entry that isn't forwarded or skipped is failed,
        // which always carries a cause (see SourceAdaptorService/forwardEach).
        throw firstFailureCause;
    }

    /**
     * Forwards every ready-to-forward entry, and passes every already-terminal
     * entry straight through — building {@code transformationResults} in bundle order and
     * returning the first genuine failure's cause (or {@code null} if none
     * occurred), for {@link #process}'s all-failed check.
     *
     * <p>Example — {@code bundleEntryResults} of size 2, entry 0 already
     * terminal (SKIPPED by the facility filter) and entry 1 ready to forward:
     * this adds entry 0's {@code terminalResult()} to {@code transformationResults}
     * unchanged, then calls {@code collectorForwardingService.forward(...)}
     * for entry 1 and appends whatever {@code TransformationResult} that
     * produces (forwarded, or failed if the Collector call itself throws).
     */
    private RuntimeException forwardEach(List<BundleEntryResult> bundleEntryResults, List<TransformationResult> transformationResults) {
        RuntimeException firstFailureCause = null;

        for (BundleEntryResult bundleEntryResult : bundleEntryResults) {
            if (!bundleEntryResult.isReadyToForward()) {
                // already skipped or failed inside SourceAdaptorService — nothing to forward, pass its result through as-is
                TransformationResult terminalResult = bundleEntryResult.terminalResult();
                transformationResults.add(terminalResult);
                if (TransformationResult.OUTCOME_FAILED.equals(terminalResult.outcome())) {
                    // adaptation-stage failure (FHIR parsing / patient-identifier) — the one failure kind with
                    // no other counter: forwarding-stage failures already have entries.rejected (4xx) and
                    // collector.retries (5xx exhausted); this is what's left uncounted before this metric existed
                    recordAdaptationFailure(bundleEntryResult.failureCause());
                }
                if (firstFailureCause == null) {
                    firstFailureCause = bundleEntryResult.failureCause(); // null for a SKIPPED entry, non-null for a FAILED one
                }
                continue;
            }

            CloudEventDto cloudEvent = bundleEntryResult.cloudEventToForward();
            populateMdc(cloudEvent); // correlationId/source/eventType/subject — scoped to this entry's forward attempt only
            try {
                CollectorResponse collectorResponse = collectorForwardingService.forward(cloudEvent);
                TransformationResult forwardedResult =
                        TransformationResult.forwarded(bundleEntryResult.bundleEntryIndex(), cloudEvent, collectorResponse);
                transformationResults.add(forwardedResult);
                entriesForwardedCounter.increment();
                if (TransformationResult.COLLECTOR_STATUS_DUPLICATE.equals(forwardedResult.collectorStatus())) {
                    entriesDuplicateCounter.increment();
                }
            } catch (CollectorClientException | CollectorForwardingException forwardingFailure) {
                log.warn("Entry[{}] ({}) failed to forward: {}",
                        bundleEntryResult.bundleEntryIndex(), cloudEvent.type(), forwardingFailure.getMessage());
                // this entry becomes FAILED, but the loop still attempts every remaining sibling entry
                transformationResults.add(TransformationResult.failed(
                        bundleEntryResult.bundleEntryIndex(), cloudEvent.type(), cloudEvent.subject(), forwardingFailure.getMessage()));
                if (firstFailureCause == null) {
                    firstFailureCause = forwardingFailure;
                }
            } finally {
                MDC.clear(); // never leaks into the next entry's log lines, forwarded or not
            }
        }

        return firstFailureCause;
    }

    /**
     * @param adaptationFailure the exception {@code SourceAdaptorService} caught while parsing the FHIR
     *                          resource or extracting the patient identifier — never {@code null} here,
     *                          per {@link BundleEntryResult#failed}'s own contract
     */
    private void recordAdaptationFailure(RuntimeException adaptationFailure) {
        Counter.builder("tiberbu.cce.emitter.entries.failed")
                .description("Bundle entries that failed during adaptation, before ever reaching the Collector")
                .tag("source", sourceIdentifier)
                .tag("reason", adaptationFailure.getClass().getSimpleName())
                .register(meterRegistry)
                .increment();
    }

    private void populateMdc(CloudEventDto cloudEvent) {
        MDC.put(MDC_CORRELATION_ID, cloudEvent.correlationid());
        MDC.put(MDC_SOURCE, sourceIdentifier);
        MDC.put(MDC_EVENT_TYPE, cloudEvent.type());
        MDC.put(MDC_SUBJECT, cloudEvent.subject());
    }

    private static long countByOutcome(List<TransformationResult> transformationResults, String outcome) {
        return transformationResults.stream().filter(result -> outcome.equals(result.outcome())).count();
    }
}
