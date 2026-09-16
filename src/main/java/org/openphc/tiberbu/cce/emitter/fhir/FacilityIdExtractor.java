package org.openphc.tiberbu.cce.emitter.fhir;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Extracts the facility ID and display name that feed the facility filter
 * and the CloudEvents {@code facilityid}/{@code facilityname} extensions,
 * from a single bundle entry's FHIR resource.
 *
 * <p>Read from the resource's own {@code Reference(Organization)} field, via
 * reflection rather than a hardcoded per-type mapping — the same
 * multi-accessor approach {@link PatientIdExtractor} uses for {@code
 * subject}/{@code patient}. Four accessor names are tried, in order:
 * {@link #ORGANIZATION_ACCESSOR_METHOD_NAMES}. A real survey of tibERbu's own production
 * data (every resource type actually seen in {@code inbound_event_log})
 * found exactly four resource types that carry an {@code Organization/}
 * reference at all:
 * <ul>
 *   <li>{@code Consent.organization} — {@code getOrganization()}, a
 *       {@code List<Reference>}</li>
 *   <li>{@code Encounter.serviceProvider} — {@code getServiceProvider()}, a
 *       single {@code Reference}</li>
 *   <li>{@code EpisodeOfCare.managingOrganization} —
 *       {@code getManagingOrganization()}, a single {@code Reference}</li>
 *   <li>{@code ServiceRequest.performer} — {@code getPerformer()}, a
 *       {@code List<Reference>} — see the {@code ORGANIZATION_RESOURCE_TYPE}
 *       check below for why this one specifically needs a reference-type
 *       gate that the other three don't</li>
 * </ul>
 * Every other resource type actually seen (AllergyIntolerance, Condition,
 * MedicationRequest, Observation) declares none of these four accessors and
 * so resolves to {@code null} — including {@code MedicationDispense} and
 * {@code Procedure}, which carry a {@code location} reference instead. That
 * one is deliberately NOT treated as a facility reference: {@code location}
 * points to a {@code Location/}, not an {@code Organization/}, and the two
 * are not interchangeable — a {@code Location} need not belong to the same
 * organization the facility filter allowlists.
 *
 * <p><b>Why {@code getPerformer()} needs a reference-type gate.</b> Unlike
 * {@code getOrganization()}/{@code getServiceProvider()}/{@code
 * getManagingOrganization()} — each, per the FHIR R4 spec, typed to reference
 * an {@code Organization} and nothing else — {@code performer} is a
 * <em>union</em> reference that can point at a {@code Practitioner},
 * {@code PractitionerRole}, {@code Organization}, {@code CareTeam}, {@code
 * HealthcareService}, {@code Patient}, {@code Device}, or {@code
 * RelatedPerson}, and {@code Observation} (real tibERbu traffic today, unlike
 * every resource type in the next paragraph) also declares {@code
 * getPerformer()} with that exact same union shape. Real tibERbu {@code
 * Observation} payloads populate it exclusively with {@code Practitioner/...}
 * references (confirmed in {@code artifacts/observation.json}), so {@link
 * #resolveFacilityId} rejects any reference whose {@code ResourceType/}
 * prefix isn't literally {@code "Organization"} — this is what keeps {@code
 * getPerformer()} safe to add here without misattributing an
 * {@code Observation}'s performing practitioner as its facility.
 *
 * <p><b>Known limitation, accepted for now.</b> {@code getOrganization()}
 * and {@code getManagingOrganization()} are not unique to the four types
 * above — {@code EnrollmentResponse}, {@code OrganizationAffiliation}, and
 * {@code PractitionerRole} also declare {@code getOrganization()}; {@code
 * CareTeam}, {@code Endpoint}, {@code Location}, {@code Patient}, and {@code
 * Person} also declare {@code getManagingOrganization()} (confirmed by
 * reflectively scanning every class in {@code org.hl7.fhir.r4.model}). Unlike
 * {@code performer} above, every one of those fields is itself typed to
 * reference only an {@code Organization} — so the reference-type gate can't
 * help here, this is a genuine resource-type collision, not a reference-type
 * one. None of those resource types appear in tibERbu traffic today, so this
 * is a latent gap rather than an active bug — left as-is for now rather than
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
 * {@code extract(resource)} returns a {@link FacilityDetails} with {@code
 * facilityId="KE-SHRF-D601602F-C9AC-4CC5-9347"} — read from {@code
 * Consent.getOrganization()}, with the {@code "Organization/"} prefix
 * stripped — and {@code facilityName} read from that same reference's {@code
 * display} field, or {@code null} when the reference carries no display.
 *
 * <p>Never throws: a resource with no matching reference resolves to
 * {@code null} (the whole {@link FacilityDetails}, not just the name), which
 * {@code FacilityFilterProperties.isFacilityAllowed()} already treats as
 * "always admit."
 */
@Component
public class FacilityIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(FacilityIdExtractor.class);

    /**
     * Accessor method names tried in priority order. A given FHIR R4 resource
     * class declares at most one of these — {@code getOrganization()} for
     * {@code Consent}, {@code getServiceProvider()} for {@code Encounter},
     * {@code getManagingOrganization()} for {@code EpisodeOfCare}, {@code
     * getPerformer()} for {@code ServiceRequest} — so the order only matters
     * in that it's checked deterministically, not because more than one could
     * ever match the same resource.
     */
    private static final String[] ORGANIZATION_ACCESSOR_METHOD_NAMES =
            {"getOrganization", "getServiceProvider", "getManagingOrganization", "getPerformer"};

    /**
     * The one FHIR resource type name a prefixed reference string is accepted
     * for. This is what makes {@code getPerformer} safe to include in {@link
     * #ORGANIZATION_ACCESSOR_METHOD_NAMES} despite it being a <em>union</em>
     * reference (Practitioner|PractitionerRole|Organization|CareTeam|
     * HealthcareService|Patient|Device|RelatedPerson on both {@code
     * ServiceRequest.performer} and {@code Observation.performer}): real
     * tibERbu {@code Observation} payloads populate {@code performer} with
     * {@code Practitioner/...} references exclusively (confirmed in {@code
     * artifacts/observation.json}), and this check rejects exactly those —
     * only a reference literally prefixed {@code "Organization/"} is ever
     * accepted as a facility.
     */
    private static final String ORGANIZATION_RESOURCE_TYPE = "Organization";

    /**
     * Extracts the facility ID and display name for one bundle entry's FHIR
     * resource from its {@code organization}/{@code serviceProvider}/{@code
     * managingOrganization} reference — see the class javadoc for which
     * resource types carry which. Both values are read from the same
     * resolved {@link Reference} in one pass.
     *
     * <p>Example — the real tibERbu {@code Consent} entry from the class
     * javadoc resolves to {@code FacilityDetails{facilityId="KE-SHRF-D601602F-C9AC-4CC5-9347",
     * facilityName=null}} (that example carries no {@code display}). A
     * resource with none of {@link #ORGANIZATION_ACCESSOR_METHOD_NAMES} at all (e.g.
     * {@code Observation}), or one whose matching reference is unpopulated,
     * resolves to {@code null}.
     *
     * @param resource the entry's parsed FHIR resource
     * @return the facility details, or {@code null} when no organization reference resolves
     */
    public FacilityDetails extract(IBaseResource resource) {
        // Step 1: a null resource has nothing to extract from — this can happen
        // defensively even though callers are not expected to pass null.
        if (resource == null) {
            return null;
        }

        // Step 2: try each accessor, and each candidate reference within it, in
        // turn. Resources that don't declare any of the accessors (most clinical
        // resource types) simply have nothing to resolve here.
        FacilityDetails facilityDetails = resolveFacilityDetails(resource);
        if (facilityDetails != null) {
            log.debug("Extracted facility ID '{}' (name '{}') from {} organization reference",
                    facilityDetails.facilityId(), facilityDetails.facilityName(), resource.fhirType());
        }
        return facilityDetails;
    }

    /**
     * Resolves {@link FacilityDetails} by trying each accessor in {@link
     * #ORGANIZATION_ACCESSOR_METHOD_NAMES} in order, and — for a {@code
     * List<Reference>} accessor like {@code Consent.getOrganization()} or
     * {@code ServiceRequest.getPerformer()} — each list entry in order,
     * returning the first candidate that {@link #referenceToFacilityDetails}
     * actually resolves to something.
     *
     * <p>Deliberately does <strong>not</strong> commit to "the first populated
     * reference" before validating it: a candidate that's populated but fails
     * the {@code Organization}-prefix gate in {@link #resolveFacilityId} (e.g.
     * a {@code Practitioner/} entry ahead of an {@code Organization/} one in
     * the same {@code performer} list) must not block a later candidate in
     * the same list from being tried — it should be skipped over exactly like
     * an unpopulated one is, not treated as a final answer.
     *
     * @return the first candidate's facility details, or {@code null} when no
     *         accessor exists on {@code resource}'s class, or none that does
     *         has any candidate that resolves
     */
    private FacilityDetails resolveFacilityDetails(IBaseResource resource) {
        for (String organizationAccessorMethodName : ORGANIZATION_ACCESSOR_METHOD_NAMES) {
            Object organizationAccessorResult = invokeOrganizationAccessorIfPresent(resource, organizationAccessorMethodName);

            if (organizationAccessorResult instanceof Reference reference) {
                FacilityDetails facilityDetails = referenceToFacilityDetails(reference);
                if (facilityDetails != null) {
                    return facilityDetails;
                }
                continue;
            }
            if (organizationAccessorResult instanceof List<?> referenceCandidates) {
                for (Object referenceCandidate : referenceCandidates) {
                    if (referenceCandidate instanceof Reference reference) {
                        FacilityDetails facilityDetails = referenceToFacilityDetails(reference);
                        if (facilityDetails != null) {
                            return facilityDetails;
                        }
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

    /**
     * Builds the {@link FacilityDetails} for a resolved organization
     * reference: the facility ID via {@link #resolveFacilityId}, and the
     * facility name from that same reference's {@code display} field —
     * both values live on the same {@link Reference} node, so no second
     * accessor lookup is needed.
     *
     * @return the facility details, or {@code null} when {@link
     *         #resolveFacilityId} itself resolves to nothing — a display
     *         name with no ID to attach it to is discarded, not surfaced
     *         on its own
     */
    private FacilityDetails referenceToFacilityDetails(Reference reference) {
        String facilityId = resolveFacilityId(reference);
        if (facilityId == null) {
            return null;
        }
        String facilityName = reference.hasDisplay() ? reference.getDisplay() : null;
        return new FacilityDetails(facilityId, facilityName);
    }

    /**
     * Extracts the bare facility ID from a {@link Reference}.
     *
     * <p>Step 1: when {@code reference.getReference()} contains a {@code
     * ResourceType/} prefix, it is accepted <strong>only</strong> when that
     * prefix is literally {@link #ORGANIZATION_RESOURCE_TYPE} — {@code
     * "Organization/1302"} resolves to {@code "1302"}, but {@code
     * "Practitioner/1302"} resolves to nothing here at all (step 1 does not
     * fire), not to {@code "1302"}. A reference with no {@code /} at all
     * (a bare id, e.g. {@code "1302"}) has no type to validate and passes
     * through unchanged, exactly as before.
     *
     * <p>Step 2: falls back to {@code identifier.value} (e.g. {@code
     * {"identifier": {"value": "1302"}}}) whenever step 1 found nothing —
     * either no reference string at all, a reference string whose {@code
     * Organization/} prefix stripped to blank (e.g. {@code "Organization/"}
     * with nothing after it), or a reference string prefixed with some other
     * resource type entirely.
     *
     * <p>Example (step 1, the common case): given {@code {"reference":
     * "Organization/KE-SHRF-D601602F-C9AC-4CC5-9347"}}, step 1 returns {@code
     * "KE-SHRF-D601602F-C9AC-4CC5-9347"} immediately.
     *
     * <p>Example (step 1, the rejection this exists for): given {@code
     * {"reference": "Practitioner/PUID-0000195-9"}} — a real {@code
     * Observation.performer} entry — step 1 does not match (the prefix isn't
     * {@code "Organization"}), so this falls through to step 2, and on to
     * {@code null} when (as in that real payload) no identifier is present
     * either. Without this check, the old blind "segment after the last /"
     * logic would have wrongly returned {@code "PUID-0000195-9"} as a
     * facility ID.
     *
     * <p>Example (step 2, the fallback): given {@code {"identifier": {"value":
     * "1302"}}} — no {@code "reference"} field at all — step 1 finds nothing;
     * step 2 then reads {@code identifier.value} and returns {@code "1302"}.
     */
    private String resolveFacilityId(Reference reference) {
        // Step 1: prefer the reference string, but only a bare id (no "/" at
        // all) or one whose "ResourceType/" prefix is literally "Organization".
        if (reference.hasReference()) {
            String rawReference = reference.getReference();
            int lastSlashIndex = rawReference.lastIndexOf('/');
            if (lastSlashIndex < 0) {
                if (!rawReference.isBlank()) {
                    return rawReference;
                }
            } else if (ORGANIZATION_RESOURCE_TYPE.equals(rawReference.substring(0, lastSlashIndex))) {
                String facilityId = rawReference.substring(lastSlashIndex + 1);
                if (!facilityId.isBlank()) {
                    return facilityId;
                }
            }
        }
        // Step 2: no usable Organization reference string — fall back to
        // identifier.value, the alternate way a FHIR reference can carry an identity.
        if (reference.hasIdentifier() && reference.getIdentifier().hasValue()) {
            return reference.getIdentifier().getValue();
        }
        // Step 3: neither a usable reference string nor an identifier — this
        // Reference carries no extractable facility identity.
        return null;
    }
}
