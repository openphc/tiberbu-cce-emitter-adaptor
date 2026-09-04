package org.openphc.tiberbu.cce.emitter.service;

import org.openphc.tiberbu.cce.emitter.model.InboundOutcome;
import org.openphc.tiberbu.cce.emitter.model.InboundRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one inbound request: bundle extraction, per-entry adaptation,
 * facility filtering and Collector forwarding.
 *
 * <p><b>Placeholder implementation.</b> The pipeline is assembled in sub-task
 * E10, once the extractors (E4–E6), the filter (E7), the envelope builder (E8)
 * and the forwarding client (E9) exist. Until then every request is answered
 * with the documented {@code 200 ignored} outcome, which keeps the endpoint
 * honest — nothing is forwarded, and the response says so.
 */
@Service
public class InboundEventService {

    private static final Logger log = LoggerFactory.getLogger(InboundEventService.class);

    static final String NON_PROCESSABLE_PAYLOAD = "Non-processable payload";

    /**
     * @param inboundRequest the normalized inbound request
     * @return the outcome — HTTP status plus the body to serialize
     */
    public InboundOutcome process(InboundRequest inboundRequest) {
        log.debug("Received inbound request on path '{}' — pipeline not yet wired (E10)",
                inboundRequest.getRequestPath());
        return InboundOutcome.ignored(NON_PROCESSABLE_PAYLOAD);
    }
}
