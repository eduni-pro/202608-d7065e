package se.ltu.d7065e.physics.entity;

/**
 * The faults the simulator can inject (T-14). Each is injected between a command and its physical
 * effect, so the command record and the physics disagree — which is what gives the monitor
 * something to detect.
 *
 * <p>Four kinds against {@code FaultClassification}'s three, and that asymmetry is deliberate.
 * This enum names <em>mechanisms the plant can be made to exhibit</em>; {@code FaultClassification},
 * which lives in {@code shared}, names <em>conclusions the monitor can reach</em>. The two need not
 * grow together, and here they do not: {@link #SENSOR_DRIFT} and {@link #SENSOR_OFFSET} are two
 * different ways for a sensor to be wrong and the monitor has one word for both of them. Keeping
 * them apart in the generator is what lets T-15 report that the word is reachable from one of them
 * and not from the other.
 */
public enum FaultKind {

    /** The room's damper ignores commands and holds a frozen position. */
    STUCK_DAMPER(false),

    /** Plant-wide filter blockage: less air through the fan and more power to move it. */
    BLOCKED_FILTER(true),

    /**
     * A sensor whose zero walks away at a constant rate; the room's true concentration is untouched.
     * The magnitude is a rate, in ppm per hour, and that is what makes the fault unbounded: left
     * running it passes every threshold in the system in turn.
     */
    SENSOR_DRIFT(false),

    /**
     * A sensor whose zero jumps once, by a fixed number of ppm, and then holds — a recalibration
     * entered against the wrong reference, or a replacement element installed without one. The
     * room's true concentration is untouched, exactly as for {@link #SENSOR_DRIFT}.
     *
     * <p>A separate kind rather than a second parameter on {@code SENSOR_DRIFT}, for a reason that
     * is about the record and not about taste: {@code InjectedFault.magnitude} is a single number
     * whose meaning is its kind's, and here the two meanings have different <b>units</b> — ppm per
     * hour against ppm. One kind carrying both would need a second field, which would make the
     * {@code KIND:TARGET:MAGNITUDE:ONSET} grammar five fields wide and break every existing spec.
     * As its own kind it costs the grammar nothing: {@code FaultSpecParser} resolves kinds by name,
     * so {@code SENSOR_OFFSET:A124:350:T+3600} parses the day the constant is added.
     *
     * <p>It is also a different fault to detect, which is the point of adding it. A ramp makes the
     * overnight floor informative only after a day, by which time the accumulated offset has already
     * inflated the quasi-steady generation estimate past the step amplitude the classifier's drift
     * leaf needs — the two bounds close against each other and T-15 measured no gap between them at
     * any rate from 5 to 160 ppm/h. The hope was that a step would hold still while the floor fills,
     * so that the two signals could be true at the same instant. <b>Measured, it does not.</b> The
     * two mechanisms fail the leaf in exactly complementary places and neither satisfies it once:
     * of 57 confirmed drift runs 12 reached an informative floor and none a matched step, and of 35
     * confirmed offset runs 15 reached a matched step and none an informative floor. A step confirms
     * within minutes of the inner loop reacting, long before the 24 h window it would need. So the
     * drift leaf is unreachable from either mechanism, and that is a property of the leaf rather
     * than of this enum.
     */
    SENSOR_OFFSET(false);

    private final boolean plantWide;

    FaultKind(boolean plantWide) {
        this.plantWide = plantWide;
    }

    /** True when the fault targets the shared air handler rather than one room. */
    public boolean isPlantWide() {
        return plantWide;
    }
}
