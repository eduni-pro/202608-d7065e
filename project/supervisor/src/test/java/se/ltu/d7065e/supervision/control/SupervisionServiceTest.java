package se.ltu.d7065e.supervision.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.DiagnosticProbe;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.shared.event.Topics;
import se.ltu.d7065e.ventilation.control.FlowSplit;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-16, T-17 and the supervisor's half of T-18, driven through the ports with a simulated clock
 * and no broker, database or HTTP — the same way the evaluation harness drives it.
 */
class SupervisionServiceTest {

    private static final Instant START = Instant.parse("2026-10-07T09:00:00Z");
    private static final ZoneId FAULTED = Topology.A145;
    private static final CaseId CASE = CaseId.of("case-A145-1");
    private static final double OCCUPIED_POSITION = 0.6;

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final RecordingWorkOrders workOrders = new RecordingWorkOrders();
    private final RecordingJournal journal = new RecordingJournal();
    private final SimulatedClock clock = new SimulatedClock(START);

    // ----- Authority -------------------------------------------------------------------------

    @Test
    void shouldDegradeTheFaultedZoneAndNoOtherZone_R7() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));

        supervisor.onFaultState(latched(MonitorState.CONFIRMED));

        List<StrategyChanged> degraded = publisher.strategies().stream()
                .filter(strategy -> strategy.mode() == ControlMode.DEGRADED).toList();
        assertEquals(1, degraded.size(), "exactly one zone may be taken from the inner loop");
        assertEquals(FAULTED, degraded.get(0).zone());
        assertEquals(Topics.strategy(FAULTED), publisher.topicOf(degraded.get(0)));
        assertEquals(ControlMode.DEGRADED, supervisor.plant().zone(FAULTED).mode());
        for (VentilationZone zone : supervisor.plant().zones()) {
            if (!zone.id().equals(FAULTED)) {
                assertEquals(ControlMode.COMPENSATING, zone.mode(),
                        zone.id() + " is healthy and must not be degraded");
            }
        }
    }

    @Test
    void shouldDoNothingWhileTheMonitorOnlySuspects_authorityRule() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));

        supervisor.onFaultState(latched(MonitorState.SUSPECT));
        supervisor.onFaultState(latched(MonitorState.BLIND));
        supervisor.onFaultState(latched(MonitorState.HEALTHY));

        assertTrue(publisher.isEmpty(), "a suspicion is not authority to command anything");
        assertTrue(workOrders.open().isEmpty());
        assertEquals(0, journal.size());
        assertTrue(supervisor.faultedZones().isEmpty());
        supervisor.plant().zones().forEach(zone ->
                assertEquals(ControlMode.NORMAL, zone.mode(), zone.id() + " must be untouched"));
    }

    // ----- T-16 compensation -----------------------------------------------------------------

    @Test
    void shouldHoldTheFiveHealthyZonesAirflowWhileRaisingTheFaultedShare_R7() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));

        supervisor.onFaultState(latched(MonitorState.CONFIRMED));

        CompensationPlan plan = supervisor.currentPlan();
        assertTrue(plan.compensates(), "a damper frozen partly open is reachable");
        assertTrue(plan.meetsRequest(),
                "at the plant's normal speed the fan has the headroom to hold all five flows");
        assertEquals(5, plan.trimmedPositions().size(), "the five healthy dampers carry the trim");

        Map<ZoneId, Double> before = flows(supervisor.episodeBaseline());
        Map<ZoneId, Double> after = flows(plan.appliedTo(supervisor.episodeBaseline()));
        double tolerance = CompensationSolver.HEALTHY_FLOW_TOLERANCE;
        for (ZoneId zone : Topology.ZONE_IDS) {
            if (zone.equals(FAULTED)) {
                continue;
            }
            double deviation = Math.abs(after.get(zone) / before.get(zone) - 1);
            assertTrue(deviation <= tolerance + 1e-9,
                    zone + " airflow moved by %.4f, outside the %.2f tolerance"
                            .formatted(deviation, tolerance));
        }
        assertTrue(after.get(FAULTED) > before.get(FAULTED) * 1.2,
                "the faulted room must actually gain airflow, had %.4f then %.4f"
                        .formatted(before.get(FAULTED), after.get(FAULTED)));
        assertEquals(plan.achievedFaultedFlowGain(), after.get(FAULTED) / before.get(FAULTED), 1e-9,
                "the reported gain must be the gain the split actually delivers");
    }

    /** Peak occupancy: the plant is already at full speed, so the fan lever is gone. */
    @Test
    void shouldHoldHealthyAirflowWithinToleranceWhenTheFanHasNoHeadroom_R7() {
        SupervisionService supervisor = supervising(partlyOpenFault(FanSpeed.NOMINAL));

        supervisor.onFaultState(latched(MonitorState.CONFIRMED));

        CompensationPlan plan = supervisor.currentPlan();
        assertTrue(plan.compensates(), "a saturated fan still allows redistribution");
        assertFalse(plan.meetsRequest(), "and it must say that it fell short of the request");
        assertTrue(plan.worstHealthyFlowDeviation()
                        <= CompensationSolver.HEALTHY_FLOW_TOLERANCE + 1e-9,
                "healthy airflow gave up %.4f".formatted(plan.worstHealthyFlowDeviation()));

        Map<ZoneId, Double> before = flows(supervisor.episodeBaseline());
        Map<ZoneId, Double> after = flows(plan.appliedTo(supervisor.episodeBaseline()));
        for (ZoneId zone : Topology.ZONE_IDS) {
            if (!zone.equals(FAULTED)) {
                assertTrue(after.get(zone) / before.get(zone)
                                >= 1 - CompensationSolver.HEALTHY_FLOW_TOLERANCE - 1e-9,
                        zone + " lost more airflow than the tolerance permits");
            }
        }
        assertTrue(after.get(FAULTED) > before.get(FAULTED));
    }

    @Test
    void shouldRestoreFlowToADamperStuckPartlyOpen_R2() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));
        supervisor.observeReading(new ReadingObserved(
                FAULTED, Ppm.of(1400), ObservedAt.of(START), Quality.GOOD));

        supervisor.onFaultState(latched(MonitorState.CONFIRMED));

        CompensationPlan plan = supervisor.currentPlan();
        assertTrue(plan.compensates());
        assertTrue(plan.meetsRequest(), "with fan headroom the whole requested gain is available");
        assertTrue(plan.achievedFaultedFlowGain() >= 1400.0 / Topology.COMFORT_LIMIT.value(),
                "the gain must at least close the gap to the comfort limit, was %.3f"
                        .formatted(plan.achievedFaultedFlowGain()));

        RecordingWorkOrders.WorkOrder order = workOrders.only();
        assertEquals(WorkOrderNarrative.WARNING, order.severity());
        assertTrue(order.summary().contains(FAULTED.value()), "the operator needs the room");
        assertTrue(order.summary().contains(FaultClassification.STUCK_DAMPER.name()),
                "and the classification");
        assertTrue(order.summary().contains("trimmed") && order.summary().contains("air handler"),
                "and what the supervisor did: " + order.summary());
    }

    @Test
    void shouldRaiseACriticalWorkOrderWithOccupancyAdviceForADamperWithNoAirPath_R2Bound() {
        SupervisionService supervisor = supervising(shutFault(Topology.NORMAL_FAN_SPEED));

        supervisor.onFaultState(latched(MonitorState.CONFIRMED));

        CompensationPlan plan = supervisor.currentPlan();
        assertFalse(plan.compensates(), "a shut damper must not be promised compensation");
        assertTrue(plan.trimmedPositions().isEmpty(), "and no healthy damper may be trimmed for it");
        assertTrue(publisher.fanSpeeds().isEmpty(), "and the fan must not be raised for nothing");
        assertEquals(List.of(ControlMode.DEGRADED),
                publisher.strategies().stream().map(StrategyChanged::mode).distinct().toList());

        RecordingWorkOrders.WorkOrder order = workOrders.only();
        assertEquals(WorkOrderNarrative.CRITICAL, order.severity());
        assertTrue(order.summary().contains("no air path"), order.summary());
        assertTrue(order.summary().contains("reduce the head-count")
                        && order.summary().contains("relocate"),
                "the stuck-shut case is answered with occupancy advice: " + order.summary());
    }

    @Test
    void shouldAnswerADamperStuckOpenAsAWarningAboutWasteRatherThanAnEvacuation_D11() {
        SupervisionService supervisor = supervising(stuckOpenFault(Topology.NORMAL_FAN_SPEED));
        supervisor.observeReading(new ReadingObserved(
                FAULTED, Ppm.of(480), ObservedAt.of(START), Quality.GOOD));

        supervisor.onFaultState(latchedAs(FaultClassification.DAMPER_STUCK_OPEN));

        CompensationPlan plan = supervisor.currentPlan();
        assertFalse(plan.compensates(),
                "an over-ventilated room has nothing to compensate, and its own CO2 says so");
        assertTrue(plan.trimmedPositions().isEmpty(),
                "and no healthy damper may be taken off the inner loop to feed a room that is "
                        + "already taking more air than it asked for");
        assertTrue(publisher.fanSpeeds().isEmpty(), "and the fan must not be raised for it");

        RecordingWorkOrders.WorkOrder order = workOrders.only();
        assertEquals(WorkOrderNarrative.WARNING, order.severity(),
                "a room with too much air is not a critical room, however little the plan did: "
                        + "compensates() answers whether the airflow was raised, not whether the "
                        + "room needed it raised");
        assertTrue(order.summary().contains(FaultClassification.DAMPER_STUCK_OPEN.name()),
                order.summary());
        assertTrue(order.summary().contains("OVER-ventilated"),
                "the operator has to be told which direction the fault runs in: " + order.summary());
        assertFalse(order.summary().contains("reduce the head-count")
                        || order.summary().contains("relocate"),
                "the one room in the building with the most air in it must not be answered with "
                        + "occupancy advice, which is what it got while both extremes shared a "
                        + "name: " + order.summary());
    }

    @Test
    void shouldStillAnswerADamperStuckShutWithOccupancyAdvice_D11() {
        SupervisionService supervisor = supervising(shutFault(Topology.NORMAL_FAN_SPEED));

        supervisor.onFaultState(latchedAs(FaultClassification.STUCK_DAMPER));

        RecordingWorkOrders.WorkOrder order = workOrders.only();
        assertEquals(WorkOrderNarrative.CRITICAL, order.severity(),
                "splitting the classification must not have softened the case that is genuinely "
                        + "critical");
        assertTrue(order.summary().contains("relocate"), order.summary());
    }

    @Test
    void shouldNeverCommandFanSpeedOutsideThePlantRange() {
        for (double baselineSpeed : new double[] {0.2, 0.35, 0.5, 0.7, 0.8, 1.0}) {
            for (ZoneId faulted : Topology.ZONE_IDS) {
                publisher.clear();
                SupervisionService supervisor = supervising(occupiedPlant(
                        Topology.zone(faulted), FanSpeed.of(baselineSpeed)));
                supervisor.onFaultState(new FaultStateChanged(faulted, MonitorState.CONFIRMED,
                        FaultClassification.STUCK_DAMPER, List.of(), ObservedAt.of(START), CASE));
                supervisor.onFaultState(new FaultStateChanged(faulted, MonitorState.SERVICED,
                        FaultClassification.STUCK_DAMPER, List.of(), ObservedAt.of(START), CASE));

                for (FanSpeedCommanded commanded : publisher.fanSpeeds()) {
                    double speed = commanded.speed().value();
                    assertTrue(speed >= FanSpeed.MINIMUM && speed <= FanSpeed.MAXIMUM,
                            "commanded %s from baseline %s for %s".formatted(
                                    commanded.speed(), baselineSpeed, faulted));
                }
            }
        }
    }

    // ----- Release ---------------------------------------------------------------------------

    @Test
    void shouldReturnEveryZoneToNormalAndTheNormalFanSpeedAndClearTheWorkOrderWhenServiced() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));
        // A measured 1000 ppm needs a modest gain, so the compensating speed stays below nominal
        // and the restore to nominal is a real command rather than a no-op.
        supervisor.observeReading(new ReadingObserved(
                FAULTED, Ppm.of(1000), ObservedAt.of(START), Quality.GOOD));
        supervisor.onFaultState(latched(MonitorState.CONFIRMED));
        assertTrue(publisher.fanSpeeds().get(0).speed().value()
                        > Topology.NORMAL_FAN_SPEED.value(),
                "compensation must have raised the fan for the restore to have something to undo");
        publisher.clear();

        supervisor.onFaultState(new FaultStateChanged(FAULTED, MonitorState.SERVICED,
                FaultClassification.STUCK_DAMPER, List.of(), ObservedAt.of(clock.now()), CASE));

        Set<ZoneId> restored = publisher.strategies().stream()
                .filter(strategy -> strategy.mode() == ControlMode.NORMAL)
                .map(StrategyChanged::zone).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.copyOf(Topology.ZONE_IDS), restored, "all six zones go back to NORMAL");
        supervisor.plant().zones().forEach(zone ->
                assertEquals(ControlMode.NORMAL, zone.mode()));
        assertEquals(1, publisher.fanSpeeds().size());
        assertEquals(Topology.NORMAL_FAN_SPEED, publisher.fanSpeeds().get(0).speed(),
                "the plant goes back to its normal speed, which is not the fan's maximum");
        assertEquals(List.of(CASE), workOrders.cleared());
        assertTrue(workOrders.open().isEmpty());
        assertTrue(supervisor.faultedZones().isEmpty());
    }

    // ----- T-18 probe ------------------------------------------------------------------------

    @Test
    void shouldRateLimitProbesToOnePerZonePerInterval_T18() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));

        supervisor.onFaultState(probeRequested());
        assertEquals(1, publisher.damperCommands().size(), "the monitor asked, the supervisor acted");
        assertEquals(Topics.damperCommand(FAULTED),
                publisher.topicOf(publisher.damperCommands().get(0)));
        assertTrue(publisher.damperCommands().get(0).reason().contains("probe"),
                "the command must name itself a probe for the monitor to key on");

        clock.advance(Duration.ofMinutes(5));
        supervisor.onFaultState(probeRequested());
        assertEquals(1, publisher.damperCommands().size(), "too soon for a second probe");

        clock.advance(ProbeLimiter.DEFAULT_INTERVAL);
        supervisor.onFaultState(probeRequested());
        assertEquals(2, publisher.damperCommands().size(), "the interval has passed");

        assertEquals(Set.of(FAULTED),
                publisher.damperCommands().stream().map(command -> command.zone())
                        .collect(java.util.stream.Collectors.toSet()),
                "the probe is per room; the other five see none");
    }

    @Test
    void shouldStepTheDamperByTheAmplitudeTheMonitorAsksFor_T18() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));

        supervisor.onFaultState(probeRequested());

        // The monitor names the target in its evidence and the supervisor commands it. Until this
        // was pinned the two carried different amplitudes, so the published request and the issued
        // command disagreed about where the damper was going.
        double frozen = Topology.zone(FAULTED).commandedPosition().value();
        assertEquals(frozen + DiagnosticProbe.STEP_INCREASE,
                publisher.damperCommands().get(0).position().value(), 1e-9,
                "the probe must step by the amplitude the monitor requested, not by one of its own");
    }

    // ----- Topic discipline and provenance ---------------------------------------------------

    @Test
    void shouldNeverPublishOnAReadingOrResidualTopic() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));
        supervisor.observeReading(new ReadingObserved(
                FAULTED, Ppm.of(1500), ObservedAt.of(START), Quality.GOOD));

        supervisor.onFaultState(probeRequested());
        clock.advance(ProbeLimiter.DEFAULT_INTERVAL);
        supervisor.onFaultState(probeRequested());
        supervisor.onFaultState(new FaultStateChanged(FAULTED, MonitorState.SERVICED,
                FaultClassification.STUCK_DAMPER, List.of(), ObservedAt.of(clock.now()), CASE));

        assertFalse(publisher.topics().isEmpty(), "the episode must have published something");
        for (String topic : publisher.topics()) {
            assertFalse(topic.startsWith("readings/"),
                    "the supervisor never writes a reading: " + topic);
            assertFalse(topic.startsWith("residuals/"),
                    "the supervisor never writes a residual: " + topic);
            assertTrue(topic.startsWith("strategy/") || topic.startsWith("commands/"), topic);
        }
    }

    @Test
    void shouldJournalEveryStrategyAndFanCommandAsTheSupervisor_R5() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));

        supervisor.onFaultState(probeRequested());

        assertEquals(List.of(SupervisionService.JOURNAL_SOURCE), journal.sources());
        assertEquals(publisher.topics().size(), journal.size(),
                "everything published is also journalled with its provenance");
    }

    // ----- Fixtures --------------------------------------------------------------------------

    // ----- T-35: a diagnosis that arrives after the case did ---------------------------------

    @Test
    void shouldTakeTheMonitorsLaterDiagnosisForACaseItLatchedAsUnclassified() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));
        supervisor.observeReading(new ReadingObserved(
                FAULTED, Ppm.of(1400), ObservedAt.of(START), Quality.GOOD));
        supervisor.onFaultState(named(FaultClassification.UNCLASSIFIED));
        assertTrue(workOrders.only().summary().contains(FaultClassification.UNCLASSIFIED.name()),
                "the first work order must say what the monitor said: " + workOrders.only().summary());

        supervisor.onFaultState(named(FaultClassification.STUCK_DAMPER));

        assertTrue(workOrders.only().summary().contains(FaultClassification.STUCK_DAMPER.name()),
                "a work order must follow the monitor when the probe names the fault: "
                        + workOrders.only().summary());
        assertEquals(List.of(), workOrders.cleared(),
                "naming a case must not close and reopen it — the operator keeps one work order");
    }

    @Test
    void shouldNotLetALaterEventRenameAnAlreadyNamedCase() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));
        supervisor.onFaultState(named(FaultClassification.STUCK_DAMPER));

        supervisor.onFaultState(named(FaultClassification.SENSOR_DRIFT));

        assertTrue(workOrders.only().summary().contains(FaultClassification.STUCK_DAMPER.name()),
                "a named diagnosis is the monitor's latched conclusion; the supervisor is not the "
                        + "place to revise it, or a work order could change its mind every cycle");
    }

    @Test
    void shouldRepublishTheDegradedModeOnEveryCycleSoARestartedControllerRelearnsIt_R7() {
        SupervisionService supervisor = supervising(partlyOpenFault(Topology.NORMAL_FAN_SPEED));
        supervisor.onFaultState(latched(MonitorState.CONFIRMED));

        supervisor.onFaultState(latched(MonitorState.CONFIRMED));

        List<StrategyChanged> degraded = publisher.strategies().stream()
                .filter(strategy -> strategy.mode() == ControlMode.DEGRADED).toList();
        assertEquals(2, degraded.size(),
                "the mode lives in the controller's heap, so a restarted controller only learns "
                        + "the damper was taken away if the supervisor keeps saying so");
        assertEquals(List.of(FAULTED, FAULTED),
                degraded.stream().map(StrategyChanged::zone).toList(),
                "and it must still be the faulted zone and no other");
    }

    private FaultStateChanged named(FaultClassification classification) {
        return new FaultStateChanged(FAULTED, MonitorState.CONFIRMED, classification,
                List.of("cusum above h"), ObservedAt.of(clock.now()), CASE);
    }

    private SupervisionService supervising(PlantState plant) {
        return new SupervisionService(plant, publisher, workOrders, journal, clock);
    }

    /** R-2's pinned scenario: the damper froze at the demand-controlled floor, 0.15. */
    private static PlantState partlyOpenFault(FanSpeed baselineSpeed) {
        return occupiedPlant(Topology.zone(FAULTED), baselineSpeed);
    }

    /**
     * The case compensation cannot reach. A zone whose own minimum is {@code SHUT} is the only
     * way the entity invariants allow a damper with no air path to be expressed, which is itself
     * worth knowing: {@code Topology.MINIMUM_POSITION} = 0.15 is a reachable fault.
     */
    private static PlantState shutFault(FanSpeed baselineSpeed) {
        VentilationZone template = Topology.zone(FAULTED);
        return occupiedPlant(new VentilationZone(template.id(), template.volumeCubicMetres(),
                template.floorAreaSquareMetres(), template.ahuId(), template.damperAuthority(),
                DamperPosition.SHUT, ControlMode.NORMAL, DamperPosition.SHUT), baselineSpeed);
    }

    /**
     * The mirror of {@link #shutFault}: a damper frozen at the far end of its travel, so the room
     * takes a larger share of the fixed total than it was asked for and the other five get the
     * shortfall (D-11).
     */
    private static PlantState stuckOpenFault(FanSpeed baselineSpeed) {
        VentilationZone template = Topology.zone(FAULTED);
        return occupiedPlant(new VentilationZone(template.id(), template.volumeCubicMetres(),
                template.floorAreaSquareMetres(), template.ahuId(), template.damperAuthority(),
                template.minimumPosition(), ControlMode.NORMAL, DamperPosition.OPEN), baselineSpeed);
    }

    /** Occupied hours: every healthy damper modulating above its floor, so a trim has somewhere to go. */
    private static PlantState occupiedPlant(VentilationZone faulted, FanSpeed baselineSpeed) {
        List<VentilationZone> zones = Topology.zones().stream()
                .map(zone -> zone.id().equals(faulted.id()) ? faulted
                        : zone.withSupervisoryCommand(DamperPosition.of(OCCUPIED_POSITION)))
                .toList();
        return new PlantState(zones, Topology.airHandler().atSpeed(baselineSpeed));
    }

    private FaultStateChanged latched(MonitorState state) {
        return new FaultStateChanged(FAULTED, state, FaultClassification.STUCK_DAMPER,
                List.of("cusum above h"), ObservedAt.of(clock.now()), CASE);
    }

    private FaultStateChanged latchedAs(FaultClassification classification) {
        return new FaultStateChanged(FAULTED, MonitorState.CONFIRMED, classification,
                List.of("cusum above h"), ObservedAt.of(clock.now()), CASE);
    }

    private FaultStateChanged probeRequested() {
        return new FaultStateChanged(FAULTED, MonitorState.COMPENSATING,
                FaultClassification.STUCK_DAMPER,
                List.of("residual still above h", SupervisionService.PROBE_REQUEST_EVIDENCE),
                ObservedAt.of(clock.now()), CASE);
    }

    private static Map<ZoneId, Double> flows(PlantSnapshot snapshot) {
        return FlowSplit.flowPerZone(snapshot.zones(), snapshot.airHandler(), 0.0);
    }
}
