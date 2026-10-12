package se.ltu.d7065e.diagnosis.entity;

/**
 * What the last commanded step told the monitor about a room's damper.
 *
 * <p>Three states rather than two, and the third is the whole point. The signal used to be a
 * {@code boolean} called {@code stepShapeUnchanged}, computed as "the amplitude ratio is a number
 * and it reaches the match fraction". That made {@code false} mean two different things — a step
 * was judged and the room did not answer, or no step has ever been judged — and the classifier's
 * stuck-damper leaf tested the negation, so it read "the commanded step produced no matching
 * response" off a room that had never been commanded to move. T-15 measured the consequence: 9 of
 * 57 drift runs confirmed as {@code STUCK_DAMPER} with zero judged steps.
 *
 * <p>{@code NOT_JUDGED} is absent evidence, not evidence of absence. A leaf that needs a missing
 * response must ask for {@link #MISSING} and let {@code NOT_JUDGED} fall through to
 * {@code UNCLASSIFIED}, which is the honest destination for a case the monitor cannot yet explain.
 * {@code FaultSignals} already applies that discipline to the overnight floor, whose {@code NaN}
 * its javadoc calls "no evidence, not healthy"; this type carries it across to the step response.
 */
public enum StepResponse {

    /** A step was judged and the response arrived with its predicted amplitude. */
    MATCHED,

    /** A step was judged and the response did not arrive, which is a damper that stopped answering. */
    MISSING,

    /** No step has been judged yet, so this signal says nothing either way. */
    NOT_JUDGED;

    /** True only when a step was actually judged and matched; never true for {@link #NOT_JUDGED}. */
    public boolean matched() {
        return this == MATCHED;
    }

    /** True only on positive evidence that a commanded step went unanswered. */
    public boolean missing() {
        return this == MISSING;
    }
}
