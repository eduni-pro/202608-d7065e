package se.ltu.d7065e.diagnosis.entity;

/**
 * What the classifier decided the fault is (T-15). {@code UNCLASSIFIED} is a first-class
 * outcome: a confirmed residual with no matching signature is still worth a work order,
 * and pretending otherwise would hide the taxonomy's limits.
 *
 * <p>A damper that has stopped answering commands gets two constants rather than one, because the
 * two extremes of travel need opposite responses (D-11). A damper stuck <em>shut</em> starves its
 * room and needs the other five trimmed so the proportional split pushes air into it. A damper
 * stuck <em>wide open</em> leaves its room over-ventilated and safe, and takes its extra air out of
 * the other five rooms' share — so there is nothing to compensate for the room itself, and a
 * supervisor that could not tell the two apart answered the second with the first's work order.
 * The direction used to live in the evidence prose, where nothing could branch on it.
 */
public enum FaultClassification {
    NONE,
    STUCK_DAMPER,
    DAMPER_STUCK_OPEN,
    BLOCKED_FILTER,
    SENSOR_DRIFT,
    UNCLASSIFIED;

    /**
     * A damper that no longer follows its commands, at either extreme of travel. The shared flow
     * model has to stop believing both of them: the split is a ratio over all six openings, so a
     * damper credited with a position it is not at moves air on paper between the other five rooms
     * (ADR-0010, R-7). Asked as one question so that a consumer cannot answer it for one extreme
     * and forget the other.
     */
    public boolean isUnresponsiveDamper() {
        return this == STUCK_DAMPER || this == DAMPER_STUCK_OPEN;
    }
}
