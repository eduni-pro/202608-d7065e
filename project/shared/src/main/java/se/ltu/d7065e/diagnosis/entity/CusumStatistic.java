package se.ltu.d7065e.diagnosis.entity;

/**
 * One-sided cumulative sum of the residual, {@code S = max(0, S + r - k)}, lifted from the
 * T-04 spike. Never negative, so a run of healthy samples cannot bank credit against a
 * later fault.
 */
public record CusumStatistic(double value) {
    public static final CusumStatistic RESET = new CusumStatistic(0);

    public CusumStatistic {
        if (value < 0 || !Double.isFinite(value)) {
            throw new IllegalArgumentException("cusum must be finite and non-negative, was: " + value);
        }
    }

    public static CusumStatistic of(double value) {
        return new CusumStatistic(value);
    }

    public boolean exceeds(double limit) {
        return value > limit;
    }

    @Override
    public String toString() {
        return "%.1f".formatted(value);
    }
}
