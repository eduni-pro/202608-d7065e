package se.ltu.d7065e.ventilation.entity;

/** A commanded or applied damper opening, 0 shut to 1 fully open. */
public record DamperPosition(double value) {
    public static final DamperPosition SHUT = new DamperPosition(0);
    public static final DamperPosition OPEN = new DamperPosition(1);

    public DamperPosition {
        if (!(value >= 0 && value <= 1)) {
            throw new IllegalArgumentException("damper position must be within [0,1], was: " + value);
        }
    }

    public static DamperPosition of(double value) {
        return new DamperPosition(value);
    }

    public DamperPosition clampedTo(DamperPosition minimum) {
        return value < minimum.value ? minimum : this;
    }

    public boolean differsFrom(DamperPosition other, double tolerance) {
        return Math.abs(value - other.value) > tolerance;
    }

    @Override
    public String toString() {
        return "%.0f%%".formatted(value * 100);
    }
}
