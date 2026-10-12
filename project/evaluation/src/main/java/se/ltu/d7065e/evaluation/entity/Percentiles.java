package se.ltu.d7065e.evaluation.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * The order statistics every experiment here reports, computed one way so that two tables cannot
 * disagree about what p95 means.
 *
 * <p>The nearest-rank definition, {@code ceil(p * n)}-th smallest, is the one
 * {@code fault-monitor}'s own R-1 test uses, and R-1's acceptance criterion is a p95 — so the two
 * have to agree or the harness would be measuring a different requirement.
 */
public record Percentiles(int count, double min, double median, double p95, double max) {

    public static Percentiles of(List<? extends Number> values) {
        if (values.isEmpty()) {
            return new Percentiles(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        List<Double> sorted = new ArrayList<>(values.size());
        values.forEach(value -> sorted.add(value.doubleValue()));
        sorted.sort(null);
        return new Percentiles(sorted.size(), sorted.get(0), at(sorted, 0.50), at(sorted, 0.95),
                sorted.get(sorted.size() - 1));
    }

    private static double at(List<Double> sorted, double fraction) {
        int rank = (int) Math.ceil(fraction * sorted.size());
        return sorted.get(Math.min(sorted.size(), Math.max(1, rank)) - 1);
    }

    public boolean isEmpty() {
        return count == 0;
    }
}
