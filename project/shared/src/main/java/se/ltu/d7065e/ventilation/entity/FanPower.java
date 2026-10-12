package se.ltu.d7065e.ventilation.entity;

/** Air-handling-unit fan power draw in watts. */
public record FanPower(double watts) {
    public FanPower {
        if (watts < 0 || !Double.isFinite(watts)) {
            throw new IllegalArgumentException("fan power must be finite and non-negative, was: " + watts);
        }
    }

    public static FanPower ofWatts(double watts) {
        return new FanPower(watts);
    }

    @Override
    public String toString() {
        return "%.1f W".formatted(watts);
    }
}
