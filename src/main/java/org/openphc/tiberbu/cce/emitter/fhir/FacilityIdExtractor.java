package org.openphc.tiberbu.cce.emitter.fhir;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.BackboneElement;
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
 * subject}/{@code patient}. The accessor names are tried in order:
 * {@link #ORGANIZATION_ACCESSOR_METHOD_NAMES}. A survey of every tibERbu event on dev
 * (every resource type seen in {@code inbound_event_log}) found these elements
 * carrying the facility as an {@code Organization/} reference:
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
 *   <li>{@code ServiceRequest.requester} — {@code getRequester()}, a single
 *       {@code Reference}, tried before {@code performer}: the ordering
 *       facility (14 ServiceRequests on dev, usually a Practitioner instead)</li>
 *   <li>{@code Composition.custodian} — {@code getCustodian()}, a single
 *       {@code Reference}: R4 "the organization responsible for ongoing
 *       maintenance of and access to the composition" (85 of 93 Compositions)</li>
 *   <li>{@code MedicationDispense.performer[].actor} and {@code
 *       Procedure.performer[].actor} then {@code .onBehalfOf} — {@code
 *       getPerformer()} returns performer components here, not references;
 *       see {@link #PERFORMER_COMPONENT_REFERENCE_METHOD_NAMES}</li>
 * </ul>
 * Every other resource type seen (AllergyIntolerance, Condition,
 * MedicationRequest) carries no Organization reference in these elements and
 * so resolves to {@code null} — a Practitioner {@code requester}/{@code
 * performer}/{@code actor} fails the Organization gate. {@code
 * Observation.performer} is usually a Practitioner only; when it also names an
 * Organization, that is used. {@code
 * MedicationDispense} and {@code Procedure} carry a {@code location} reference
 * instead, which is deliberately NOT treated as a facility reference: {@code location}
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
 * Observation} payloads put the practitioner first — as a {@code
 * Practitioner/...} reference, or by {@code identifier} alone — so {@link
 * #resolveFacilityId} accepts only an {@code Organization/<id>} reference
 * string — this is what keeps {@code getPerformer()} safe to add here without
 * misattributing an {@code Observation}'s performing practitioner as its facility.
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
     * Accessor method names tried in priority order:
     * {@code getOrganization()} for {@code Consent}, {@code getServiceProvider()} for
     * {@code Encounter}, {@code getManagingOrganization()} for {@code EpisodeOfCare},
     * {@code getCustodian()} for {@code Composition}, {@code getRequester()} then
     * {@code getPerformer()} for {@code ServiceRequest}.
     *
     * <p>{@code ServiceRequest} is the one type with two of them, so the order matters there:
     * {@code requester} — "the individual [or organization] who initiated the request" — names
     * the facility that placed the order, which is the facility the event belongs to;
     * {@code performer} — "the desired performer" — names where it is to be done. On dev, 8
     * of the 14 ServiceRequests with an Organization requester have a different performer
     * Organization. A Practitioner requester (the usual case) fails the Organization gate, so
     * the walk carries on to {@code performer}.
     */
    private static final String[] ORGANIZATION_ACCESSOR_METHOD_NAMES =
            {"getOrganization", "getServiceProvider", "getManagingOrganization", "getCustodian", "getRequester",
             "getPerformer"};

    /** References read, in order, from a {@code MedicationDispense}/{@code Procedure} performer component. */
    private static final String[] PERFORMER_COMPONENT_REFERENCE_METHOD_NAMES = {"getActor", "getOnBehalfOf"};

    /**
     * The one FHIR resource type name a prefixed reference string is accepted
     * for. This is what makes {@code getPerformer} safe to include in {@link
     * #ORGANIZATION_ACCESSOR_METHOD_NAMES} despite it being a <em>union</em>
     * reference (Practitioner|PractitionerRole|Organization|CareTeam|
     * HealthcareService|Patient|Device|RelatedPerson on both {@code
     * ServiceRequest.performer} and {@code Observation.performer}): only a
     * reference string literally prefixed {@code "Organization/"} is ever
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
                    } else if (referenceCandidate instanceof BackboneElement performerComponent) {
                        // MedicationDispense/Procedure performer[] holds components, not
                        // references — the reference sits one level down, in actor/onBehalfOf.
                        FacilityDetails facilityDetails = performerComponentToFacilityDetails(performerComponent);
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
     * Resolves a performer component — the element type of {@code performer[]}
     * on {@code MedicationDispense} and {@code Procedure} — by trying {@code
     * actor}, then {@code onBehalfOf} (declared on {@code Procedure} only). A
     * Practitioner {@code actor} fails the Organization gate, so the walk falls
     * through to the Organization it acted for.
     *
     * <p>Example — a real tibERbu {@code Procedure.performer[0]} of {@code
     * {"actor": {"reference": "Practitioner/KE-SHRPR-..."}, "onBehalfOf":
     * {"reference": "Organization/KE-SHRF-5FF8CB67-ACDA-4705-AD03", "display":
     * "LIKONI SUBCOUNTY HOSPITAL"}}} resolves to that {@code onBehalfOf}
     * Organization and its display.
     *
     * @return the facility details, or {@code null} when neither reference is an Organization
     */
    private FacilityDetails performerComponentToFacilityDetails(BackboneElement performerComponent) {
        for (String performerComponentReferenceMethodName : PERFORMER_COMPONENT_REFERENCE_METHOD_NAMES) {
            if (invokeOrganizationAccessorIfPresent(performerComponent, performerComponentReferenceMethodName)
                    instanceof Reference reference) {
                FacilityDetails facilityDetails = referenceToFacilityDetails(reference);
                if (facilityDetails != null) {
                    return facilityDetails;
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
    private Object invokeOrganizationAccessorIfPresent(IBase resource, String organizationAccessorMethodName) {
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
     * Extracts the facility ID from a {@link Reference}: the part after
     * {@code Organization/} in its reference string. Any other reference
     * resolves to {@code null} — another resource type ({@code
     * "Practitioner/PUID-0000195-9"}), a bare id with no prefix, or no reference
     * string at all. {@code identifier} is never read: it carries no resource
     * type, so a Practitioner's registration number cannot be told apart from a
     * facility code.
     *
     * <p>Example: {@code {"reference": "Organization/KE-SHRF-D601602F-C9AC-4CC5-9347"}}
     * resolves to {@code "KE-SHRF-D601602F-C9AC-4CC5-9347"}. A real tibERbu
     * {@code Observation.performer[0]} of {@code {"display": "Amina Yusuf",
     * "identifier": {"value": "RN-52817 (demo)"}}} resolves to {@code null}, so
     * the walk carries on to the {@code Organization/} reference in {@code
     * performer[1]}.
     */
    private String resolveFacilityId(Reference reference) {
        // Step 1: only a reference string starting "Organization/" can name a facility —
        // a Practitioner/ reference, a bare id, or an identifier-only reference cannot.
        String organizationReferencePrefix = ORGANIZATION_RESOURCE_TYPE + "/";
        if (!reference.hasReference() || !reference.getReference().startsWith(organizationReferencePrefix)) {
            return null;
        }
        // Step 2: strip the prefix; "Organization/" with nothing after it names no facility.
        String facilityId = reference.getReference().substring(organizationReferencePrefix.length());
        return facilityId.isBlank() ? null : facilityId;
    }
}
