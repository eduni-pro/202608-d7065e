package se.ltu.d7065e.diagnosis.control;

/**
 * What the lower CUSUM arm is driven by (T-32 section 5). Three values rather than a boolean,
 * because turning the second arm on is not one decision but two: whether to accumulate on
 * negative residuals at all, and whether to put a plausibility guard in front of the arm the way
 * the upper arm has one. The two answers cost different things, both measured, and the experiment
 * that measured them needs all three settings reachable.
 *
 * <h2>Why the guard is not symmetric for free</h2>
 *
 * The upper arm is driven by {@code measured - ceiling}, where the ceiling is the trajectory of a
 * room filled to its design occupancy: CO2 above it is CO2 that arriving people could not
 * account for, so the guard exists because unseen occupancy can only ever <em>add</em> CO2. The
 * mirror of that statement on the low side is the trajectory of an <em>empty</em> room, zero
 * generation, which is the least CO2 the room could possibly hold — and clamped at the 420 ppm
 * outdoor concentration, because no amount of ventilation takes a room below the air it is
 * ventilated with.
 *
 * <p>That guard is sound, and it is expensive in a way the upper one is not: a damper stuck wide
 * open never crosses it. "Everybody left the room" explains a room that stopped getting stuffier
 * at least as well as "the damper ignored the close command" does, and inside one 10 min judging
 * window nothing separates the two. The guard therefore keeps R-3 and loses the whole stuck-open
 * signature; removing it finds the stuck-open damper and loses R-3. The numbers are in
 * {@code FalseAlarmWeekTest} and {@code TwoSidedDetectionTest}, and the choice is reported rather
 * than hidden: an observer with no occupancy signal cannot have both.
 *
 * <h2>What is left of the guard after ADR-0010</h2>
 *
 * Only the clamp. The empty-room trajectory was removed because it was not sound after all, in a
 * direction the class comment above did not anticipate: it falls at the <em>believed</em> airflow
 * while the room falls at the actual one, so a healthy room drops through its own floor whenever
 * the shared split has moved and the belief has not caught up. The clamp — the outdoor
 * concentration, less the sensor-noise allowance the classifier already subtracts for the same
 * reason — depends on no belief and no anchor, so it has no such failure mode, and it is the whole
 * of what {@code GUARDED} claimed to detect. See {@code ZoneObserver.unconditionalFloorPpm}.
 */
public enum LowerArm {

    /**
     * No lower arm. The detector is bit for bit the one-sided one the T-04 spike measured, which
     * is what makes it the control arm of the R-3 experiment rather than a legacy setting.
     */
    OFF,

    /**
     * Driven by {@code measured - floor}, where the floor is the outdoor concentration less an
     * allowance for sensor noise. Sees a sensor drifting low once its reading passes into the
     * physically impossible region below outdoor, which is the one fault in this taxonomy with a
     * safety consequence: an under-reading sensor makes the inner loop under-ventilate an occupied
     * room. Does not see a damper stuck wide open. The default, because it buys that one diagnosis
     * and, since ADR-0010, costs R-3 nothing measurable.
     */
    GUARDED,

    /**
     * Driven by the honest residual, {@code measured - predicted}. Sees both low-side faults,
     * including the damper stuck wide open, and confuses either with a room emptying out inside a
     * judged window — which a working building does several times a day. Measured at 6 false
     * confirmations per fault-free simulated week against R-3's limit of 1, with all six rooms
     * latched by the end of the week. Kept reachable because that number is a result.
     */
    HONEST;

    public boolean isOn() {
        return this != OFF;
    }
}
