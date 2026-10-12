package se.ltu.d7065e.evaluation.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.control.ClassifyWhen;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.StepResponse;
import se.ltu.d7065e.evaluation.control.ConfusionMatrixExperiment.Arm;
import se.ltu.d7065e.evaluation.control.ConfusionMatrixExperiment.Injected;
import se.ltu.d7065e.evaluation.control.ProbeOrderExperiment.Run;

/**
 * T-35's verdict, pinned on the fixed seed so that the reasoning in the write-up cannot drift from
 * the runs. Every assertion here records a result the project would rather have gone the other way:
 * the reorder is sound, it does what it was designed to do, and it changes nothing.
 */
class ProbeOrderExperimentTest {

    private static final long SEED = ConfusionMatrixExperiment.FIXED_SEED;

    @Test
    void shouldLeaveTheDeployedOrderingAtOnConfirmation() {
        assertEquals(ClassifyWhen.ON_CONFIRMATION, MonitorParameters.defaults().classifyWhen(),
                "T-35 measured the reorder and did not keep it; if this default has moved, the "
                        + "matrix and the delays in T-15 and T-24 have to be re-measured");
    }

    @Test
    void shouldOpenTheCaseOnTheSameCycleUnderBothOrderings() {
        Run before = run(ClassifyWhen.ON_CONFIRMATION, Injected.STUCK_DAMPER);
        Run after = run(ClassifyWhen.AFTER_PROBE, Injected.STUCK_DAMPER);

        assertNotEquals(-1, before.detectionMinutes(), "the fixture needs a confirmed case");
        assertEquals(before.detectionMinutes(), after.detectionMinutes(),
                "R-1: deferring the diagnosis must not defer the detection");
        assertEquals(before.firstZone(), after.firstZone(),
                "and it must not change which room the work order names");
    }

    @Test
    void shouldWithholdTheNameForAWhileAndThenReachTheSameOne() {
        Run before = run(ClassifyWhen.ON_CONFIRMATION, Injected.STUCK_DAMPER);
        Run after = run(ClassifyWhen.AFTER_PROBE, Injected.STUCK_DAMPER);

        assertEquals(0, before.namingMinutes(), "ON_CONFIRMATION names the fault as it latches");
        assertEquals(FaultClassification.UNCLASSIFIED, after.latchedAs(),
                "AFTER_PROBE must raise the work order without a diagnosis");
        assertTrue(after.namingMinutes() > 0 && after.namingMinutes()
                        <= MonitorParameters.defaults().probeInterval().toMinutes(),
                "the wait must be real and bounded by the probe interval, was "
                        + after.namingMinutes());
        assertEquals(before.latchedAs(), after.settledAs(),
                "the wait bought a different answer; T-35's verdict rests on it buying the same one");
    }

    /**
     * The measurement the verdict turns on. The probe is commanded, its step is judged, and it
     * reports {@code MISSING} on a room whose damper is in perfect health — because by then the
     * supervisor is compensating and, more fundamentally, because the amplitude's measured leg
     * carries the standing residual that confirmed the case. The third signal arrives and says the
     * same thing for every fault, so it decides nothing.
     * {@code ProbeOrderedClassificationTest} isolates the cause.
     */
    @Test
    void shouldJudgeTheProbeAndStillLearnNothingFromItOnADriftingSensor() {
        Run after = run(ClassifyWhen.AFTER_PROBE, Injected.SENSOR_DRIFT);

        assertTrue(after.namingMinutes() > 0, "the drifting sensor's case was not deferred at all");
        assertEquals(StepResponse.MISSING, after.stepAtNaming(),
                "if the probe now reports MATCHED, the step amplitude has been fixed and the "
                        + "ordering question is worth re-measuring");
        assertEquals(run(ClassifyWhen.ON_CONFIRMATION, Injected.SENSOR_DRIFT).latchedAs(),
                after.settledAs(),
                "a judged probe changed the drifting sensor's diagnosis, which would be the result "
                        + "T-35 was looking for and did not find");
    }

    private static Run run(ClassifyWhen when, Injected injected) {
        return ProbeOrderExperiment.run(when, Arm.CLOSED_LOOP, injected, SEED,
                MonitorParameters.defaults().withClassifyWhen(when));
    }
}
