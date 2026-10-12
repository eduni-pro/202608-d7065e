package se.ltu.d7065e.evaluation.boundary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * T-22 — getting the harness's runs out of the harness.
 *
 * <p>The decision and its reasoning live in {@code aiadocs/results/T-22.md}; the short version is
 * that the runs are written as CSV and that Parquet is produced on demand by a DuckDB that the
 * project does not depend on. Neither Java SE, nor Jakarta EE, nor MicroProfile can write Parquet,
 * so the hierarchy's first three steps all fail and the question becomes which library — measured,
 * {@code parquet-hadoop} plus {@code hadoop-common} is 98 jars and 64.8 MiB and {@code duckdb_jdbc}
 * is one jar of 81.2 MiB, against result sets of a few hundred kilobytes. The capability is kept and
 * the dependency is not: this class writes the one script that converts every table.
 *
 * <p>The script is executed and round-tripped, not assumed: the {@code duckdb_jdbc} jar is run
 * from the local Maven cache on a throwaway classpath, outside this reactor, so the capability is
 * demonstrated without the build acquiring the dependency. All six tables convert and read back to
 * 1731 rows, matching the CSVs exactly. The transcript and the sizes are in T-22.md, including the
 * one table that Parquet makes <em>bigger</em> — a columnar footer is not free at thirteen rows.
 */
public final class DataExport {

    private static final String SCRIPT = "export.sql";

    private DataExport() {
    }

    public static String run(ResultFiles files) {
        List<Path> tables = csvFiles(files.directory());
        StringBuilder script = new StringBuilder();
        script.append("-- Parquet export for the D7065E evaluation harness (T-22).\n")
                .append("-- Written by se.ltu.d7065e.evaluation. Run it from this directory.\n")
                .append("--\n")
                .append("--   duckdb -c \".read ").append(SCRIPT).append("\"\n")
                .append("--\n")
                .append("-- DuckDB is deliberately NOT a dependency of this project: see\n")
                .append("-- aiadocs/results/T-22.md for the measured sizes that decided it.\n");
        for (Path table : tables) {
            String name = table.getFileName().toString();
            String parquet = name.substring(0, name.length() - ".csv".length()) + ".parquet";
            script.append('\n')
                    .append("COPY (SELECT * FROM read_csv_auto('").append(name)
                    .append("', header = true))\n")
                    .append("  TO '").append(parquet)
                    .append("' (FORMAT PARQUET, COMPRESSION ZSTD);\n");
        }
        script.append("\n-- Round trip, so the export can be checked rather than assumed:\n");
        for (Path table : tables) {
            String name = table.getFileName().toString();
            String parquet = name.substring(0, name.length() - ".csv".length()) + ".parquet";
            script.append("SELECT '").append(parquet).append("' AS table_name, count(*) AS rows")
                    .append(" FROM read_parquet('").append(parquet).append("');\n");
        }
        files.write(SCRIPT, script.toString());

        long bytes = tables.stream().mapToLong(DataExport::sizeOf).sum();
        StringBuilder out = new StringBuilder();
        out.append("T-22 — export of the harness's run data\n")
                .append("  %d CSV tables, %,d bytes in total, in %s%n"
                        .formatted(tables.size(), bytes, files.directory()))
                .append("  Parquet is not worth its dependency at this scale and is not vendored; "
                        + "%s converts every table in one statement each.%n".formatted(SCRIPT))
                .append("  Measured closures, 2026-10-05: parquet-hadoop 1.15.2 + hadoop-common "
                        + "3.4.1 = 98 jars / 64.8 MiB; duckdb_jdbc 1.5.6.0 = 1 jar / 81.2 MiB.\n");
        List<List<String>> rows = new ArrayList<>();
        tables.forEach(table -> rows.add(List.of(table.getFileName().toString(),
                "%,d".formatted(sizeOf(table)), "%,d".formatted(countRows(table)))));
        out.append(ResultFiles.table(List.of("table", "bytes", "rows"), rows));
        return out.toString();
    }

    private static List<Path> csvFiles(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot size " + path, e);
        }
    }

    private static long countRows(Path path) {
        try (Stream<String> lines = Files.lines(path)) {
            return Math.max(0, lines.count() - 1);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
