package se.ltu.d7065e.ventilation.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The inner loop, driven directly: no broker, no database, no wall clock.
 *
 * <p>Every test names what it verifies, which for most of this class is <strong>T-09</strong> —
 * "dcv-controller with hysteresis and a freshness check" — and not a requirement. R-1 is a
 * confirmation delay and R-2 is zone CO2 over occupied minutes: both are whole-system claims
 * that the monitor and the supervisor prove over scenarios in the evaluation harness, and no
 * assertion about a commanded damper position can produce evidence for either. Naming them here
 * would put a claim in the report's requirements table that this file does not support. R-4
 * (staleness) and R-7 (per-room independence) are named where they are genuinely what is
 * asserted.
 */
class DcvControlLoopTest {

    private static final ZoneId ROOM = Topology.A115;
    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final double OPENED_AT_900 = 0.575;

    private TestClock clock;
    private DcvControlLoop loop;

    @BeforeEach
    void setUp() {
        clock = new TestClock(Instant.parse("2026-10-05T08:00:00Z"));
        loop = new DcvControlLoop(ControlSettings.standard(), clock);
    }

    /** T-09: crossing the open threshold moves that one room to the ramp's position. */
    @Test
    void shouldOpenDamperWhenCo2RisesPastOpenThreshold() {
        assertEquals(Topology.MINIMUM_POSITION, loop.commandedPosition(ROOM));

        List<CommandIssued> commands = tickOn(900);

        assertEquals(1, commands.size(), "one room moved, so one command");
        CommandIssued command = commands.getFirst();
        assertEquals(ROOM, command.zone());
        assertEquals(OPENED_AT_900, command.position().value(), 1e-9);
        assertEquals(OPENED_AT_900, loop.commandedPosition(ROOM).value(), 1e-9);
        assertTrue(command.reason().contains("above the open threshold"), command.reason());
    }

    /** T-09: and falling back below the close threshold throttles that room to its minimum. */
    @Test
    void shouldThrottleDamperBackWhenCo2FallsBelowCloseThreshold() {
        tickOn(900);

        List<CommandIssued> commands = tickOn(600);

        assertEquals(1, commands.size());
        assertEquals(Topology.MINIMUM_POSITION, commands.getFirst().position());
        assertTrue(commands.getFirst().reason().contains("below the close threshold"));
    }

    /**
     * T-09's hysteresis band: no command while CO2 moves inside it, and the position holds
     * throughout. The band is what stops the damper chattering, and the monitor keys its
     * prediction on command steps, so a damper hunting around one threshold would bury the step
     * in noise. Nothing here measures a confirmation delay, which is what R-1 asks for.
     */
    @Test
    void shouldIssueNoCommandWhileCo2OscillatesInsideTheHysteresisBand() {
        tickOn(900);

        for (double ppm : new double[] {790, 660, 755, 651, 799, 700}) {
            assertEquals(List.of(), tickOn(ppm), "%.0f ppm is inside the band".formatted(ppm));
        }
        assertEquals(OPENED_AT_900, loop.commandedPosition(ROOM).value(), 1e-9,
                "the position held throughout");
    }

    /** T-09's deadband: a position change this small is not worth a command, a larger one is. */
    @Test
    void shouldIssueNoCommandWhenTheChangeIsInsideTheDeadband() {
        tickOn(900);

        assertEquals(List.of(), tickOn(903), "903 ppm asks for 0.588, a 0.013 move");
        assertEquals(OPENED_AT_900, loop.commandedPosition(ROOM).value(), 1e-9);

        List<CommandIssued> commands = tickOn(910);

        assertEquals(1, commands.size(), "910 ppm asks for 0.618, a 0.043 move");
        assertEquals(0.6175, commands.getFirst().position().value(), 1e-9);
    }

    /**
     * T-09: a degraded room is not commanded by the inner loop at all, however high its CO2.
     * The supervisor owns that damper while the fault is latched, and both its compensation
     * (R-2) and the isolation of the other five (R-7) come undone if a controller command slips
     * through — but what is asserted here is only the loop's silence, neither outcome.
     */
    @Test
    void shouldNotCommandDegradedZoneWhileCo2IsHigh() {
        tickOn(900);
        degrade(ROOM);

        for (int tick = 0; tick < 5; tick++) {
            assertEquals(List.of(), tickOn(1500), "a degraded room is not commanded");
        }
        assertEquals(ControlMode.DEGRADED, loop.mode(ROOM));
        assertEquals(OPENED_AT_900, loop.commandedPosition(ROOM).value(), 1e-9,
                "the supervisor owns this damper now; the loop left it alone");
    }

    /** R-7: degrading one room must not stop the other five being controlled. */
    @Test
    void shouldKeepControllingTheOtherFiveRoomsWhileOneIsDegraded() {
        degrade(ROOM);
        for (ZoneId room : Topology.ZONE_IDS) {
            observe(room, 900);
        }

        List<CommandIssued> commands = loop.tick();

        assertEquals(5, commands.size(), "five healthy rooms, five commands");
        assertTrue(commands.stream().noneMatch(command -> command.zone().equals(ROOM)));
        for (ZoneId room : Topology.ZONE_IDS) {
            double expected = room.equals(ROOM)
                    ? Topology.MINIMUM_POSITION.value() : OPENED_AT_900;
            assertEquals(expected, loop.commandedPosition(room).value(), 1e-9, room.value());
        }
    }

    /** T-09: once the supervisor hands the room back, normal control resumes on the next tick. */
    @Test
    void shouldResumeControlWhenTheSupervisorReturnsTheZoneToNormal() {
        degrade(ROOM);
        tickOn(1500);

        loop.applyStrategy(new StrategyChanged(
                ROOM, ControlMode.NORMAL, null, ObservedAt.of(clock.now()), null));
        List<CommandIssued> commands = tickOn(1500);

        assertEquals(1, commands.size());
        assertEquals(DamperPosition.OPEN, commands.getFirst().position());
    }

    /**
     * T-09: a compensating room is driven to the supervisor's trim once and then left there. It
     * has been trimmed to push flow to a faulted neighbour, so its own CO2 gets no vote until
     * the strategy changes back — undoing the trim would take the flow straight back out of the
     * room the compensation exists to serve. Whether that compensation holds the faulted room
     * under 1000 ppm is R-2's claim, and the harness measures it end to end.
     */
    @Test
    void shouldNotUndoSupervisoryTrimOnCompensatingZone() {
        DamperPosition trim = DamperPosition.of(0.40);
        loop.applyStrategy(new StrategyChanged(ROOM, ControlMode.COMPENSATING, trim.value(),
                ObservedAt.of(clock.now()), CaseId.newId()));

        List<CommandIssued> commands = tickOn(1500);

        assertEquals(1, commands.size(), "the loop drives the damper to the trim once");
        assertEquals(trim, commands.getFirst().position(), "and to the trim, not to fully open");
        assertEquals(ZoneControl.TRIM_HONOURED, commands.getFirst().reason());

        for (int tick = 0; tick < 5; tick++) {
            assertEquals(List.of(), tickOn(1500), "and then leaves it there");
        }
        assertEquals(trim, loop.commandedPosition(ROOM));
    }

    /**
     * R-4: three sample intervals without a reading and the controller stops acting. It is not
     * the monitor's BLIND state — no event is raised and no verdict is reached — it is only a
     * refusal to compute a position from data the loop does not trust.
     */
    @Test
    void shouldNotCommandOnStaleReading() {
        observe(ROOM, 1500);
        clock.advance(Duration.ofSeconds(4));

        assertEquals(List.of(), loop.tick(), "1500 ppm would open fully, but the reading is stale");
        assertEquals(Topology.MINIMUM_POSITION, loop.commandedPosition(ROOM));
    }

    /** R-4: while readings are starved the last position stands, and a fresh one resumes control. */
    @Test
    void shouldHoldLastPositionWhileReadingsAreStale() {
        tickOn(900);

        clock.advance(Duration.ofSeconds(4));
        assertEquals(List.of(), loop.tick());
        clock.advance(Duration.ofMinutes(10));
        assertEquals(List.of(), loop.tick());
        assertEquals(OPENED_AT_900, loop.commandedPosition(ROOM).value(), 1e-9);
        assertEquals(0, loop.roomsWithFreshReadings());

        List<CommandIssued> commands = tickOn(1500);

        assertEquals(1, commands.size(), "a fresh reading puts the loop back to work");
        assertEquals(DamperPosition.OPEN, commands.getFirst().position());
    }

    /** R-4: a reading the sensor boundary marked unusable does not refresh the window either. */
    @Test
    void shouldNotCommandOnReadingWhoseQualityIsNotGood() {
        loop.observe(new ReadingObserved(
                ROOM, Ppm.of(1500), ObservedAt.of(clock.now()), Quality.STALE));

        assertEquals(List.of(), loop.tick());
        assertEquals(Topology.MINIMUM_POSITION, loop.commandedPosition(ROOM));
    }

    /** ADR-0008's floor: ventilation never shuts entirely — 0.15 holds on every path. */
    @Test
    void shouldNeverCommandBelowTheMinimumPosition() {
        tickOn(900);

        for (double ppm : new double[] {649, 500, 420, 0}) {
            tickOn(ppm);
            assertTrue(loop.commandedPosition(ROOM).value() >= Topology.MINIMUM_POSITION.value(),
                    "%.0f ppm drove the damper to %s".formatted(ppm, loop.commandedPosition(ROOM)));
        }
        assertEquals(Topology.MINIMUM_POSITION, loop.commandedPosition(ROOM));
    }

    /** T-09's verification: the loop closes for all six rooms, each on its own CO2. */
    @Test
    void shouldControlAllSixRoomsIndependently() {
        double[] ppm = {1000, 900, 850, 700, 600, 801};
        for (int i = 0; i < Topology.ZONE_IDS.size(); i++) {
            observe(Topology.ZONE_IDS.get(i), ppm[i]);
        }

        List<CommandIssued> commands = loop.tick();

        assertEquals(3, commands.size(), "1000, 900 and 850 ppm move; 801, 700 and 600 do not");
        assertEquals(1.0, loop.commandedPosition(Topology.ZONE_IDS.get(0)).value(), 1e-9);
        assertEquals(OPENED_AT_900, loop.commandedPosition(Topology.ZONE_IDS.get(1)).value(), 1e-9);
        assertEquals(0.3625, loop.commandedPosition(Topology.ZONE_IDS.get(2)).value(), 1e-9);
        assertEquals(6, loop.roomsWithFreshReadings());
    }

    private List<CommandIssued> tickOn(double ppm) {
        clock.advance(INTERVAL);
        observe(ROOM, ppm);
        return loop.tick();
    }

    private void observe(ZoneId room, double ppm) {
        loop.observe(new ReadingObserved(
                room, Ppm.of(ppm), ObservedAt.of(clock.now()), Quality.GOOD));
    }

    private void degrade(ZoneId room) {
        loop.applyStrategy(new StrategyChanged(
                room, ControlMode.DEGRADED, null, ObservedAt.of(clock.now()), CaseId.newId()));
    }
}
