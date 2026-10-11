package se.ltu.d7065e.evaluation.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import se.ltu.d7065e.evaluation.control.LearnedContenderExperiment.FaultArm;
import se.ltu.d7065e.evaluation.control.LearnedContenderExperiment.FaultFreeRun;
import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * T-29's two arms on one seed, so that a change to the plant, the monitor or the harness cannot
 * move a published number without a test saying so.
 *
 * <p>The first test is the one that matters most and the only one that is about T-25 rather than
 * T-29: the fault arm has to be T-25's fault arm, minute for minute, or the two experiments'
 * tables cannot be put side by side. Three of the others assert findings the project would rather
 * not have, and each carries in its failure message what it would mean if it started failing.
 *
 * <p>One seed and one fit, held in static fields, because each arm is a simulated run and the
 * suite should not pay for four of them.
 */
class LearnedContenderExperimentTest {

    private static final long SEED = 1;

    private static final FaultFreeRun WEEK = LearnedContenderExperiment.faultFreeRun(SEED);
    private static final FaultArm FAULT = LearnedContenderExperiment.faultArm(SEED,
            WEEK.detectors());

    /** The strictest point the learned scale has: further out than every training sample. */
    private static final OneClassNoveltyDetector.AlarmRule STRICTEST =
            new OneClassNoveltyDetector.AlarmRule(1.0, 2);

    /** The quietest point the grid resolves, which is the contender's best case on R-3. */
    private static final OneClassNoveltyDetector.AlarmRule QUIETEST =
            new OneClassNoveltyDetector.AlarmRule(1.0, 30);

    @Test
    void shouldReproduceTheBaselineExperimentsFaultArmMinuteForMinute() {
        BaselineComparisonExperiment.FaultRun t25 = BaselineComparisonExperiment.faultRun(SEED);

        assertEquals(t25.reference(), FAULT.reference(),
                "T-29's fault arm no longer agrees with T-25's about the minute the frozen damper "
                        + "first ignored a command, so the two experiments' delays are measured "
                        + "from different events and the report may not put them side by side");
        assertEquals(t25.monitorDelay(), FAULT.monitorDelay(),
                "the incumbent's confirmation delay differs between T-25's arm and T-29's, which "
                        + "means the arms have drifted apart rather than that the monitor changed");
        assertEquals(t25.onset(), FAULT.onset(), "the arms inject the fault at different minutes");
    }

    /**
     * A finding, not a wish: T-25's arm is 8 h of warm-up plus 12 h of fault, and the monitor's
     * overnight window is 24 h, so the contender's drift feature can never be measured on the
     * fault arm. If this starts failing, T-25's arm has been lengthened and T-29's statement that
     * the fault arm is judged on two features is out of date.
     */
    @Test
    void shouldNeverKnowTheOvernightFloorOnTheFaultArm() {
        assertFalse(FAULT.floorEverKnown(),
                "the overnight floor became measurable on the fault arm, so T-29's two-feature "
                        + "caveat and the three it reports for the held-out week need re-measuring");
        assertEquals(2, FAULT.judgedFeaturesOnFaultDay(),
                "the fault arm is no longer judged on exactly the residual and the fan-power "
                        + "ratio");
    }

    @Test
    void shouldJudgeTheHeldOutWeekOnAllThreeFeatures() {
        assertEquals(3, WEEK.judgedFeaturesOnHeldOut(),
                "the held-out week lost a feature, which would make the false-alarm column and "
                        + "the fault-arm column less comparable than T-29 claims");
    }

    /**
     * T-29's sharpest finding, on the seed that produces it: seed 1 is one of the five of twenty
     * on which the frozen damper never moves the residual far enough for the incumbent to confirm,
     * and the contender is blind to exactly the same seed. The two read the same residual, so what
     * limits detection is the evidence rather than the decision rule, and a learned rule cannot
     * add evidence. If this starts failing, one of the two gained a signal the other has not and
     * the experiment's account of why the contender loses needs re-measuring.
     */
    @Test
    void shouldShareTheIncumbentsBlindSpotBecauseItReadsTheSameResidual() {
        int faulted = Topology.ZONE_IDS.indexOf(Topology.A124);
        boolean contenderFlagged = STRICTEST.firstFlag(FAULT.exceedance()[faulted], FAULT.onset(),
                FAULT.minutes()) >= 0;

        assertEquals(-1, FAULT.monitorDelay(),
                "seed 1 is no longer one of the incumbent's misses, so this test is comparing the "
                        + "two detectors on a run that is not the blind one any more");
        assertFalse(contenderFlagged,
                "the contender saw a fault the incumbent missed, which would mean a learned rule "
                        + "does extract something the CUSUM does not — good news to go and "
                        + "re-measure T-29 for, not a test to relax");
    }

    /**
     * The other side of the same coin: the contender is not blind in general. At the loosest point
     * on its learned scale it flags the injected room even on the seed both detectors miss, which
     * is why T-29's verdict rests on false alarms and delay rather than on detection.
     */
    @Test
    void shouldFlagEvenTheBlindSeedOnceTheLearnedLimitIsLoosened() {
        int faulted = Topology.ZONE_IDS.indexOf(Topology.A124);
        OneClassNoveltyDetector.AlarmRule loosest =
                new OneClassNoveltyDetector.AlarmRule(0.95, 2);

        assertTrue(loosest.firstFlag(FAULT.exceedance()[faulted], FAULT.onset(),
                        FAULT.minutes()) >= 0,
                "the contender cannot reach the injected room at any limit, so T-29 would be "
                        + "reporting a detector that cannot see the fault rather than one that "
                        + "cannot stay quiet while seeing it");
    }

    /**
     * The verdict's mechanism on one seed: the contender cannot hold the incumbent's false-alarm
     * volume even at the quietest operating point the grid resolves. If this starts failing, the
     * contender has become competitive on R-3 and T-29's "dropped" needs re-running rather than
     * relaxing.
     */
    @Test
    void shouldRaiseMoreFalseAlarmsThanTheIncumbentOverTheHeldOutWeek() {
        int contender = 0;
        for (int zone = 0; zone < Topology.ZONE_IDS.size(); zone++) {
            contender += QUIETEST.alarms(WEEK.exceedance()[zone], WEEK.heldOutFrom(),
                    WEEK.heldOutUntil());
        }

        assertTrue(contender > WEEK.monitorHeldOutCases(),
                "the contender raised %d alarms against the monitor's %d confirmed cases over the "
                        .formatted(contender, WEEK.monitorHeldOutCases())
                        + "same held-out week — it has stopped losing on R-3, which is good news "
                        + "to go and re-measure T-29 for, not a test to relax");
    }

    /** Every room's fit has to have learned something, or its column is silence rather than a score. */
    @Test
    void shouldFitEveryRoomFromItsOwnCommissioningWindow() {
        assertEquals(0, WEEK.degenerateFits(),
                "a room's commissioning window produced no usable feature combination, so that "
                        + "room was scored as no-evidence for the whole run");
    }
}
