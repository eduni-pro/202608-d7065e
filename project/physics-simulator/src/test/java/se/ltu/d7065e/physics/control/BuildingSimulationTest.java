package se.ltu.d7065e.physics.control;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.physics.entity.PhysicsSnapshot;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** T-07: the CO2 balance, the shared air handler's flow split, and the coupling between rooms. */
class BuildingSimulationTest {

    private static final Duration TICK = Duration.ofSeconds(1);
    private static final Instant MONDAY_0800 =
            LocalDateTime.of(2026, 10, 5, 8, 0).toInstant(ZoneOffset.UTC);
    private static final Instant SATURDAY_0800 =
            LocalDateTime.of(2026, 10, 10, 8, 0).toInstant(ZoneOffset.UTC);

    @Test
    @DisplayName("T-07: a step of ten occupants raises CO2 monotonically within one simulated hour")
    void shouldRaiseCo2MonotonicallyWhenTenOccupantsArrive() throws InterruptedException {
        BuildingSimulation simulation = withOccupants(Topology.A115, 10);

        List<Double> series = trueCo2Series(simulation, Topology.A115, Duration.ofHours(1));

        assertTrue(isMonotonicallyRising(series),
                "true CO2 must rise on every tick while the room is occupied and the damper still");
        assertTrue(series.getLast() > 900,
                "ten people in A115 at the 15%% minimum position approach ~990 ppm, was: "
                        + series.getLast());
    }

    @Test
    @DisplayName("T-07: a damper step reverses the occupant-driven rise")
    void shouldReverseCo2RiseWhenDamperOpens() throws InterruptedException {
        BuildingSimulation simulation = withOccupants(Topology.A115, 10);
        double afterRise = trueCo2Series(simulation, Topology.A115, Duration.ofHours(1)).getLast();

        simulation.commandDamper(Topology.A115, DamperPosition.OPEN);
        List<Double> afterStep =
                trueCo2Series(simulation, Topology.A115, Duration.ofMinutes(30));

        assertTrue(afterStep.getFirst() < afterRise, "the first tick after the step already falls");
        assertTrue(isMonotonicallyFalling(afterStep), "and it keeps falling towards the new steady state");
        assertTrue(afterStep.getLast() < afterRise - 200,
                "opening from 15%% to 100%% must pull A115 well down, was: " + afterStep.getLast());
    }

    @Test
    @DisplayName("T-07: the flow split conserves air, so the six room flows sum to the fan flow")
    void shouldSplitExactlyTheFlowTheFanDelivers() throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();
        simulation.commandDamper(Topology.A145, DamperPosition.OPEN);
        simulation.commandDamper(Topology.A125, DamperPosition.of(0.6));

        PhysicsSnapshot snapshot = simulation.advance();

        assertEquals(snapshot.fanFlow().cubicMetresPerSecond(),
                snapshot.totalRoomFlow().cubicMetresPerSecond(), 1e-12,
                "sum of Q_i must equal Q_fan");
        assertEquals(
                Topology.NOMINAL_FLOW.cubicMetresPerSecond() * Topology.NORMAL_FAN_SPEED.value(),
                snapshot.fanFlow().cubicMetresPerSecond(), 1e-12,
                "at the normal speed with no blockage the fan delivers Q_nom * s");

        simulation.commandFanSpeed(FanSpeed.NOMINAL);
        PhysicsSnapshot atNominal = simulation.advance();

        assertEquals(Topology.NOMINAL_FLOW.cubicMetresPerSecond(),
                atNominal.fanFlow().cubicMetresPerSecond(), 1e-12,
                "and driving the fan to nominal delivers Q_nom, which is the headroom T-16 spends");
        assertEquals(atNominal.fanFlow().cubicMetresPerSecond(),
                atNominal.totalRoomFlow().cubicMetresPerSecond(), 1e-12,
                "conservation must hold at any speed");
    }

    @Test
    @DisplayName("T-07, R-7: one damper opening takes flow from the other five rooms")
    void shouldCoupleTheSixRoomsThroughTheSharedAirHandler() throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();
        PhysicsSnapshot before = simulation.advance();

        simulation.commandDamper(Topology.A145, DamperPosition.OPEN);
        PhysicsSnapshot after = simulation.advance();

        assertTrue(flow(after, Topology.A145) > flow(before, Topology.A145),
                "the room that opened gains flow");
        assertAll(Topology.ZONE_IDS.stream()
                .filter(zone -> !zone.equals(Topology.A145))
                .map(zone -> () -> assertTrue(flow(after, zone) < flow(before, zone),
                        zone + " must lose flow when A145 opens: " + flow(before, zone)
                                + " -> " + flow(after, zone))));
    }

    @Test
    @DisplayName("T-07, T-15: an empty night decays every room to within 5 ppm of outdoor")
    void shouldDecayEveryRoomToOutdoorOverAnEmptyNight() throws InterruptedException {
        BuildingSimulation simulation =
                new BuildingSimulation(new SimulatedClock(MONDAY_0800), TICK, 42);

        PhysicsSnapshot atSixAm = advanceFor(simulation, Duration.ofHours(22));

        assertAll(Topology.ZONE_IDS.stream().map(zone -> () -> assertEquals(
                Topology.OUTDOOR.value(), atSixAm.trueCo2(zone).value(), 5.0,
                zone + " must have decayed to outdoor by 06:00 after an empty night")));
    }

    @Test
    @DisplayName("R-3: two runs on the same seed are identical, a different seed is not")
    void shouldReplayIdenticallyOnTheSameSeed() throws InterruptedException {
        List<Double> first = measuredSeries(42);
        List<Double> second = measuredSeries(42);

        assertEquals(first, second, "the same seed must replay tick for tick");
        assertNotEquals(first, measuredSeries(7), "a different seed must give a different run");
    }

    @Test
    @DisplayName("T-07: the schedules leave the building empty all weekend")
    void shouldLeaveEveryRoomEmptyOnASaturday() throws InterruptedException {
        BuildingSimulation simulation =
                new BuildingSimulation(new SimulatedClock(SATURDAY_0800), Duration.ofMinutes(5), 42);

        int occupantMinutes = 0;
        for (long tick = 0; tick < Duration.ofHours(12).toMinutes() / 5; tick++) {
            PhysicsSnapshot snapshot = simulation.advance();
            for (ZoneId zone : Topology.ZONE_IDS) {
                occupantMinutes += snapshot.occupancy(zone);
            }
        }

        assertEquals(0, occupantMinutes, "nobody works Saturday, so the night's decay continues");
    }

    private static BuildingSimulation emptyBuilding() {
        return new BuildingSimulation(
                new SimulatedClock(MONDAY_0800), TICK, 42, new FixedOccupancy());
    }

    private static BuildingSimulation withOccupants(ZoneId zone, int occupants) {
        return new BuildingSimulation(new SimulatedClock(MONDAY_0800), TICK, 42,
                new FixedOccupancy().with(zone, occupants));
    }

    private static List<Double> measuredSeries(long seed) throws InterruptedException {
        BuildingSimulation simulation =
                new BuildingSimulation(new SimulatedClock(MONDAY_0800), TICK, seed);
        List<Double> series = new ArrayList<>();
        for (long tick = 0; tick < Duration.ofHours(2).toSeconds(); tick++) {
            PhysicsSnapshot snapshot = simulation.advance();
            for (ZoneId zone : Topology.ZONE_IDS) {
                series.add(snapshot.measuredCo2(zone).value());
            }
        }
        return series;
    }

    static PhysicsSnapshot advanceFor(BuildingSimulation simulation, Duration duration)
            throws InterruptedException {
        PhysicsSnapshot snapshot = null;
        for (long tick = 0; tick < duration.toSeconds(); tick++) {
            snapshot = simulation.advance();
        }
        return snapshot;
    }

    private static List<Double> trueCo2Series(
            BuildingSimulation simulation, ZoneId zone, Duration duration)
            throws InterruptedException {
        List<Double> series = new ArrayList<>();
        for (long tick = 0; tick < duration.toSeconds(); tick++) {
            series.add(simulation.advance().trueCo2(zone).value());
        }
        return series;
    }

    private static boolean isMonotonicallyRising(List<Double> series) {
        for (int index = 1; index < series.size(); index++) {
            if (series.get(index) <= series.get(index - 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMonotonicallyFalling(List<Double> series) {
        for (int index = 1; index < series.size(); index++) {
            if (series.get(index) >= series.get(index - 1)) {
                return false;
            }
        }
        return true;
    }

    private static double flow(PhysicsSnapshot snapshot, ZoneId zone) {
        return snapshot.flow(zone).cubicMetresPerSecond();
    }
}
