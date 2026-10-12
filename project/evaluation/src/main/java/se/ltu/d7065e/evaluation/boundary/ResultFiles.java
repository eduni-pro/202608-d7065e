package se.ltu.d7065e.evaluation.boundary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where a run's raw rows land: one CSV per experiment under {@code evaluation/results/}, plus the
 * one-line-per-table stdout the operator reads.
 *
 * <p>CSV rather than Parquet, for the reasons T-22 records. The columnar format buys nothing at
 * this scale and the writers that produce it cost 65–81 MiB of dependency; the same directory
 * carries {@code export.sql}, which turns every CSV here into Parquet in one statement for
 * anyone who wants one.
 */
public final class ResultFiles {

    private final Path directory;

    public ResultFiles() {
        this(defaultDirectory());
    }

    public ResultFiles(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the results directory " + directory, e);
        }
    }

    /**
     * {@code evaluation/results/} when run from the repository's {@code project/} directory, as
     * {@code make experiments} does, and {@code results/} when run from inside the module.
     */
    private static Path defaultDirectory() {
        String configured = System.getenv("RESULTS_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path inProjectRoot = Path.of("evaluation");
        return Files.isDirectory(inProjectRoot) ? inProjectRoot.resolve("results") : Path.of("results");
    }

    public Path directory() {
        return directory;
    }

    /** Opens a CSV with the given header; the returned table is written on {@link Csv#close()}. */
    public Csv csv(String name, String... header) {
        return new Csv(directory.resolve(name), header);
    }

    public void write(String name, String content) {
        try {
            Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + name, e);
        }
    }

    /** One CSV file, buffered in memory because every table here is thousands of rows at most. */
    public static final class Csv implements AutoCloseable {

        private final Path path;
        private final List<String> lines = new ArrayList<>();

        private Csv(Path path, String... header) {
            this.path = path;
            lines.add(String.join(",", header));
        }

        /** One row. A {@code Double} is written with six decimals, everything else by toString. */
        public void row(Object... values) {
            List<String> cells = new ArrayList<>(values.length);
            for (Object value : values) {
                cells.add(cell(value));
            }
            lines.add(String.join(",", cells));
        }

        public int rows() {
            return lines.size() - 1;
        }

        public Path path() {
            return path;
        }

        @Override
        public void close() {
            try {
                Files.write(path, lines, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write " + path, e);
            }
        }

        private static String cell(Object value) {
            if (value == null) {
                return "";
            }
            if (value instanceof Double d) {
                return Double.isFinite(d) ? "%.6f".formatted(d) : "";
            }
            if (value instanceof Float f) {
                return "%.6f".formatted(f.doubleValue());
            }
            String text = String.valueOf(value);
            return text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0
                    ? text
                    : '"' + text.replace("\"", "\"\"") + '"';
        }
    }

    /** A fixed-width table on stdout, so the summary is readable without a spreadsheet. */
    public static String table(List<String> header, List<List<String>> rows) {
        int[] widths = new int[header.size()];
        for (int column = 0; column < header.size(); column++) {
            widths[column] = header.get(column).length();
        }
        for (List<String> row : rows) {
            for (int column = 0; column < row.size() && column < widths.length; column++) {
                widths[column] = Math.max(widths[column], row.get(column).length());
            }
        }
        StringBuilder out = new StringBuilder();
        appendRow(out, header, widths);
        for (int column = 0; column < widths.length; column++) {
            out.append(column == 0 ? "" : "  ").append("-".repeat(widths[column]));
        }
        out.append('\n');
        for (List<String> row : rows) {
            appendRow(out, row, widths);
        }
        return out.toString();
    }

    private static void appendRow(StringBuilder out, List<String> row, int[] widths) {
        for (int column = 0; column < widths.length; column++) {
            String cell = column < row.size() ? row.get(column) : "";
            out.append(column == 0 ? "" : "  ")
                    .append(String.format(Locale.ROOT, "%-" + widths[column] + "s", cell));
        }
        out.append('\n');
    }
}
