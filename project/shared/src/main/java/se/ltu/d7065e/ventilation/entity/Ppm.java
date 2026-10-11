package se.ltu.d7065e.ventilation.entity;

/** A CO2 concentration in parts per million. */
public record Ppm(double value) {
    public Ppm {
        if (value < 0 || !Double.isFinite(value)) {
            throw new IllegalArgumentException("ppm must be finite and non-negative, was: " + value);
        }
    }

    public static Ppm of(double value) {
        return new Ppm(value);
    }

    public boolean exceeds(Ppm limit) {
        return value > limit.value;
    }

    @Override
    public String toString() {
        return "%.1f ppm".formatted(value);
    }
}
