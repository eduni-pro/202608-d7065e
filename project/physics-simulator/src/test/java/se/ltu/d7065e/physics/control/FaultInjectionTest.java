package se.ltu.d7065e.physics.control;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.physics.entity.FaultKind;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.physics.entity.PhysicsSnapshot;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** T-14: each fault injected between the command and its physical effect. */
class FaultInjectionTest {

    private static final Duration TICK = Duration.ofSeconds(1);
    private static final Instant START =
            LocalDateTime.of(2026, 10, 5, 8, 0).toInstant(ZoneOffset.UTC);

    @Test
    @DisplayName("T-14: a stuck damper leaves its room's flow unchanged when the command changes")
    void shouldIgnoreDamperCommandsWhileTheDamperIsStuck() throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();
        simulation.inject(
                InjectedFault.stuckDamper(Topology.A115, Topology.MINIMUM_POSITION, START));
        PhysicsSnapshot before = simulation.advance();

        simulation.commandDamper(Topology.A115, DamperPosition.OPEN);
        PhysicsSnapshot after = simulation.advance();

        assertEquals(Topology.MINIMUM_POSITION, after.appliedPosition(Topology.A115),
                "the damper holds its frozen position against the command");
        assertAll(Topology.ZONE_IDS.stream().map(zone -> () -> assertEquals(
                flow(before, zone), flow(after, zone), 1e-12,
                zone + " must see no flow change, because the stuck damper never moved")));
    }

    @Test
    @DisplayName("T-14: a damper can be frozen at any position, not only shut")
    void shouldFreezeAStuckDamperAtTheInjectedPosition() throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();
        PhysicsSnapshot atMinimum = simulation.advance();

        simulation.inject(InjectedFault.stuckDamper(Topology.A115, DamperPosition.of(0.8), START));
        PhysicsSnapshot stuckOpen = simulation.advance();

        assertEquals(0.8, stuckOpen.appliedPosition(Topology.A115).value(), 1e-12);
        assertTrue(flow(stuckOpen, Topology.A115) > flow(atMinimum, Topology.A115),
                "a damper stuck 80%% open passes more air than one at the 15%% minimum");
    }

    @Test
    @DisplayName("T-14, R-7: a stuck damper in one room leaves the other five on their commands")
    void shouldLeaveTheOtherRoomsOnTheirOwnCommandsWhileOneDamperIsStuck()
            throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();
        simulation.inject(
                InjectedFault.stuckDamper(Topology.A115, Topology.MINIMUM_POSITION, START));
        simulation.commandDamper(Topology.A115, DamperPosition.OPEN);
        simulation.commandDamper(Topology.A124, DamperPosition.of(0.5));

        PhysicsSnapshot snapshot = simulation.advance();

        assertEquals(0.5, snapshot.appliedPosition(Topology.A124).value(), 1e-12,
                "A124 obeys its own command");
        assertEquals(Topology.MINIMUM_POSITION.value(),
                snapshot.appliedPosition(Topology.A115).value(), 1e-12);
    }

    @Test
    @DisplayName("T-14: a blocked filter lowers the total flow and raises the fan power together")
    void shouldLowerFlowAndRaisePowerWhenTheFilterBlocks() throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();
        PhysicsSnapshot healthy = simulation.advance();

        simulation.inject(InjectedFault.blockedFilter(0.4, START));
        PhysicsSnapshot blocked = simulation.advance();

        assertAll(
                () -> assertTrue(
                        blocked.totalRoomFlow().cubicMetresPerSecond()
                                < healthy.totalRoomFlow().cubicMetresPerSecond(),
                        "less air: " + blocked.totalRoomFlow() + " against " + healthy.totalRoomFlow()),
                () -> assertTrue(blocked.fanPower().watts() > healthy.fanPower().watts(),
                        "and more power: " + blocked.fanPower() + " against " + healthy.fanPower()),
                () -> assertEquals(
                        Topology.NOMINAL_FLOW.cubicMetresPerSecond()
                                * Topology.NORMAL_FAN_SPEED.value() * 0.6,
                        blocked.fanFlow().cubicMetresPerSecond(), 1e-12,
                        "Q_fan = Q_nom * s * (1 - beta)"),
                // The published power is a measurement, so it carries noise: assert the law within
                // a few sigma rather than exactly. Without that noise the reading would never
                // change, the sensor would judge it stale, and the monitor would discard its only
                // cross-room signal before using it.
                () -> assertEquals(
                        Topology.NOMINAL_POWER_WATTS
                                * Math.pow(Topology.NORMAL_FAN_SPEED.value(), 3) * 1.4,
                        blocked.fanPower().watts(), 4 * Topology.FAN_POWER_NOISE_SIGMA_WATTS,
                        "P = P_nom * s^3 * (1 + beta), within measurement noise"),
                () -> assertEquals(0.4, blocked.filterBlockage(), 1e-12));
    }

    @Test
    @DisplayName("T-14, T-15: sensor drift moves the published value while the room's air does not")
    void shouldDriftTheSensorAndNotTheRoom() throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();
        simulation.inject(InjectedFault.sensorDrift(Topology.A124, 300, START));

        PhysicsSnapshot afterAnHour =
                BuildingSimulationTest.advanceFor(simulation, Duration.ofHours(1));

        assertAll(
                () -> assertEquals(Topology.OUTDOOR.value(),
                        afterAnHour.trueCo2(Topology.A124).value(), 1e-9,
                        "an empty room's true CO2 sits at outdoor, drift or no drift"),
                () -> assertEquals(Topology.OUTDOOR.value() + 300,
                        afterAnHour.measuredCo2(Topology.A124).value(),
                        4 * Topology.SENSOR_NOISE_SIGMA_PPM,
                        "the published value carries a full hour of 300 ppm/h drift"),
                () -> assertEquals(Topology.OUTDOOR.value(),
                        afterAnHour.measuredCo2(Topology.A115).value(),
                        4 * Topology.SENSOR_NOISE_SIGMA_PPM,
                        "and the other five sensors are untouched"));
    }

    /**
     * The fault T-15 needed and the generator could not produce. The distinguishing property is in
     * the second assertion rather than the first: an offset is the <em>same</em> number after one
     * hour and after six, where a ramp's would have grown sixfold, and it is that stillness that
     * lets the overnight floor fill while the step-response amplitude stays believable.
     */
    @Test
    @DisplayName("T-15: a step sensor offset jumps once and holds, where a drift keeps growing")
    void shouldOffsetTheSensorByAFixedAmountThatDoesNotGrow() throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();
        simulation.inject(InjectedFault.sensorOffset(Topology.A124, 350, START));

        PhysicsSnapshot afterAnHour =
                BuildingSimulationTest.advanceFor(simulation, Duration.ofHours(1));
        double measuredAfterAnHour = afterAnHour.measuredCo2(Topology.A124).value();
        PhysicsSnapshot afterSixHours =
                BuildingSimulationTest.advanceFor(simulation, Duration.ofHours(5));

        assertAll(
                () -> assertEquals(Topology.OUTDOOR.value(),
                        afterSixHours.trueCo2(Topology.A124).value(), 1e-9,
                        "an empty room's true CO2 sits at outdoor, offset or no offset"),
                () -> assertEquals(Topology.OUTDOOR.value() + 350, measuredAfterAnHour,
                        4 * Topology.SENSOR_NOISE_SIGMA_PPM,
                        "the offset is there in full from the onset, not accumulated towards"),
                () -> assertEquals(Topology.OUTDOOR.value() + 350,
                        afterSixHours.measuredCo2(Topology.A124).value(),
                        4 * Topology.SENSOR_NOISE_SIGMA_PPM,
                        "and six hours later it is the same 350 ppm — a drift would be at 2100"),
                () -> assertEquals(Topology.OUTDOOR.value(),
                        afterSixHours.measuredCo2(Topology.A115).value(),
                        4 * Topology.SENSOR_NOISE_SIGMA_PPM,
                        "and the other five sensors are untouched"));
    }

    @Test
    @DisplayName("T-14: a drift and an offset on one sensor add, because both elements are wrong")
    void shouldAddADriftAndAnOffsetOnTheSameSensor() throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();
        simulation.inject(InjectedFault.sensorOffset(Topology.A124, 200, START));
        simulation.inject(InjectedFault.sensorDrift(Topology.A124, 100, START));

        PhysicsSnapshot afterAnHour =
                BuildingSimulationTest.advanceFor(simulation, Duration.ofHours(1));

        assertEquals(Topology.OUTDOOR.value() + 300,
                afterAnHour.measuredCo2(Topology.A124).value(),
                4 * Topology.SENSOR_NOISE_SIGMA_PPM,
                "a 200 ppm miscalibration plus an hour of 100 ppm/h drift is 300 ppm of error");
    }

    @Test
    @DisplayName("T-14: a fault does nothing before its onset")
    void shouldNotBiteBeforeItsOnset() throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();
        simulation.inject(InjectedFault.blockedFilter(0.5, START.plusSeconds(1800)));

        PhysicsSnapshot early = BuildingSimulationTest.advanceFor(simulation, Duration.ofMinutes(10));
        PhysicsSnapshot late = BuildingSimulationTest.advanceFor(simulation, Duration.ofMinutes(25));

        assertAll(
                () -> assertEquals(0, early.filterBlockage(), 1e-12),
                () -> assertTrue(early.activeFaults().isEmpty(), "nothing is active yet"),
                () -> assertEquals(0.5, late.filterBlockage(), 1e-12),
                () -> assertEquals(FaultKind.BLOCKED_FILTER,
                        late.activeFaults().getFirst().kind(),
                        "and the snapshot carries the ground-truth marker once it bites"));
    }

    @Test
    @DisplayName("T-14: clearing the faults returns the plant to health")
    void shouldReturnToHealthWhenFaultsAreCleared() throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();
        simulation.inject(InjectedFault.blockedFilter(0.5, START));
        simulation.advance();

        simulation.clearFaults();
        PhysicsSnapshot healthy = simulation.advance();

        assertEquals(0, healthy.filterBlockage(), 1e-12);
        assertTrue(healthy.activeFaults().isEmpty());
    }

    private static BuildingSimulation emptyBuilding() {
        return new BuildingSimulation(
                new SimulatedClock(START.minusSeconds(1)), TICK, 42, new FixedOccupancy());
    }

    private static double flow(PhysicsSnapshot snapshot, ZoneId zone) {
        return snapshot.flow(zone).cubicMetresPerSecond();
    }

    /**
     * The defect this noise exists to prevent: a noiseless plant publishes the same watts forever,
     * {@code co2-sensor} judges an unchanging value stale, and the observer may consume only GOOD
     * readings — so the monitor's one cross-room signal was being discarded on a healthy plant.
     */
    @Test
    @DisplayName("T-14: a healthy plant's fan power keeps moving, so it is never judged stale")
    void shouldPublishAMovingFanPowerOnAHealthyPlant() throws InterruptedException {
        BuildingSimulation simulation = emptyBuilding();

        java.util.Set<Double> readings = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 6; i++) {
            readings.add(simulation.advance().fanPower().watts());
        }

        assertEquals(6, readings.size(),
                "every sample must differ, or value-change staleness marks the signal unusable");
    }
}
