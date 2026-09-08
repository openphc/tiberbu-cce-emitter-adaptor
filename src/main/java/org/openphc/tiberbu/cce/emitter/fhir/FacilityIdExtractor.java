package org.openphc.tiberbu.cce.emitter.fhir;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Extracts the facility ID that feeds both the facility filter and the
 * CloudEvents {@code facilityid} extension, from a single bundle entry's FHIR
 * resource.
 *
 * <p>Read from the resource's {@code organization} reference, via reflection
 * ({@code getOrganization()}) rather than a hardcoded per-type mapping — the
 * same no-allowlist approach {@link PatientIdExtractor} uses for {@code
 * subject}/{@code patient}. Based on real tibERbu {@code Consent} payloads,
 * where it is always populated. Given a {@code Consent} entry
 * <pre>{@code
 * {
 *   "resourceType": "Consent",
 *   "id": "VCR-20260901-57098420",
 *   "organization": [
 *     { "reference": "Organization/KE-SHRF-D601602F-C9AC-4CC5-9347" }
 *   ]
 * }
 * }</pre>
 * {@code extract(resource)} returns {@code "KE-SHRF-D601602F-C9AC-4CC5-9347"}
 * — read from {@code Consent.getOrganization()} (a {@code List<Reference>} for
 * this resource type), with the {@code "Organization/"} prefix stripped.
 *
 * <p>Never throws: a resource with no {@code organization} reference resolves
 * to {@code null}, which {@code FacilityFilterProperties.admits()} already
 * treats as "always admit."
 */
@Component
public class FacilityIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(FacilityIdExtractor.class);

    private static final String ORGANIZATION_ACCESSOR_METHOD_NAME = "getOrganization";

    /**
     * Extracts the facility ID for one bundle entry's FHIR resource from its
     * {@code organization} reference.
     *
     * <p>Example — the real tibERbu {@code Consent} entry from the class
     * javadoc resolves to {@code "KE-SHRF-D601602F-C9AC-4CC5-9347"}. A
     * resource with no {@code organization} field at all (e.g. {@code
     * Observation}), or one whose {@code organization} reference is
     * unpopulated, resolves to {@code null}.
     *
     * @param resource the entry's parsed FHIR resource
     * @return the facility ID, or {@code null} when no organization reference resolves
     */
    public String extract(IBaseResource resource) {
        // Step 1: a null resource has nothing to extract from — this can happen
        // defensively even though callers are not expected to pass null.
        if (resource == null) {
            return null;
        }

        // Step 2: try the organization reference. Resources that don't declare
        // getOrganization() at all (most clinical resource types) simply have
        // nothing to resolve here.
        Reference organizationReference = resolveOrganizationReference(resource);
        String facilityId = organizationReference != null ? referenceToFacilityId(organizationReference) : null;
        if (facilityId != null) {
            log.debug("Extracted facility ID '{}' from {} organization reference", facilityId, resource.fhirType());
        }
        return facilityId;
    }

    /**
     * Reflectively invokes {@code getOrganization()} on {@code resource} and
     * normalizes the result to a single {@link Reference}, handling both shapes
     * HAPI generates for reference-typed fields across FHIR R4 resources: a
     * direct {@code Reference}, or a {@code List<Reference>} (e.g. {@code
     * Consent.getOrganization()}) — the first populated entry wins.
     *
     * <p>Example: called against
     * {@code "organization": [{"reference": "Organization/KE-SHRF-..."}]},
     * this reflectively invokes {@code Consent.getOrganization()} (returns a
     * {@code List<Reference>}), and returns that single populated {@code Reference}.
     *
     * @return the resolved reference, or {@code null} when the accessor does
     *         not exist on {@code resource}'s class, or nothing populated is found
     */
    private Reference resolveOrganizationReference(IBaseResource resource) {
        // Step 1: reflectively call getOrganization() — invokeIfPresent returns
        // null rather than throwing when the method doesn't exist on this class.
        Object accessorResult = invokeIfPresent(resource);

        // Step 2a: direct Reference shape — return it only if it actually
        // carries a value.
        if (accessorResult instanceof Reference reference) {
            return isPopulated(reference) ? reference : null;
        }
        // Step 2b: List<Reference> shape (e.g. Consent.getOrganization()) —
        // walk the list and take the first entry that carries a value.
        if (accessorResult instanceof List<?> referenceCandidates) {
            for (Object candidate : referenceCandidates) {
                if (candidate instanceof Reference reference && isPopulated(reference)) {
                    return reference;
                }
            }
        }
        // Step 3: accessor didn't exist, or existed but yielded nothing usable
        // (empty list, or an unpopulated Reference).
        return null;
    }

    /**
     * Reflectively invokes {@code getOrganization()} on {@code resource},
     * treating "the method doesn't exist on this resource type" as a normal,
     * silent outcome — that's exactly what happens for every resource type
     * that doesn't define an {@code organization} field (e.g. {@code
     * Observation}, {@code Encounter}).
     *
     * @return the raw return value of the accessor, or {@code null} if the
     *         method does not exist or invoking it unexpectedly failed
     */
    private Object invokeIfPresent(IBaseResource resource) {
        try {
            Method accessorMethod = resource.getClass().getMethod(ORGANIZATION_ACCESSOR_METHOD_NAME);
            return accessorMethod.invoke(resource);
        } catch (NoSuchMethodException methodNotDeclaredOnThisResourceType) {
            // Expected and silent — most resource types don't declare
            // getOrganization().
            return null;
        } catch (ReflectiveOperationException unexpectedReflectionFailure) {
            // The method DID exist but invoking it failed anyway — unlike the
            // case above, this is unexpected, so it is logged rather than
            // silently swallowed.
            log.warn("Failed to invoke {}() on {}: {}", ORGANIZATION_ACCESSOR_METHOD_NAME,
                    resource.fhirType(), unexpectedReflectionFailure.getMessage());
            return null;
        }
    }

    /** @return {@code true} when {@code reference} carries an actual reference string or identifier, not just an empty shell */
    private boolean isPopulated(Reference reference) {
        return reference.hasReference() || reference.hasIdentifier();
    }

    /**
     * Extracts the bare facility ID from a {@link Reference}: the segment after
     * the last {@code /} in {@code reference.getReference()} — so both
     * {@code "Location/1302"} and {@code "Organization/1302"} resolve to
     * {@code "1302"}, and a bare {@code "1302"} passes through unchanged. Falls
     * back to {@code identifier.value} (e.g. {@code {"identifier": {"value":
     * "1302"}}}) whenever the reference string is absent, OR present but blank
     * after stripping (e.g. a reference string that is only {@code "Organization/"}
     * with nothing after it) — the two checks are independent, not either/or.
     *
     * <p>Example (step 1, the common case): {@code referenceToFacilityId} given
     * a {@link Reference} built from {@code {"reference":
     * "Organization/KE-SHRF-D601602F-C9AC-4CC5-9347"}} returns {@code
     * "KE-SHRF-D601602F-C9AC-4CC5-9347"} — {@code hasReference()} is {@code
     * true}, the {@code "Organization/"} prefix is stripped, and what remains
     * is non-blank, so step 1 returns immediately.
     *
     * <p>Example (step 2, the fallback): given a {@link Reference} built from
     * {@code {"identifier": {"value": "1302"}}} — no {@code "reference"} field
     * at all — {@code hasReference()} is {@code false}, so step 1 finds
     * nothing; step 2 then reads {@code identifier.value} and returns {@code
     * "1302"}. The same fallback also fires when a {@code "reference"} field
     * is present but strips to nothing, e.g. {@code {"reference":
     * "Organization/", "identifier": {"value": "1302"}}} — step 1's stripped
     * result is blank, so step 2 still returns {@code "1302"}.
     */
    private String referenceToFacilityId(Reference reference) {
        // Step 1: prefer the reference string when it's present and, after
        // stripping any "ResourceType/" prefix, actually non-blank.
        if (reference.hasReference()) {
            String rawReference = reference.getReference();
            String facilityId = rawReference.contains("/")
                    ? rawReference.substring(rawReference.lastIndexOf('/') + 1)
                    : rawReference;
            if (!facilityId.isBlank()) {
                return facilityId;
            }
        }
        // Step 2: no usable reference string — fall back to identifier.value,
        // the alternate way a FHIR reference can carry an identity.
        if (reference.hasIdentifier() && reference.getIdentifier().hasValue()) {
            return reference.getIdentifier().getValue();
        }
        // Step 3: neither a usable reference string nor an identifier — this
        // Reference carries no extractable facility identity.
        return null;
    }
}
