package org.openphc.tiberbu.cce.emitter.fhir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implements the tibERbu bundle-first contract on the raw {@code POST /inbound}
 * request body.
 *
 * <p><b>What "the request body" looks like.</b> Every method on this class that
 * takes a {@code requestBody} parameter means the <em>entire</em> HTTP body
 * tibERbu posted — the full wrapper envelope, transport {@code meta} block
 * included, exactly as it arrived. It is never just the FHIR Bundle, and never
 * just one entry. A real envelope looks like this (trimmed to the fields this
 * class cares about; a genuine tibERbu payload carries more under {@code meta}
 * and under each resource's own {@code meta}):
 *
 * <pre>{@code
 * {
 *   "meta": {
 *     "resourceType": "Bundle",
 *     "resourceId": "VCR-20260901-57098420",
 *     "event": "upserted",
 *     "source": "shr-mediator",
 *     "bundleId": "VCR-20260901-57098420"
 *   },
 *   "resource": {
 *     "resourceType": "Bundle",
 *     "type": "transaction",
 *     "entry": [
 *       {
 *         "request": { "method": "PUT", "url": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A" },
 *         "resource": { "resourceType": "Patient", "id": "KE-SHRP-170CDF0A-1363-4972-B36A" }
 *       },
 *       {
 *         "request": { "method": "PUT", "url": "Consent/VCR-20260901-57098420" },
 *         "resource": {
 *           "resourceType": "Consent",
 *           "id": "VCR-20260901-57098420",
 *           "status": "active",
 *           "patient": { "reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A" }
 *         }
 *       }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <p>For that envelope, {@link #extract(String)} returns a single {@link
 * BundleEntry} — {@code bundleEntryIndex=1}, {@code resourceType="Consent"},
 * and {@code resourceJson} holding only the {@code Consent} object shown above.
 * The {@code Patient} entry is skipped because its {@code resourceType} is
 * {@code "Patient"} (see {@link #isPatientEntry}); the {@code Consent} entry
 * is not. The {@code meta} block and every {@code request} object are read
 * only far enough to be confirmed irrelevant — their values never appear in
 * the result.
 *
 * <p><b>Two different {@code "resource"} keys.</b> The envelope has a
 * {@code "resource"} key at the top level, holding the FHIR Bundle itself. Each
 * element of that Bundle's {@code entry[]} array has its <em>own</em>,
 * unrelated {@code "resource"} key, holding one FHIR resource (Patient,
 * Consent, ...). This class deliberately mirrors that with two similarly named
 * but distinct constants — {@link #ENVELOPE_RESOURCE_FIELD} for the outer one,
 * {@link #ENTRY_RESOURCE_FIELD} for the inner one — so the two nesting levels
 * are never confused in the code that reads them.
 *
 * <p>This class only extracts entries; it does not perform FHIR validation. A
 * shape that does not match the contract — not JSON, no {@code resource}, the
 * resource is not a {@code Bundle}, an absent/empty {@code entry[]}, or a
 * bundle holding only the patient entry — is never an error here. It simply
 * yields an empty list, which the caller turns into {@code 200 ignored}.
 */
@Component
public class BundleEntryExtractor {

    private static final Logger log = LoggerFactory.getLogger(BundleEntryExtractor.class);

    /** Key holding the FHIR Bundle at the top level of the request body: {@code {"resource": {...}}}. */
    private static final String ENVELOPE_RESOURCE_FIELD = "resource";

    /** The {@code resourceType} key, read at both the Bundle level and the per-entry resource level. */
    private static final String BUNDLE_RESOURCE_TYPE_FIELD = "resourceType";

    /** The only value {@link #ENVELOPE_RESOURCE_FIELD} is accepted as: {@code "resourceType": "Bundle"}. */
    private static final String BUNDLE_RESOURCE_TYPE_VALUE = "Bundle";

    /** Key holding the entry array inside the Bundle: {@code {"resource": {"entry": [...]}}}. */
    private static final String BUNDLE_ENTRIES_FIELD = "entry";

    /** Key holding one FHIR resource inside a single bundle entry: {@code {"entry": [{"resource": {...}}]}}. */
    private static final String ENTRY_RESOURCE_FIELD = "resource";

    /**
     * The value a bundle entry's {@code resource.resourceType} must equal for
     * that entry to be skipped. Checked on every entry independently — there
     * is no assumption that the Patient entry sits at a fixed position, or
     * that a bundle contains exactly one (or even any).
     */
    private static final String PATIENT_RESOURCE_TYPE_VALUE = "Patient";

    private final ObjectMapper objectMapper;

    public BundleEntryExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts every candidate event payload from one inbound tibERbu request.
     *
     * <p>{@code requestBody} is the entire HTTP body — see the class-level
     * javadoc for a full worked example. Given that example envelope, this
     * method returns a one-element list: {@code [BundleEntry(bundleEntryIndex=1,
     * resourceType="Consent", resourceJson="{\"resourceType\":\"Consent\",...}")]}
     * — the {@code Patient} entry is skipped, the {@code Consent} entry is not.
     *
     * <p>Given a bundle that holds only a Patient entry —
     * {@code {"resource": {"resourceType": "Bundle", "entry": [{"resource":
     * {"resourceType": "Patient", "id": "KE-SHRP-7E93454F-6D34-47C5-A6C2"}}]}}}
     * — this method returns an empty list.
     *
     * @param requestBody the raw {@code POST /inbound} body, exactly as received
     * @return every candidate event payload once every {@code Patient} entry
     *         is removed, in bundle order; empty when the body does not match
     *         the bundle contract
     */
    public List<BundleEntry> extract(String requestBody) {
        // Step 1: confirm the envelope actually wraps a FHIR Bundle. Anything
        // else (non-JSON, no "resource", wrong resourceType) is not an error —
        // it just means there is nothing to extract.
        JsonNode bundleNode = readBundleNode(requestBody);
        if (bundleNode == null) {
            return List.of();
        }

        // Step 2: the Bundle must carry a non-empty entry[] array to have
        // anything worth iterating.
        JsonNode bundleEntriesNode = bundleNode.get(BUNDLE_ENTRIES_FIELD);
        if (bundleEntriesNode == null || !bundleEntriesNode.isArray() || bundleEntriesNode.isEmpty()) {
            log.debug("Bundle has no entries to process — nothing to extract");
            return List.of();
        }

        // Step 3: walk every entry, checking each one's own resourceType —
        // never its position. Any entry CONFIRMED to be a Patient resource is
        // skipped, no matter where in the bundle it appears; every other
        // entry either becomes a BundleEntry or is dropped for some other
        // reason — see toBundleEntry() for why an entry might be dropped.
        List<BundleEntry> candidateEventPayloads = new ArrayList<>();
        for (int bundleEntryIndex = 0; bundleEntryIndex < bundleEntriesNode.size(); bundleEntryIndex++) {
            JsonNode bundleEntryNode = bundleEntriesNode.get(bundleEntryIndex);
            if (isPatientEntry(bundleEntryNode)) {
                log.debug("Bundle entry[{}] is a Patient resource — skipping", bundleEntryIndex);
                continue;
            }
            toBundleEntry(bundleEntryIndex, bundleEntryNode).ifPresent(candidateEventPayloads::add);
        }

        log.debug("Extracted {} candidate event payload(s) from {} bundle entries",
                candidateEventPayloads.size(), bundleEntriesNode.size());
        return candidateEventPayloads;
    }

    /**
     * Validates the outer envelope and returns the FHIR Bundle node, or
     * {@code null} when the body does not match the contract at all.
     *
     * <p>{@code requestBody} here is the same whole HTTP body {@link
     * #extract(String)} receives — not a Bundle, not an entry. For the example
     * envelope in the class-level javadoc, this method returns the
     * {@code JsonNode} for everything under the top-level {@code "resource"}
     * key (the {@code {"resourceType": "Bundle", "type": "transaction", "entry": [...]}}
     * object) — the sibling {@code "meta"} block is read only far enough to be
     * ignored, and is discarded here.
     *
     * <p>For a bare FHIR resource with no envelope at all —
     * {@code {"resourceType": "Patient", "id": "patient-001"}} — this returns
     * {@code null}, because there is no {@code "resource"} key to find.
     *
     * @param requestBody the entire raw {@code POST /inbound} body
     * @return the {@code resource} node when it is present and is a FHIR
     *         {@code Bundle}; {@code null} otherwise
     */
    private JsonNode readBundleNode(String requestBody) {
        // Step 1: the body must be a parseable JSON object at all. A body that
        // is not JSON, or is JSON but not an object (e.g. a bare string or
        // array), cannot contain an envelope.
        JsonNode envelopeNode = parseAsJsonObject(requestBody);
        if (envelopeNode == null) {
            return null;
        }

        // Step 2: pull the OUTER "resource" key — the one that wraps the whole
        // Bundle, not the per-entry one read later in toBundleEntry().
        JsonNode bundleNode = envelopeNode.get(ENVELOPE_RESOURCE_FIELD);
        if (bundleNode == null || !bundleNode.isObject()) {
            log.debug("Envelope has no '{}' object — nothing to extract", ENVELOPE_RESOURCE_FIELD);
            return null;
        }

        // Step 3: the tibERbu contract only ever sends a Bundle here. Anything
        // else (a bare Patient, an Encounter, ...) is "not the contract" per
        // api-reference.md § 3.3 and is treated as non-processable.
        JsonNode bundleResourceTypeNode = bundleNode.get(BUNDLE_RESOURCE_TYPE_FIELD);
        String bundleResourceType = bundleResourceTypeNode == null ? null : bundleResourceTypeNode.asText(null);
        if (!BUNDLE_RESOURCE_TYPE_VALUE.equals(bundleResourceType)) {
            log.debug("resource.resourceType is '{}', not '{}' — nothing to extract",
                    bundleResourceType, BUNDLE_RESOURCE_TYPE_VALUE);
            return null;
        }

        return bundleNode;
    }

    /**
     * Builds a {@link BundleEntry} from one element of {@code resource.entry[]}.
     *
     * <p>{@code bundleEntryNode} here is neither the whole request body nor the
     * Bundle — it is a single array element, the small object pairing one
     * {@code request} with one {@code resource}, for example:
     * <pre>{@code
     * {
     *   "request": { "method": "PUT", "url": "Consent/VCR-20260901-57098420" },
     *   "resource": {
     *     "resourceType": "Consent",
     *     "id": "VCR-20260901-57098420",
     *     "status": "active",
     *     "patient": { "reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A" }
     *   }
     * }
     * }</pre>
     * For that input, and {@code bundleEntryIndex=1}, this returns
     * {@code BundleEntry(1, "{\"resourceType\":\"Consent\",\"id\":\"VCR-20260901-57098420\",...}", "Consent")}
     * — the {@code request} sibling is read only long enough to be skipped over
     * and never appears in the result.
     *
     * <p>Returns {@code empty} when the entry cannot yield a processable event:
     * no {@code resource} object, or a {@code resource} with no usable
     * {@code resourceType}. For example {@code {"request": {"method": "PUT",
     * "url": "Consent/VCR-20260901-57098420"}}} (no {@code resource} at all)
     * yields {@code empty}.
     *
     * @param bundleEntryIndex this entry's position in {@code resource.entry[]},
     *                         carried into the result for traceability
     * @param bundleEntryNode  one element of {@code resource.entry[]}, or
     *                         {@code null} if the array held a JSON {@code null}
     *                         at this position
     * @return the extracted entry, or empty when it cannot be processed
     */
    private Optional<BundleEntry> toBundleEntry(int bundleEntryIndex, JsonNode bundleEntryNode) {
        // Step 1: find the INNER "resource" key — one FHIR resource, distinct
        // from the outer Bundle-wrapping "resource" read in readBundleNode().
        // bundleEntryNode may itself be null/non-object if the entry array
        // held something unexpected (e.g. a bare string) — JsonNode.get(...)
        // on such nodes safely returns null rather than throwing.
        JsonNode eventResourceNode = bundleEntryNode == null ? null : bundleEntryNode.get(ENTRY_RESOURCE_FIELD);
        if (eventResourceNode == null || !eventResourceNode.isObject()) {
            log.debug("Bundle entry[{}] has no '{}' object — skipping", bundleEntryIndex, ENTRY_RESOURCE_FIELD);
            return Optional.empty();
        }

        // Step 2: every downstream step (patient identifier extraction, facility
        // extraction, CloudEvent type) needs to know the FHIR resource type,
        // so an entry that does not carry one cannot be processed and is
        // dropped here rather than deferred to a later, noisier failure.
        JsonNode eventResourceTypeNode = eventResourceNode.get(BUNDLE_RESOURCE_TYPE_FIELD);
        String eventResourceType = eventResourceTypeNode == null ? null : eventResourceTypeNode.asText(null);
        if (eventResourceType == null || eventResourceType.isBlank()) {
            log.debug("Bundle entry[{}].resource has no resourceType — skipping", bundleEntryIndex);
            return Optional.empty();
        }

        // Step 3: hand the resource back on as a JSON string (not the parsed
        // JsonNode) — this is what SourceAdaptorService passes to
        // FhirResourceParser later, so re-parsing with HAPI happens exactly
        // once, per entry, in one place.
        String eventResourceJson = writeAsJson(eventResourceNode);
        if (eventResourceJson == null) {
            return Optional.empty();
        }

        return Optional.of(new BundleEntry(bundleEntryIndex, eventResourceJson, eventResourceType));
    }

    /**
     * @param bundleEntryNode one element of {@code resource.entry[]}, from
     *                        any position in the bundle, or {@code null} if
     *                        the array held a JSON {@code null} at that
     *                        position
     * @return {@code true} only when the entry carries a {@code resource}
     *         object whose {@code resourceType} is exactly {@code
     *         "Patient"} — position is never consulted
     */
    private boolean isPatientEntry(JsonNode bundleEntryNode) {
        JsonNode entryResourceNode = bundleEntryNode == null ? null : bundleEntryNode.get(ENTRY_RESOURCE_FIELD);
        if (entryResourceNode == null || !entryResourceNode.isObject()) {
            return false;
        }

        JsonNode entryResourceTypeNode = entryResourceNode.get(BUNDLE_RESOURCE_TYPE_FIELD);
        String entryResourceType = entryResourceTypeNode == null ? null : entryResourceTypeNode.asText(null);
        return PATIENT_RESOURCE_TYPE_VALUE.equals(entryResourceType);
    }

    /**
     * Parses {@code requestBody} as JSON and confirms its root is an object.
     *
     * <p>Examples of inputs this method receives, and what each returns:
     * <ul>
     *   <li>{@code null} or {@code ""} → {@code null} (nothing to parse)</li>
     *   <li>{@code "not json at all"} → {@code null} (invalid JSON)</li>
     *   <li>{@code "42"} → {@code null} (valid JSON, but the root is a number, not an object)</li>
     *   <li>the full envelope from the class-level javadoc → the parsed root
     *       object, with its {@code "meta"} and {@code "resource"} keys intact</li>
     * </ul>
     *
     * @param requestBody the entire raw {@code POST /inbound} body
     * @return the parsed root object, or {@code null} when the body is blank,
     *         is not valid JSON, or does not parse to a JSON object
     */
    private JsonNode parseAsJsonObject(String requestBody) {
        // A blank body can never be JSON — skip straight to "nothing here"
        // rather than paying for a parse attempt that would only fail anyway.
        if (requestBody == null || requestBody.isBlank()) {
            return null;
        }
        try {
            JsonNode parsedNode = objectMapper.readTree(requestBody);
            // readTree() succeeds for any valid JSON, including a bare number
            // or string — isObject() rejects those, since the envelope must
            // be a JSON object to have a "resource" key at all.
            return parsedNode.isObject() ? parsedNode : null;
        } catch (JsonProcessingException malformedJson) {
            log.debug("Request body is not valid JSON — nothing to extract: {}", malformedJson.getMessage());
            return null;
        }
    }

    /**
     * Serializes one entry's FHIR resource node back to a compact JSON string.
     *
     * <p>{@code resourceNode} is already the isolated per-entry {@code
     * "resource"} object — for example the {@code JsonNode} representing
     * {@code {"resourceType": "Consent", "id": "VCR-20260901-57098420",
     * "status": "active"}} — never the whole request body and never the entry's
     * sibling {@code "request"} object. The returned string round-trips back
     * to the same JSON, just without the original field ordering or
     * whitespace guarantees.
     *
     * @param resourceNode one entry's {@code resource} object
     * @return the compact JSON text for {@code resourceNode}, or {@code null}
     *         if serialization unexpectedly failed
     */
    private String writeAsJson(JsonNode resourceNode) {
        try {
            return objectMapper.writeValueAsString(resourceNode);
        } catch (JsonProcessingException serializationFailure) {
            // A node just parsed from JSON should always re-serialize; guard anyway so a
            // hiccup here degrades to "skip this entry", never an unhandled exception.
            log.warn("Failed to serialize bundle entry resource back to JSON: {}",
                    serializationFailure.getMessage());
            return null;
        }
    }
}
