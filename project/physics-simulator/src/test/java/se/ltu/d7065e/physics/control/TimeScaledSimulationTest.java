package se.ltu.d7065e.physics.control;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.ltu.d7065e.shared.boundary.ScaledClock;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.physics.entity.PhysicsSnapshot;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * {@code TIME_SCALE}: running the building faster than the wall clock so the headline scenario
 * fits a 15-minute oral slot, without the physics or the fault schedule noticing.
 *
 * <p>These tests pin the one property the whole demo profile rests on: a tick that spans sixty
 * simulated seconds is sixty one-second ticks and nothing else. If that stopped holding, every
 * number read off the demo would be a number about the demo rather than about the system.
 */
class TimeScaledSimulationTest {

    private static final Instant FIRST_TICK =
            LocalDateTime.of(2026, 10, 9, 7, 45).toInstant(ZoneOffset.UTC);
    private static final Instant REAL_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final long SEED = 42;

    @Test
    @DisplayName("TIME_SCALE=1 leaves the run exactly as it was: tick for tick, ppm for ppm")
    void shouldLeaveTheRunUnchangedAtScaleOne() throws InterruptedException {
        BuildingSimulation unscaled =
                new BuildingSimulation(new SimulatedClock(FIRST_TICK.minus(SECOND)), SECOND, SEED);
        BuildingSimulation scaled = new BuildingSimulation(
                new ScaledClock(new SimulatedClock(REAL_START), FIRST_TICK, SECOND, 1), SECOND, SEED);

        List<String> before = run(unscaled, 1800);
        List<String> after = run(scaled, 1800);

        assertEquals(before, after,
                "half a simulated hour of six rooms must replay identically at TIME_SCALE=1");
    }

    @Test
    @DisplayName("TIME_SCALE=60: one advance covers 60 s of simulated time")
    void shouldAdvanceSimulatedTimeBySixtySecondsPerTick() throws InterruptedException {
        BuildingSimulation simulation = new BuildingSimulation(
                new ScaledClock(new SimulatedClock(REAL_START), FIRST_TICK, minute(), 60),
                minute(), SEED);

        PhysicsSnapshot first = simulation.advance();
        PhysicsSnapshot second = simulation.advance();

        assertAll(
                () -> assertEquals(FIRST_TICK, first.at(), "START_AT places the first tick"),
                () -> assertEquals(FIRST_TICK.plusSeconds(60), second.at(),
                        "and each tick carries 60 s of the building's day"));
    }

    @Test
    @DisplayName("TIME_SCALE=60: a 60 s tick is sixty one-second Euler steps, to 1e-9 ppm")
    void shouldIntegrateASixtySecondTickAsSixtyOneSecondSteps() throws InterruptedException {
        // A known occupant step rather than the schedules, so the comparison is of integration
        // alone: ten people in the meeting room with its damper at the 15 % minimum.
        // Both clocks sit on FIRST_TICK, so both runs integrate forward from the same instant and
        // only the step length differs.
        BuildingSimulation perSecond = new BuildingSimulation(
                new SimulatedClock(FIRST_TICK), SECOND, SEED, tenInTheMeetingRoom());
        BuildingSimulation perMinute = new BuildingSimulation(
                new SimulatedClock(FIRST_TICK), minute(), SEED, tenInTheMeetingRoom());

        // Half a simulated hour each: 1800 one-second ticks against 30 sixty-second ticks.
        PhysicsSnapshot bySecond = advanceTicks(perSecond, 1800);
        PhysicsSnapshot byMinute = advanceTicks(perMinute, 30);

        assertEquals(bySecond.at(), byMinute.at(), "both runs must have reached the same instant");

        assertAll(Topology.ZONE_IDS.stream().map(zone -> () -> assertEquals(
                bySecond.trueCo2(zone).value(), byMinute.trueCo2(zone).value(), 1e-9,
                zone + " must reach the same true CO2 whichever tick length got it there")));
        assertTrue(bySecond.trueCo2(Topology.A115).value() > 700,
                "and the comparison must be made somewhere the room is actually moving, was: "
                        + bySecond.trueCo2(Topology.A115).value());
    }

    @Test
    @DisplayName("REAL_EPOCH: a container joining late integrates the lead-in instead of skipping it")
    void shouldIntegrateTheSimulatedTimeThatPassedBeforeItsFirstTick() throws InterruptedException {
        // Two containers on one shared timeline: one starts at the epoch, one ten real seconds
        // later, which at 60x is ten simulated minutes of the building's day. The plant's state at
        // a given simulated instant may not depend on which of the two is running it, or START_AT
        // would mean something different in every demo and the fault onsets aimed at it with it.
        SimulatedClock onTime = new SimulatedClock(REAL_START);
        BuildingSimulation fromTheEpoch = new BuildingSimulation(
                new ScaledClock(onTime, FIRST_TICK.minus(minute()), REAL_START, 60),
                minute(), SEED, tenInTheMeetingRoom());

        SimulatedClock late = new SimulatedClock(REAL_START);
        BuildingSimulation joiningLate = new BuildingSimulation(
                new ScaledClock(late, FIRST_TICK.minus(minute()), REAL_START, 60),
                minute(), SEED, tenInTheMeetingRoom());
        late.advance(Duration.ofSeconds(10));

        // Half a simulated hour of shared timeline each. The late one spends its first ten ticks
        // catching up — the first carries the whole ten-minute backlog, the next nine find the
        // clock has not moved again — and only then starts pacing, so both end on the same tick.
        PhysicsSnapshot onTimeEnd = advanceTicks(fromTheEpoch, 30);
        PhysicsSnapshot lateEnd = advanceTicks(joiningLate, 30);

        assertAll(
                () -> assertEquals(onTimeEnd.at(), lateEnd.at(),
                        "both must have reached the same simulated instant"),
                () -> assertEquals(onTimeEnd.trueCo2(Topology.A115).value(),
                        lateEnd.trueCo2(Topology.A115).value(), 1e-9,
                        "and the same true CO2, so the ten minutes before the late container's "
                                + "first tick were integrated rather than jumped over"),
                () -> assertTrue(onTimeEnd.trueCo2(Topology.A115).value() > 700,
                        "measured somewhere the room is actually moving, was: "
                                + onTimeEnd.trueCo2(Topology.A115).value()));
    }

    @ParameterizedTest(name = "TIME_SCALE={0}")
    @ValueSource(longs = {1, 60})
    @DisplayName("a fault onset in simulated seconds bites at the same simulated instant at any scale")
    void shouldBiteAtTheSameSimulatedInstantWhateverTheScale(long timeScale)
            throws InterruptedException {
        Duration tick = SECOND.multipliedBy(timeScale);
        Instant onset = FIRST_TICK.plusSeconds(1830);
        BuildingSimulation simulation = new BuildingSimulation(
                new ScaledClock(new SimulatedClock(REAL_START), FIRST_TICK, tick, timeScale),
                tick, SEED, tenInTheMeetingRoom());
        simulation.inject(
                InjectedFault.stuckDamper(Topology.A115, Topology.MINIMUM_POSITION, onset));
        simulation.commandDamper(Topology.A115, DamperPosition.OPEN);

        PhysicsSnapshot firstBite = null;
        PhysicsSnapshot lastClean = null;
        for (int tickIndex = 0; tickIndex < 2400 / timeScale; tickIndex++) {
            PhysicsSnapshot snapshot = simulation.advance();
            if (!snapshot.activeFaults().isEmpty()) {
                firstBite = firstBite == null ? snapshot : firstBite;
            } else {
                lastClean = snapshot;
            }
        }

        PhysicsSnapshot bite = firstBite;
        PhysicsSnapshot clean = lastClean;
        assertAll(
                () -> assertEquals(firstTickAtOrAfter(onset, tick), bite.at(),
                        "the fault must first be reported on the first tick landing on or after "
                                + "its simulated onset"),
                () -> assertTrue(clean.at().isBefore(onset),
                        "and nothing may be reported active before the onset, was: " + clean.at()),
                () -> assertEquals(DamperPosition.OPEN.value(),
                        clean.appliedPosition(Topology.A115).value(), 1e-12,
                        "before the onset the damper obeys the command to open"),
                () -> assertEquals(Topology.MINIMUM_POSITION.value(),
                        bite.appliedPosition(Topology.A115).value(), 1e-12,
                        "and from the onset it holds its frozen position against that command"));
    }

    @ParameterizedTest(name = "TIME_SCALE={0}")
    @ValueSource(longs = {1, 60})
    @DisplayName("START_AT puts the first tick on the requested instant, and the schedules follow it")
    void shouldStartTheSimulatedDayAtTheRequestedInstant(long timeScale) throws InterruptedException {
        Duration tick = SECOND.multipliedBy(timeScale);
        BuildingSimulation simulation = new BuildingSimulation(
                new ScaledClock(new SimulatedClock(REAL_START), FIRST_TICK, tick, timeScale),
                tick, SEED);

        PhysicsSnapshot first = simulation.advance();

        assertAll(
                () -> assertEquals(FIRST_TICK, first.at(),
                        "the first tick is the configured instant, not the host's clock"),
                () -> assertEquals(0, first.occupancy(Topology.A115),
                        "A115 is empty at 07:45 on the Friday the demo starts from"),
                () -> assertEquals(Topology.OUTDOOR.value(), first.trueCo2(Topology.A115).value(),
                        1e-9, "and every room starts at outdoor concentration"));
    }

    @Test
    @DisplayName("START_AT 07:45 on the demo's Friday reaches the 08:00 arrivals in 15 simulated min")
    void shouldReachTheMorningArrivalsFromTheConfiguredStart() throws InterruptedException {
        BuildingSimulation simulation = new BuildingSimulation(
                new ScaledClock(new SimulatedClock(REAL_START), FIRST_TICK, minute(), 60),
                minute(), SEED);

        int firstOccupiedMinute = -1;
        for (int minute = 0; minute < 30 && firstOccupiedMinute < 0; minute++) {
            if (simulation.advance().occupancy(Topology.A115) > 0) {
                firstOccupiedMinute = minute;
            }
        }

        assertEquals(15, firstOccupiedMinute,
                "the meeting room's 08:00 booking is 15 simulated minutes after START_AT, which is "
                        + "15 real seconds of a 60x demo");
    }

    private static Duration minute() {
        return Duration.ofMinutes(1);
    }

    private static PhysicsSnapshot advanceTicks(BuildingSimulation simulation, int ticks)
            throws InterruptedException {
        PhysicsSnapshot snapshot = simulation.advance();
        for (int tick = 1; tick < ticks; tick++) {
            snapshot = simulation.advance();
        }
        return snapshot;
    }

    private static Instant firstTickAtOrAfter(Instant onset, Duration tick) {
        long sinceStart = Duration.between(FIRST_TICK, onset).toSeconds();
        long ticks = (sinceStart + tick.toSeconds() - 1) / tick.toSeconds();
        return FIRST_TICK.plus(tick.multipliedBy(ticks));
    }

    private static OccupancySource tenInTheMeetingRoom() {
        return new FixedOccupancy().with(Topology.A115, 10);
    }

    /** Every room's true and measured value per tick, as text, so a mismatch names the tick. */
    private static List<String> run(BuildingSimulation simulation, int ticks)
            throws InterruptedException {
        List<String> series = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            PhysicsSnapshot snapshot = simulation.advance();
            StringBuilder row = new StringBuilder(snapshot.at().toString());
            Topology.ZONE_IDS.forEach(zone -> row.append(" %.9f/%.9f".formatted(
                    snapshot.trueCo2(zone).value(), snapshot.measuredCo2(zone).value())));
            series.add(row.toString());
        }
        return series;
    }
}
