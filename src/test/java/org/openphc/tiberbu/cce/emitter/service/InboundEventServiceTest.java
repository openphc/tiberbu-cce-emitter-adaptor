package org.openphc.tiberbu.cce.emitter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.tiberbu.cce.emitter.adaptor.SourceAdaptorService;
import org.openphc.tiberbu.cce.emitter.config.EmitterProperties;
import org.openphc.tiberbu.cce.emitter.exception.CollectorClientException;
import org.openphc.tiberbu.cce.emitter.exception.CollectorForwardingException;
import org.openphc.tiberbu.cce.emitter.exception.PatientIdNotFoundException;
import org.openphc.tiberbu.cce.emitter.model.BundleEntryResult;
import org.openphc.tiberbu.cce.emitter.model.CloudEventDto;
import org.openphc.tiberbu.cce.emitter.model.CollectorResponse;
import org.openphc.tiberbu.cce.emitter.model.InboundOutcome;
import org.openphc.tiberbu.cce.emitter.model.InboundRequest;
import org.openphc.tiberbu.cce.emitter.model.ProcessedEventsResponse;
import org.openphc.tiberbu.cce.emitter.model.TransformationResult;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link InboundEventService}'s orchestration decisions in isolation —
 * {@link SourceAdaptorService} and {@link CollectorForwardingService} are
 * both mocked, so these tests are entirely about the outcome-selection logic
 * (ignored / skipped / accepted / re-thrown), not the real adaptation or
 * forwarding mechanics already covered elsewhere.
 *
 * <p>The central rule under test, per the multi-entry failure policy this
 * class documents: any forwarded or skipped entry makes the whole request a
 * success, even alongside a genuinely failed sibling entry. Only when every
 * entry fails does {@code process} re-throw instead of returning.
 */
@ExtendWith(MockitoExtension.class)
class InboundEventServiceTest {

    private static final String PATIENT_ID = "KE-SHRP-170CDF0A-1363-4972-B36A";

    @Mock
    private SourceAdaptorService sourceAdaptorService;

    @Mock
    private CollectorForwardingService collectorForwardingService;

    private InboundEventService serviceUnderTest() {
        return new InboundEventService(
                sourceAdaptorService, collectorForwardingService, new SimpleMeterRegistry(),
                new EmitterProperties("tiberbu"));
    }

    private InboundRequest sampleRequest() {
        return InboundRequest.from("{}", null, "/inbound");
    }

    private CloudEventDto cloudEvent(String type) {
        return new CloudEventDto("1.0", "evt-" + type, "tiberbu", type, PATIENT_ID,
                "2026-09-01T11:55:42.118Z", "application/fhir+json", "FAC-0001", null,
                "corr-1", new ObjectMapper().createObjectNode().put("resourceType", type));
    }

    private CollectorResponse acceptedResponse() {
        return new CollectorResponse(new CollectorResponse.DataPayload("evt-1", "accepted", "corr-1", "2026-09-01T11:55:43Z"), null);
    }

    private CollectorResponse duplicateResponse() {
        return new CollectorResponse(new CollectorResponse.DataPayload("evt-1", "duplicate", "corr-1", "2026-09-01T11:55:43Z"), null);
    }

    @Nested
    @DisplayName("no candidate entries")
    class NoCandidateEntries {

        @Test
        @DisplayName("an empty adaptation result answers 200 ignored")
        void emptyAdaptationResultAnswersIgnored() {
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of());

            InboundOutcome outcome = serviceUnderTest().process(sampleRequest());

            assertThat(outcome.httpStatus()).isEqualTo(HttpStatus.OK);
            assertThat(outcome.responseBody().toString()).contains("ignored");
        }
    }

    @Nested
    @DisplayName("every entry forwards")
    class EveryEntryForwards {

        @Test
        @DisplayName("a single forwarded entry answers 202 processed")
        void singleForwardedEntryAnswersProcessed() {
            CloudEventDto cloudEvent = cloudEvent("Consent");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(BundleEntryResult.readyToForward(1, cloudEvent)));
            when(collectorForwardingService.forward(cloudEvent)).thenReturn(acceptedResponse());

            InboundOutcome outcome = serviceUnderTest().process(sampleRequest());

            assertThat(outcome.httpStatus()).isEqualTo(HttpStatus.ACCEPTED);
            ProcessedEventsResponse response = (ProcessedEventsResponse) outcome.responseBody();
            assertThat(response.eventsForwarded()).isEqualTo(1);
            assertThat(response.events().get(0).collectorStatus()).isEqualTo("accepted");
        }

        @Test
        @DisplayName("a Collector duplicate is still reported as forwarded, with collectorStatus=duplicate")
        void duplicateCollectorStatusIsStillForwarded() {
            CloudEventDto cloudEvent = cloudEvent("Consent");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(BundleEntryResult.readyToForward(1, cloudEvent)));
            when(collectorForwardingService.forward(cloudEvent)).thenReturn(duplicateResponse());

            InboundOutcome outcome = serviceUnderTest().process(sampleRequest());

            assertThat(outcome.httpStatus()).isEqualTo(HttpStatus.ACCEPTED);
            ProcessedEventsResponse response = (ProcessedEventsResponse) outcome.responseBody();
            assertThat(response.events().get(0).outcome()).isEqualTo(TransformationResult.OUTCOME_FORWARDED);
            assertThat(response.events().get(0).collectorStatus()).isEqualTo("duplicate");
        }

        @Test
        @DisplayName("multiple forwarded entries all count toward eventsForwarded")
        void multipleForwardedEntriesAllCount() {
            CloudEventDto firstEvent = cloudEvent("Consent");
            CloudEventDto secondEvent = cloudEvent("Observation");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(
                    BundleEntryResult.readyToForward(1, firstEvent), BundleEntryResult.readyToForward(2, secondEvent)));
            when(collectorForwardingService.forward(firstEvent)).thenReturn(acceptedResponse());
            when(collectorForwardingService.forward(secondEvent)).thenReturn(acceptedResponse());

            InboundOutcome outcome = serviceUnderTest().process(sampleRequest());

            assertThat(outcome.httpStatus()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(((ProcessedEventsResponse) outcome.responseBody()).eventsForwarded()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("every entry skipped or failed — nothing forwards")
    class NothingForwards {

        @Test
        @DisplayName("a single facility-filtered entry answers 200 skipped")
        void singleSkippedEntryAnswersSkipped() {
            TransformationResult skippedResult = TransformationResult.skipped(1, "Consent", PATIENT_ID, "NOT_IN_ALLOWLIST");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(BundleEntryResult.skipped(skippedResult)));

            InboundOutcome outcome = serviceUnderTest().process(sampleRequest());

            assertThat(outcome.httpStatus()).isEqualTo(HttpStatus.OK);
            ProcessedEventsResponse response = (ProcessedEventsResponse) outcome.responseBody();
            assertThat(response.status()).isEqualTo(ProcessedEventsResponse.STATUS_SKIPPED);
            assertThat(response.eventsForwarded()).isZero();
        }

        @Test
        @DisplayName("when every entry fails, the first entry's original exception is re-thrown")
        void allEntriesFailedRethrowsTheFirstFailure() {
            PatientIdNotFoundException originalFailure = new PatientIdNotFoundException("no patient reference");
            TransformationResult failedResult = TransformationResult.failed(1, "Observation", null, "no patient reference");
            when(sourceAdaptorService.processBundleEntries(any()))
                    .thenReturn(List.of(BundleEntryResult.failed(failedResult, originalFailure)));

            assertThatExceptionOfType(PatientIdNotFoundException.class)
                    .isThrownBy(() -> serviceUnderTest().process(sampleRequest()))
                    .isSameAs(originalFailure);
        }

        @Test
        @DisplayName("when the only entry's forwarding fails, that forwarding exception is re-thrown")
        void onlyEntryForwardingFailureIsRethrown() {
            CloudEventDto cloudEvent = cloudEvent("Consent");
            CollectorForwardingException forwardingFailure = new CollectorForwardingException("Collector unreachable");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(BundleEntryResult.readyToForward(1, cloudEvent)));
            when(collectorForwardingService.forward(cloudEvent)).thenThrow(forwardingFailure);

            assertThatExceptionOfType(CollectorForwardingException.class)
                    .isThrownBy(() -> serviceUnderTest().process(sampleRequest()))
                    .isSameAs(forwardingFailure);
        }
    }

    @Nested
    @DisplayName("mixed outcomes — any forwarded or skipped entry wins over a failed sibling")
    class MixedOutcomes {

        @Test
        @DisplayName("one forwarded entry alongside one failed entry still answers 202, with both outcomes in the body")
        void oneForwardedAndOneFailedStillAnswersProcessed() {
            CloudEventDto forwardableEvent = cloudEvent("Consent");
            TransformationResult failedResult = TransformationResult.failed(2, "Observation", null, "missing required field");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(
                    BundleEntryResult.readyToForward(1, forwardableEvent),
                    BundleEntryResult.failed(failedResult, new PatientIdNotFoundException("missing required field"))));
            when(collectorForwardingService.forward(forwardableEvent)).thenReturn(acceptedResponse());

            InboundOutcome outcome = serviceUnderTest().process(sampleRequest());

            assertThat(outcome.httpStatus()).isEqualTo(HttpStatus.ACCEPTED);
            ProcessedEventsResponse response = (ProcessedEventsResponse) outcome.responseBody();
            assertThat(response.eventsForwarded()).isEqualTo(1);
            assertThat(response.events()).hasSize(2);
            assertThat(response.events().get(1).outcome()).isEqualTo(TransformationResult.OUTCOME_FAILED);
        }

        @Test
        @DisplayName("one skipped entry alongside one failed entry (nothing forwarded) still answers 200 skipped")
        void oneSkippedAndOneFailedStillAnswersSkipped() {
            TransformationResult skippedResult = TransformationResult.skipped(1, "Consent", PATIENT_ID, "NOT_IN_ALLOWLIST");
            TransformationResult failedResult = TransformationResult.failed(2, "Observation", null, "missing required field");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(
                    BundleEntryResult.skipped(skippedResult),
                    BundleEntryResult.failed(failedResult, new PatientIdNotFoundException("missing required field"))));

            InboundOutcome outcome = serviceUnderTest().process(sampleRequest());

            assertThat(outcome.httpStatus()).isEqualTo(HttpStatus.OK);
            ProcessedEventsResponse response = (ProcessedEventsResponse) outcome.responseBody();
            assertThat(response.status()).isEqualTo(ProcessedEventsResponse.STATUS_SKIPPED);
            assertThat(response.events()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("MDC lifecycle")
    class MdcLifecycle {

        @Test
        @DisplayName("MDC carries the CloudEvent's identity during forwarding, and is cleared once process() returns")
        void mdcIsPopulatedDuringForwardAndClearedAfter() {
            CloudEventDto cloudEvent = cloudEvent("Consent");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(BundleEntryResult.readyToForward(1, cloudEvent)));

            String[] observedSubjectDuringForward = new String[1];
            when(collectorForwardingService.forward(cloudEvent)).thenAnswer(invocation -> {
                observedSubjectDuringForward[0] = MDC.get("subject");
                return acceptedResponse();
            });

            serviceUnderTest().process(sampleRequest());

            assertThat(observedSubjectDuringForward[0]).isEqualTo(PATIENT_ID);
            assertThat(MDC.get("correlationId")).isNull();
            assertThat(MDC.get("source")).isNull();
            assertThat(MDC.get("eventType")).isNull();
            assertThat(MDC.get("subject")).isNull();
        }

        @Test
        @DisplayName("MDC is still cleared when forwarding throws")
        void mdcIsClearedEvenWhenForwardingThrows() {
            CloudEventDto cloudEvent = cloudEvent("Consent");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(BundleEntryResult.readyToForward(1, cloudEvent)));
            when(collectorForwardingService.forward(cloudEvent))
                    .thenThrow(new CollectorForwardingException("Collector unreachable"));

            assertThatExceptionOfType(CollectorForwardingException.class)
                    .isThrownBy(() -> serviceUnderTest().process(sampleRequest()));

            assertThat(MDC.get("correlationId")).isNull();
            assertThat(MDC.get("subject")).isNull();
        }
    }

    @Nested
    @DisplayName("metrics")
    class Metrics {

        @Test
        @DisplayName("events.received is incremented exactly once per process() call, regardless of outcome")
        void eventsReceivedIncrementedOncePerCall() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            InboundEventService service = new InboundEventService(
                    sourceAdaptorService, collectorForwardingService, meterRegistry, new EmitterProperties("tiberbu"));
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of());

            service.process(sampleRequest());
            service.process(sampleRequest());

            double receivedCount = meterRegistry.counter(
                    "tiberbu.cce.emitter.events.received", "source", "tiberbu", "path", "/inbound").count();
            assertThat(receivedCount).isEqualTo(2.0);
        }

        @Test
        @DisplayName("entries.forwarded counts every entry that reaches the Collector, accepted or duplicate alike")
        void eventsForwardedCountsEveryEntryThatReachesTheCollector() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            InboundEventService service = new InboundEventService(
                    sourceAdaptorService, collectorForwardingService, meterRegistry, new EmitterProperties("tiberbu"));
            CloudEventDto accepted = cloudEvent("Consent");
            CloudEventDto duplicate = cloudEvent("Observation");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(
                    BundleEntryResult.readyToForward(0, accepted),
                    BundleEntryResult.readyToForward(1, duplicate)));
            when(collectorForwardingService.forward(accepted)).thenReturn(acceptedResponse());
            when(collectorForwardingService.forward(duplicate)).thenReturn(duplicateResponse());

            service.process(sampleRequest());

            assertThat(meterRegistry.counter("tiberbu.cce.emitter.entries.forwarded", "source", "tiberbu").count())
                    .isEqualTo(2.0);
        }

        @Test
        @DisplayName("entries.duplicate counts only the forwarded entries the Collector reports as duplicate")
        void eventsDuplicateCountsOnlyDuplicateOutcomes() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            InboundEventService service = new InboundEventService(
                    sourceAdaptorService, collectorForwardingService, meterRegistry, new EmitterProperties("tiberbu"));
            CloudEventDto accepted = cloudEvent("Consent");
            CloudEventDto duplicate = cloudEvent("Observation");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(
                    BundleEntryResult.readyToForward(0, accepted),
                    BundleEntryResult.readyToForward(1, duplicate)));
            when(collectorForwardingService.forward(accepted)).thenReturn(acceptedResponse());
            when(collectorForwardingService.forward(duplicate)).thenReturn(duplicateResponse());

            service.process(sampleRequest());

            assertThat(meterRegistry.counter("tiberbu.cce.emitter.entries.duplicate").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("entries.received counts every candidate bundle entry, regardless of its eventual outcome")
        void entriesReceivedCountsEveryCandidateEntryRegardlessOfOutcome() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            InboundEventService service = new InboundEventService(
                    sourceAdaptorService, collectorForwardingService, meterRegistry, new EmitterProperties("tiberbu"));
            CloudEventDto forwardable = cloudEvent("Consent");
            TransformationResult skippedResult = TransformationResult.skipped(1, "Consent", PATIENT_ID, "NOT_IN_ALLOWLIST");
            TransformationResult failedResult = TransformationResult.failed(2, "Observation", null, "no patient reference");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(
                    BundleEntryResult.readyToForward(0, forwardable),
                    BundleEntryResult.skipped(skippedResult),
                    BundleEntryResult.failed(failedResult, new PatientIdNotFoundException("no patient reference"))));
            when(collectorForwardingService.forward(forwardable)).thenReturn(acceptedResponse());

            service.process(sampleRequest());

            assertThat(meterRegistry.counter("tiberbu.cce.emitter.entries.received", "source", "tiberbu").count())
                    .isEqualTo(3.0);
        }

        @Test
        @DisplayName("entries.failed counts only adaptation-stage failures, tagged with the exception's simple name")
        void entriesFailedCountsOnlyAdaptationStageFailures() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            InboundEventService service = new InboundEventService(
                    sourceAdaptorService, collectorForwardingService, meterRegistry, new EmitterProperties("tiberbu"));
            CloudEventDto forwardable = cloudEvent("Consent");
            TransformationResult skippedResult = TransformationResult.skipped(1, "Consent", PATIENT_ID, "NOT_IN_ALLOWLIST");
            TransformationResult failedResult = TransformationResult.failed(2, "Observation", null, "no patient reference");
            when(sourceAdaptorService.processBundleEntries(any())).thenReturn(List.of(
                    BundleEntryResult.readyToForward(0, forwardable),
                    BundleEntryResult.skipped(skippedResult), // must NOT be counted as a failure
                    BundleEntryResult.failed(failedResult, new PatientIdNotFoundException("no patient reference"))));
            when(collectorForwardingService.forward(forwardable)).thenReturn(acceptedResponse());

            service.process(sampleRequest());

            assertThat(meterRegistry.counter(
                    "tiberbu.cce.emitter.entries.failed", "source", "tiberbu", "reason", "PatientIdNotFoundException").count())
                    .isEqualTo(1.0);
        }
    }
}
