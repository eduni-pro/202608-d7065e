package se.ltu.d7065e.evaluation.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.evaluation.control.OneClassNoveltyDetector.AlarmRule;
import se.ltu.d7065e.evaluation.control.OneClassNoveltyDetector.Sample;

/**
 * The arithmetic T-29's contender rests on, pinned on synthetic data rather than on a simulated
 * week, so that a change to the plant cannot make these pass or fail for the wrong reason.
 *
 * <p>The properties worth pinning are the ones a one-class detector is easy to get wrong in: that
 * an unmeasured feature is absent rather than zero, that the limit is learned from the data rather
 * than carried in the code, and that a limit learned in two dimensions is not applied to a sample
 * that presents three.
 */
class OneClassNoveltyDetectorTest {

    private static final long SEED = 7;

    @Test
    void shouldScoreASampleFromTheTrainingDistributionAsOrdinary() {
        OneClassNoveltyDetector detector = OneClassNoveltyDetector.fitTo(normalWindow(2000));

        assertTrue(detector.exceedance(new Sample(0, 1.0, 10)) < 0.90,
                "a sample at the centre of the fitted region scored in the learned tail");
    }

    @Test
    void shouldScoreASampleFarFromTheTrainingDistributionAsNovel() {
        OneClassNoveltyDetector detector = OneClassNoveltyDetector.fitTo(normalWindow(2000));

        assertEquals(1.0, detector.exceedance(new Sample(400, 1.0, 10)),
                "a residual an order of magnitude outside the training spread did not come out "
                        + "beyond every training sample");
    }

    /**
     * The property the experiment depends on and the one a naive implementation breaks: a feature
     * nobody measured must not be filled in with zero. Zero is a perfectly ordinary residual, so
     * substituting it would hide novelty in the features that <em>were</em> measured.
     */
    @Test
    void shouldTreatANanFeatureAsAbsentRatherThanAsZero() {
        OneClassNoveltyDetector detector = OneClassNoveltyDetector.fitTo(normalWindow(2000));
        Sample novelWithTheFloorAbsent = new Sample(400, 1.0, Double.NaN);

        assertEquals(2, detector.judgedFeatures(novelWithTheFloorAbsent),
                "the absent floor was still counted among the features judged");
        assertEquals(1.0, detector.exceedance(novelWithTheFloorAbsent),
                "a novel residual stopped being novel once another feature went unmeasured, which "
                        + "is what substituting zero for NaN would do");
    }

    /**
     * Each feature combination carries its own learned limit, because a distance over three
     * features is on a different scale from one over two. Judging the two against one limit is the
     * mistake that would make the fault arm, where the overnight floor is never known, look either
     * blind or hysterical.
     */
    @Test
    void shouldLearnASeparateLimitForEachCombinationOfPresentFeatures() {
        List<Sample> window = new ArrayList<>(normalWindow(2000));
        window.addAll(normalWindow(2000).stream()
                .map(sample -> new Sample(sample.residualPpm(), sample.fanPowerRatio(), Double.NaN))
                .toList());
        OneClassNoveltyDetector detector = OneClassNoveltyDetector.fitTo(window);

        List<String> described = detector.describe();

        assertTrue(described.stream().anyMatch(line -> line.startsWith("[residual_ppm, "
                        + "fan_power_ratio]")),
                "no limit was learned for the two-feature combination: " + described);
        assertTrue(described.stream().anyMatch(line -> line.startsWith("[residual_ppm, "
                        + "fan_power_ratio, overnight_floor_excess_ppm]")),
                "no limit was learned for the three-feature combination: " + described);
    }

    /**
     * A combination the commissioning window never populated <em>jointly</em> is no evidence
     * either way, even when each of its features was measured often enough on its own. Returning a
     * low score instead would let the detector certify a sample it has no joint model for as
     * healthy. The window here measures the residual and the power ratio together, and the floor
     * only in minutes when neither of the other two was available, so all three features are
     * individually well measured and no training sample carries all three at once.
     */
    @Test
    void shouldReportNoEvidenceForACombinationNeverSeenTogether() {
        List<Sample> disjoint = new ArrayList<>(normalWindow(2000).stream()
                .map(sample -> new Sample(sample.residualPpm(), sample.fanPowerRatio(),
                        Double.NaN))
                .toList());
        normalWindow(2000).forEach(sample -> disjoint.add(new Sample(Double.NaN, Double.NaN,
                sample.overnightFloorExcessPpm())));
        OneClassNoveltyDetector detector = OneClassNoveltyDetector.fitTo(disjoint);

        assertEquals(1.0, detector.exceedance(new Sample(400, 1.0, Double.NaN)),
                "the pair the window did measure together stopped being judged");
        assertTrue(Double.isNaN(detector.exceedance(new Sample(400, 1.0, 10))),
                "a sample presenting three features the window never carried together was scored "
                        + "against a joint limit that cannot exist");
    }

    /** A detector with nothing to learn from says so, rather than scoring on an invented region. */
    @Test
    void shouldNotClaimToBeFittedWhenTheWindowIsTooShort() {
        assertFalse(OneClassNoveltyDetector.fitTo(normalWindow(20)).fitted(),
                "a twenty-sample window produced a fit the detector was willing to score with");
    }

    /**
     * A feature that never moved has no scale in which to measure a deviation, so it is treated as
     * unmeasured. The alternative — an arbitrarily small standard deviation — would make the
     * detector infinitely sensitive to that one feature and alarm on the first rounding error.
     */
    @Test
    void shouldIgnoreAFeatureThatNeverMovedOverTheTrainingWindow() {
        List<Sample> constantFanPower = normalWindow(2000).stream()
                .map(sample -> new Sample(sample.residualPpm(), 1.0,
                        sample.overnightFloorExcessPpm()))
                .toList();
        OneClassNoveltyDetector detector = OneClassNoveltyDetector.fitTo(constantFanPower);

        assertEquals(2, detector.judgedFeatures(new Sample(0, 1.0, 10)),
                "a feature with no training spread was still judged");
        assertTrue(Double.isNaN(detector.learnedDeviation(1)),
                "a feature with no training spread was given a scale");
    }

    @Test
    void shouldEndAnEpisodeWhenTheScoreFallsBackInsideTheLearnedRegion() {
        double[] scores = {1.0, 1.0, 0.1, 1.0, 1.0, 1.0, 1.0};
        AlarmRule rule = new AlarmRule(1.0, 2);

        assertEquals(2, rule.alarms(scores, 0, scores.length),
                "a score that fell back and rose again was counted as one long alarm");
        assertEquals(1, rule.firstFlag(scores, 0, scores.length),
                "the first flag did not land on the second consecutive minute beyond the limit");
    }

    /** A minute with no learned limit behind it cannot sustain an episode. */
    @Test
    void shouldBreakAnEpisodeOnAMinuteWithNoEvidence() {
        double[] scores = {1.0, Double.NaN, 1.0, 1.0};
        AlarmRule rule = new AlarmRule(1.0, 3);

        assertEquals(0, rule.alarms(scores, 0, scores.length),
                "an unscored minute was allowed to extend a run of novel minutes");
    }

    @Test
    void shouldRejectAnOperatingPointOutsideTheUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> new AlarmRule(1.5, 2));
        assertThrows(IllegalArgumentException.class, () -> new AlarmRule(0.99, 0));
    }

    /** A fault-free window: a noisy residual, a noisy power ratio and a steady floor. */
    private static List<Sample> normalWindow(int minutes) {
        Random random = new Random(SEED);
        List<Sample> window = new ArrayList<>(minutes);
        for (int minute = 0; minute < minutes; minute++) {
            window.add(new Sample(random.nextGaussian() * 30,
                    1.0 + random.nextGaussian() * 0.01,
                    10 + random.nextGaussian() * 3));
        }
        return window;
    }
}
