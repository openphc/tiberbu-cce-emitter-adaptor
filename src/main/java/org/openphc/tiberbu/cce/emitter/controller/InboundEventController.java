package org.openphc.tiberbu.cce.emitter.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.openphc.tiberbu.cce.emitter.model.InboundOutcome;
import org.openphc.tiberbu.cce.emitter.model.InboundRequest;
import org.openphc.tiberbu.cce.emitter.service.InboundEventService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The adaptor's single inbound endpoint.
 *
 * <p>Deliberately thin: it assembles an {@link InboundRequest}, hands it to the
 * service layer and turns the returned {@link InboundOutcome} into a response.
 * No parsing, no filtering, no business rules live here.
 */
@RestController
public class InboundEventController {

    /** The one mapped path. Only POST is bound, so other methods get 405. */
    public static final String INBOUND_PATH = "/inbound";

    private final InboundEventService inboundEventService;

    public InboundEventController(InboundEventService inboundEventService) {
        this.inboundEventService = inboundEventService;
    }

    /**
     * Receives a tibERbu bundle payload.
     *
     * <p>No {@code consumes} restriction is declared on purpose. A body that is
     * not JSON must be answered with {@code 200 ignored} per the API contract,
     * not rejected as {@code 415 Unsupported Media Type} before the pipeline
     * ever sees it. For the same reason the body is optional — an empty body is
     * an ignored payload, not a {@code 400}.
     *
     * @param rawRequestBody     the request body exactly as received, may be absent
     * @param requestHeaders     all request headers, captured on {@link InboundRequest}
     *                           for generic lookup — no specific header is currently
     *                           required or relied upon
     * @param httpServletRequest used only to record the request path
     * @return {@code 202} with a per-event receipt, or {@code 200} with an acknowledgement
     */
    @PostMapping(path = INBOUND_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> receiveInboundEvent(
            @RequestBody(required = false) String rawRequestBody,
            @RequestHeader Map<String, String> requestHeaders,
            HttpServletRequest httpServletRequest) {

        InboundRequest inboundRequest = InboundRequest.from(
                rawRequestBody, requestHeaders, httpServletRequest.getRequestURI());

        InboundOutcome inboundOutcome = inboundEventService.process(inboundRequest);

        return ResponseEntity.status(inboundOutcome.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(inboundOutcome.responseBody());
    }
}
