package se.ltu.d7065e.evaluation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import se.ltu.d7065e.evaluation.boundary.DataExport;
import se.ltu.d7065e.evaluation.boundary.ResultFiles;
import se.ltu.d7065e.evaluation.control.BaselineComparisonExperiment;
import se.ltu.d7065e.evaluation.control.ComfortExperiment;
import se.ltu.d7065e.evaluation.control.ConfusionMatrixExperiment;
import se.ltu.d7065e.evaluation.control.DetectionDelayExperiment;
import se.ltu.d7065e.evaluation.control.IsolationExperiment;
import se.ltu.d7065e.evaluation.control.ProbeOrderExperiment;
import se.ltu.d7065e.evaluation.control.LearnedContenderExperiment;
import se.ltu.d7065e.evaluation.control.ResilienceExperiment;
import se.ltu.d7065e.evaluation.control.ScalingExperiment;

/**
 * The R-1…R-7 evidence runs, as {@code make experiments} invokes them.
 *
 * <pre>
 *   java -jar evaluation/target/evaluation-1.0.0-SNAPSHOT.jar all
 *   java -jar evaluation/target/evaluation-1.0.0-SNAPSHOT.jar T-24 T-26
 * </pre>
 *
 * <p>Every experiment writes its raw runs as one CSV under {@code evaluation/results/} and prints
 * the table the report quotes. The prose, the method and the verdict for each one live in
 * {@code aiadocs/results/T-nn.md}, written from this output.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        ResultFiles files = new ResultFiles();
        List<String> wanted = args.length == 0 || "all".equalsIgnoreCase(args[0])
                ? List.of("T-15", "T-24", "T-25", "T-26", "T-27", "T-28", "T-29", "T-34b", "T-35",
                        "T-22")
                : List.of(args);

        System.out.printf("D7065E evaluation harness — the R-1..R-7 evidence%n");
        System.out.printf("  Java %s on %s %s, results in %s%n",
                Runtime.version(), System.getProperty("os.name"),
                System.getProperty("os.arch"), files.directory().toAbsolutePath());
        System.out.printf("  Every control class driven here is the class the container runs; the "
                + "clock is simulated and the broker is a list.%n%n");

        List<String> timings = new ArrayList<>();
        long started = System.nanoTime();
        for (String experiment : wanted) {
            Function<ResultFiles, String> run = dispatch(experiment);
            if (run == null) {
                System.out.printf("unknown experiment %s; expected one of T-15 T-24 T-25 T-26 "
                        + "T-27 T-28 T-29 T-34b T-35 T-22, or all%n", experiment);
                continue;
            }
            long before = System.nanoTime();
            System.out.print(run.apply(files));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - before);
            timings.add("%s %.1f s".formatted(experiment, elapsed.toMillis() / 1000.0));
            System.out.printf("  (%s took %.1f s of wall clock)%n%n",
                    experiment, elapsed.toMillis() / 1000.0);
        }
        System.out.printf("done in %.1f s — %s%n",
                Duration.ofNanos(System.nanoTime() - started).toMillis() / 1000.0,
                String.join(", ", timings));
    }

    private static Function<ResultFiles, String> dispatch(String experiment) {
        return switch (experiment.toUpperCase(java.util.Locale.ROOT)) {
            case "T-15", "T15" -> ConfusionMatrixExperiment::run;
            case "T-24", "T24" -> DetectionDelayExperiment::run;
            case "T-25", "T25" -> BaselineComparisonExperiment::run;
            case "T-26", "T26" -> ComfortExperiment::run;
            case "T-27", "T27" -> ResilienceExperiment::run;
            case "T-28", "T28" -> ScalingExperiment::run;
            case "T-29", "T29" -> LearnedContenderExperiment::run;
            case "T-34B", "T34B" -> IsolationExperiment::run;
            case "T-35", "T35" -> ProbeOrderExperiment::run;
            case "T-22", "T22" -> DataExport::run;
            default -> null;
        };
    }
}
