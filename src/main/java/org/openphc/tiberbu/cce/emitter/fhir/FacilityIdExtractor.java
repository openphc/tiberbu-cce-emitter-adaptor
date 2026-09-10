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
 * <p>Read from the resource's own {@code Reference(Organization)} field, via
 * reflection rather than a hardcoded per-type mapping — the same
 * multi-accessor approach {@link PatientIdExtractor} uses for {@code
 * subject}/{@code patient}. Three accessor names are tried, in order:
 * {@link #ORGANIZATION_ACCESSOR_METHOD_NAMES}. A real survey of tibERbu's own production
 * data (every resource type actually seen in {@code inbound_event_log})
 * found exactly three resource types that carry an {@code Organization/}
 * reference at all:
 * <ul>
 *   <li>{@code Consent.organization} — {@code getOrganization()}, a
 *       {@code List<Reference>}</li>
 *   <li>{@code Encounter.serviceProvider} — {@code getServiceProvider()}, a
 *       single {@code Reference}</li>
 *   <li>{@code EpisodeOfCare.managingOrganization} —
 *       {@code getManagingOrganization()}, a single {@code Reference}</li>
 * </ul>
 * Every other resource type actually seen (AllergyIntolerance, Condition,
 * MedicationRequest, Observation) declares none of these three accessors and
 * so resolves to {@code null} — including {@code MedicationDispense} and
 * {@code Procedure}, which carry a {@code location} reference instead. That
 * one is deliberately NOT treated as a facility reference: {@code location}
 * points to a {@code Location/}, not an {@code Organization/}, and the two
 * are not interchangeable — a {@code Location} need not belong to the same
 * organization the facility filter allowlists.
 *
 * <p><b>Known limitation, accepted for now.</b> {@code getOrganization()}
 * and {@code getManagingOrganization()} are not unique to the three types
 * above — {@code EnrollmentResponse}, {@code OrganizationAffiliation}, and
 * {@code PractitionerRole} also declare {@code getOrganization()}; {@code
 * CareTeam}, {@code Endpoint}, {@code Location}, {@code Patient}, and {@code
 * Person} also declare {@code getManagingOrganization()} (confirmed by
 * reflectively scanning every class in {@code org.hl7.fhir.r4.model}). None
 * of those resource types appear in tibERbu traffic today, so this is a
 * latent gap rather than an active bug — left as-is for now rather than
 * adding a resource-type gate ahead of it mattering.
 *
 * <p>Given a {@code Consent} entry
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
 * — read from {@code Consent.getOrganization()}, with the
 * {@code "Organization/"} prefix stripped.
 *
 * <p>Never throws: a resource with no matching reference resolves to
 * {@code null}, which {@code FacilityFilterProperties.isFacilityAllowed()}
 * already treats as "always admit."
 */
@Component
public class FacilityIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(FacilityIdExtractor.class);

    /**
     * Accessor method names tried in priority order. A given FHIR R4 resource
     * class declares at most one of these — {@code getOrganization()} for
     * {@code Consent}, {@code getServiceProvider()} for {@code Encounter},
     * {@code getManagingOrganization()} for {@code EpisodeOfCare} — so the
     * order only matters in that it's checked deterministically, not because
     * more than one could ever match the same resource.
     */
    private static final String[] ORGANIZATION_ACCESSOR_METHOD_NAMES = {"getOrganization", "getServiceProvider", "getManagingOrganization"};

    /**
     * Extracts the facility ID for one bundle entry's FHIR resource from its
     * {@code organization}/{@code serviceProvider}/{@code managingOrganization}
     * reference — see the class javadoc for which resource types carry which.
     *
     * <p>Example — the real tibERbu {@code Consent} entry from the class
     * javadoc resolves to {@code "KE-SHRF-D601602F-C9AC-4CC5-9347"}. A
     * resource with none of {@link #ORGANIZATION_ACCESSOR_METHOD_NAMES} at all (e.g.
     * {@code Observation}), or one whose matching reference is unpopulated,
     * resolves to {@code null}.
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

        // Step 2: try each accessor in turn. Resources that don't declare any
        // of them (most clinical resource types) simply have nothing to resolve here.
        Reference organizationReference = resolveOrganizationReference(resource);
        String facilityId = organizationReference != null ? referenceToFacilityId(organizationReference) : null;
        if (facilityId != null) {
            log.debug("Extracted facility ID '{}' from {} organization reference", facilityId, resource.fhirType());
        }
        return facilityId;
    }

    /**
     * Resolves the organization-equivalent reference by trying each accessor
     * in {@link #ORGANIZATION_ACCESSOR_METHOD_NAMES} in order, returning the first one
     * that both exists on {@code resource}'s class and is populated, and
     * normalizing the result to a single {@link Reference} — handling both
     * shapes HAPI generates for reference-typed fields across FHIR R4
     * resources: a direct {@code Reference} (e.g. {@code
     * Encounter.getServiceProvider()}), or a {@code List<Reference>} (e.g.
     * {@code Consent.getOrganization()}), taking the first populated entry.
     *
     * @return the resolved reference, or {@code null} when no accessor exists
     *         on {@code resource}'s class, or none that does yields anything populated
     */
    private Reference resolveOrganizationReference(IBaseResource resource) {
        for (String organizationAccessorMethodName : ORGANIZATION_ACCESSOR_METHOD_NAMES) {
            Object organizationAccessorResult = invokeOrganizationAccessorIfPresent(resource, organizationAccessorMethodName);

            if (organizationAccessorResult instanceof Reference reference) {
                if (isPopulated(reference)) {
                    return reference;
                }
                continue;
            }
            if (organizationAccessorResult instanceof List<?> referenceCandidates) {
                for (Object referenceCandidate : referenceCandidates) {
                    if (referenceCandidate instanceof Reference reference && isPopulated(reference)) {
                        return reference;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Reflectively invokes {@code organizationAccessorMethodName} on {@code resource},
     * treating "the method doesn't exist on this resource type" as a normal,
     * silent outcome — that's exactly what happens for every accessor name
     * that isn't the one this particular resource type declares (e.g. {@code
     * Observation} declares none of {@link #ORGANIZATION_ACCESSOR_METHOD_NAMES} at all).
     *
     * @return the raw return value of the accessor, or {@code null} if the
     *         method does not exist or invoking it unexpectedly failed
     */
    private Object invokeOrganizationAccessorIfPresent(IBaseResource resource, String organizationAccessorMethodName) {
        try {
            Method organizationAccessorMethod = resource.getClass().getMethod(organizationAccessorMethodName);
            return organizationAccessorMethod.invoke(resource);
        } catch (NoSuchMethodException methodNotDeclaredOnThisResourceType) {
            // Expected and silent — most resource types don't declare any of
            // ORGANIZATION_ACCESSOR_METHOD_NAMES.
            return null;
        } catch (ReflectiveOperationException unexpectedReflectionFailure) {
            // The method DID exist but invoking it failed anyway — unlike the
            // case above, this is unexpected, so it is logged rather than
            // silently swallowed.
            log.warn("Failed to invoke {}() on {}: {}", organizationAccessorMethodName,
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
