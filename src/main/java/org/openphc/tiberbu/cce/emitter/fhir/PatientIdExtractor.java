package org.openphc.tiberbu.cce.emitter.fhir;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.openphc.tiberbu.cce.emitter.exception.PatientIdNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Extracts the patient identifier that becomes the CloudEvents {@code subject},
 * from a single bundle entry's FHIR resource.
 *
 * <p>{@code entry[0]} (the Patient) is always skipped by {@link BundleEntryExtractor}
 * before extraction runs, so a {@code Patient} resource never legitimately reaches
 * {@link #extract(IBaseResource)}. This class does not need its own Patient-specific
 * resolution branch — every resource this method actually sees references a patient
 * indirectly, either as {@code subject} (e.g. {@code Encounter}, {@code Observation})
 * or as {@code patient} (e.g. {@code Consent}, {@code Immunization}).
 *
 * <p>Both accessors are found by reflection rather than a hardcoded per-type
 * mapping, matching the adaptor's no-allowlist contract (§3.3): whatever FHIR
 * resource type sits at {@code entry[1..n]} is handled the same way, with no
 * per-type code change needed for a resource type not seen before.
 *
 * <p>Example: given a {@code Consent} entry
 * <pre>{@code
 * {
 *   "resourceType": "Consent",
 *   "id": "VCR-20260901-57098420",
 *   "status": "active",
 *   "patient": { "reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A" }
 * }
 * }</pre>
 * {@code extract(resource)} returns {@code "KE-SHRP-170CDF0A-1363-4972-B36A"} —
 * read from {@code Consent.getPatient()} since {@code Consent} has no
 * {@code getSubject()} method, with the {@code "Patient/"} prefix stripped.
 */
@Component
public class PatientIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(PatientIdExtractor.class);

    private static final String PATIENT_REFERENCE_PREFIX = "Patient/";

    /**
     * Accessor method names tried in priority order. In practice a given FHIR R4
     * resource class defines at most one of these — {@code getSubject()} for
     * resources that model a generic subject (Encounter, Observation, Condition,
     * ServiceRequest, Procedure, ...), {@code getPatient()} for resources that
     * model the patient relationship directly (Consent, Immunization,
     * AllergyIntolerance, NutritionOrder, ...).
     */
    private static final String[] PATIENT_REFERENCE_ACCESSOR_METHOD_NAMES = {"getSubject", "getPatient"};

    /**
     * @param resource the entry's parsed FHIR resource; never a {@code Patient} in
     *                 practice (see class javadoc)
     * @return the patient identifier, with any {@code "Patient/"} prefix stripped
     * @throws PatientIdNotFoundException if {@code resource} is {@code null}, is
     *                                    unexpectedly a {@code Patient}, exposes
     *                                    neither {@code getSubject()} nor
     *                                    {@code getPatient()}, or the reference it
     *                                    finds is empty or blank
     */
    public String extract(IBaseResource resource) {
        if (resource == null) {
            throw new PatientIdNotFoundException("Cannot extract patient identifier from a null resource");
        }

        String resourceType = resource.fhirType();

        if (resource instanceof Patient) {
            // Structurally unreachable under the bundle contract — entry[0] is always
            // skipped before any resource reaches here. Reject rather than guess an
            // identity, so a future contract change surfaces loudly instead of
            // silently misattributing an event to the wrong subject.
            throw new PatientIdNotFoundException(
                    "Patient resource reached patient identifier extraction — entry[0] "
                            + "should have been skipped by BundleEntryExtractor");
        }

        Reference patientReference = resolvePatientReference(resource);
        if (patientReference == null) {
            throw new PatientIdNotFoundException("No patient reference found in " + resourceType + " resource");
        }

        String patientId = stripPatientPrefix(patientReference.getReference());
        if (patientId.isBlank()) {
            throw new PatientIdNotFoundException("Patient reference is blank in " + resourceType + " resource");
        }

        log.debug("Extracted patient identifier '{}' from {} resource", patientId, resourceType);
        return patientId;
    }

    /**
     * Resolves the patient reference by trying each accessor in
     * {@link #PATIENT_REFERENCE_ACCESSOR_METHOD_NAMES} in order, returning the
     * first one that both exists on {@code resource}'s class and is populated —
     * i.e. {@link Reference#hasReference()} is {@code true}, not just present but
     * empty.
     *
     * @return the resolved reference, or {@code null} if no accessor exists, or
     *         every accessor that does exist returns an empty reference
     */
    private Reference resolvePatientReference(IBaseResource resource) {
        for (String accessorMethodName : PATIENT_REFERENCE_ACCESSOR_METHOD_NAMES) {
            Reference reference = invokeReferenceAccessor(resource, accessorMethodName);
            if (reference != null && reference.hasReference()) {
                return reference;
            }
        }
        return null;
    }

    /**
     * Reflectively invokes {@code accessorMethodName} on {@code resource} when it
     * exists and returns a {@link Reference}.
     *
     * <p>HAPI FHIR model getters for optional reference fields never return Java
     * {@code null} — an unset field auto-vivifies into an empty {@code Reference}
     * (one that fails {@link Reference#hasReference()}), which is what most calls
     * here return. This method itself only returns {@code null} when the accessor
     * does not exist on the resource's class at all, or reflection genuinely fails.
     */
    private Reference invokeReferenceAccessor(IBaseResource resource, String accessorMethodName) {
        try {
            Method accessorMethod = resource.getClass().getMethod(accessorMethodName);
            if (!Reference.class.isAssignableFrom(accessorMethod.getReturnType())) {
                return null;
            }
            return (Reference) accessorMethod.invoke(resource);
        } catch (NoSuchMethodException methodNotDeclaredOnThisResourceType) {
            return null;
        } catch (ReflectiveOperationException unexpectedReflectionFailure) {
            log.warn("Failed to invoke {}() on {}: {}",
                    accessorMethodName, resource.fhirType(), unexpectedReflectionFailure.getMessage());
            return null;
        }
    }

    private String stripPatientPrefix(String rawReference) {
        return rawReference.startsWith(PATIENT_REFERENCE_PREFIX)
                ? rawReference.substring(PATIENT_REFERENCE_PREFIX.length())
                : rawReference;
    }
}
