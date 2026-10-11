package se.ltu.d7065e.ventilation.entity;

/** A volumetric airflow in cubic metres per second. */
public record AirflowRate(double cubicMetresPerSecond) {
    public AirflowRate {
        if (cubicMetresPerSecond < 0 || !Double.isFinite(cubicMetresPerSecond)) {
            throw new IllegalArgumentException(
                    "airflow must be finite and non-negative, was: " + cubicMetresPerSecond);
        }
    }

    public static AirflowRate ofCubicMetresPerSecond(double value) {
        return new AirflowRate(value);
    }

    public static AirflowRate ofLitresPerSecond(double litresPerSecond) {
        return new AirflowRate(litresPerSecond / 1000.0);
    }

    public double litresPerSecond() {
        return cubicMetresPerSecond * 1000.0;
    }

    @Override
    public String toString() {
        return "%.1f l/s".formatted(litresPerSecond());
    }
}
