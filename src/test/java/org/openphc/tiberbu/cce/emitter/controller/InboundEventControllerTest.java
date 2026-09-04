package org.openphc.tiberbu.cce.emitter.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openphc.tiberbu.cce.emitter.model.InboundOutcome;
import org.openphc.tiberbu.cce.emitter.model.InboundRequest;
import org.openphc.tiberbu.cce.emitter.model.ProcessedEventsResponse;
import org.openphc.tiberbu.cce.emitter.model.TransformationResult;
import org.openphc.tiberbu.cce.emitter.service.InboundEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InboundEventController.class)
class InboundEventControllerTest {

    private static final String CONSENT_BUNDLE_ENVELOPE = """
            {"meta":{"bundleId":"VCR-20260901-57098420"},
             "resource":{"resourceType":"Bundle","type":"transaction","entry":[]}}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InboundEventService inboundEventService;

    @Test
    @DisplayName("a forwarded bundle answers 202 with the per-event receipt")
    void returnsProcessedReceiptWhenEventsWereForwarded() throws Exception {
        given(inboundEventService.process(any())).willReturn(
                InboundOutcome.accepted(ProcessedEventsResponse.from(List.of(
                        new TransformationResult(
                                "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                                "Consent",
                                "KE-SHRP-170CDF0A-1363-4972-B36A",
                                TransformationResult.COLLECTOR_STATUS_ACCEPTED)))));

        mockMvc.perform(post("/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONSENT_BUNDLE_ENVELOPE))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("processed"))
                .andExpect(jsonPath("$.eventsForwarded").value(1))
                .andExpect(jsonPath("$.events[0].eventId").value("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
                .andExpect(jsonPath("$.events[0].type").value("Consent"))
                .andExpect(jsonPath("$.events[0].subject").value("KE-SHRP-170CDF0A-1363-4972-B36A"))
                .andExpect(jsonPath("$.events[0].collectorStatus").value("accepted"));
    }

    @Test
    @DisplayName("an unforwardable payload answers 200 ignored")
    void returnsIgnoredAcknowledgement() throws Exception {
        given(inboundEventService.process(any()))
                .willReturn(InboundOutcome.ignored("Non-processable payload"));

        mockMvc.perform(post("/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ignored"))
                .andExpect(jsonPath("$.message").value("Non-processable payload"));
    }

    @Test
    @DisplayName("a filtered facility answers 200 skipped")
    void returnsSkippedAcknowledgement() throws Exception {
        given(inboundEventService.process(any())).willReturn(InboundOutcome.skipped(
                "Event skipped by facility filter: facilityId='9999' source='tiberbu'"));

        mockMvc.perform(post("/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONSENT_BUNDLE_ENVELOPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.message")
                        .value("Event skipped by facility filter: facilityId='9999' source='tiberbu'"));
    }

    @Test
    @DisplayName("any header sent still reaches the service, generically")
    void anyHeaderSentReachesTheService() throws Exception {
        given(inboundEventService.process(any()))
                .willReturn(InboundOutcome.ignored("Non-processable payload"));

        mockMvc.perform(post("/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-001")
                        .content(CONSENT_BUNDLE_ENVELOPE))
                .andExpect(status().isOk());

        ArgumentCaptor<InboundRequest> capturedRequest = ArgumentCaptor.forClass(InboundRequest.class);
        org.mockito.Mockito.verify(inboundEventService).process(capturedRequest.capture());

        InboundRequest inboundRequest = capturedRequest.getValue();
        assertThat(inboundRequest.getHeader("X-Request-Id")).contains("req-001");
        assertThat(inboundRequest.getRawBody()).isEqualTo(CONSENT_BUNDLE_ENVELOPE);
        assertThat(inboundRequest.getRequestPath()).isEqualTo("/inbound");
    }

    @Test
    @DisplayName("a request with no custom headers still succeeds")
    void noCustomHeadersStillSucceeds() throws Exception {
        given(inboundEventService.process(any()))
                .willReturn(InboundOutcome.ignored("Non-processable payload"));

        mockMvc.perform(post("/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONSENT_BUNDLE_ENVELOPE))
                .andExpect(status().isOk());

        ArgumentCaptor<InboundRequest> capturedRequest = ArgumentCaptor.forClass(InboundRequest.class);
        org.mockito.Mockito.verify(inboundEventService).process(capturedRequest.capture());
        assertThat(capturedRequest.getValue().getHeader("X-Facility-Id")).isEmpty();
    }

    @Test
    @DisplayName("a non-JSON body reaches the service instead of being rejected as 415")
    void acceptsNonJsonBodiesSoTheyCanBeIgnored() throws Exception {
        given(inboundEventService.process(any()))
                .willReturn(InboundOutcome.ignored("Non-processable payload"));

        mockMvc.perform(post("/inbound")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json at all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));
    }

    @Test
    @DisplayName("an empty body reaches the service instead of being rejected as 400")
    void acceptsAnEmptyBody() throws Exception {
        given(inboundEventService.process(any()))
                .willReturn(InboundOutcome.ignored("Non-processable payload"));

        mockMvc.perform(post("/inbound").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));

        ArgumentCaptor<InboundRequest> capturedRequest = ArgumentCaptor.forClass(InboundRequest.class);
        org.mockito.Mockito.verify(inboundEventService).process(capturedRequest.capture());
        assertThat(capturedRequest.getValue().getRawBody()).isNull();
    }

    @Test
    @DisplayName("GET, PUT and DELETE on /inbound are 405")
    void rejectsEveryMethodExceptPost() throws Exception {
        mockMvc.perform(get("/inbound")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/inbound")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/inbound")).andExpect(status().isMethodNotAllowed());

        org.mockito.Mockito.verifyNoInteractions(inboundEventService);
    }
}
