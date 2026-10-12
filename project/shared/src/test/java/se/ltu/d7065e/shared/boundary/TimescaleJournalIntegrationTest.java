package se.ltu.d7065e.shared.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.CusumStatistic;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.diagnosis.entity.Residual;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanPowerObserved;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.ResidualComputed;
import se.ltu.d7065e.shared.event.ServiceReported;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.ventilation.entity.AhuId;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * R-5 against a real PostgreSQL, which is the only place it can be tested (D-06).
 *
 * <p>{@link TimescaleJournalAdapter} is native SQL in PostgreSQL's dialect — a {@code ?::jsonb}
 * cast for a decision's evidence, {@code BETWEEN} over {@code timestamptz}, {@code min}/{@code max}
 * over a case's decisions — so no in-memory substitute executes the same statements, and until this
 * file existed none of them had ever been executed by a test: not the seven inserts, not the
 * per-zone window scans, not the case window, and above all not the one-hour evidence lookback that
 * decides which readings and residuals belong to a case at all. That lookback is the part R-5 turns
 * on, because a reconstruction that begins at the first decision cannot say why the case opened.
 *
 * <h2>How to run it</h2>
 *
 * <p>With the compose journal already up — {@code docker compose up -d timescaledb}, or the whole
 * demo stack — it runs with no configuration: {@code mvn -o test -pl shared}. From cold,
 * {@code make journal-test} starts the service, runs the test and stops the service again. Without
 * either, every test here <b>skips</b>, so {@code mvn -o clean test} is green on a machine with no
 * Docker and the default build starts nothing.
 *
 * <p>Starting a container is deliberately not the default. The suite's value is that it runs in
 * about ten seconds on a laptop with nothing installed, and a default that waits on a database —
 * or, on a machine without the image, downloads 2.4 GB of it under {@code mvn -o} — would trade
 * that away for a test that is better run on purpose.
 *
 * <h2>Why it can share a live journal</h2>
 *
 * <p>Each test writes under a zone id and a case id of its own, generated per run, so its queries
 * can only match its own rows even when the demo stack is up and writing beside it; and every row
 * carries one {@code source} per run, which is what {@link #closeJournal()} deletes. Nothing here
 * creates or drops a table, so running it against a live stack costs that stack nothing — and the
 * autostart path removes only a container this run created, because compose derives one project
 * name per checkout and the sibling worktrees share the compose file.
 */
class TimescaleJournalIntegrationTest {

    private static final String URL = System.getProperty(
            "r5.journal.url", "jdbc:postgresql://localhost:5432/ventilation");
    private static final String USER = System.getProperty("r5.journal.user", "ventilation");
    private static final String PASSWORD = System.getProperty("r5.journal.password", "ventilation");
    private static final boolean AUTOSTART =
            Boolean.parseBoolean(System.getProperty("r5.journal.autostart", "false"));
    private static final Duration STARTUP_BUDGET = Duration.ofSeconds(90);

    /** The adapter's own lookback, restated so a change to it fails this test rather than hiding. */
    private static final Duration EVIDENCE_LOOKBACK = Duration.ofHours(1);

    /** Whole seconds, because {@code timestamptz} keeps microseconds and nothing finer. */
    private static final Instant BASE = Instant.parse("2026-10-05T09:00:00Z");

    private static final List<String> TABLES = List.of(
            "readings", "fan_power", "commands", "residuals", "decisions", "strategies");

    private static String source;
    private static TimescaleJournalAdapter journal;
    private static String unavailable;
    private static boolean startedHere;

    /**
     * Resolves the journal once without aborting, so {@link #requireJournal()} can skip each test
     * individually. Aborting here instead would leave surefire reporting {@code Tests run: 0} with
     * the reason nowhere on the page, and a suite that quietly does not test R-5 is how R-5 came to
     * be untested in the first place.
     */
    @BeforeAll
    static void openJournal() {
        source = "r5-test-" + UUID.randomUUID().toString().substring(0, 8);
        if (!reachable()) {
            if (!AUTOSTART) {
                unavailable = ("no journal at %s. Bring the compose service up — "
                        + "`docker compose up -d timescaledb` — and run this again, or run "
                        + "`make journal-test`, which starts it, runs these tests and stops it "
                        + "again.").formatted(URL);
                return;
            }
            if (!startCompose()) {
                unavailable = "docker could not start the compose journal at " + URL;
                return;
            }
        }
        journal = new TimescaleJournalAdapter(URL, USER, PASSWORD);
    }

    @BeforeEach
    void requireJournal() {
        Assumptions.assumeTrue(journal != null, () -> unavailable);
    }

    @AfterAll
    static void closeJournal() {
        if (journal == null) {
            return;
        }
        journal.close();
        if (startedHere) {
            // Nothing to tidy: the compose service has no volume behind it, so removing the
            // container takes this run's rows with it.
            compose("rm", "-sf", "timescaledb");
            return;
        }
        deleteRowsOfThisRun();
    }

    @Test
    @DisplayName("R-5: the three zone-scoped inserts come back as the records that were written")
    void shouldRoundTripEveryInsertTheReplayCanRead() {
        ZoneId zone = newZone();
        ReadingObserved reading = new ReadingObserved(zone, Ppm.of(1020.4), at(0), Quality.GOOD);
        CommandIssued command = new CommandIssued(zone, DamperPosition.of(0.65), at(1),
                "co2 above setpoint", CommandId.of(newId()));
        ResidualComputed residual = new ResidualComputed(zone, Residual.of(-42.5),
                CusumStatistic.of(175.25), at(2), command.commandId());
        append(reading, command, residual);

        List<DomainEvent> replayed = journal.replayZone(zone, at(-1).value(), at(3).value());

        assertEquals(List.of(reading, command, residual), replayed,
                "every field has to survive the column it was written to, in time order");
    }

    @Test
    @DisplayName("T-20: the two plant-wide inserts land, though the replay has no read path for them")
    void shouldWriteThePlantWideRowsNoReplayReadsBack() {
        AhuId ahu = AhuId.of("AHU-" + newId());
        journal.append(new FanPowerObserved(ahu, FanPower.ofWatts(312.75), at(0), Quality.GOOD),
                source);
        journal.append(new FanSpeedCommanded(ahu, FanSpeed.of(0.85), at(1), "compensating",
                CommandId.of(newId())), source);

        // Asserted with a count rather than a replay because the adapter has no query for either:
        // fan power and fan speed are plant-wide, and replayZone selects by zone. The insert is
        // still the thing under test — a wrong column name in either would have thrown above and
        // nothing in the suite would have noticed before this file existed.
        assertEquals(1, count("SELECT count(*) FROM fan_power WHERE ahu = '%s'".formatted(ahu.value())));
        assertEquals(1, count(
                "SELECT count(*) FROM commands WHERE ahu = '%s' AND zone IS NULL".formatted(ahu.value())),
                "a fan-speed command carries an ahu and no zone, which is what the table's CHECK "
                        + "constraint allows");
    }

    @Test
    @DisplayName("R-5: a case is rebuilt from its decisions, its strategies and its zone's evidence")
    void shouldRebuildOneCaseFromTheJournal() {
        ZoneId zone = newZone();
        CaseId caseId = newCase();
        List<String> evidence = List.of("cusum 175 ppm > h 150 ppm for 2 cycles",
                "fan power flat at 137 W");
        ReadingObserved reading = new ReadingObserved(zone, Ppm.of(1120.0), at(-30), Quality.GOOD);
        ResidualComputed residual = new ResidualComputed(zone, Residual.of(166.0),
                CusumStatistic.of(175.0), at(-1), null);
        FaultStateChanged suspect = new FaultStateChanged(zone, MonitorState.SUSPECT,
                FaultClassification.NONE, List.of("upper arm passed the threshold"), at(0), caseId);
        FaultStateChanged confirmed = new FaultStateChanged(zone, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER, evidence, at(2), caseId);
        StrategyChanged degraded = new StrategyChanged(zone, ControlMode.DEGRADED, null, at(3),
                caseId);
        StrategyChanged compensating = new StrategyChanged(zone, ControlMode.COMPENSATING, 0.35,
                at(4), caseId);
        append(reading, residual, suspect, confirmed, degraded, compensating);

        List<DomainEvent> replayed = journal.replayCase(caseId);

        assertEquals(List.of(reading, residual, suspect, confirmed, degraded, compensating),
                replayed,
                "R-5 is the whole diagnosis in time order: what was measured, what was decided "
                        + "and what was done about it");
        FaultStateChanged rebuilt = (FaultStateChanged) replayed.get(3);
        assertEquals(evidence, rebuilt.evidence(),
                "the evidence is the answer to \"how do you know?\", and it crosses the wire as a "
                        + "jsonb cast that no in-memory test exercises");
        assertEquals(0.35, ((StrategyChanged) replayed.get(5)).trimmedPosition(), 1e-12);
    }

    @Test
    @DisplayName("R-5: the replay reaches an hour back from the first decision, and no further")
    void shouldGatherEvidenceFromTheHourBeforeTheCaseOpenedAndNoMore() {
        ZoneId zone = newZone();
        CaseId caseId = newCase();
        ReadingObserved tooEarly = new ReadingObserved(zone, Ppm.of(700), at(-61), Quality.GOOD);
        ReadingObserved onTheBoundary = new ReadingObserved(zone, Ppm.of(800), at(-60), Quality.GOOD);
        ReadingObserved insideTheWindow = new ReadingObserved(zone, Ppm.of(900), at(-59), Quality.GOOD);
        ReadingObserved afterTheCaseClosed = new ReadingObserved(zone, Ppm.of(1000), at(6),
                Quality.GOOD);
        FaultStateChanged opened = new FaultStateChanged(zone, MonitorState.SUSPECT,
                FaultClassification.NONE, List.of("opened"), at(0), caseId);
        FaultStateChanged closed = new FaultStateChanged(zone, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER, List.of("confirmed"), at(5), caseId);
        append(tooEarly, onTheBoundary, insideTheWindow, afterTheCaseClosed, opened, closed);

        List<DomainEvent> replayed = journal.replayCase(caseId);

        assertTrue(replayed.contains(insideTheWindow),
                "the readings that raised the suspicion are all earlier than the first decision, "
                        + "so a replay that excluded them could not say why the case opened");
        assertTrue(replayed.contains(onTheBoundary),
                "BETWEEN is inclusive, so the instant exactly one hour back is in the window");
        assertEquals(List.of(), replayed.stream().filter(tooEarly::equals).toList(),
                "an hour is the stated bound and a minute past it is out, or the window is not a "
                        + "bound at all");
        assertEquals(List.of(), replayed.stream().filter(afterTheCaseClosed::equals).toList(),
                "the window closes at the last decision; later readings belong to no case yet");
        assertEquals(EVIDENCE_LOOKBACK, Duration.between(onTheBoundary.at().value(),
                opened.at().value()), "this test's arithmetic has to match the adapter's bound");
    }

    @Test
    @DisplayName("R-5: a case the journal has never seen replays as nothing, not as an error")
    void shouldReplayAnUnknownCaseAsAnEmptyTimeline() {
        assertEquals(List.of(), journal.replayCase(newCase()));
    }

    @Test
    @DisplayName("the three nullable columns mean no command, no case and no trim, not a default")
    void shouldStoreTheAbsenceOfACommandACaseAndATrim() {
        ZoneId zone = newZone();
        CaseId caseId = newCase();
        ResidualComputed unattributed = new ResidualComputed(zone, Residual.of(7.25),
                CusumStatistic.RESET, at(-1), null);
        FaultStateChanged caseless = new FaultStateChanged(zone, MonitorState.HEALTHY,
                FaultClassification.NONE, List.of(), at(0), null);
        FaultStateChanged opening = new FaultStateChanged(zone, MonitorState.SUSPECT,
                FaultClassification.NONE, List.of("opened"), at(1), caseId);
        StrategyChanged untrimmed = new StrategyChanged(zone, ControlMode.NORMAL, null, at(2),
                caseId);
        append(unattributed, caseless, opening, untrimmed);

        List<DomainEvent> zoneEvents = journal.replayZone(zone, at(-2).value(), at(3).value());
        List<DomainEvent> caseEvents = journal.replayCase(caseId);

        assertEquals(List.of(unattributed), zoneEvents);
        assertNull(((ResidualComputed) zoneEvents.getFirst()).commandId(),
                "a residual recorded before any command must not come back attributed to one");
        assertEquals(List.of(unattributed, opening, untrimmed), caseEvents,
                "a decision with no case id belongs to no case and must not join this one");
        assertNull(((StrategyChanged) caseEvents.get(2)).trimmedPosition(),
                "an untrimmed zone is a null trim, not a zero one — zero would close the damper");
    }

    @Test
    @DisplayName("measured: the journal keeps an observation instant to the microsecond, not finer")
    void shouldKeepAnObservationInstantToTheColumnsResolution() {
        ZoneId zone = newZone();
        Instant nanosecondPrecision = BASE.plusNanos(123_456_789);
        ReadingObserved written = new ReadingObserved(zone, Ppm.of(640.0),
                ObservedAt.of(nanosecondPrecision), Quality.GOOD);
        append(written);

        List<DomainEvent> replayed = journal.replayZone(zone,
                nanosecondPrecision.minusSeconds(1), nanosecondPrecision.plusSeconds(1));

        // Measured, not fixed and not tuned: timestamptz holds microseconds, and the round trip
        // does not truncate the three digits below that — it ROUNDS them, so .123456789 comes back
        // as .123457 and the replayed record is not equal to the published one. It bounds how
        // faithful R-5's reconstruction is, and the bound is half a microsecond. Every instant the
        // system actually journals comes from a clock ticking in whole simulated seconds, so
        // nothing in the deployment reaches it.
        assertEquals(1, replayed.size());
        assertNotEquals(written, replayed.getFirst(),
                "if this ever passes, the column gained resolution and the comment is stale");
        assertEquals(BASE.plusNanos(123_457_000), replayed.getFirst().at().value(),
                "rounded to the nearest microsecond, not truncated towards it");
    }

    @Test
    @DisplayName("D-10: a service report is not journalled, so it writes to no table at all")
    void shouldWriteNothingForAServiceReport() {
        ZoneId zone = newZone();
        CaseId caseId = newCase();

        journal.append(new ServiceReported(zone, caseId, at(0), "damper actuator replaced"), source);

        assertEquals(List.of(), journal.replayZone(zone, at(-1).value(), at(1).value()));
        assertEquals(List.of(), journal.replayCase(caseId));
    }

    // ----- fixture ---------------------------------------------------------------------------

    private static void append(DomainEvent... events) {
        for (DomainEvent event : events) {
            journal.append(event, source);
        }
    }

    private static ObservedAt at(int minutesFromBase) {
        return ObservedAt.of(BASE.plus(Duration.ofMinutes(minutesFromBase)));
    }

    /** A zone no topology contains, so a live stack's rows cannot answer this test's queries. */
    private static ZoneId newZone() {
        return ZoneId.of("R5-" + newId());
    }

    private static CaseId newCase() {
        return CaseId.of("r5-case-" + newId());
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static long count(String sql) {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("could not count rows: " + sql, e);
        }
    }

    private static void deleteRowsOfThisRun() {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            for (String table : TABLES) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE source = ?")) {
                    statement.setString(1, source);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "the test's rows are still in the journal under source " + source, e);
        }
    }

    private static boolean reachable() {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                Statement statement = connection.createStatement()) {
            // The schema and not just the port: Postgres accepts TCP connections before its
            // entrypoint has finished running init.sql on a container's first boot.
            statement.executeQuery("SELECT 1 FROM decisions LIMIT 1").close();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean startCompose() {
        String existing = compose("ps", "-q", "timescaledb");
        if (compose("up", "-d", "timescaledb") == null) {
            return false;
        }
        // Ownership, not success. Compose derives one project name per checkout and the sibling
        // worktrees share the compose file, so a container that was already there belongs to
        // somebody else's stack — waiting for it is fine, and removing it afterwards would take a
        // running demo's journal with it. `rm` only ever removes a container this run created.
        startedHere = existing != null && existing.isBlank();
        Instant deadline = Instant.now().plus(STARTUP_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            if (reachable()) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Runs one compose command against the project's own file and returns what it printed, or
     * {@code null} when docker is not there or the command failed.
     */
    private static String compose(String... arguments) {
        Path root = projectRoot();
        if (root == null) {
            return null;
        }
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-f",
                root.resolve("docker-compose.yml").toString()));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(STARTUP_BUDGET.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                System.out.println("docker compose " + String.join(" ", arguments) + " failed: "
                        + output);
                return null;
            }
            return output;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** Surefire runs in the module directory, so the compose file is one or more levels up. */
    private static Path projectRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("docker-compose.yml"))) {
            directory = directory.getParent();
        }
        return directory;
    }
}
