package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.CusumStatistic;
import se.ltu.d7065e.diagnosis.entity.Residual;

/** T-12 — the properties the detector's statistic has to have whatever is fed to it. */
class CusumTest {

    private static final double K = 30;
    private static final double H = 150;
    private static final double SIGMA = 30;

    private final Cusum cusum = new Cusum(K, H);

    @Test
    void shouldNeverGoNegative() {
        Random random = new Random(42);
        CusumStatistic statistic = CusumStatistic.RESET;
        for (int sample = 0; sample < 100_000; sample++) {
            statistic = cusum.next(statistic, Residual.of(random.nextGaussian() * 500 - 200));
            assertTrue(statistic.value() >= 0, "cusum went negative at sample " + sample);
        }
    }

    @Test
    void shouldBeMonotoneNonDecreasingInTheResidual() {
        Random random = new Random(7);
        for (int trial = 0; trial < 20_000; trial++) {
            CusumStatistic previous = CusumStatistic.of(random.nextDouble() * 400);
            double lower = random.nextGaussian() * 200;
            double higher = lower + random.nextDouble() * 200;
            double atLower = cusum.next(previous, Residual.of(lower)).value();
            double atHigher = cusum.next(previous, Residual.of(higher)).value();
            assertTrue(atHigher >= atLower,
                    "a larger residual produced a smaller statistic: %f -> %f, %f -> %f"
                            .formatted(lower, atLower, higher, atHigher));
        }
    }

    @Test
    void shouldBankNoCreditFromHealthySamples() {
        CusumStatistic afterAGoodRun = CusumStatistic.RESET;
        for (int sample = 0; sample < 1_000; sample++) {
            afterAGoodRun = cusum.next(afterAGoodRun, Residual.of(-100));
        }
        assertEquals(0, afterAGoodRun.value(), 0,
                "a run of healthy samples must not bank credit against a later fault");
        assertEquals(cusum.next(CusumStatistic.RESET, Residual.of(200)).value(),
                cusum.next(afterAGoodRun, Residual.of(200)).value(), 1e-9);
    }

    @Test
    void shouldEventuallyTripOnAConstantBias() {
        CusumStatistic statistic = CusumStatistic.RESET;
        int samples = 0;
        while (!cusum.alarms(statistic) && samples < 1_000) {
            statistic = cusum.next(statistic, Residual.of(K + 10));
            samples++;
        }
        assertTrue(cusum.alarms(statistic), "a constant bias above k must eventually trip h");
        assertEquals(16, samples, "a bias of k+10 ppm needs just over h/10 samples to pass h");
    }

    @Test
    void shouldNotTripOnZeroMeanNoiseWithinASimulatedWeek() {
        int weekOfMinutes = 7 * 24 * 60;
        Random random = new Random(42);
        CusumStatistic statistic = CusumStatistic.RESET;
        for (int sample = 0; sample < weekOfMinutes; sample++) {
            statistic = cusum.next(statistic, Residual.of(random.nextGaussian() * SIGMA));
            assertTrue(!cusum.alarms(statistic),
                    "zero-mean noise tripped h at sample " + sample + " of a simulated week");
        }
    }

    @Test
    void shouldKeepTheLowerArmNonNegative() {
        Random random = new Random(42);
        CusumStatistic statistic = CusumStatistic.RESET;
        for (int sample = 0; sample < 100_000; sample++) {
            statistic = cusum.nextLower(statistic, Residual.of(random.nextGaussian() * 500 + 200));
            assertTrue(statistic.value() >= 0, "the lower arm went negative at sample " + sample);
        }
    }

    /**
     * The two arms have to be the same statistic read in opposite directions, or {@code h} means
     * two different things and the claim that no tuning parameter was added is false.
     */
    @Test
    void shouldMirrorTheUpperArmExactly() {
        Random random = new Random(11);
        for (int trial = 0; trial < 20_000; trial++) {
            CusumStatistic previous = CusumStatistic.of(random.nextDouble() * 400);
            double residual = random.nextGaussian() * 200;
            assertEquals(cusum.next(previous, Residual.of(residual)).value(),
                    cusum.nextLower(previous, Residual.of(-residual)).value(), 1e-9,
                    "the arms disagree on a residual of " + residual + " ppm");
        }
    }

    @Test
    void shouldNotTripTheLowerArmOnZeroMeanNoiseWithinASimulatedWeek() {
        int weekOfMinutes = 7 * 24 * 60;
        Random random = new Random(42);
        CusumStatistic statistic = CusumStatistic.RESET;
        for (int sample = 0; sample < weekOfMinutes; sample++) {
            statistic = cusum.nextLower(statistic, Residual.of(random.nextGaussian() * SIGMA));
            assertTrue(!cusum.alarms(statistic),
                    "zero-mean noise tripped the lower arm at sample " + sample + " of a week");
        }
    }

    /**
     * {@code ResidualComputed} has one {@code cusum} field and a frozen schema, so the published
     * series is the arm that is further along. A consumer therefore sees whichever side is
     * accumulating and cannot tell from that number alone which side it was — the limitation the
     * report owns, pinned here so it is a decision rather than an accident.
     */
    @Test
    void shouldPublishWhicheverArmIsFurtherAlong() {
        CusumStatistic high = CusumStatistic.of(120);
        CusumStatistic low = CusumStatistic.of(45);
        assertEquals(high, cusum.published(high, low));
        assertEquals(high, cusum.published(low, high));
        assertEquals(CusumStatistic.RESET,
                cusum.published(CusumStatistic.RESET, CusumStatistic.RESET));
    }

    @Test
    void shouldReproduceTheSpikesRunLengthEstimate() {
        double samples = cusum.averageRunLengthInControl(SIGMA);
        assertTrue(samples > 1.0e5 && samples < 1.2e5,
                "ARL0 moved away from the spike's 1.1e5 samples: " + samples);
    }
}
