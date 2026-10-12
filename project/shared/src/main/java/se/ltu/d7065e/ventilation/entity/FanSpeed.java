package se.ltu.d7065e.ventilation.entity;

/**
 * Commanded air-handling-unit fan speed as a fraction of nominal. The lower bound is the
 * plant's minimum continuous speed; ventilation never stops entirely (ADR-0008).
 */
public record FanSpeed(double value) {
    public static final double MINIMUM = 0.2;
    public static final double MAXIMUM = 1.0;
    public static final FanSpeed NOMINAL = new FanSpeed(1.0);

    public FanSpeed {
        if (!(value >= MINIMUM && value <= MAXIMUM)) {
            throw new IllegalArgumentException(
                    "fan speed must be within [%s,%s], was: %s".formatted(MINIMUM, MAXIMUM, value));
        }
    }

    public static FanSpeed of(double value) {
        return new FanSpeed(value);
    }

    /** Clamps into range rather than throwing, for control output that may overshoot. */
    public static FanSpeed clamped(double value) {
        return new FanSpeed(Math.min(MAXIMUM, Math.max(MINIMUM, value)));
    }

    @Override
    public String toString() {
        return "%.0f%%".formatted(value * 100);
    }
}
