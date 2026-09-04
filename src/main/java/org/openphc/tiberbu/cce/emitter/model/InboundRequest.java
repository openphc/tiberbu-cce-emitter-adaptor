package org.openphc.tiberbu.cce.emitter.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Normalized view of an inbound {@code POST /inbound} call.
 *
 * <p>Holds the raw request body untouched — parsing happens downstream, so a
 * body that is not JSON at all still reaches the pipeline and is answered with
 * {@code 200 ignored} rather than a transport-level error.
 *
 * <p>Header names are stored lowercased so lookups are case-insensitive, which
 * HTTP requires and which no caller should have to think about.
 *
 * <p><b>No header is currently relied upon.</b> {@link #getHeader(String)} is a
 * generic, case-insensitive lookup rather than a set of named accessors for
 * specific headers, since which headers (if any) tibERbu can or does send has
 * not been confirmed. Facility ID is currently resolved entirely from the FHIR
 * resource (see {@code FacilityIdExtractor}) and correlation ID is always
 * adaptor-generated — if a relevant header does turn out to be available, this
 * generic lookup already covers reading it.
 */
public final class InboundRequest {

    private final String rawBody;
    private final Map<String, String> headersByLowercaseName;
    private final String requestPath;

    private InboundRequest(String rawBody, Map<String, String> headersByLowercaseName, String requestPath) {
        this.rawBody = rawBody;
        this.headersByLowercaseName = headersByLowercaseName;
        this.requestPath = requestPath;
    }

    /**
     * @param rawBody       the request body exactly as received, may be {@code null} or empty
     * @param requestHeaders headers in any casing, may be {@code null}
     * @param requestPath   the request URI path, used as a metric tag
     * @return a normalized request with lowercased, unmodifiable headers
     */
    public static InboundRequest from(String rawBody, Map<String, String> requestHeaders, String requestPath) {
        Map<String, String> lowercasedHeaders = new LinkedHashMap<>();
        if (requestHeaders != null) {
            requestHeaders.forEach((headerName, headerValue) -> {
                if (headerName != null) {
                    lowercasedHeaders.put(headerName.toLowerCase(java.util.Locale.ROOT), headerValue);
                }
            });
        }
        return new InboundRequest(rawBody, Collections.unmodifiableMap(lowercasedHeaders), requestPath);
    }

    /**
     * @param headerName header name in any casing
     * @return the header value, or empty when absent or blank
     */
    public Optional<String> getHeader(String headerName) {
        if (headerName == null) {
            return Optional.empty();
        }
        String headerValue = headersByLowercaseName.get(headerName.toLowerCase(java.util.Locale.ROOT));
        return (headerValue == null || headerValue.isBlank()) ? Optional.empty() : Optional.of(headerValue);
    }

    /**
     * Cheap pre-parse guard. A body with no {@code "resourceType"} anywhere in it
     * cannot contain a FHIR Bundle, so the pipeline can answer {@code 200 ignored}
     * without paying for a JSON or HAPI parse.
     *
     * @return {@code true} when the body could plausibly hold a FHIR resource
     */
    public boolean containsFhirResource() {
        return rawBody != null && rawBody.contains("\"resourceType\"");
    }

    /** @return the request body exactly as received */
    public String getRawBody() {
        return rawBody;
    }

    /** @return an unmodifiable map of headers, keyed by lowercased name */
    public Map<String, String> getHeadersByLowercaseName() {
        return headersByLowercaseName;
    }

    /** @return the request URI path */
    public String getRequestPath() {
        return requestPath;
    }
}
