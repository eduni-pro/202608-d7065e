package se.ltu.d7065e.devices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.shared.control.BuildingStatePort;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.ventilation.entity.AhuId;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

class HvacCommandApplierTest {

    /**
     * Several tests below inject a failure the device is supposed to log, so the device logs it.
     * Silencing it here keeps an expected warning out of the build output, where it reads as a
     * broken test rather than as a passing one.
     */
    @BeforeAll
    static void silenceExpectedWarnings() {
        Logger.getLogger(HvacCommandApplier.class.getName()).setLevel(Level.OFF);
    }

    private static final ObservedAt AT = ObservedAt.of(Instant.parse("2026-10-07T08:00:00Z"));
    private static final ZoneId ROOM = Topology.A115;

    private final StubBuilding building = new StubBuilding();
    private final StubClock clock = new StubClock();
    private final HvacCommandApplier applier = new HvacCommandApplier(building, clock);

    @Test
    void shouldApplyValidDamperCommandExactlyOnce() {
        applier.onDamperCommand(damperCommand(ROOM, 0.6));

        assertEquals(List.of(new DamperWrite(ROOM, 0.6)), building.damperWrites);
    }

    @Test
    void shouldRejectZoneThatIsNotOneOfTheSixRooms() {
        applier.onDamperCommand(damperCommand(ZoneId.of("B999"), 0.6));

        assertTrue(building.damperWrites.isEmpty(), "an unknown room must not be written");
    }

    @Test
    void shouldClampBelowMinimumPositionUpToMinimumRatherThanApplyingIt() {
        applier.onDamperCommand(damperCommand(ROOM, 0.02));

        assertEquals(
                List.of(new DamperWrite(ROOM, Topology.MINIMUM_POSITION.value())),
                building.damperWrites);
        assertEquals(0.15, building.damperWrites.getFirst().position());
    }

    @Test
    void shouldApplyAboveMinimumPositionUnchanged() {
        applier.applyDamperPosition(ROOM, 1.0, CommandId.newId());

        assertEquals(List.of(new DamperWrite(ROOM, 1.0)), building.damperWrites);
    }

    @Test
    void shouldRejectDamperPositionOutsideTheUnitInterval() {
        applier.applyDamperPosition(ROOM, 1.4, CommandId.newId());

        assertTrue(building.damperWrites.isEmpty(), "an out-of-range position must not be written");
    }

    @Test
    void shouldApplyValidFanSpeedCommand() {
        applier.onFanSpeedCommand(fanSpeedCommand(Topology.AIR_HANDLER, 0.8));

        assertEquals(List.of(0.8), building.fanSpeedWrites);
    }

    @Test
    void shouldRejectOutOfRangeFanSpeed() {
        applier.applyFanSpeed(Topology.AIR_HANDLER, 1.4, CommandId.newId());
        applier.applyFanSpeed(Topology.AIR_HANDLER, 0.05, CommandId.newId());

        assertTrue(building.fanSpeedWrites.isEmpty(), "ventilation never stops, and never overspeeds");
    }

    @Test
    void shouldRejectFanSpeedForAnUnknownAirHandler() {
        applier.applyFanSpeed(AhuId.of("AHU-ELSEWHERE"), 0.8, CommandId.newId());

        assertTrue(building.fanSpeedWrites.isEmpty(), "this device serves one air handler");
    }

    @Test
    void shouldRetryAFailedWriteAndSucceedWithinTheAttemptBudget() {
        building.failuresRemaining = 2;

        applier.onDamperCommand(damperCommand(ROOM, 0.6));

        assertEquals(List.of(new DamperWrite(ROOM, 0.6)), building.damperWrites);
        assertEquals(2, clock.waits.size(), "one backoff per retry");
    }

    @Test
    void shouldDropACommandWhoseWriteKeepsFailingRatherThanBlockForever() {
        building.failuresRemaining = Integer.MAX_VALUE;

        applier.onDamperCommand(damperCommand(ROOM, 0.6));

        assertTrue(building.damperWrites.isEmpty());
        assertEquals(2, clock.waits.size(), "three attempts, two backoffs, then dropped");
    }

    private static CommandIssued damperCommand(ZoneId zone, double position) {
        return new CommandIssued(zone, DamperPosition.of(position), AT, "test",
                CommandId.of("cmd-1"));
    }

    private static FanSpeedCommanded fanSpeedCommand(AhuId ahu, double speed) {
        return new FanSpeedCommanded(ahu, FanSpeed.of(speed), AT, "test", CommandId.of("cmd-2"));
    }

    private record DamperWrite(ZoneId zone, double position) {
    }

    private static final class StubBuilding implements BuildingStatePort {

        private final List<DamperWrite> damperWrites = new ArrayList<>();
        private final List<Double> fanSpeedWrites = new ArrayList<>();
        private int failuresRemaining;

        @Override
        public void applyDamperPosition(ZoneId zone, DamperPosition position) {
            failOnce();
            damperWrites.add(new DamperWrite(zone, position.value()));
        }

        @Override
        public void applyFanSpeed(FanSpeed speed) {
            failOnce();
            fanSpeedWrites.add(speed.value());
        }

        private void failOnce() {
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IllegalStateException("BuildSim rejected the write");
            }
        }

        @Override
        public Ppm readCo2(ZoneId zone) {
            throw new UnsupportedOperationException("the actuator does not read sensors");
        }

        @Override
        public DamperPosition readDamperPosition(ZoneId zone) {
            throw new UnsupportedOperationException("the actuator writes, the monitor reads");
        }

        @Override
        public FanSpeed readFanSpeed() {
            throw new UnsupportedOperationException("the actuator writes, the monitor reads");
        }

        @Override
        public FanPower readFanPower() {
            throw new UnsupportedOperationException("the actuator does not read sensors");
        }

        @Override
        public void writeCo2(ZoneId zone, Ppm ppm) {
            throw new UnsupportedOperationException("the simulator writes sensor values");
        }

        @Override
        public void writeFanPower(FanPower power) {
            throw new UnsupportedOperationException("the simulator writes sensor values");
        }
    }

    private static final class StubClock implements ClockPort {

        private final List<Duration> waits = new ArrayList<>();
        private Instant now = AT.value();

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public void waitFor(Duration duration) {
            waits.add(duration);
            now = now.plus(duration);
        }
    }
}
