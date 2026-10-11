package se.ltu.d7065e.shared.boundary;

import jakarta.json.Json;
import jakarta.json.JsonString;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.CusumStatistic;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.diagnosis.entity.Residual;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanPowerObserved;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.ResidualComputed;
import se.ltu.d7065e.shared.event.ServiceReported;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The hot store over plain JDBC with native SQL, as ADR-0002 requires: the residual window
 * queries are time-ordered scans with a limit, which is what Timescale is for and what an ORM
 * would obscure. Every statement is parameterised.
 */
public final class TimescaleJournalAdapter implements ResidualJournalPort, AutoCloseable {

    /**
     * How far before a case's first decision the replay reaches back.
     *
     * <p>R-5 asks for a diagnosis to be reconstructable, and a reconstruction that starts at the
     * first decision cannot say why the case opened: the readings and residuals that raised the
     * suspicion are all earlier than it. One hour covers the CUSUM accumulation and the
     * step-response window that preceded confirmation at the 60 s monitor cycle.
     */
    private static final Duration EVIDENCE_LOOKBACK = Duration.ofHours(1);

    private final Connection connection;

    public TimescaleJournalAdapter(String jdbcUrl, String user, String password) {
        try {
            connection = DriverManager.getConnection(jdbcUrl, user, password);
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open the journal at " + jdbcUrl, e);
        }
    }

    @Override
    public void append(DomainEvent event, String source) {
        switch (event) {
            case ReadingObserved e -> update("""
                    INSERT INTO readings (observed_at, zone, ppm, quality, source)
                    VALUES (?, ?, ?, ?, ?)
                    """, s -> {
                s.setTimestamp(1, Timestamp.from(e.at().value()));
                s.setString(2, e.zone().value());
                s.setDouble(3, e.ppm().value());
                s.setString(4, e.quality().name());
                s.setString(5, source);
            });
            case FanPowerObserved e -> update("""
                    INSERT INTO fan_power (observed_at, ahu, watts, quality, source)
                    VALUES (?, ?, ?, ?, ?)
                    """, s -> {
                s.setTimestamp(1, Timestamp.from(e.at().value()));
                s.setString(2, e.ahu().value());
                s.setDouble(3, e.watts().watts());
                s.setString(4, e.quality().name());
                s.setString(5, source);
            });
            case CommandIssued e -> update("""
                    INSERT INTO commands (issued_at, zone, position, reason, command_id, source)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, s -> {
                s.setTimestamp(1, Timestamp.from(e.at().value()));
                s.setString(2, e.zone().value());
                s.setDouble(3, e.position().value());
                s.setString(4, e.reason());
                s.setString(5, e.commandId().value());
                s.setString(6, source);
            });
            case FanSpeedCommanded e -> update("""
                    INSERT INTO commands (issued_at, ahu, fan_speed, reason, command_id, source)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, s -> {
                s.setTimestamp(1, Timestamp.from(e.at().value()));
                s.setString(2, e.ahu().value());
                s.setDouble(3, e.speed().value());
                s.setString(4, e.reason());
                s.setString(5, e.commandId().value());
                s.setString(6, source);
            });
            case ResidualComputed e -> update("""
                    INSERT INTO residuals (observed_at, zone, residual_ppm, cusum, command_id, source)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, s -> {
                s.setTimestamp(1, Timestamp.from(e.at().value()));
                s.setString(2, e.zone().value());
                s.setDouble(3, e.residual().ppm());
                s.setDouble(4, e.cusum().value());
                setNullableString(s, 5, e.commandId() == null ? null : e.commandId().value());
                s.setString(6, source);
            });
            case FaultStateChanged e -> update("""
                    INSERT INTO decisions
                        (decided_at, zone, state, classification, evidence, case_id, source)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                    """, s -> {
                s.setTimestamp(1, Timestamp.from(e.at().value()));
                s.setString(2, e.zone().value());
                s.setString(3, e.state().name());
                s.setString(4, e.classification().name());
                s.setString(5, Json.createArrayBuilder(e.evidence()).build().toString());
                setNullableString(s, 6, e.caseId() == null ? null : e.caseId().value());
                s.setString(7, source);
            });
            case StrategyChanged e -> update("""
                    INSERT INTO strategies
                        (changed_at, zone, mode, trimmed_position, case_id, source)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, s -> {
                s.setTimestamp(1, Timestamp.from(e.at().value()));
                s.setString(2, e.zone().value());
                s.setString(3, e.mode().name());
                if (e.trimmedPosition() == null) {
                    s.setNull(4, Types.DOUBLE);
                } else {
                    s.setDouble(4, e.trimmedPosition());
                }
                setNullableString(s, 5, e.caseId() == null ? null : e.caseId().value());
                s.setString(6, source);
            });
            case ServiceReported ignored -> {
                // Not journalled, and not an omission: the report's effect is already where R-5
                // reads it, because the monitor answers it with FaultStateChanged(SERVICED) on
                // faults/{zone} and that row carries the same case id into `decisions`. A table
                // for the request itself would add a schema and no reconstruction.
            }
        }
    }

    /**
     * R-5: everything that produced one diagnosis. The decisions carry the case id directly;
     * the residuals and commands are gathered over the case's time window extended back by
     * {@link #EVIDENCE_LOOKBACK}, because a residual is recorded before anyone knows which case
     * it will belong to, and the ones that raised the suspicion all precede the first decision.
     */
    @Override
    public List<DomainEvent> replayCase(CaseId caseId) {
        List<DomainEvent> events = new ArrayList<>();
        Instant[] window = caseWindow(caseId);
        if (window == null) {
            return events;
        }
        String zone = caseZone(caseId);
        events.addAll(replayZone(ZoneId.of(zone), window[0].minus(EVIDENCE_LOOKBACK), window[1]));
        events.addAll(query("""
                SELECT decided_at, zone, state, classification, evidence, case_id
                FROM decisions WHERE case_id = ? ORDER BY decided_at
                """, s -> s.setString(1, caseId.value()), rs -> new FaultStateChanged(
                ZoneId.of(rs.getString("zone")),
                MonitorState.valueOf(rs.getString("state")),
                FaultClassification.valueOf(rs.getString("classification")),
                evidenceOf(rs.getString("evidence")),
                ObservedAt.of(rs.getTimestamp("decided_at").toInstant()),
                CaseId.of(rs.getString("case_id")))));
        events.addAll(query("""
                SELECT changed_at, zone, mode, trimmed_position, case_id
                FROM strategies WHERE case_id = ? ORDER BY changed_at
                """, s -> s.setString(1, caseId.value()), rs -> new StrategyChanged(
                ZoneId.of(rs.getString("zone")),
                ControlMode.valueOf(rs.getString("mode")),
                (Double) rs.getObject("trimmed_position"),
                ObservedAt.of(rs.getTimestamp("changed_at").toInstant()),
                CaseId.of(rs.getString("case_id")))));
        events.sort(Comparator.comparing(e -> e.at().value()));
        return events;
    }

    @Override
    public List<DomainEvent> replayZone(ZoneId zone, Instant from, Instant to) {
        List<DomainEvent> events = new ArrayList<>(query("""
                SELECT observed_at, zone, ppm, quality FROM readings
                WHERE zone = ? AND observed_at BETWEEN ? AND ? ORDER BY observed_at
                """, s -> {
            s.setString(1, zone.value());
            s.setTimestamp(2, Timestamp.from(from));
            s.setTimestamp(3, Timestamp.from(to));
        }, rs -> new ReadingObserved(
                ZoneId.of(rs.getString("zone")),
                Ppm.of(rs.getDouble("ppm")),
                ObservedAt.of(rs.getTimestamp("observed_at").toInstant()),
                Quality.valueOf(rs.getString("quality")))));
        events.addAll(query("""
                SELECT issued_at, zone, position, reason, command_id FROM commands
                WHERE zone = ? AND issued_at BETWEEN ? AND ? ORDER BY issued_at
                """, s -> {
            s.setString(1, zone.value());
            s.setTimestamp(2, Timestamp.from(from));
            s.setTimestamp(3, Timestamp.from(to));
        }, rs -> new CommandIssued(
                ZoneId.of(rs.getString("zone")),
                DamperPosition.of(rs.getDouble("position")),
                ObservedAt.of(rs.getTimestamp("issued_at").toInstant()),
                rs.getString("reason"),
                CommandId.of(rs.getString("command_id")))));
        events.addAll(query("""
                SELECT observed_at, zone, residual_ppm, cusum, command_id FROM residuals
                WHERE zone = ? AND observed_at BETWEEN ? AND ? ORDER BY observed_at
                """, s -> {
            s.setString(1, zone.value());
            s.setTimestamp(2, Timestamp.from(from));
            s.setTimestamp(3, Timestamp.from(to));
        }, rs -> new ResidualComputed(
                ZoneId.of(rs.getString("zone")),
                Residual.of(rs.getDouble("residual_ppm")),
                CusumStatistic.of(rs.getDouble("cusum")),
                ObservedAt.of(rs.getTimestamp("observed_at").toInstant()),
                rs.getString("command_id") == null
                        ? null : CommandId.of(rs.getString("command_id")))));
        events.sort(Comparator.comparing(e -> e.at().value()));
        return events;
    }

    private Instant[] caseWindow(CaseId caseId) {
        List<Instant[]> rows = query("""
                SELECT min(decided_at) AS opened, max(decided_at) AS closed
                FROM decisions WHERE case_id = ?
                """, s -> s.setString(1, caseId.value()), rs -> {
            Timestamp opened = rs.getTimestamp("opened");
            Timestamp closed = rs.getTimestamp("closed");
            return opened == null ? null : new Instant[] {opened.toInstant(), closed.toInstant()};
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String caseZone(CaseId caseId) {
        List<String> zones = query(
                "SELECT zone FROM decisions WHERE case_id = ? LIMIT 1",
                s -> s.setString(1, caseId.value()), rs -> rs.getString("zone"));
        return zones.get(0);
    }

    private static List<String> evidenceOf(String json) {
        try (var reader = Json.createReader(new StringReader(json))) {
            return reader.readArray().stream().map(v -> ((JsonString) v).getString())
                    .toList();
        }
    }

    private static void setNullableString(PreparedStatement s, int index, String value)
            throws SQLException {
        if (value == null) {
            s.setNull(index, Types.VARCHAR);
        } else {
            s.setString(index, value);
        }
    }

    // One connection, so every statement is serialised on it. PreparedStatement and ResultSet are
    // not thread-safe and the dashboard replays cases from JAX-RS worker threads, so two
    // concurrent requests would otherwise share one PostgreSQL connection.
    private synchronized void update(String sql, StatementBinder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("journal write failed: " + sql, e);
        }
    }

    private synchronized <T> List<T> query(String sql, StatementBinder binder, RowReader<T> reader) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    T row = reader.read(rs);
                    if (row != null) {
                        rows.add(row);
                    }
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("journal read failed: " + sql, e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot close the journal", e);
        }
    }

    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private interface RowReader<T> {
        T read(ResultSet resultSet) throws SQLException;
    }
}
