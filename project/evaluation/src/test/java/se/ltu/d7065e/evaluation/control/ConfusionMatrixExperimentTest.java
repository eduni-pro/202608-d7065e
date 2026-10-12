package se.ltu.d7065e.evaluation.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.evaluation.control.ConfusionMatrixExperiment.Arm;
import se.ltu.d7065e.evaluation.control.ConfusionMatrixExperiment.Injected;
import se.ltu.d7065e.evaluation.control.ConfusionMatrixExperiment.Run;

/**
 * The cells of T-15's fixed-seed matrix, pinned one run at a time so that a change to the
 * classifier, the plant or the harness cannot move a published number without a test saying so.
 *
 * <p>Four of these five tests assert a result the project would rather not have. They are written
 * that way on purpose: each one carries, in its failure message, what it would mean if the
 * assertion started failing, so that a future run which finally classifies a blocked filter or a
 * drifting sensor is read as good news to go and write up rather than as a broken test to relax.
 */
class ConfusionMatrixExperimentTest {

    @Test
    void shouldClassifyAStuckDamperAsStuckDamperInClosedLoop() {
        Run run = ConfusionMatrixExperiment.run(Arm.CLOSED_LOOP, Injected.STUCK_DAMPER,
                ConfusionMatrixExperiment.FIXED_SEED, ConfusionMatrixExperiment.DRIFT_PPM_PER_HOUR);

        assertEquals(FaultClassification.STUCK_DAMPER, run.emitted(),
                "the one cell of T-15's matrix that is on its diagonal moved");
        assertTrue(run.localised(), "the work order named a room the fault was not injected in");
    }

    /**
     * The regression for the defect that made the first version of the stepped arm measure nothing
     * at all: its 26 h history put the judged step at 02:00, in an empty building already sitting
     * at the outdoor concentration, where a damper that refuses to open changes nothing a sensor
     * can see. The assertion is behavioural rather than a check on the constant, because what
     * matters is that the step lands somewhere a frozen damper is observable.
     */
    @Test
    void shouldJudgeTheSteppedArmsStepWhileTheBuildingIsStillOccupied() {
        Run run = ConfusionMatrixExperiment.run(Arm.STEPPED, Injected.STUCK_DAMPER,
                ConfusionMatrixExperiment.FIXED_SEED, ConfusionMatrixExperiment.DRIFT_PPM_PER_HOUR);

        assertEquals(FaultClassification.STUCK_DAMPER, run.emitted(),
                "the stepped arm's commanded step was not judged against an observable room — "
                        + "check that the history still ends inside an occupied stretch before "
                        + "treating this as a classifier regression");
    }

    @Test
    void shouldLeaveTheBuildingUnconfirmedWhenNothingIsInjected() {
        Run run = ConfusionMatrixExperiment.run(Arm.CLOSED_LOOP, Injected.NOTHING,
                ConfusionMatrixExperiment.FIXED_SEED, ConfusionMatrixExperiment.DRIFT_PPM_PER_HOUR);

        assertEquals(FaultClassification.NONE, run.emitted(),
                "a false confirmation on the fixed seed's fault-free arm, which is R-3's row of "
                        + "this matrix");
        assertEquals(0, run.roomsConfirmed(), "a room was confirmed with no fault injected");
    }

    /**
     * A finding, not a wish, and the same one {@code FaultClassifier}'s javadoc and
     * {@code CrossRoomSignatureTest} already own: under ADR-0008's proportional split no damper
     * command can push all six residuals the same way, so the plant-level branch is unreachable
     * and the confirmed case carries the fan-power excess as evidence instead.
     */
    @Test
    void shouldReportABlockedFilterAsUnclassifiedRatherThanBlockedFilter() {
        Run run = ConfusionMatrixExperiment.run(Arm.CLOSED_LOOP, Injected.BLOCKED_FILTER,
                ConfusionMatrixExperiment.FIXED_SEED, ConfusionMatrixExperiment.DRIFT_PPM_PER_HOUR);

        assertEquals(FaultClassification.UNCLASSIFIED, run.emitted(),
                "if this now reads BLOCKED_FILTER the residual gained a plant-level common mode "
                        + "and both this matrix and the classifier's own finding are out of date — "
                        + "measure why before relaxing it");
    }

    /**
     * The second half of T-15's sensor finding, and the reason {@code FaultKind.SENSOR_OFFSET} was
     * added. The drift leaf wants an informative overnight floor and a matched step response in the
     * same instant; a ramp can have the first and never the second, and a step offset can have the
     * second and never the first. Measured over every sensor-fault run that confirmed: the ramp
     * reached an informative floor 12 times and a matched step 0 times, the offset a matched step 15
     * times and an informative floor 0 times, and neither reached both even once.
     *
     * <p>This pins one run of each so the claim cannot rot. If either assertion starts failing, the
     * conjunction has become satisfiable and {@code SENSOR_DRIFT} may now be reachable — which is
     * news to go and measure, not a test to relax.
     */
    @Test
    void shouldReachTheDriftLeafsTwoConditionsOnlyOneAtATime() {
        Run ramp = ConfusionMatrixExperiment.run(Arm.STEPPED, Injected.SENSOR_DRIFT,
                ConfusionMatrixExperiment.FIXED_SEED, ConfusionMatrixExperiment.DRIFT_PPM_PER_HOUR);
        Run step = ConfusionMatrixExperiment.run(Arm.CLOSED_LOOP, Injected.SENSOR_OFFSET,
                ConfusionMatrixExperiment.FIXED_SEED, ConfusionMatrixExperiment.OFFSET_PPM);

        assertTrue(ramp.floorInformative(),
                "a ramp outlives the overnight window, so by its confirmation the floor has seen "
                        + "a day of the fault");
        assertFalse(ramp.stepAtConfirmation().matched(),
                "and the offset it accumulated getting there has inflated the generation estimate "
                        + "past the step amplitude the leaf needs");

        assertFalse(step.floorInformative(),
                "a step offset large enough to confirm does so within the hour, far inside the "
                        + "24 h the floor needs — it was confirmed " + step.firstMinute() + " min in");
        assertNotEquals(FaultClassification.SENSOR_DRIFT, step.emitted(),
                "a step sensor offset is still not named as a sensor fault, so adding the fault "
                        + "kind did not make the leaf reachable");
    }

    /**
     * The result this experiment exists to report. {@code InjectedFault.sensorDrift} is a rate, so
     * over the 24 h the overnight floor needs to become informative the offset has already grown
     * past the amplitude the step-shape test needs; the drift therefore arrives as a damper that
     * stopped answering. The monitor's own {@code CrossRoomSignatureTest} reaches SENSOR_DRIFT
     * only because its test double applies the offset as an instantaneous step.
     */
    @Test
    void shouldMisreportADriftingSensorAsAStuckDamper() {
        Run run = ConfusionMatrixExperiment.run(Arm.CLOSED_LOOP, Injected.SENSOR_DRIFT,
                ConfusionMatrixExperiment.FIXED_SEED, ConfusionMatrixExperiment.DRIFT_PPM_PER_HOUR);

        assertEquals(FaultClassification.STUCK_DAMPER, run.emitted(),
                "the drifting sensor's classification moved; if it now reads SENSOR_DRIFT, T-15's "
                        + "headline finding has been fixed and the report has to be rewritten");
        assertTrue(run.judgedSteps() > 0,
                "the stuck-damper leaf was reached with no step judged at all, which is the "
                        + "vacuous path through the tree and a different defect from the measured "
                        + "one — the amplitude test failing on a step that was judged");
    }
}
