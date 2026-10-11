package se.ltu.d7065e.evaluation.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.physics.control.OccupancySource;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * D-10 end to end across four containers: a stuck damper is detected, latched and compensated, an
 * operator reports the repair on {@code service/{zone}}, and the whole response unwinds — with
 * nothing restarted.
 *
 * <p>This is the test the gap was worth having. {@code fault-monitor}'s own tests show the state
 * machine leaving CONFIRMED, and {@code supervisor}'s own tests show it releasing on a hand-made
 * {@code SERVICED} event; neither showed that anything in the system could produce one. Here the
 * report travels the route it travels in the deployment — the monitor's service adapter, the
 * monitor's case, {@code faults/{zone}}, the supervisor — so what is asserted is that the two
 * halves meet.
 *
 * <p>One room is occupied and the other five are empty. That is not a convenience: at design
 * occupancy in all six the plant saturates and five rooms latch cases of their own (the R-3
 * limitation the isolation experiment measures), and a release only unwinds the plant when the last
 * case closes. The subject here is the release, so the fixture is the controlled single-fault
 * condition rather than the one that also reproduces a known defect.
 */
class ServiceReleaseTest {

    private static final ZoneId FAULTED = Topology.A115;
    private static final Duration SETTLE = Duration.ofHours(4);
    private static final Duration PATIENCE = Duration.ofHours(2);

    @Test
    void shouldUnwindTheWholeCompensationWhenTheRepairIsReported() {
        SystemHarness harness = compensating(42);
        CaseId latched = harness.monitor().caseOf(FAULTED).id();
        double compensatingSpeed = harness.supervisor().plant().airHandler().commandedSpeed().value();

        assertEquals(Set.of(FAULTED), harness.supervisor().faultedZones());
        assertTrue(harness.overlay().raised().stream().anyMatch(w -> w.caseId().equals(latched)),
                "the fixture needs a work order for the technician to act on");
        assertTrue(compensatingSpeed > Topology.NORMAL_FAN_SPEED.value(),
                "the fixture needs a compensation in force, and the fan carries part of it");

        harness.repair();
        harness.reportServiced(FAULTED, latched, "damper actuator replaced");

        assertEquals(MonitorState.SERVICED, harness.monitor().stateOf(FAULTED));
        assertEquals(Set.of(), harness.supervisor().faultedZones(),
                "the supervisor holds the case until the monitor says it closed");
        assertNull(harness.supervisor().currentPlan(), "the compensation plan must be torn down");
        assertTrue(harness.overlay().cleared().contains(latched),
                "the work order must be cleared, or the technician is called out twice");
        assertEquals(Topology.NORMAL_FAN_SPEED.value(),
                harness.supervisor().plant().airHandler().commandedSpeed().value(), 1e-9,
                "the fan has to come back down, or the repair costs its power for ever");
        for (ZoneId zone : Topology.ZONE_IDS) {
            assertEquals(ControlMode.NORMAL, harness.supervisor().plant().zone(zone).mode(),
                    "every zone goes back to the inner loop, including " + zone);
        }
        assertEquals(Set.copyOf(Topology.ZONE_IDS), zonesToldTheyAreNormalAgain(harness),
                "the inner loop learns it has its dampers back on strategy/{zone}, so every room "
                        + "has to be told");
    }

    @Test
    void shouldReturnThroughWarmupToHealthyOnceTheRoomIsRepaired() {
        SystemHarness harness = compensating(42);
        CaseId latched = harness.monitor().caseOf(FAULTED).id();

        harness.repair();
        harness.reportServiced(FAULTED, latched, "damper actuator replaced");
        harness.tick();

        assertEquals(MonitorState.WARMUP, harness.monitor().stateOf(FAULTED));
        assertNull(harness.monitor().caseOf(FAULTED), "service must close the case, not keep it");
        int cycles = harness.tickUntil(Duration.ofHours(2),
                () -> harness.monitor().stateOf(FAULTED) == MonitorState.HEALTHY);
        assertNotEquals(-1, cycles, "a repaired room has to be able to become healthy again");
        assertEquals(Set.of(), harness.supervisor().faultedZones(),
                "and it must not be accused again for the fault that was repaired");
    }

    @Test
    void shouldRefuseARepairReportForARoomThatHasNoCase() {
        SystemHarness harness = new SystemHarness(42, MonitorParameters.defaults(),
                occupiedOnlyIn(FAULTED));
        harness.tickFor(SETTLE);
        assertNull(harness.monitor().caseOf(FAULTED), "the fixture needs a quiet building");

        assertThrows(IllegalArgumentException.class,
                () -> harness.reportServiced(FAULTED, CaseId.of("case-0001"), "repaired"));

        assertEquals(Set.of(), harness.supervisor().faultedZones());
        assertEquals(Topology.NORMAL_FAN_SPEED.value(),
                harness.supervisor().plant().airHandler().commandedSpeed().value(), 1e-9,
                "a refused report must not move the plant");
    }

    /**
     * A building with one stuck damper latched, compensated, and a work order out for it. It waits
     * for the fan to be raised as well as for the plan to exist: the solver reaches for the healthy
     * dampers' trim first and only asks the fan for what the trim could not give, so a plan that has
     * just appeared is not yet the expensive state whose unwinding this test is about.
     */
    private SystemHarness compensating(long seed) {
        SystemHarness harness = new SystemHarness(seed, MonitorParameters.defaults(),
                occupiedOnlyIn(FAULTED));
        harness.tickFor(SETTLE);
        harness.inject(InjectedFault.stuckDamper(FAULTED, Topology.MINIMUM_POSITION, harness.now()));
        harness.command(FAULTED, 0.80, "the first ignored command");
        int cycles = harness.tickUntil(PATIENCE,
                () -> harness.monitor().stateOf(FAULTED).isLatched()
                        && harness.supervisor().currentPlan() != null
                        && harness.supervisor().plant().airHandler().commandedSpeed().value()
                                > Topology.NORMAL_FAN_SPEED.value());
        assertNotEquals(-1, cycles, "the fixture needs a latched case the supervisor acted on");
        return harness;
    }

    /**
     * The zones told they are NORMAL again. {@code NORMAL} is published on {@code strategy/{zone}}
     * from the release and from nowhere else — degrading and compensating are the only other things
     * that publish there — so this set is exactly what the release announced.
     */
    private static Set<ZoneId> zonesToldTheyAreNormalAgain(SystemHarness harness) {
        return harness.events().all().stream()
                .map(published -> published.event())
                .filter(StrategyChanged.class::isInstance)
                .map(StrategyChanged.class::cast)
                .filter(strategy -> strategy.mode() == ControlMode.NORMAL)
                .map(StrategyChanged::zone)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * One room at the design occupancy ADR-0008 sized its damper authority from, the other five
     * empty. {@link DesignOccupancy} is the same count, applied to every room at once.
     */
    private static OccupancySource occupiedOnlyIn(ZoneId occupied) {
        Map<ZoneId, Integer> headCounts = new LinkedHashMap<>();
        for (VentilationZone zone : Topology.zones()) {
            headCounts.put(zone.id(),
                    zone.id().equals(occupied) ? DesignOccupancy.designOccupancy(zone) : 0);
        }
        return at -> headCounts;
    }
}
