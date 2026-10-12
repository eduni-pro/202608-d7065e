package se.ltu.d7065e.supervision.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * What a restarted supervisor has to come back knowing (ADR-0012). The restart is modelled the way
 * it actually happens: the old service hands over an immutable snapshot, and a <em>new</em> service
 * takes it back over a <em>new</em> plant picture — because the process restarts and the plant does
 * not, so a fresh picture finds the dampers and the fan where the last run left them.
 *
 * <p>This is the control half of the property. That the same belief survives a JSON document on a
 * real file, and that the compensation does not then compound its own trim, is proved at the
 * boundary where the document lives.
 */
@DisplayName("A supervisor across a restart")
class SupervisorRestartTest {

    private static final Instant START = Instant.parse("2026-10-07T09:00:00Z");
    private static final ZoneId FAULTED = Topology.A145;
    private static final CaseId CASE = CaseId.of("case-A145-1");
    private static final double OCCUPIED_POSITION = 0.6;

    private final SimulatedClock clock = new SimulatedClock(START);
    private final RecordingJournal journal = new RecordingJournal();
    private final RecordingPublisher firstRun = new RecordingPublisher();
    private final RecordingWorkOrders firstRunOrders = new RecordingWorkOrders();
    private final RecordingPublisher resumed = new RecordingPublisher();
    private final RecordingWorkOrders resumedOrders = new RecordingWorkOrders();

    // ----- The cases, which gate everything the supervisor does ------------------------------

    @Test
    void shouldKeepItsOpenCaseAcrossARestart() {
        SupervisionService restarted = restarted(compensating());

        assertEquals(Set.of(FAULTED), restarted.faultedZones(),
                "an empty case set means no compensation, no work order, and a service exit that "
                        + "silently fails to put the plant back");
    }

    @Test
    void shouldKeepTheDiagnosisAndTheCaseIdentityAcrossARestart() {
        SupervisionService first = compensating();
        SupervisedCase before = first.snapshot().cases().get(0);

        SupervisedCase after = restarted(first).snapshot().cases().get(0);

        assertEquals(before.caseId(), after.caseId(),
                "the operator's work order is keyed by the case id, so a new one would orphan the "
                        + "alert the previous run raised");
        assertEquals(before.classification(), after.classification());
    }

    @Test
    void shouldKeepTheFrozenDamperPositionTheGainIsComputedFromAcrossARestart() {
        SupervisionService first = compensating();
        VentilationZone before = first.snapshot().cases().get(0).frozenZone();

        VentilationZone after = restarted(first).snapshot().cases().get(0).frozenZone();

        assertEquals(Topology.MINIMUM_POSITION, after.commandedPosition(),
                "the damper froze at the demand-controlled floor, and that is the quantity the "
                        + "compensation gain is computed from — a wrong one is a wrong trim for "
                        + "every healthy room");
        assertEquals(before, after);
    }

    @Test
    void shouldTakeTheDamperBackFromTheInnerLoopAsSoonAsItRecovers() {
        SupervisionService restarted = restarted(compensating());

        restarted.accept(new CommandIssued(FAULTED, DamperPosition.OPEN,
                ObservedAt.of(clock.now()), "inner loop, which has not been told again yet",
                CommandId.newId()));

        assertEquals(ControlMode.DEGRADED, restarted.plant().zone(FAULTED).mode(),
                "a zone recovered as a case is a zone the inner loop no longer owns");
        assertEquals(Topology.MINIMUM_POSITION, restarted.plant().zone(FAULTED).commandedPosition(),
                "and a controller command arriving before it relearns the mode must not move the "
                        + "supervisor's estimate of a damper that is not moving");
    }

    // ----- The baseline, which is the one that can make the plant worse -----------------------

    @Test
    void shouldKeepThePlantBaselineAcrossARestart() {
        SupervisionService first = compensating();
        PlantSnapshot before = first.episodeBaseline();

        PlantSnapshot after = restarted(first).episodeBaseline();

        assertEquals(before, after,
                "every solve starts from the baseline, so a restart that loses it re-snapshots an "
                        + "already trimmed plant and compounds on top of its own trim");
        assertEquals(DamperPosition.of(OCCUPIED_POSITION),
                after.zone(Topology.A125).commandedPosition(),
                "and it has to be the pre-trim positions, not the trimmed ones the restarted "
                        + "process finds the plant at");
    }

    @Test
    void shouldCarryNoBaselineWhileNothingIsLatched() {
        SupervisionService idle = supervising(occupiedPlant(), firstRun, firstRunOrders);

        SupervisionSnapshot nothingToResumeFrom = idle.snapshot();

        assertTrue(nothingToResumeFrom.cases().isEmpty());
        assertNull(nothingToResumeFrom.baseline(),
                "no open case means no episode, and an episode baseline without a case would "
                        + "freeze a plant picture that nothing will ever release");
    }

    @Test
    void shouldForgetTheEpisodeOnceTheLastCaseIsServicedSoARestartDoesNotResumeIt() {
        SupervisionService first = compensating();

        first.onFaultState(serviced());

        SupervisionSnapshot afterRelease = first.snapshot();
        assertTrue(afterRelease.cases().isEmpty());
        assertNull(afterRelease.baseline(),
                "a document written after the release must not bring a closed case back");
    }

    // ----- The probe history, which is a guard on a physical disturbance ----------------------

    @Test
    void shouldKeepTheProbeHistorySoARecoveredRepublishCannotProbeInsideTheGuard() {
        SupervisionService first = compensating();
        first.onFaultState(probeRequested());
        assertEquals(1, firstRun.damperCommands().size(), "the first probe is granted");
        SupervisionService restarted = restarted(first);

        clock.advance(ProbeLimiter.DEFAULT_INTERVAL.minus(Duration.ofMinutes(1)));
        restarted.onFaultState(probeRequested());

        assertEquals(List.of(), resumed.damperCommands(),
                "a probe is a real disturbance of a room already in trouble, so the guard has to "
                        + "outlive the process doing the guarding");
    }

    @Test
    void shouldProbeAgainOnceTheRecoveredGuardIntervalHasPassed() {
        SupervisionService first = compensating();
        first.onFaultState(probeRequested());
        SupervisionService restarted = restarted(first);

        clock.advance(ProbeLimiter.DEFAULT_INTERVAL.plus(Duration.ofMinutes(1)));
        restarted.onFaultState(probeRequested());

        assertEquals(1, resumed.damperCommands().size(),
                "the guard is an interval and not a ban: a recovered limiter must still let the "
                        + "masking defence step the damper again later");
    }

    // ----- What comes back without having been stored -----------------------------------------

    @Test
    void shouldRaiseTheWorkOrdersAgainAfterARestartWithoutHavingStoredThem() {
        SupervisionService first = compensating();
        RecordingWorkOrders.WorkOrder before = firstRunOrders.only();
        SupervisionService restarted = restarted(first);
        assertTrue(resumedOrders.open().isEmpty(), "a restarted process starts with no alert set");

        restarted.onFaultState(latched(MonitorState.COMPENSATING));

        RecordingWorkOrders.WorkOrder after = resumedOrders.only();
        assertEquals(before.caseId(), after.caseId());
        assertEquals(before.summary(), after.summary(),
                "the narrative is a pure function of the frozen zone, the diagnosis and the plan, "
                        + "all three of which recovery rebuilds, so storing it would be storing a "
                        + "derivation");
        assertEquals(before.severity(), after.severity());
    }

    @Test
    void shouldRecomputeThePlanItDidNotStoreOnTheFirstCycleAfterARestart() {
        SupervisionService first = compensating();
        CompensationPlan before = first.currentPlan();
        SupervisionService restarted = restarted(first);
        assertNull(restarted.currentPlan(),
                "nothing is in force until the next cycle recomputes it");

        restarted.onFaultState(latched(MonitorState.COMPENSATING));

        assertEquals(before, restarted.currentPlan(),
                "the same baseline and the same cases have to yield the same plan, or recovery "
                        + "would quietly re-actuate the plant differently");
    }

    @Test
    void shouldPutThePlantBackToNormalWhenACaseItRecoveredIsServiced() {
        SupervisionService restarted = restarted(compensating());

        restarted.onFaultState(serviced());

        restarted.plant().zones().forEach(zone -> assertEquals(ControlMode.NORMAL, zone.mode(),
                zone.id() + " must be handed back to the inner loop"));
        assertEquals(List.of(CASE), resumedOrders.cleared(),
                "the technician's service exit is the one way out of the latch, and a restart "
                        + "used to make it silently do nothing");
        List<StrategyChanged> normal = resumed.strategies().stream()
                .filter(strategy -> strategy.mode() == ControlMode.NORMAL).toList();
        assertEquals(Topology.ZONE_IDS.size(), normal.size(),
                "every zone has to be told, because the mode lives in the controller's heap");
        assertEquals(Topology.NORMAL_FAN_SPEED, resumed.fanSpeeds().get(0).speed(),
                "and the air handler has to come down off the compensating speed");
    }

    @Test
    void shouldLeaveASupervisorUntouchedByASnapshotWithNothingInIt() {
        SupervisionService cold = supervising(occupiedPlant(), resumed, resumedOrders);

        cold.recover(new SupervisionSnapshot(List.of(), null, Map.of()));

        assertTrue(cold.faultedZones().isEmpty());
        assertNull(cold.episodeBaseline());
        assertTrue(resumed.isEmpty(), "resuming nothing must not command anything");
        assertEquals(0, journal.size());
    }

    @Test
    void shouldHandOverASnapshotALaterReleaseCannotRewrite() {
        SupervisionService first = compensating();
        SupervisionSnapshot saved = first.snapshot();

        first.onFaultState(serviced());

        assertEquals(List.of(FAULTED),
                saved.cases().stream().map(open -> open.frozenZone().id()).toList(),
                "the store encodes what it was handed, so a live view would let a release that "
                        + "happened afterwards rewrite a belief that really was held");
        assertNotNull(saved.baseline());
    }

    // ----- Fixtures ---------------------------------------------------------------------------

    /** A supervisor that has latched R-2's pinned scenario and is holding a compensation. */
    private SupervisionService compensating() {
        SupervisionService supervisor = supervising(occupiedPlant(), firstRun, firstRunOrders);
        supervisor.onFaultState(latched(MonitorState.CONFIRMED));
        return supervisor;
    }

    /**
     * A new process over the plant a restarted supervisor actually finds — dampers at the trimmed
     * positions the inner loop is honouring and the fan at the speed the last run raised it to,
     * every zone back to NORMAL because the mode lived in the dead process's heap — given back
     * the snapshot that process published.
     */
    private SupervisionService restarted(SupervisionService crashed) {
        PlantSnapshot asLeft = crashed.currentPlan().appliedTo(crashed.episodeBaseline());
        SupervisionService supervisor = supervising(
                new PlantState(asLeft.zones(), asLeft.airHandler()), resumed, resumedOrders);
        supervisor.recover(crashed.snapshot());
        return supervisor;
    }

    private SupervisionService supervising(PlantState plant, RecordingPublisher publisher,
            RecordingWorkOrders workOrders) {
        return new SupervisionService(plant, publisher, workOrders, journal, clock);
    }

    /** Occupied hours: the healthy dampers modulating above their floor, the faulted one frozen. */
    private static PlantState occupiedPlant() {
        List<VentilationZone> zones = Topology.zones().stream()
                .map(zone -> zone.id().equals(FAULTED) ? zone
                        : zone.withSupervisoryCommand(DamperPosition.of(OCCUPIED_POSITION)))
                .toList();
        return new PlantState(zones, Topology.airHandler());
    }

    private FaultStateChanged latched(MonitorState state) {
        return new FaultStateChanged(FAULTED, state, FaultClassification.STUCK_DAMPER,
                List.of("cusum above h"), ObservedAt.of(clock.now()), CASE);
    }

    private FaultStateChanged probeRequested() {
        return new FaultStateChanged(FAULTED, MonitorState.COMPENSATING,
                FaultClassification.STUCK_DAMPER,
                List.of("residual still above h", SupervisionService.PROBE_REQUEST_EVIDENCE),
                ObservedAt.of(clock.now()), CASE);
    }

    private FaultStateChanged serviced() {
        return new FaultStateChanged(FAULTED, MonitorState.SERVICED, FaultClassification.NONE,
                List.of("technician cleared the latch"), ObservedAt.of(clock.now()), CASE);
    }
}
