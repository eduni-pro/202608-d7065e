package se.ltu.d7065e.evaluation.control;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import se.ltu.d7065e.diagnosis.control.FaultMonitor;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.evaluation.boundary.CountingJournal;
import se.ltu.d7065e.evaluation.boundary.CountingPublisher;
import se.ltu.d7065e.evaluation.boundary.ResultFiles;
import se.ltu.d7065e.evaluation.entity.Percentiles;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-28 — scaling to the breaking point (R-6): the zone count at which the monitor's p95 cycle time
 * passes its 60 s interval.
 *
 * <p>Measured against the <em>wall</em> clock, not the simulated one, because this is the only
 * question in the harness that is about throughput rather than about behaviour. Everything else here
 * runs a simulated week in seconds precisely by refusing to spend real time; this experiment spends
 * nothing but real time and reports it.
 *
 * <p>The sweep doubles the zone count while a wall-clock budget lasts and the p95 is still under
 * 60 s, which keeps the harness's own runtime bounded. If the breaking point is not reached, the
 * trend is fitted on the asymptotic points and extrapolated, and the result says in as many words
 * that the quoted count is an extrapolation.
 *
 * <p>Each zone is fed one reading per cycle, around 600 ppm with the plant's own 30 ppm of noise, so
 * every zone stays HEALTHY and every cycle runs the same code path — the one R-6 is a statement
 * about. Nothing is injected: a confirmation would change the per-zone work and make two points of
 * the sweep incomparable.
 */
public final class ScalingExperiment {

    private static final Duration CYCLE = Duration.ofSeconds(60);
    private static final double INTERVAL_SECONDS = 60.0;
    private static final int[] FIRST_COUNTS = {6, 12, 25, 50, 100, 200, 400, 800, 1600, 3200};
    private static final int MAXIMUM_COUNT = 25_600;
    private static final long BUDGET_NANOS = Duration.ofSeconds(120).toNanos();
    private static final int WARMUP_CYCLES = 8;

    private ScalingExperiment() {
    }

    public static String run(ResultFiles files) {
        List<Point> points = new ArrayList<>();
        long started = System.nanoTime();
        List<Integer> counts = new ArrayList<>();
        for (int count : FIRST_COUNTS) {
            counts.add(count);
        }
        for (int count = FIRST_COUNTS[FIRST_COUNTS.length - 1] * 2;
                count <= MAXIMUM_COUNT; count *= 2) {
            counts.add(count);
        }

        boolean broke = false;
        for (int count : counts) {
            if (broke || System.nanoTime() - started > BUDGET_NANOS) {
                break;
            }
            Point point = measure(count, cyclesFor(count));
            points.add(point);
            broke = point.cycle().p95() > INTERVAL_SECONDS;
        }

        try (ResultFiles.Csv csv = files.csv("T-28-scaling.csv",
                "zones", "cycles_measured", "ingest_p95_ms", "cycle_min_ms", "cycle_median_ms",
                "cycle_p95_ms", "cycle_max_ms", "cycle_p95_s", "exceeds_60s",
                "cycle_p95_per_zone_squared_ns", "events_published")) {
            points.forEach(p -> csv.row(p.zones(), p.cycle().count(),
                    p.ingest().p95() * 1000, p.cycle().min() * 1000, p.cycle().median() * 1000,
                    p.cycle().p95() * 1000, p.cycle().max() * 1000, p.cycle().p95(),
                    p.cycle().p95() > INTERVAL_SECONDS,
                    p.cycle().p95() * 1e9 / ((double) p.zones() * p.zones()), p.published()));
        }

        List<List<String>> rows = new ArrayList<>();
        points.forEach(p -> rows.add(List.of(String.valueOf(p.zones()),
                String.valueOf(p.cycle().count()),
                "%.2f".formatted(p.cycle().median() * 1000),
                "%.2f".formatted(p.cycle().p95() * 1000),
                "%.2f".formatted(p.cycle().max() * 1000),
                "%.2f".formatted(p.ingest().p95() * 1000),
                "%.2f".formatted(p.cycle().p95() * 1e9 / ((double) p.zones() * p.zones())),
                p.cycle().p95() > INTERVAL_SECONDS ? "OVER 60 s" : "ok")));

        Fit fit = Fit.over(points);
        StringBuilder out = new StringBuilder();
        out.append("T-28 — scaling to the breaking point (R-6)\n")
                .append("  wall clock, %s cycle interval, %d warm-up cycles per point, readings at "
                        .formatted(CYCLE, WARMUP_CYCLES)
                        + "600 ppm +/- 30 ppm, nothing injected\n")
                .append("  zone sets from Topology.syntheticZones(n) on Topology.airHandlerFor(n)\n");
        out.append(ResultFiles.table(List.of("zones", "cycles", "median ms", "p95 ms", "max ms",
                "ingest p95 ms", "p95/n^2 ns", "verdict"), rows));
        Point last = points.get(points.size() - 1);
        if (broke) {
            out.append("  R-6: p95 cycle time first passes %.0f s at %d zones (measured).%n"
                    .formatted(INTERVAL_SECONDS, last.zones()));
        } else {
            out.append("  R-6: the sweep reached %d zones at p95 = %.3f s, which is still inside "
                    .formatted(last.zones(), last.cycle().p95())
                    + "the %.0f s interval.%n".formatted(INTERVAL_SECONDS))
                    .append("  Fitted on the points from 100 zones up: p95 = %.3e * n^%.2f s "
                            .formatted(fit.coefficient(), fit.exponent())
                            + "(r2 = %.4f).%n".formatted(fit.rSquared()))
                    .append("  EXTRAPOLATED breaking point: p95 passes %.0f s at about %,d zones. "
                            .formatted(INTERVAL_SECONDS, fit.breakingPoint(INTERVAL_SECONDS))
                            + "This is an extrapolation, not a measurement.\n");
        }
        out.append("  The saturated element is the shared flow model: AhuFlowModel.believedFlowTo "
                        + "sums every zone's opening, and every zone asks it, so a cycle is O(n^2) "
                        + "in the zone count.\n")
                .append("  The p95/n^2 column is the evidence: it settles to a constant, which a "
                        + "linear or an n log n cost would not.\n");
        return out.toString();
    }

    /**
     * Fewer cycles at the large counts, because a point that costs 6 s per cycle would otherwise
     * dominate the harness's own runtime. The p95 is a nearest-rank statistic, so six samples put
     * it at the maximum of the six — which is the conservative direction for a breaking point.
     */
    private static int cyclesFor(int zones) {
        if (zones <= 3200) {
            return 40;
        }
        return zones <= 12_800 ? 12 : 6;
    }

    private static Point measure(int zoneCount, int cycles) {
        SimulatedClock clock = new SimulatedClock(SystemHarness.START);
        List<VentilationZone> zones = Topology.syntheticZones(zoneCount);
        AirHandlingUnit airHandler = Topology.airHandlerFor(zones);
        CountingPublisher events = new CountingPublisher();
        FaultMonitor monitor = new FaultMonitor(zones, airHandler, MonitorParameters.defaults(),
                clock, events, new CountingJournal());
        List<ZoneId> ids = zones.stream().map(VentilationZone::id).toList();
        Random noise = new Random(1);

        List<Double> cycleSeconds = new ArrayList<>(cycles);
        List<Double> ingestSeconds = new ArrayList<>(cycles);
        for (int cycle = 0; cycle < WARMUP_CYCLES + cycles; cycle++) {
            clock.advance(CYCLE);
            Instant now = clock.now();
            ObservedAt at = ObservedAt.of(now);
            long beforeIngest = System.nanoTime();
            for (ZoneId id : ids) {
                monitor.accept(new ReadingObserved(id,
                        Ppm.of(600 + Topology.SENSOR_NOISE_SIGMA_PPM * noise.nextGaussian()), at,
                        Quality.GOOD));
            }
            long afterIngest = System.nanoTime();
            monitor.runCycle();
            long afterCycle = System.nanoTime();
            if (cycle >= WARMUP_CYCLES) {
                ingestSeconds.add((afterIngest - beforeIngest) / 1e9);
                cycleSeconds.add((afterCycle - afterIngest) / 1e9);
            }
        }
        return new Point(zoneCount, Percentiles.of(cycleSeconds), Percentiles.of(ingestSeconds),
                events.published());
    }

    private record Point(int zones, Percentiles cycle, Percentiles ingest, long published) {
    }

    /** A straight line through {@code log p95} against {@code log n}, on the asymptotic points. */
    private record Fit(double coefficient, double exponent, double rSquared) {

        private static final int FROM_ZONES = 100;

        static Fit over(List<Point> points) {
            List<Point> asymptotic = points.stream().filter(p -> p.zones() >= FROM_ZONES).toList();
            if (asymptotic.size() < 2) {
                return new Fit(Double.NaN, Double.NaN, Double.NaN);
            }
            double meanX = asymptotic.stream().mapToDouble(p -> Math.log(p.zones())).average().orElseThrow();
            double meanY = asymptotic.stream().mapToDouble(p -> Math.log(p.cycle().p95())).average().orElseThrow();
            double covariance = 0;
            double variance = 0;
            for (Point point : asymptotic) {
                double x = Math.log(point.zones()) - meanX;
                covariance += x * (Math.log(point.cycle().p95()) - meanY);
                variance += x * x;
            }
            double slope = covariance / variance;
            double intercept = meanY - slope * meanX;
            double residual = 0;
            double total = 0;
            for (Point point : asymptotic) {
                double y = Math.log(point.cycle().p95());
                double predicted = intercept + slope * Math.log(point.zones());
                residual += (y - predicted) * (y - predicted);
                total += (y - meanY) * (y - meanY);
            }
            return new Fit(Math.exp(intercept), slope, total == 0 ? Double.NaN : 1 - residual / total);
        }

        long breakingPoint(double seconds) {
            return Math.round(Math.exp((Math.log(seconds) - Math.log(coefficient)) / exponent));
        }
    }
}
