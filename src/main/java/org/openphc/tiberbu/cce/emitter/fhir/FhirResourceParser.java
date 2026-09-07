package org.openphc.tiberbu.cce.emitter.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.openphc.tiberbu.cce.emitter.exception.FhirMappingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parses a single bundle entry's raw FHIR R4 JSON into a HAPI {@link IBaseResource}.
 *
 * <p>A new {@link IParser} is created per call via {@link FhirContext#newJsonParser()}
 * rather than injected as a singleton bean. {@code IParser} instances are cheap to
 * create but are documented as not thread-safe, so sharing one across concurrent
 * requests would be a correctness bug. The {@link FhirContext} itself is the
 * expensive, thread-safe part, and that one stays a shared singleton.
 */
@Component
public class FhirResourceParser {

    private static final Logger log = LoggerFactory.getLogger(FhirResourceParser.class);

    private final FhirContext fhirContext;

    public FhirResourceParser(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    /**
     * @param resourceJson the raw FHIR R4 JSON for a single resource
     * @return the parsed resource
     * @throws FhirMappingException if the JSON is null, blank, or cannot be parsed
     *                              as a valid FHIR R4 resource
     */
    public IBaseResource parse(String resourceJson) {
        if (resourceJson == null || resourceJson.isBlank()) {
            throw new FhirMappingException("FHIR resource JSON is null or blank");
        }

        IParser jsonParser = fhirContext.newJsonParser();
        try {
            IBaseResource parsedResource = jsonParser.parseResource(resourceJson);
            log.debug("Parsed FHIR resource: resourceType={}, id={}",
                    parsedResource.fhirType(), parsedResource.getIdElement().getIdPart());
            return parsedResource;
        } catch (RuntimeException parseFailure) {
            throw new FhirMappingException(
                    "Failed to parse FHIR resource JSON: " + parseFailure.getMessage(), parseFailure);
        }
    }
}
