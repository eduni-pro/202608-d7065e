package se.ltu.d7065e.diagnosis.control;

/**
 * When a confirmed case is given its classification: in the cycle the CUSUM confirms, or once a
 * commanded step has been judged.
 *
 * <p>The question this type exists to settle is an ordering one. {@code FaultClassifier}'s
 * stuck-damper leaf wants a step response, {@link ZoneObserver} only judges a step the monitor has
 * seen commanded, and the monitor may not command — it asks the supervisor for a probe by putting
 * {@link ZoneMonitor#PROBE_REQUEST} in the evidence of a {@code FaultStateChanged}, and the
 * supervisor acts on that event only once the case is latched. So at the instant the CUSUM
 * confirms, no step the monitor <em>asked for</em> has ever been judged. What it may have is an
 * ordinary control command that happened to arm and resolve a window first, which is luck rather
 * than design; when none did, the leaf reads {@code NOT_JUDGED} as good enough and the stuck-damper
 * diagnosis rests on two signals rather than three.
 *
 * <p>Both settings keep {@code SUSPECT} reversible and {@code CONFIRMED} latching, and both open
 * the case on the same cycle, so neither changes detection delay. What they change is how much the
 * work order claims at the moment it is raised.
 *
 * @see ProbeOrderExperiment for the measurement that chose the default
 */
public enum ClassifyWhen {

    /**
     * Classify in the cycle the CUSUM confirms. The work order carries a named diagnosis from the
     * first instant the operator sees it, and the stuck-damper leaf reaches its conclusion without
     * a step the monitor asked for, recording in its own evidence when it had no judged step at all.
     */
    ON_CONFIRMATION,

    /**
     * Confirm with the classification deferred, ask for the probe at once, and name the fault when
     * the step window closes. The case latches on the same cycle as {@code ON_CONFIRMATION} — it
     * just latches as {@code UNCLASSIFIED} and is reclassified when the evidence arrives, which is
     * the only order in which the third signal can exist before the decision is taken.
     *
     * <p><b>Measured and not adopted.</b> It costs a median 11 min of work order that names no
     * fault, and the same 11 min before an unresponsive damper's believed opening is pinned, which
     * is R-7's isolation. It buys nothing: the probe is commanded and judged, and reports
     * {@code MISSING} in 27 of the 27 closed-loop runs where a step was judged — on a stuck damper,
     * on a drifting sensor whose damper is healthy, on a blocked filter, and on the run with nothing
     * injected at all. The cause is that the amplitude's measured leg carries the standing residual
     * (see {@code ZoneObserver.recordStepAmplitude}), so after any confirmation the ratio is a
     * statement about the residual rather than about the response. Correcting that baseline was
     * measured too, as an unlanded counterfactual: the signal then discriminates, but barely — 3 of
     * 8 genuinely stuck dampers read {@code MATCHED} and 5 of 11 healthy ones read {@code MISSING} —
     * and the reorder goes from changing nothing to losing 3 of the 8 correct stuck-damper
     * diagnoses. By the time the probe can be commanded the supervisor is already compensating, so
     * the room's answer is mostly the compensation's, which is the masking
     * {@code ZoneMonitor.recordProbeOutcome} already warns about, now measured on the diagnosis
     * itself.
     */
    AFTER_PROBE
}
