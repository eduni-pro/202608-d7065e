package se.ltu.d7065e.ventilation.control;

import java.time.Duration;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * The inner loop's tuning: the hysteresis band, the deadband that decides whether a new
 * position is worth publishing, and the sample interval the freshness check measures against.
 *
 * <p>The comfort limit and the minimum position are not tunable here — they come from
 * {@link Topology}, because 1000 ppm is the requirement R-2 is written against and 0.15 is the
 * position a "stuck shut" damper sticks at (ADR-0008). Letting a deployment override either
 * would let configuration move the goalposts the evaluation measures.
 */
public record ControlSettings(
        Ppm openAbove,
        Ppm closeBelow,
        Ppm comfortLimit,
        DamperPosition minimumPosition,
        double publishDeadband,
        Duration sampleInterval) {

    /**
     * Three intervals, the same number R-4 uses. The monitor counts them to reach
     * {@code BLIND}; the controller counts them to stop commanding. Same number, different
     * decision — see {@link DcvControlLoop}.
     */
    public static final int STALE_INTERVALS = 3;

    /**
     * Where the ramp starts opening the damper, 200 ppm below the comfort limit.
     *
     * <p>Not taken from anywhere: derived here and recorded in ADR-0008's parameter table, which is
     * the single source for every number in this system. The derivation is the band itself. The
     * ramp has to reach full flow <em>at</em> the 1000 ppm limit rather than past it, or R-2's
     * claim rests on a controller that was still accelerating when the room went out of comfort;
     * starting it at 800 ppm leaves the ramp 200 ppm of travel, which is the slack between the
     * 420 ppm outdoor level and the limit that a room may spend before the damper is doing
     * everything it can.
     */
    public static final Ppm DEFAULT_OPEN_ABOVE = Ppm.of(800);

    /**
     * Where the damper is allowed back to its floor, 150 ppm below {@link #DEFAULT_OPEN_ABOVE}.
     *
     * <p>The gap is the hysteresis band, and it is sized against sensor noise rather than chosen
     * for roundness: at sigma = 30 ppm ({@link Topology#SENSOR_NOISE_SIGMA_PPM}) a 150 ppm band is
     * five sigma, so noise alone cannot walk a reading across both edges and set the damper
     * chattering. Chatter is not merely inelegant here — the monitor keys its prediction on
     * command steps, so a controller that oscillated on noise would bury the one step response
     * the diagnosis is reading.
     */
    public static final Ppm DEFAULT_CLOSE_BELOW = Ppm.of(650);

    /**
     * Below this, a new position is not worth a command. The monitor keys its prediction on
     * command steps (T-11), so a stream of no-op commands would bury the step it is looking for.
     */
    public static final double DEFAULT_PUBLISH_DEADBAND = 0.02;

    public static final Duration DEFAULT_SAMPLE_INTERVAL = Duration.ofSeconds(1);

    public ControlSettings {
        if (openAbove == null || closeBelow == null || comfortLimit == null
                || minimumPosition == null || sampleInterval == null) {
            throw new IllegalArgumentException("control settings must not be null");
        }
        if (openAbove.value() <= closeBelow.value()) {
            throw new IllegalArgumentException(
                    "the open threshold %s must sit above the close threshold %s, or there is no "
                            .formatted(openAbove, closeBelow) + "hysteresis band at all");
        }
        if (comfortLimit.value() <= openAbove.value()) {
            throw new IllegalArgumentException(
                    "the comfort limit %s must sit above the open threshold %s"
                            .formatted(comfortLimit, openAbove));
        }
        if (!(publishDeadband > 0)) {
            throw new IllegalArgumentException(
                    "the publish deadband must be positive, was: " + publishDeadband);
        }
        if (sampleInterval.isZero() || sampleInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "the sample interval must be positive, was: " + sampleInterval);
        }
    }

    /**
     * The configuration ADR-0008's parameter table records: the 650/800 ppm band against the
     * 1000 ppm limit, sampled every second. See {@link #DEFAULT_OPEN_ABOVE} and
     * {@link #DEFAULT_CLOSE_BELOW} for why those two numbers and not others.
     */
    public static ControlSettings standard() {
        return band(DEFAULT_OPEN_ABOVE.value(), DEFAULT_CLOSE_BELOW.value(),
                DEFAULT_SAMPLE_INTERVAL);
    }

    /** The two tunable thresholds plus the sample interval; everything else is fixed. */
    public static ControlSettings band(
            double openAbovePpm, double closeBelowPpm, Duration sampleInterval) {
        return new ControlSettings(Ppm.of(openAbovePpm), Ppm.of(closeBelowPpm),
                Topology.COMFORT_LIMIT, Topology.MINIMUM_POSITION, DEFAULT_PUBLISH_DEADBAND,
                sampleInterval);
    }

    /** A reading older than this is not trusted, and the loop stops commanding on it. */
    public Duration freshnessLimit() {
        return sampleInterval.multipliedBy(STALE_INTERVALS);
    }
}
