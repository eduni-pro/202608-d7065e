-- Parquet export for the D7065E evaluation harness (T-22).
-- Written by se.ltu.d7065e.evaluation. Run it from this directory.
--
--   duckdb -c ".read export.sql"
--
-- DuckDB is deliberately NOT a dependency of this project: see
-- aiadocs/results/T-22.md for the measured sizes that decided it.

COPY (SELECT * FROM read_csv_auto('T-15-confusion-matrix.csv', header = true))
  TO 'T-15-confusion-matrix.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);

COPY (SELECT * FROM read_csv_auto('T-24-detection-delay.csv', header = true))
  TO 'T-24-detection-delay.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);

COPY (SELECT * FROM read_csv_auto('T-25-baseline-comparison.csv', header = true))
  TO 'T-25-baseline-comparison.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);

COPY (SELECT * FROM read_csv_auto('T-26-comfort.csv', header = true))
  TO 'T-26-comfort.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);

COPY (SELECT * FROM read_csv_auto('T-27-resilience.csv', header = true))
  TO 'T-27-resilience.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);

COPY (SELECT * FROM read_csv_auto('T-28-scaling.csv', header = true))
  TO 'T-28-scaling.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);

COPY (SELECT * FROM read_csv_auto('T-29-learned-contender.csv', header = true))
  TO 'T-29-learned-contender.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);

COPY (SELECT * FROM read_csv_auto('T-34b-room-isolation.csv', header = true))
  TO 'T-34b-room-isolation.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);

COPY (SELECT * FROM read_csv_auto('T-35-probe-order.csv', header = true))
  TO 'T-35-probe-order.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);

-- Round trip, so the export can be checked rather than assumed:
SELECT 'T-15-confusion-matrix.parquet' AS table_name, count(*) AS rows FROM read_parquet('T-15-confusion-matrix.parquet');
SELECT 'T-24-detection-delay.parquet' AS table_name, count(*) AS rows FROM read_parquet('T-24-detection-delay.parquet');
SELECT 'T-25-baseline-comparison.parquet' AS table_name, count(*) AS rows FROM read_parquet('T-25-baseline-comparison.parquet');
SELECT 'T-26-comfort.parquet' AS table_name, count(*) AS rows FROM read_parquet('T-26-comfort.parquet');
SELECT 'T-27-resilience.parquet' AS table_name, count(*) AS rows FROM read_parquet('T-27-resilience.parquet');
SELECT 'T-28-scaling.parquet' AS table_name, count(*) AS rows FROM read_parquet('T-28-scaling.parquet');
SELECT 'T-29-learned-contender.parquet' AS table_name, count(*) AS rows FROM read_parquet('T-29-learned-contender.parquet');
SELECT 'T-34b-room-isolation.parquet' AS table_name, count(*) AS rows FROM read_parquet('T-34b-room-isolation.parquet');
SELECT 'T-35-probe-order.parquet' AS table_name, count(*) AS rows FROM read_parquet('T-35-probe-order.parquet');
