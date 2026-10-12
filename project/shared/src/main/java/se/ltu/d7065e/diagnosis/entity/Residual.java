package se.ltu.d7065e.diagnosis.entity;

/**
 * Measured CO2 minus the concentration the observer predicted for the last command,
 * in ppm. Positive means the room is dirtier than the command should have left it.
 */
public record Residual(double ppm) {
    public Residual {
        if (!Double.isFinite(ppm)) {
            throw new IllegalArgumentException("residual must be finite, was: " + ppm);
        }
    }

    public static Residual of(double ppm) {
        return new Residual(ppm);
    }

    @Override
    public String toString() {
        return "%+.1f ppm".formatted(ppm);
    }
}
