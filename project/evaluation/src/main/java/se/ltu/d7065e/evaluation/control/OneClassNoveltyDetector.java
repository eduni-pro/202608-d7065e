package se.ltu.d7065e.evaluation.control;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-29's contender: a learned one-class novelty detector, fitted on a fault-free commissioning
 * window and asked afterwards how unlike that window each new sample is.
 *
 * <h2>Why a Mahalanobis distance and not a per-room residual quantile</h2>
 *
 * The two candidates T-29 weighed were a learned quantile on the residual alone and a learned
 * Mahalanobis distance over the small feature vector the monitor already computes. The Mahalanobis
 * form was chosen because a residual quantile is not a different <em>detector</em> from the
 * incumbent, only a different number: the CUSUM already watches that one series, so beating it
 * with a quantile on the same series would measure the choice of statistic and nothing else. The
 * contest T-29 is asking about is whether a learned <em>joint</em> model of the normal region beats
 * a hand-written decision tree over the same signals, and that needs the cross-feature term — the
 * covariance — which is exactly what the hand-built side has to encode as rules instead.
 *
 * <p>What is learned is everything that locates and scales the normal region: each feature's mean
 * and standard deviation, the correlations between them, and the distance at which a sample counts
 * as unlike the training set. Nothing in this class carries a ppm figure, a fan-power tolerance or
 * a floor tolerance; the incumbent's twenty-four {@code MonitorParameters} are all hand-set, and
 * the contrast is the point of the experiment.
 *
 * <h2>Three features, not four</h2>
 *
 * The fourth feature T-29 wanted, the step-amplitude ratio, is not reachable: it is
 * {@code ZoneMonitor.stepResponse()}, which is package-private to
 * {@code se.ltu.d7065e.diagnosis.control}, and widening it would be a change to
 * {@code fault-monitor} that T-29 is not permitted to make. The three that are reachable are
 * {@code FaultMonitor.residualOf}, the fan-power ratio the harness can reconstruct from the
 * snapshot it published and {@code AhuFlowModel.believedFanPowerWatts}, and the trailing-day floor,
 * computed by the monitor's own {@link se.ltu.d7065e.diagnosis.control.OvernightFloor} rather than
 * re-implemented here.
 *
 * <h2>Absent is not zero</h2>
 *
 * {@code NaN} marks a feature nobody has measured yet — a residual before the monitor's warm-up, a
 * fan-power ratio with no believed power to divide by, an overnight floor with less than a day of
 * history behind it. Substituting zero would be a lie in the feature's own units and would put the
 * sample at a distance the data never supported, so an absent feature is instead marginalised out:
 * the distance is taken over the sub-vector that is present, against the sub-matrix for those same
 * features, and the limit it is judged against is learned from every training sample that carried
 * <em>at least</em> those features, by ignoring whatever else those samples carried. That last
 * part is what keeps one operating point meaningful across a run whose feature set changes: the
 * score this class reports is not a distance but the fraction of comparable training samples that
 * lay closer to the centre, so "the 99.9th percentile of normal" means the same thing whether two
 * features are present or three. It also means a detector commissioned on complete data can still
 * judge an incomplete sample, which the evaluation needs: T-25's fault arm is shorter than the
 * overnight floor's own window, so the fault arm is judged on two features and the held-out week
 * on three.
 */
public final class OneClassNoveltyDetector {

    /** The feature order of {@link Sample}, for the CSV header and the fit report. */
    public static final List<String> FEATURE_NAMES =
            List.of("residual_ppm", "fan_power_ratio", "overnight_floor_excess_ppm");

    /**
     * How many training samples a feature, or a combination of features, needs before anything is
     * estimated from it. A mean over a handful of samples is noise, and a correlation over a
     * handful is worse; below this the feature or the combination is treated as unmeasured, which
     * costs detection rather than inventing a normal region.
     */
    private static final int MINIMUM_TRAINING_SAMPLES = 200;

    /**
     * Correlations are clamped off ±1 so that a pair of features that happened to move together
     * over the training window cannot make the sub-matrix singular. At two features this alone
     * guarantees an inverse; at three it does not, which is what {@link #RIDGE} is for.
     */
    private static final double MAXIMUM_CORRELATION = 0.999;

    /** Added to the diagonal, repeatedly, when a sub-matrix will not invert. */
    private static final double RIDGE = 1e-6;

    private static final int RIDGE_ATTEMPTS = 6;

    private static final double PIVOT_FLOOR = 1e-12;

    private final double[] mean;
    private final double[] deviation;
    private final int[] observed;
    private final Map<Integer, Fitted> byMask;

    private OneClassNoveltyDetector(double[] mean, double[] deviation, int[] observed,
            Map<Integer, Fitted> byMask) {
        this.mean = mean;
        this.deviation = deviation;
        this.observed = observed;
        this.byMask = byMask;
    }

    /**
     * Fits the normal region to a window the caller guarantees is fault-free. Nothing here is
     * labelled: the window is simply the building running, and the detector never sees a fault.
     */
    public static OneClassNoveltyDetector fitTo(List<Sample> commissioning) {
        int features = FEATURE_NAMES.size();
        double[] mean = new double[features];
        double[] deviation = new double[features];
        int[] observed = new int[features];
        double[] total = new double[features];
        double[] totalSquared = new double[features];

        for (Sample sample : commissioning) {
            double[] values = sample.values();
            for (int feature = 0; feature < features; feature++) {
                if (!Double.isNaN(values[feature])) {
                    total[feature] += values[feature];
                    totalSquared[feature] += values[feature] * values[feature];
                    observed[feature]++;
                }
            }
        }
        for (int feature = 0; feature < features; feature++) {
            if (observed[feature] < MINIMUM_TRAINING_SAMPLES) {
                mean[feature] = Double.NaN;
                deviation[feature] = Double.NaN;
                continue;
            }
            mean[feature] = total[feature] / observed[feature];
            double variance = Math.max(0,
                    totalSquared[feature] / observed[feature] - mean[feature] * mean[feature]);
            deviation[feature] = Math.sqrt(variance);
            if (!(deviation[feature] > 0)) {
                // A feature that never moved over a whole commissioning window has no scale to
                // measure a deviation in, so it is unmeasured rather than infinitely sensitive.
                deviation[feature] = Double.NaN;
            }
        }

        double[][] correlation = correlation(commissioning, mean, deviation);
        Map<Integer, Fitted> byMask = new LinkedHashMap<>();
        for (int mask = 1; mask < 1 << features; mask++) {
            // Every training sample that carried at least these features can price this
            // combination, by ignoring the ones it carried on top. Restricting the limit to the
            // samples whose feature set matched exactly would leave a detector trained on complete
            // data unable to judge an incomplete sample at all, which is the one case the
            // evaluation needs most: T-25's fault arm is too short for the overnight floor.
            List<Sample> comparable = new ArrayList<>();
            for (Sample sample : commissioning) {
                if ((maskOf(sample, deviation) & mask) == mask) {
                    comparable.add(sample);
                }
            }
            if (comparable.size() < MINIMUM_TRAINING_SAMPLES) {
                continue;
            }
            int[] present = presentOf(mask, features);
            double[][] inverse = inverseOf(correlation, present);
            if (inverse == null) {
                continue;
            }
            double[] distances = new double[comparable.size()];
            for (int index = 0; index < distances.length; index++) {
                distances[index] = squaredDistance(
                        comparable.get(index), present, mean, deviation, inverse);
            }
            Arrays.sort(distances);
            byMask.put(mask, new Fitted(present, inverse, distances));
        }
        return new OneClassNoveltyDetector(mean, deviation, observed, byMask);
    }

    /**
     * How novel this sample is, as the fraction of comparable training samples that lay strictly
     * closer to the centre of the normal region: 0.0 is the most ordinary sample the commissioning
     * window held, 1.0 is further out than every one of them. {@code NaN} when too few training
     * samples carried this combination of features for a limit to be learned, which is no evidence
     * either way and must not be read as health.
     */
    public double exceedance(Sample sample) {
        Fitted fitted = byMask.get(maskOf(sample, deviation));
        if (fitted == null) {
            return Double.NaN;
        }
        double squared = squaredDistance(
                sample, fitted.present(), mean, deviation, fitted.inverse());
        return (double) strictlyBelow(fitted.distances(), squared) / fitted.distances().length;
    }

    /** The squared Mahalanobis distance itself, for the CSV; {@code NaN} when nothing is judged. */
    public double squaredDistance(Sample sample) {
        Fitted fitted = byMask.get(maskOf(sample, deviation));
        return fitted == null
                ? Double.NaN
                : squaredDistance(sample, fitted.present(), mean, deviation, fitted.inverse());
    }

    /** How many of the three features this sample contributes to its own distance. */
    public int judgedFeatures(Sample sample) {
        Fitted fitted = byMask.get(maskOf(sample, deviation));
        return fitted == null ? 0 : fitted.present().length;
    }

    /** Whether anything at all was learned, so a degenerate fit is reported rather than scored. */
    public boolean fitted() {
        return !byMask.isEmpty();
    }

    /** The centre the commissioning window put a feature at, or {@code NaN} when it is unmeasured. */
    public double learnedMean(int feature) {
        return mean[feature];
    }

    /** The scale the commissioning window put a feature at, or {@code NaN} when it is unmeasured. */
    public double learnedDeviation(int feature) {
        return deviation[feature];
    }

    /** What was learned, line by line, so the report quotes the fit rather than describing it. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (int feature = 0; feature < FEATURE_NAMES.size(); feature++) {
            lines.add("%-26s mean %10.4f  sd %9.4f  from %d training samples".formatted(
                    FEATURE_NAMES.get(feature), mean[feature], deviation[feature],
                    observed[feature]));
        }
        byMask.forEach((mask, fitted) -> lines.add(
                "%-26s %d samples, d2 at the 0.999 quantile = %.1f, largest %.1f".formatted(
                        nameOf(mask), fitted.distances().length,
                        quantileOf(fitted.distances(), 0.999),
                        fitted.distances()[fitted.distances().length - 1])));
        return lines;
    }

    /** The names of the features in a mask, as {@link #describe()} prints them. */
    public static String nameOf(int mask) {
        List<String> names = new ArrayList<>();
        for (int feature = 0; feature < FEATURE_NAMES.size(); feature++) {
            if ((mask & 1 << feature) != 0) {
                names.add(FEATURE_NAMES.get(feature));
            }
        }
        return names.toString();
    }

    // ----- the fit ----------------------------------------------------------------------------

    /**
     * The correlation matrix, estimated pairwise-complete: each entry uses the training samples in
     * which <em>both</em> of its features were measured. One matrix for all feature combinations,
     * rather than one fit per combination, because the combinations here differ only in how much
     * history has accumulated and the samples they share describe the same building.
     */
    private static double[][] correlation(List<Sample> commissioning, double[] mean,
            double[] deviation) {
        int features = FEATURE_NAMES.size();
        double[][] correlation = new double[features][features];
        for (int row = 0; row < features; row++) {
            correlation[row][row] = 1;
            for (int column = row + 1; column < features; column++) {
                double product = 0;
                int pairs = 0;
                for (Sample sample : commissioning) {
                    double[] values = sample.values();
                    if (usable(values, row, deviation) && usable(values, column, deviation)) {
                        product += standardised(values, row, mean, deviation)
                                * standardised(values, column, mean, deviation);
                        pairs++;
                    }
                }
                double estimate = pairs < MINIMUM_TRAINING_SAMPLES ? 0 : product / pairs;
                double clamped = Math.max(-MAXIMUM_CORRELATION,
                        Math.min(MAXIMUM_CORRELATION, estimate));
                correlation[row][column] = clamped;
                correlation[column][row] = clamped;
            }
        }
        return correlation;
    }

    private static boolean usable(double[] values, int feature, double[] deviation) {
        return !Double.isNaN(values[feature]) && !Double.isNaN(deviation[feature]);
    }

    private static double standardised(double[] values, int feature, double[] mean,
            double[] deviation) {
        return (values[feature] - mean[feature]) / deviation[feature];
    }

    private static int maskOf(Sample sample, double[] deviation) {
        double[] values = sample.values();
        int mask = 0;
        for (int feature = 0; feature < values.length; feature++) {
            if (usable(values, feature, deviation)) {
                mask |= 1 << feature;
            }
        }
        return mask;
    }

    private static int[] presentOf(int mask, int features) {
        int[] present = new int[Integer.bitCount(mask)];
        int next = 0;
        for (int feature = 0; feature < features; feature++) {
            if ((mask & 1 << feature) != 0) {
                present[next++] = feature;
            }
        }
        return present;
    }

    private static double squaredDistance(Sample sample, int[] present, double[] mean,
            double[] deviation, double[][] inverse) {
        double[] values = sample.values();
        double[] standardised = new double[present.length];
        for (int index = 0; index < present.length; index++) {
            standardised[index] = standardised(values, present[index], mean, deviation);
        }
        double squared = 0;
        for (int row = 0; row < present.length; row++) {
            for (int column = 0; column < present.length; column++) {
                squared += standardised[row] * inverse[row][column] * standardised[column];
            }
        }
        // The quadratic form of a positive-definite inverse cannot be negative; rounding at the
        // last bits of a near-singular matrix can still produce a tiny negative, and a negative
        // distance would sort below every training sample and read as the most ordinary reading
        // the building ever produced.
        return Math.max(0, squared);
    }

    /**
     * The inverse of the sub-matrix for the present features, by Gauss-Jordan elimination with
     * partial pivoting — written out rather than taken from a matrix library, because the matrix
     * is at most three by three and a dependency for that is not justifiable. A matrix that will
     * not invert is nudged by {@link #RIDGE} on the diagonal and retried, which is the smallest
     * honest repair: it shrinks the correlations slightly towards independence. {@code null} when
     * even that fails, and the combination is then reported as unlearnable rather than scored.
     */
    private static double[][] inverseOf(double[][] correlation, int[] present) {
        double ridge = 0;
        for (int attempt = 0; attempt <= RIDGE_ATTEMPTS; attempt++) {
            double[][] block = new double[present.length][present.length];
            for (int row = 0; row < present.length; row++) {
                for (int column = 0; column < present.length; column++) {
                    block[row][column] = correlation[present[row]][present[column]];
                }
                block[row][row] += ridge;
            }
            double[][] inverse = inverse(block);
            if (inverse != null) {
                return inverse;
            }
            ridge = ridge == 0 ? RIDGE : ridge * 10;
        }
        return null;
    }

    private static double[][] inverse(double[][] matrix) {
        int size = matrix.length;
        double[][] work = new double[size][2 * size];
        for (int row = 0; row < size; row++) {
            System.arraycopy(matrix[row], 0, work[row], 0, size);
            work[row][size + row] = 1;
        }
        for (int column = 0; column < size; column++) {
            int pivot = column;
            for (int row = column + 1; row < size; row++) {
                if (Math.abs(work[row][column]) > Math.abs(work[pivot][column])) {
                    pivot = row;
                }
            }
            if (Math.abs(work[pivot][column]) < PIVOT_FLOOR) {
                return null;
            }
            double[] swapped = work[column];
            work[column] = work[pivot];
            work[pivot] = swapped;
            double scale = work[column][column];
            for (int index = 0; index < 2 * size; index++) {
                work[column][index] /= scale;
            }
            for (int row = 0; row < size; row++) {
                double factor = work[row][column];
                if (row != column && factor != 0) {
                    for (int index = 0; index < 2 * size; index++) {
                        work[row][index] -= factor * work[column][index];
                    }
                }
            }
        }
        double[][] inverse = new double[size][size];
        for (int row = 0; row < size; row++) {
            System.arraycopy(work[row], size, inverse[row], 0, size);
        }
        return inverse;
    }

    /** How many sorted training distances lie strictly below this one. */
    private static int strictlyBelow(double[] sorted, double distance) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] < distance) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static double quantileOf(double[] sorted, double fraction) {
        int rank = (int) Math.ceil(fraction * sorted.length);
        return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1];
    }

    /** One fitted feature combination: which features, their inverse, and the learned scale. */
    private record Fitted(int[] present, double[][] inverse, double[] distances) {
    }

    /**
     * One room-minute as the detector sees it. {@code NaN} in any component means the feature was
     * not measured at that minute, which is treated as absent evidence and never as zero.
     *
     * @param residualPpm                what the monitor's own model got wrong this cycle, signed;
     *                                   {@code NaN} before the monitor leaves WARMUP
     * @param fanPowerRatio              observed AHU power over the power the affinity law
     *                                   predicts at the believed speed, as the classifier's
     *                                   common-mode signal; {@code NaN} when there is no believed
     *                                   power to divide by
     * @param overnightFloorExcessPpm    the trailing-day minimum reading above the outdoor
     *                                   concentration; {@code NaN} until a full day of history has
     *                                   accumulated, which is the monitor's own rule
     */
    public record Sample(double residualPpm, double fanPowerRatio,
            double overnightFloorExcessPpm) {

        double[] values() {
            return new double[] {residualPpm, fanPowerRatio, overnightFloorExcessPpm};
        }
    }

    /**
     * One operating point on the learned scale, and the structural twin of
     * {@link ThresholdDetector}: a limit and a persistence requirement, with episode counting that
     * ends an episode when the score falls back. The limit is a quantile of the training
     * distribution rather than a ppm figure, which is the whole difference — but the persistence
     * requirement is hand-set exactly as the incumbent's
     * {@code confirmPersistenceCycles} is, and T-29 sweeps it rather than claiming otherwise.
     *
     * @param quantile         how far into the learned tail a sample must fall; 1.0 means further
     *                         out than every sample the commissioning window held
     * @param sustainedMinutes how many consecutive minutes it must stay there
     */
    public record AlarmRule(double quantile, int sustainedMinutes) {

        public AlarmRule {
            if (!(quantile > 0 && quantile <= 1)) {
                throw new IllegalArgumentException(
                        "the quantile must lie within (0,1], was: " + quantile);
            }
            if (sustainedMinutes < 1) {
                throw new IllegalArgumentException(
                        "the duration must be at least a minute, was: " + sustainedMinutes);
            }
        }

        /** The first minute at or after {@code from} at which the rule flags, or -1. */
        public int firstFlag(double[] exceedances, int from, int until) {
            int above = 0;
            for (int minute = Math.max(0, from); minute < until; minute++) {
                above = beyond(exceedances[minute]) ? above + 1 : 0;
                if (above >= sustainedMinutes) {
                    return minute;
                }
            }
            return -1;
        }

        /**
         * How many separate times the rule flagged, counted the way
         * {@link ThresholdDetector#alarms} counts: an episode ends when the score falls back
         * inside the learned region, so a room that stays novel all afternoon is one alarm.
         */
        public int alarms(double[] exceedances, int from, int until) {
            int above = 0;
            int alarms = 0;
            boolean flagged = false;
            for (int minute = Math.max(0, from); minute < until; minute++) {
                if (beyond(exceedances[minute])) {
                    above++;
                    if (above >= sustainedMinutes && !flagged) {
                        alarms++;
                        flagged = true;
                    }
                } else {
                    above = 0;
                    flagged = false;
                }
            }
            return alarms;
        }

        /** A minute with no learned limit behind it breaks the run rather than extending it. */
        private boolean beyond(double exceedance) {
            return !Double.isNaN(exceedance) && exceedance >= quantile;
        }

        /** The limit on its own, for a table column that carries the duration separately. */
        public String limit() {
            return "q=%.4f".formatted(quantile);
        }

        @Override
        public String toString() {
            return "%s for %d min".formatted(limit(), sustainedMinutes);
        }
    }
}
