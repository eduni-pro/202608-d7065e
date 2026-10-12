import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;

/// One well-mixed room, V·dC/dt = n·G + Q·(C_out − C), stepped with forward Euler at 1 s.
/// Truth and prediction are integrated in lockstep: truth with the damper position the
/// hardware actually reached, prediction with the position that was commanded.
class ZoneSimulation {

    static final int INTEGRATION_STEP_S = 1;
    /// ppm is a volume fraction × 10⁶, so m³ of CO₂ per second becomes ppm·m³ per second after × 10⁶.
    static final double M3_TO_PPM = 1e6;

    record Zone(double volumeM3, double airflowAt100PercentM3PerS, double outdoorPpm, double co2PerPersonM3PerS) {

        static Zone level0Room() {
            var volumeM3 = 70.0 * 3.0;
            var airChangesPerHourAt100Percent = 3.0;
            var co2PerPersonLitrePerS = 0.005;
            return new Zone(volumeM3, airChangesPerHourAt100Percent * volumeM3 / 3600.0, 420.0, co2PerPersonLitrePerS / 1000.0);
        }

        double airChangesPerHour(double damperPosition) {
            return damperPosition * airflowAt100PercentM3PerS * 3600.0 / volumeM3;
        }

        double steadyStatePpm(int occupants, double damperPosition) {
            return outdoorPpm + occupants * co2PerPersonM3PerS * M3_TO_PPM / (damperPosition * airflowAt100PercentM3PerS);
        }

        double co2RatePpmPerS(double ppm, int occupants, double damperPosition) {
            var generationPpmM3PerS = occupants * co2PerPersonM3PerS * M3_TO_PPM;
            var airflowM3PerS = damperPosition * airflowAt100PercentM3PerS;
            return (generationPpmM3PerS + airflowM3PerS * (outdoorPpm - ppm)) / volumeM3;
        }
    }

    record Scenario(int horizonS, int sampleIntervalS, int occupancyStepAtS, int occupants,
                    int commandStepAtS, double initialPosition, double commandedPosition) {

        static Scenario standard() {
            return new Scenario(180 * 60, 60, 10 * 60, 10, 40 * 60, 0.20, 0.80);
        }

        int occupantsAt(int second) {
            return second >= occupancyStepAtS ? occupants : 0;
        }

        double commandAt(int second) {
            return second >= commandStepAtS ? commandedPosition : initialPosition;
        }
    }

    enum Damper {
        HEALTHY, STUCK;

        double actualPosition(double commanded, double stuckAt) {
            return switch (this) {
                case HEALTHY -> commanded;
                case STUCK -> stuckAt;
            };
        }
    }

    /// One-sided upper CUSUM: S₀ = 0, Sₜ = max(0, Sₜ₋₁ + rₜ − k); alarm when Sₜ ≥ h.
    record Cusum(double kPpm, double hPpm) {

        double next(double previous, double residual) {
            return Math.max(0.0, previous + residual - kPpm);
        }

        /// Siegmund's approximation of the in-control average run length for a zero-mean unit-variance residual.
        double averageRunLengthInControl(double sigmaPpm) {
            var k = kPpm / sigmaPpm;
            var b = hPpm / sigmaPpm + 1.166;
            return (Math.exp(2 * k * b) - 2 * k * b - 1) / (2 * k * k);
        }
    }

    record Sample(int t, int n, double commanded, double actual, double cTrue, double cMeasured,
                  double cPredicted, double residual, double cusum) {

        String csvLine() {
            return "%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f".formatted(
                    t, n, commanded, actual, cTrue, cMeasured, cPredicted, residual, cusum);
        }
    }

    record Run(Damper damper, long seed, List<Sample> samples, double thresholdPpm) {

        boolean crossed() {
            return firstCrossingSecond().isPresent();
        }

        OptionalInt firstCrossingSecond() {
            return firstCrossingSecond(thresholdPpm);
        }

        OptionalInt firstCrossingSecond(double threshold) {
            return samples.stream()
                    .filter(sample -> sample.cusum() >= threshold)
                    .mapToInt(Sample::t)
                    .findFirst();
        }

        double meanResidual() {
            return samples.stream().mapToDouble(Sample::residual).average().orElse(0.0);
        }

        double meanResidualAfter(int second) {
            return samples.stream()
                    .filter(sample -> sample.t() > second)
                    .mapToDouble(Sample::residual)
                    .average()
                    .orElse(0.0);
        }

        double maxResidual() {
            return samples.stream().mapToDouble(Sample::residual).max().orElse(0.0);
        }

        double minResidual() {
            return samples.stream().mapToDouble(Sample::residual).min().orElse(0.0);
        }

        double residualStandardDeviation() {
            var mean = meanResidual();
            var variance = samples.stream()
                    .mapToDouble(sample -> Math.pow(sample.residual() - mean, 2))
                    .average()
                    .orElse(0.0);
            return Math.sqrt(variance);
        }

        double maxCusum() {
            return samples.stream().mapToDouble(Sample::cusum).max().orElse(0.0);
        }
    }

    Zone zone;
    Scenario scenario;
    Cusum cusum;
    double sigmaPpm;

    ZoneSimulation(Zone zone, Scenario scenario, Cusum cusum, double sigmaPpm) {
        this.zone = zone;
        this.scenario = scenario;
        this.cusum = cusum;
        this.sigmaPpm = sigmaPpm;
    }

    Run run(Damper damper, long seed) {
        var noise = new Random(seed);
        var cTrue = zone.outdoorPpm();
        var cPredicted = zone.outdoorPpm();
        var cusumValue = 0.0;
        var samples = new ArrayList<Sample>();
        for (var second = 0; second <= scenario.horizonS(); second += INTEGRATION_STEP_S) {
            var occupants = scenario.occupantsAt(second);
            var commanded = scenario.commandAt(second);
            var actual = damper.actualPosition(commanded, scenario.initialPosition());
            if (second % scenario.sampleIntervalS() == 0) {
                var measured = cTrue + sigmaPpm * noise.nextGaussian();
                var residual = measured - cPredicted;
                cusumValue = cusum.next(cusumValue, residual);
                samples.add(new Sample(second, occupants, commanded, actual, cTrue, measured, cPredicted, residual, cusumValue));
            }
            cTrue += INTEGRATION_STEP_S * zone.co2RatePpmPerS(cTrue, occupants, actual);
            cPredicted += INTEGRATION_STEP_S * zone.co2RatePpmPerS(cPredicted, occupants, commanded);
        }
        return new Run(damper, seed, List.copyOf(samples), cusum.hPpm());
    }
}
