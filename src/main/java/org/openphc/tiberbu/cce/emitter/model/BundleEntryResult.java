package org.openphc.tiberbu.cce.emitter.model;

/**
 * One bundle entry's outcome from {@code SourceAdaptorService.processBundleEntries()} —
 * purely an internal handoff to {@code InboundEventService}, never
 * serialized. Exactly one of two states:
 * <ul>
 *   <li><b>Ready to forward</b> — adaptation succeeded and passed the facility
 *       filter; {@link #cloudEventToForward()} is non-null and still needs
 *       {@code CollectorForwardingService.forward()} called on it.</li>
 *   <li><b>Terminal</b> — the entry is already done, one way or another
 *       (facility-filtered, or a FHIR parsing/patient-identifier failure);
 *       {@link #terminalResult()} is the final {@link TransformationResult},
 *       nothing more to do for this entry.</li>
 * </ul>
 *
 * <p>{@link #failureCause()} carries the original exception for a terminal
 * FAILED result only — {@link TransformationResult} itself only keeps the
 * exception's message (for the response body), not the exception object.
 * {@code InboundEventService} needs the original exception to re-throw when
 * every entry in the bundle ends up failed (see its class javadoc), so it has
 * to survive somewhere past the point {@link TransformationResult} is built.
 */
public record BundleEntryResult(
        int bundleEntryIndex, CloudEventDto cloudEventToForward, TransformationResult terminalResult, RuntimeException failureCause) {

    /**
     * @param bundleEntryIndex the entry's position in {@code resource.entry[]}
     * @param cloudEvent       the built CloudEvent, ready to forward
     * @return a ready-to-forward entry
     */
    public static BundleEntryResult readyToForward(int bundleEntryIndex, CloudEventDto cloudEvent) {
        return new BundleEntryResult(bundleEntryIndex, cloudEvent, null, null);
    }

    /**
     * @param result an {@link TransformationResult#OUTCOME_SKIPPED} result
     * @return a terminal, facility-filtered entry
     */
    public static BundleEntryResult skipped(TransformationResult result) {
        return new BundleEntryResult(result.bundleEntryIndex(), null, result, null);
    }

    /**
     * @param result an {@link TransformationResult#OUTCOME_FAILED} result
     * @param cause  the exception that caused it, preserved for the
     *               all-entries-failed re-throw case
     * @return a terminal, failed entry
     */
    public static BundleEntryResult failed(TransformationResult result, RuntimeException cause) {
        return new BundleEntryResult(result.bundleEntryIndex(), null, result, cause);
    }

    /** @return {@code true} when this entry still needs to be forwarded to the Collector */
    public boolean isReadyToForward() {
        return cloudEventToForward != null;
    }
}
