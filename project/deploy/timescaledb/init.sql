-- Hot store for the supervisory loop (T-19). Six typed hypertables rather than one blob
-- table: the residual window query (T-12, R-6) scans residuals only, and a decision is
-- reconstructed (R-5) by joining on case_id and the zone's time window.
--
-- Every table carries provenance (T-20): which process wrote the row, and the command the
-- observation is judged against. Without command_id a residual cannot be tied to the command
-- that should have produced the response, which is the whole basis of the diagnosis.

CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE readings (
    observed_at  TIMESTAMPTZ      NOT NULL,
    zone         TEXT             NOT NULL,
    ppm          DOUBLE PRECISION NOT NULL,
    quality      TEXT             NOT NULL,
    source       TEXT             NOT NULL,
    received_at  TIMESTAMPTZ      NOT NULL DEFAULT now()
);
SELECT create_hypertable('readings', 'observed_at');
CREATE INDEX ON readings (zone, observed_at DESC);

CREATE TABLE fan_power (
    observed_at  TIMESTAMPTZ      NOT NULL,
    ahu          TEXT             NOT NULL,
    watts        DOUBLE PRECISION NOT NULL,
    quality      TEXT             NOT NULL,
    source       TEXT             NOT NULL,
    received_at  TIMESTAMPTZ      NOT NULL DEFAULT now()
);
SELECT create_hypertable('fan_power', 'observed_at');
CREATE INDEX ON fan_power (ahu, observed_at DESC);

CREATE TABLE commands (
    issued_at    TIMESTAMPTZ      NOT NULL,
    zone         TEXT,
    ahu          TEXT,
    position     DOUBLE PRECISION,
    fan_speed    DOUBLE PRECISION,
    reason       TEXT             NOT NULL,
    command_id   TEXT             NOT NULL,
    source       TEXT             NOT NULL,
    received_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT commands_target CHECK (zone IS NOT NULL OR ahu IS NOT NULL)
);
SELECT create_hypertable('commands', 'issued_at');
CREATE INDEX ON commands (zone, issued_at DESC);
CREATE INDEX ON commands (command_id);

CREATE TABLE residuals (
    observed_at  TIMESTAMPTZ      NOT NULL,
    zone         TEXT             NOT NULL,
    residual_ppm DOUBLE PRECISION NOT NULL,
    cusum        DOUBLE PRECISION NOT NULL,
    command_id   TEXT,
    source       TEXT             NOT NULL,
    received_at  TIMESTAMPTZ      NOT NULL DEFAULT now()
);
SELECT create_hypertable('residuals', 'observed_at');
CREATE INDEX ON residuals (zone, observed_at DESC);

CREATE TABLE decisions (
    decided_at     TIMESTAMPTZ NOT NULL,
    zone           TEXT        NOT NULL,
    state          TEXT        NOT NULL,
    classification TEXT        NOT NULL,
    evidence       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    case_id        TEXT,
    source         TEXT        NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
SELECT create_hypertable('decisions', 'decided_at');
CREATE INDEX ON decisions (case_id, decided_at);
CREATE INDEX ON decisions (zone, decided_at DESC);

CREATE TABLE strategies (
    changed_at       TIMESTAMPTZ NOT NULL,
    zone             TEXT        NOT NULL,
    mode             TEXT        NOT NULL,
    trimmed_position DOUBLE PRECISION,
    case_id          TEXT,
    source           TEXT        NOT NULL,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
SELECT create_hypertable('strategies', 'changed_at');
CREATE INDEX ON strategies (case_id, changed_at);
