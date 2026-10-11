package se.ltu.d7065e.shared.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.shared.event.FanPowerObserved;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.Topics;
import se.ltu.d7065e.ventilation.entity.AhuId;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-36: one observation driven through all three processes — a real broker, a real
 * {@code fault-monitor} container and a real PostgreSQL — and asserted where it lands.
 *
 * <p>{@link TimescaleJournalIntegrationTest} already crosses a process boundary, so the gap this
 * closes is not "nothing is integration tested" but a narrower and more specific one: <b>the MQTT
 * hop had never been driven by a test</b>. Everything between publishing a payload and a row
 * existing was covered only in pieces — {@link EventCodec} by in-JVM round trips,
 * {@link MqttAdapter#matches} by string assertions, the SQL by the journal tests — and the one
 * claim that matters operationally, that a message put on the bus by one process is decoded,
 * routed and journalled by <em>another process that is actually running</em>, was carried by the
 * demo transcript alone. A transcript is not a test. Here the publish is ours, the subscriber is
 * the deployed container, the insert is its own, and the assertion is a row.
 *
 * <h2>The identifier this test injects under, and why it is not a zone</h2>
 *
 * <p>The obvious shape — inject a CO₂ reading for one of the six rooms — is rejected because it is
 * not free. {@code FaultMonitor.accept} hands a {@link ReadingObserved} to that room's
 * {@code ZoneMonitor}, which advances the observer and accumulates the CUSUM arms, so a fabricated
 * reading becomes part of the evidence the system is being judged on. A test that corrupts the
 * measurement it is meant to support is the wrong trade at any price.
 *
 * <p>A reading for a room outside {@code ZONE_COUNT} is <b>tolerated and not an error</b>:
 * {@code accept} looks the room up with {@code monitors.get(reading.zone())} and does nothing at
 * all when that is null. But the same guard also skips {@code journal.append}, so an invented
 * <em>zone</em> leaves no row — it is safe and simultaneously <b>unobservable</b>, which would
 * give a test that cannot tell a working pipeline from a broken one.
 *
 * <p>So this test injects under an invented <b>AHU id</b> instead. {@code FanPowerObserved} is
 * journalled unconditionally, keyed on an {@link AhuId} that nothing validates against the
 * topology, and it travels exactly the path in question: the same wildcard subscription
 * {@code readings/+/fan-power}, the same {@code EventCodec.decode}, the same {@code MonitorRuntime}
 * wiring and the same {@code TimescaleJournalAdapter.append} as the plant's own samples. It enters
 * no room's observer and no room's statistic.
 *
 * <p>The one piece of live state it touches is {@code FaultMonitor.observedFanPowerWatts}, a single
 * scalar the plant's next fan-power sample overwrites — once per second in the deployed stack. The
 * value published is the wattage the plant itself last journalled, so even the one cycle that could
 * read it reads a number the plant had just reported.
 *
 * <p>{@code commands/{ahu}/fan-speed} was available on the same terms and is deliberately not used:
 * it is AHU-keyed and journalled unconditionally too, but it also calls
 * {@code AhuFlowModel.fanSpeedBelieved}, which changes the believed fan speed for <em>every</em>
 * room until the supervisor next commands. That would put a fabricated number into all six
 * residuals, which is the pollution the paragraphs above are about.
 *
 * <h2>How to run it</h2>
 *
 * <p>It needs the deployed stack: {@code make up}, then {@code mvn -o test -pl shared}. Without a
 * broker, without the journal, or without a {@code fault-monitor} that has journalled recently,
 * every test here <b>skips</b> with the reason printed, so {@code mvn -o clean test} stays green on
 * a laptop with no Docker and starts nothing. Unlike the journal test there is no autostart path,
 * and there should not be: this test asserts on a running system, and a test that stood the system
 * up would be asserting on a system it had just configured itself.
 *
 * <h2>Why it is safe to run against a live stack</h2>
 *
 * <p>Every row it causes is keyed by an identifier generated per run, so its queries can only match
 * its own rows. Cleanup keys on those identifiers and never on {@code source}: unlike the journal
 * test, which writes under a source of its own, these rows are written by the running monitor and
 * carry {@code fault-monitor}, so a delete by source would take the live system's evidence with it.
 */
class MqttPipelineIntegrationTest {

    private static final String BROKER = System.getProperty("t36.broker.url", "tcp://localhost:1883");
    private static final String URL = System.getProperty(
            "t36.journal.url", "jdbc:postgresql://localhost:5432/ventilation");
    private static final String USER = System.getProperty("t36.journal.user", "ventilation");
    private static final String PASSWORD = System.getProperty("t36.journal.password", "ventilation");

    /** The source the running monitor stamps its rows with, restated so a change to it skips. */
    private static final String MONITOR_SOURCE = "fault-monitor";

    /**
     * How recently the monitor must have journalled for it to count as up. The deployed stack
     * samples fan power once a real second, so a minute is two orders of magnitude of slack and
     * still short enough that a container stopped a moment ago is reported as stopped.
     */
    private static final Duration MONITOR_FRESHNESS = Duration.ofSeconds(60);

    /** The hop is a broker dispatch and one insert; the budget is for a loaded host, not for it. */
    private static final Duration ARRIVAL_BUDGET = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private static MqttAdapter broker;
    private static String unavailable;
    private static double plantWatts;

    /**
     * Resolves broker, journal and a live monitor once without aborting, so {@link #requireStack()}
     * can skip each test individually with the reason attached. Aborting here instead would leave
     * surefire reporting {@code Tests run: 0} with the reason nowhere on the page, and a suite that
     * quietly does not test the MQTT hop is how the MQTT hop came to be untested in the first place.
     */
    @BeforeAll
    static void openStack() {
        Double watts = lastPlantWatts();
        if (watts == null) {
            return;
        }
        plantWatts = watts;
        try {
            broker = new MqttAdapter(BROKER, "t36-pipeline-test");
        } catch (RuntimeException e) {
            unavailable = ("no broker at %s (%s). Bring the stack up with `make up` and run this "
                    + "again.").formatted(BROKER, e.getMessage());
        }
    }

    @BeforeEach
    void requireStack() {
        Assumptions.assumeTrue(broker != null, () -> unavailable);
    }

    @AfterAll
    static void closeStack() {
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    @DisplayName("T-36: a reading published on the broker is journalled by the running monitor")
    void shouldLandAPublishedReadingInTheJournalThroughTheRunningMonitor() {
        AhuId ahu = newAhu();
        ObservedAt at = now();
        try {
            broker.publish(Topics.fanPower(ahu),
                    new FanPowerObserved(ahu, FanPower.ofWatts(plantWatts), at, Quality.GOOD));

            awaitFanPowerRow(ahu);
            JournalledFanPower row = readFanPower(ahu);

            assertEquals(plantWatts, row.watts(), 1e-9,
                    "the wattage has to survive encoding, the broker, decoding and the insert — "
                            + "three process boundaries, none of them exercised by a unit test");
            assertEquals(at.value(), row.observedAt(),
                    "the observation instant is the publisher's, not the subscriber's: a monitor "
                            + "that restamped what it received would make R-5 a reconstruction of "
                            + "when the journal heard rather than of when the plant was measured");
            assertEquals(MONITOR_SOURCE, row.source(),
                    "provenance (T-20) is the running monitor's, which is what makes this a test "
                            + "of the deployed process rather than of this JVM");
        } finally {
            deleteFanPowerRows(ahu);
        }
    }

    /**
     * The negative half of the design decision in this class's documentation, asserted rather than
     * assumed: a reading for a room the monitor does not watch is inert.
     *
     * <p>Absence is asserted behind a barrier instead of behind a sleep. {@code MqttClient.publish}
     * blocks until the broker has acknowledged the message at QoS 1, so the reading is at the
     * broker before the barrier is even sent; the broker forwards one client's messages in order
     * and Paho delivers them on one callback thread. The fan-power row therefore cannot exist until
     * the reading has already been handled, which makes the absence a measurement rather than a
     * guess about how long to wait.
     */
    @Test
    @DisplayName("T-36: a reading for a room outside the zone set is dropped, not journalled")
    void shouldLeaveNoTraceOfAReadingForARoomTheMonitorDoesNotWatch() {
        ZoneId absent = newZone();
        AhuId barrier = newAhu();
        try {
            broker.publish(Topics.co2Reading(absent),
                    new ReadingObserved(absent, Ppm.of(1480.0), now(), Quality.GOOD));
            broker.publish(Topics.fanPower(barrier),
                    new FanPowerObserved(barrier, FanPower.ofWatts(plantWatts), now(),
                            Quality.GOOD));

            awaitFanPowerRow(barrier);

            assertEquals(0, readingRowCount(absent),
                    "a room outside ZONE_COUNT has no observer to update and no history worth "
                            + "keeping, so the guard in FaultMonitor.accept has to skip the insert "
                            + "as well as the model — a journalled reading no room owns would be "
                            + "evidence for a zone the system does not run");
        } finally {
            deleteFanPowerRows(barrier);
            deleteReadingRows(absent);
        }
    }

    // ----- fixture ---------------------------------------------------------------------------

    /** An AHU no topology contains, so a live stack's rows cannot answer this test's queries. */
    private static AhuId newAhu() {
        return AhuId.of("AHU-T36-" + newId());
    }

    private static ZoneId newZone() {
        return ZoneId.of("T36-" + newId());
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Whole seconds, because {@code timestamptz} rounds anything finer than a microsecond. */
    private static ObservedAt now() {
        return ObservedAt.of(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private static void awaitFanPowerRow(AhuId ahu) {
        Instant deadline = Instant.now().plus(ARRIVAL_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            if (fanPowerRowCount(ahu) == 1) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + ahu + " to reach the journal");
            }
        }
        fail(("published a fan-power observation for %s and no row reached the journal within %ds. "
                + "The message left this JVM, so either the running monitor is not subscribed to "
                + "%s or it is not journalling to %s.")
                .formatted(ahu, ARRIVAL_BUDGET.toSeconds(), Topics.ALL_FAN_POWER, URL));
    }

    private static long fanPowerRowCount(AhuId ahu) {
        return count("SELECT count(*) FROM fan_power WHERE ahu = ?", ahu.value());
    }

    private static long readingRowCount(ZoneId zone) {
        return count("SELECT count(*) FROM readings WHERE zone = ?", zone.value());
    }

    /** What the running monitor wrote, read back in one statement. */
    private record JournalledFanPower(double watts, Instant observedAt, String source) {}

    private static JournalledFanPower readFanPower(AhuId ahu) {
        String sql = "SELECT watts, observed_at, source FROM fan_power WHERE ahu = ?";
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ahu.value());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new JournalledFanPower(rs.getDouble(1),
                        rs.getTimestamp(2).toInstant(), rs.getString(3));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the journalled row for " + ahu, e);
        }
    }

    private static void deleteFanPowerRows(AhuId ahu) {
        update("DELETE FROM fan_power WHERE ahu = ?", ahu.value());
    }

    private static void deleteReadingRows(ZoneId zone) {
        update("DELETE FROM readings WHERE zone = ?", zone.value());
    }

    /**
     * The newest wattage the running monitor journalled, or {@code null} with {@link #unavailable}
     * set to the reason.
     *
     * <p>One query answers three preconditions at once, which is why it is this query and not a
     * port check: a fresh row under the monitor's own source proves the monitor process is up, that
     * it is receiving from the broker, and that it is writing to the database this test reads. A
     * container that is merely running and deaf would pass a port check and fail this one. It also
     * yields the value the tests publish, so the scalar they disturb is set to the plant's own.
     */
    private static Double lastPlantWatts() {
        String sql = """
                SELECT watts FROM fan_power
                WHERE source = ? AND received_at > now() - ?::interval
                ORDER BY received_at DESC LIMIT 1
                """;
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, MONITOR_SOURCE);
            statement.setString(2, MONITOR_FRESHNESS.toSeconds() + " seconds");
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            }
        } catch (SQLException e) {
            unavailable = ("no journal at %s (%s). Bring the stack up with `make up` and run this "
                    + "again.").formatted(URL, e.getMessage());
            return null;
        }
        unavailable = ("the journal at %s is up but no %s has written to it in the last %ds, so "
                + "there is no running monitor for a published message to reach. Bring the stack "
                + "up with `make up` and run this again.")
                .formatted(URL, MONITOR_SOURCE, MONITOR_FRESHNESS.toSeconds());
        return null;
    }

    private static long count(String sql, String parameter) {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the journal: " + sql, e);
        }
    }

    private static void update(String sql, String parameter) {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "this test's rows are still in the journal under " + parameter, e);
        }
    }
}
