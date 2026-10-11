import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/// T-04 kill-criterion spike: does a residual separate a stuck damper from a busy room at σ = 30 ppm?
class Main {

    static final double SIGMA_PPM = 30.0;
    static final double CUSUM_K_IN_SIGMA = 1.0;
    static final double CUSUM_H_IN_SIGMA = 5.0;
    static final long SEED = 42L;
    static final int CHECK_SEEDS = 20;
    static final double CUSUM_PLOT_CEILING_IN_H = 8.0;
    static final String CSV_HEADER = "t,n,commanded,actual,C_true,C_measured,C_predicted,residual,cusum";

    ZoneSimulation.Zone zone = ZoneSimulation.Zone.level0Room();
    ZoneSimulation.Scenario scenario = ZoneSimulation.Scenario.standard();
    ZoneSimulation.Cusum cusum = new ZoneSimulation.Cusum(CUSUM_K_IN_SIGMA * SIGMA_PPM, CUSUM_H_IN_SIGMA * SIGMA_PPM);
    ZoneSimulation simulation = new ZoneSimulation(zone, scenario, cusum, SIGMA_PPM);

    void main() throws IOException {
        // CSV and SVG need a dot as decimal separator whatever the machine's locale is
        Locale.setDefault(Locale.ROOT);
        printSetup();

        var healthy = simulation.run(ZoneSimulation.Damper.HEALTHY, SEED);
        var stuck = simulation.run(ZoneSimulation.Damper.STUCK, SEED);
        writeCsv(Path.of("healthy.csv"), healthy);
        writeCsv(Path.of("stuck.csv"), stuck);
        printRuns(healthy, stuck);

        var healthyChecks = checkRuns(ZoneSimulation.Damper.HEALTHY);
        var stuckChecks = checkRuns(ZoneSimulation.Damper.STUCK);
        printFalseAlarmCheck(healthyChecks);
        printDetectionCheck(stuckChecks);
        printThresholdSensitivity(healthyChecks, stuckChecks);

        writePlot(Path.of("plot.svg"), healthy, stuck);

        var noFalseAlarm = !healthy.crossed() && healthyChecks.stream().noneMatch(ZoneSimulation.Run::crossed);
        var alwaysDetected = stuck.crossed() && stuckChecks.stream().allMatch(ZoneSimulation.Run::crossed);
        IO.println("");
        IO.println("Verdict rule: every stuck run crosses h, no healthy run crosses h");
        IO.println("Separable at σ=%.0f ppm: %s".formatted(SIGMA_PPM, noFalseAlarm && alwaysDetected ? "yes" : "no"));
    }

    void printSetup() {
        IO.println("T-04 residual-separation spike — one well-mixed room, healthy vs stuck damper");
        IO.println("Model      V·dC/dt = n·G + Q·(C_out − C), forward Euler at %d s".formatted(ZoneSimulation.INTEGRATION_STEP_S));
        IO.println("Room       V = %.0f m³, C_out = %.0f ppm, G = %.1e m³/s per person (0.005 L/s → ×10⁶ gives %.1f ppm·m³/s)"
                .formatted(zone.volumeM3(), zone.outdoorPpm(), zone.co2PerPersonM3PerS(),
                        zone.co2PerPersonM3PerS() * ZoneSimulation.M3_TO_PPM));
        IO.println("Airflow    Q = position × Q_max, Q_max = %.3f m³/s = %.1f ACH; %.0f %% → %.2f ACH, %.0f %% → %.2f ACH"
                .formatted(zone.airflowAt100PercentM3PerS(), zone.airChangesPerHour(1.0),
                        scenario.initialPosition() * 100, zone.airChangesPerHour(scenario.initialPosition()),
                        scenario.commandedPosition() * 100, zone.airChangesPerHour(scenario.commandedPosition())));
        IO.println("Steady     %d people: %.0f ppm at %.0f %%, %.0f ppm at %.0f %%".formatted(
                scenario.occupants(), zone.steadyStatePpm(scenario.occupants(), scenario.initialPosition()),
                scenario.initialPosition() * 100, zone.steadyStatePpm(scenario.occupants(), scenario.commandedPosition()),
                scenario.commandedPosition() * 100));
        IO.println("Scenario   n: 0 → %d at t = %d min; damper command %.0f %% → %.0f %% at t = %d min; horizon %d min; sample every %d s"
                .formatted(scenario.occupants(), scenario.occupancyStepAtS() / 60, scenario.initialPosition() * 100,
                        scenario.commandedPosition() * 100, scenario.commandStepAtS() / 60, scenario.horizonS() / 60,
                        scenario.sampleIntervalS()));
        IO.println("Observer   same model, commanded position, true n (simplification — the real observer will not have n)");
        IO.println("Noise      Gaussian σ = %.0f ppm on the measurement, seed %d for both runs".formatted(SIGMA_PPM, SEED));
        IO.println("CUSUM      one-sided, k = %.0f ppm (%.1f σ), h = %.0f ppm (%.1f σ); Siegmund ARL₀ ≈ %.1e samples ≈ %.0f days at 60 s"
                .formatted(cusum.kPpm(), CUSUM_K_IN_SIGMA, cusum.hPpm(), CUSUM_H_IN_SIGMA,
                        cusum.averageRunLengthInControl(SIGMA_PPM), cusum.averageRunLengthInControl(SIGMA_PPM) / 1440));
        IO.println("");
    }

    void printRuns(ZoneSimulation.Run healthy, ZoneSimulation.Run stuck) {
        IO.println("Run (seed %d)   mean r    std r    min r    max r   mean r after cmd   max CUSUM   crossing".formatted(SEED));
        Stream.of(healthy, stuck).forEach(this::printRun);
        var separationInSigma = (stuck.meanResidualAfter(scenario.commandStepAtS()) - healthy.meanResidualAfter(scenario.commandStepAtS())) / SIGMA_PPM;
        IO.println("Separation of the post-command mean residual: %.1f σ".formatted(separationInSigma));
        IO.println("");
    }

    void printRun(ZoneSimulation.Run run) {
        IO.println("%-14s %7.1f  %7.1f  %7.1f  %7.1f  %17.1f  %10.1f   %s".formatted(
                run.damper().name().toLowerCase(), run.meanResidual(), run.residualStandardDeviation(),
                run.minResidual(), run.maxResidual(), run.meanResidualAfter(scenario.commandStepAtS()),
                run.maxCusum(), crossingText(run)));
    }

    String crossingText(ZoneSimulation.Run run) {
        if (run.firstCrossingSecond().isEmpty()) {
            return "no crossing";
        }
        var second = run.firstCrossingSecond().getAsInt();
        return "t = %d min (%d min after the ignored command)".formatted(second / 60, (second - scenario.commandStepAtS()) / 60);
    }

    List<ZoneSimulation.Run> checkRuns(ZoneSimulation.Damper damper) {
        return IntStream.rangeClosed(1, CHECK_SEEDS)
                .mapToObj(seed -> simulation.run(damper, seed))
                .toList();
    }

    void printFalseAlarmCheck(List<ZoneSimulation.Run> healthyRuns) {
        var crossings = healthyRuns.stream().filter(ZoneSimulation.Run::crossed).count();
        var peaks = healthyRuns.stream().mapToDouble(ZoneSimulation.Run::maxCusum).sorted().toArray();
        var worstSeed = healthyRuns.stream().max(Comparator.comparingDouble(ZoneSimulation.Run::maxCusum)).orElseThrow().seed();
        IO.println("False-alarm check   healthy damper, seeds 1..%d: %d of %d crossed h = %.0f ppm; peak CUSUM per seed median %.1f ppm, max %.1f ppm (seed %d)"
                .formatted(CHECK_SEEDS, crossings, CHECK_SEEDS, cusum.hPpm(), peaks[peaks.length / 2], peaks[peaks.length - 1], worstSeed));
    }

    void printDetectionCheck(List<ZoneSimulation.Run> stuckRuns) {
        var crossings = stuckRuns.stream().filter(ZoneSimulation.Run::crossed).count();
        var delaysMin = delaysAfterCommandMin(stuckRuns, cusum.hPpm());
        IO.println("Detection check     stuck damper, seeds 1..%d: %d of %d crossed h; delay after the ignored command min/median/max = %d/%d/%d min"
                .formatted(CHECK_SEEDS, crossings, CHECK_SEEDS, delaysMin[0], delaysMin[delaysMin.length / 2], delaysMin[delaysMin.length - 1]));
    }

    void printThresholdSensitivity(List<ZoneSimulation.Run> healthyRuns, List<ZoneSimulation.Run> stuckRuns) {
        IO.println("");
        IO.println("Threshold sensitivity on the same %d seeds (the CUSUM statistic depends on k only, so h is evaluated after the fact):".formatted(CHECK_SEEDS));
        DoubleStream.of(4.0, 5.0, 6.0, 8.0).forEach(hInSigma -> printThresholdRow(hInSigma, healthyRuns, stuckRuns));
    }

    void printThresholdRow(double hInSigma, List<ZoneSimulation.Run> healthyRuns, List<ZoneSimulation.Run> stuckRuns) {
        var h = hInSigma * SIGMA_PPM;
        var healthyCrossings = healthyRuns.stream().filter(run -> run.firstCrossingSecond(h).isPresent()).count();
        var stuckCrossings = stuckRuns.stream().filter(run -> run.firstCrossingSecond(h).isPresent()).count();
        var delaysMin = delaysAfterCommandMin(stuckRuns, h);
        var marker = hInSigma == CUSUM_H_IN_SIGMA ? "  <- used" : "";
        IO.println("  h = %3.0f ppm (%.0f σ): healthy false alarms %d/%d, stuck detected %d/%d, stuck delay median/max %d/%d min%s"
                .formatted(h, hInSigma, healthyCrossings, CHECK_SEEDS, stuckCrossings, CHECK_SEEDS,
                        delaysMin[delaysMin.length / 2], delaysMin[delaysMin.length - 1], marker));
    }

    int[] delaysAfterCommandMin(List<ZoneSimulation.Run> runs, double h) {
        return runs.stream()
                .map(run -> run.firstCrossingSecond(h))
                .filter(OptionalInt::isPresent)
                .mapToInt(crossing -> (crossing.getAsInt() - scenario.commandStepAtS()) / 60)
                .sorted()
                .toArray();
    }

    void writeCsv(Path path, ZoneSimulation.Run run) throws IOException {
        var lines = Stream.concat(Stream.of(CSV_HEADER), run.samples().stream().map(ZoneSimulation.Sample::csvLine)).toList();
        Files.write(path, lines);
    }

    void writePlot(Path path, ZoneSimulation.Run healthy, ZoneSimulation.Run stuck) throws IOException {
        var co2 = new SvgPlot.Panel("CO₂ (ppm): measured, and the trajectory the command should have produced", "",
                List.of(new SvgPlot.Series("healthy (measured)", SvgPlot.HEALTHY, SvgPlot.SOLID, healthy.samples(), ZoneSimulation.Sample::cMeasured),
                        new SvgPlot.Series("stuck (measured)", SvgPlot.STUCK, SvgPlot.SOLID, stuck.samples(), ZoneSimulation.Sample::cMeasured),
                        new SvgPlot.Series("predicted from command", SvgPlot.PREDICTED, SvgPlot.DASHED, healthy.samples(), ZoneSimulation.Sample::cPredicted)),
                List.of(), List.of(), SvgPlot.AUTO, SvgPlot.AUTO);
        var residual = new SvgPlot.Panel("Residual = measured − predicted (ppm)", "",
                List.of(new SvgPlot.Series("healthy damper", SvgPlot.HEALTHY, SvgPlot.SOLID, healthy.samples(), ZoneSimulation.Sample::residual),
                        new SvgPlot.Series("stuck damper", SvgPlot.STUCK, SvgPlot.SOLID, stuck.samples(), ZoneSimulation.Sample::residual)),
                List.of(new SvgPlot.ReferenceLine("+2σ", 2 * SIGMA_PPM, SvgPlot.AXIS, SvgPlot.DOTTED),
                        new SvgPlot.ReferenceLine("−2σ", -2 * SIGMA_PPM, SvgPlot.AXIS, SvgPlot.DOTTED),
                        new SvgPlot.ReferenceLine("", 0.0, SvgPlot.AXIS, SvgPlot.SOLID)),
                List.of(), SvgPlot.AUTO, SvgPlot.AUTO);
        var cusumCeiling = CUSUM_PLOT_CEILING_IN_H * cusum.hPpm();
        var cusumPanel = new SvgPlot.Panel("One-sided CUSUM of the residual (ppm), k = %.0f ppm — axis capped at %.0f ppm".formatted(cusum.kPpm(), cusumCeiling),
                clippedNote(stuck, cusumCeiling),
                List.of(new SvgPlot.Series("healthy damper", SvgPlot.HEALTHY, SvgPlot.SOLID, healthy.samples(), ZoneSimulation.Sample::cusum),
                        new SvgPlot.Series("stuck damper", SvgPlot.STUCK, SvgPlot.SOLID, stuck.samples(), ZoneSimulation.Sample::cusum)),
                List.of(new SvgPlot.ReferenceLine("h = %.0f ppm".formatted(cusum.hPpm()), cusum.hPpm(), SvgPlot.THRESHOLD, SvgPlot.DASHED)),
                crossingAnnotations(stuck), 0.0, cusumCeiling);
        var events = List.of(
                new SvgPlot.Event("n: 0 → %d".formatted(scenario.occupants()), scenario.occupancyStepAtS()),
                new SvgPlot.Event("command: %.0f %% → %.0f %%".formatted(scenario.initialPosition() * 100, scenario.commandedPosition() * 100), scenario.commandStepAtS()));
        new SvgPlot(List.of(co2, residual, cusumPanel), events, scenario.horizonS()).write(path,
                "T-04 residual separation — healthy vs stuck damper, σ = %.0f ppm, seed %d".formatted(SIGMA_PPM, SEED),
                "V = %.0f m³, Q_max = %.1f ACH, %d people from t = %d min, damper commanded %.0f %% → %.0f %% at t = %d min; stuck damper stays at %.0f %%"
                        .formatted(zone.volumeM3(), zone.airChangesPerHour(1.0), scenario.occupants(), scenario.occupancyStepAtS() / 60,
                                scenario.initialPosition() * 100, scenario.commandedPosition() * 100, scenario.commandStepAtS() / 60,
                                scenario.initialPosition() * 100));
    }

    String clippedNote(ZoneSimulation.Run stuck, double ceiling) {
        var leavesAt = stuck.firstCrossingSecond(ceiling);
        if (leavesAt.isEmpty()) {
            return "";
        }
        return "stuck CUSUM leaves the panel at t = %d min and reaches %.0f ppm at t = %d min".formatted(
                leavesAt.getAsInt() / 60, stuck.maxCusum(), scenario.horizonS() / 60);
    }

    List<SvgPlot.Annotation> crossingAnnotations(ZoneSimulation.Run stuck) {
        if (stuck.firstCrossingSecond().isEmpty()) {
            return List.of();
        }
        var second = stuck.firstCrossingSecond().getAsInt();
        var cusumAtCrossing = stuck.samples().stream()
                .filter(sample -> sample.t() == second)
                .mapToDouble(ZoneSimulation.Sample::cusum)
                .findFirst()
                .orElse(cusum.hPpm());
        return List.of(new SvgPlot.Annotation("crossed at t = %d min (+%d min)".formatted(second / 60, (second - scenario.commandStepAtS()) / 60),
                second, cusumAtCrossing, SvgPlot.STUCK));
    }
}
