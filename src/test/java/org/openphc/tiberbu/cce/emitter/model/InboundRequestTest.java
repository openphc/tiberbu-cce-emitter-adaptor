package org.openphc.tiberbu.cce.emitter.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboundRequestTest {

    @Nested
    @DisplayName("header lookup")
    class HeaderLookup {

        @Test
        @DisplayName("is case-insensitive in both directions")
        void findsHeadersRegardlessOfCasing() {
            InboundRequest inboundRequest = InboundRequest.from(
                    "{}", Map.of("X-Request-Id", "req-001"), "/inbound");

            assertThat(inboundRequest.getHeader("X-Request-Id")).contains("req-001");
            assertThat(inboundRequest.getHeader("x-request-id")).contains("req-001");
            assertThat(inboundRequest.getHeader("X-REQUEST-ID")).contains("req-001");
        }

        @Test
        @DisplayName("returns empty for absent, blank and null header names")
        void returnsEmptyWhenHeaderIsUnusable() {
            InboundRequest inboundRequest = InboundRequest.from(
                    "{}", Map.of("X-Blank-Header", "   "), "/inbound");

            assertThat(inboundRequest.getHeader("X-Absent-Header")).isEmpty();
            assertThat(inboundRequest.getHeader("X-Blank-Header")).isEmpty();
            assertThat(inboundRequest.getHeader(null)).isEmpty();
        }

        @Test
        @DisplayName("an empty header map is handled with no assumptions about which headers, if any, arrive")
        void emptyHeaderMapIsTheNormalCase() {
            InboundRequest inboundRequest = InboundRequest.from("{}", Map.of(), "/inbound");

            assertThat(inboundRequest.getHeadersByLowercaseName()).isEmpty();
            assertThat(inboundRequest.getHeader("x-anything")).isEmpty();
        }

        @Test
        @DisplayName("the header map is unmodifiable and detached from the source map")
        void headerMapIsDefensivelyCopied() {
            Map<String, String> mutableHeaders = new HashMap<>();
            mutableHeaders.put("Content-Type", "application/json");

            InboundRequest inboundRequest = InboundRequest.from("{}", mutableHeaders, "/inbound");
            mutableHeaders.put("Content-Type", "TAMPERED");

            assertThat(inboundRequest.getHeader("Content-Type")).contains("application/json");
            assertThatThrownBy(() -> inboundRequest.getHeadersByLowercaseName().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("containsFhirResource pre-parse guard")
    class FhirResourceGuard {

        @Test
        void acceptsAnEnvelopeCarryingABundle() {
            String bundleEnvelope = """
                    {"meta":{},"resource":{"resourceType":"Bundle","entry":[]}}""";
            assertThat(InboundRequest.from(bundleEnvelope, Map.of(), "/inbound")
                    .containsFhirResource()).isTrue();
        }

        @Test
        void rejectsBodiesThatCannotHoldAFhirResource() {
            assertThat(InboundRequest.from(null, Map.of(), "/inbound").containsFhirResource()).isFalse();
            assertThat(InboundRequest.from("", Map.of(), "/inbound").containsFhirResource()).isFalse();
            assertThat(InboundRequest.from("not json at all", Map.of(), "/inbound")
                    .containsFhirResource()).isFalse();
            assertThat(InboundRequest.from("{\"meta\":{}}", Map.of(), "/inbound")
                    .containsFhirResource()).isFalse();
        }
    }

    @Test
    @DisplayName("a null header map is tolerated")
    void toleratesNullHeaderMap() {
        InboundRequest inboundRequest = InboundRequest.from("{}", null, "/inbound");

        assertThat(inboundRequest.getHeadersByLowercaseName()).isEmpty();
        assertThat(inboundRequest.getHeader("x-anything")).isEmpty();
        assertThat(inboundRequest.getRequestPath()).isEqualTo("/inbound");
        assertThat(inboundRequest.getRawBody()).isEqualTo("{}");
    }
}
