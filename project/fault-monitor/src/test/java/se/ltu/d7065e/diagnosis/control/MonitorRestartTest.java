package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.FaultCase;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * What a restarted monitor has to come back knowing (ADR-0012). The restart is modelled the way it
 * actually happens: the old process hands over an immutable snapshot, and a <em>new</em> monitor
 * takes it back having never seen a reading — because the process restarts and the building does
 * not.
 *
 * <p>The latch is the property under test and the reason the record exists. A confirmed fault stays
 * confirmed until a technician clears it, which is what stops the supervisor oscillating, and a
 * restart that forgot it was a second exit through the one exit the design reserves for a human.
 *
 * <p>This is the control half of the property. That the same belief survives a JSON document on a
 * real file is proved at the boundary where the document lives.
 */
@DisplayName("A fault monitor across a restart")
class MonitorRestartTest {

    private static final ZoneId FAULTED = Topology.A115;
    private static final ZoneId HEALTHY = Topology.A125;
    private static final Duration BUDGET = Duration.ofMinutes(15);

    // ----- The latch, which is the whole point ------------------------------------------------

    @Test
    void shouldKeepALatchedDiagnosisAcrossARestart() {
        FaultMonitor crashed = confirmedStuckDamper();
        FaultCase before = crashed.caseOf(FAULTED);

        FaultMonitor restarted = restarted(crashed);

        assertEquals(MonitorState.CONFIRMED, restarted.stateOf(FAULTED),
                "a restart that forgets the latch is a second exit from a confirmed fault, and "
                        + "the design reserves exactly one, for a technician");
        FaultCase after = restarted.caseOf(FAULTED);
        assertEquals(before.id(), after.id(),
                "the operator's work order is keyed by the case id, so a new one would orphan the "
                        + "alert the previous run raised");
        assertEquals(FaultClassification.STUCK_DAMPER, after.classification());
    }

    @Test
    void shouldKeepTheEvidenceSoTheRecoveredDiagnosisCanStillBeExplained() {
        FaultMonitor crashed = confirmedStuckDamper();
        FaultCase before = crashed.caseOf(FAULTED);

        FaultCase after = restarted(crashed).caseOf(FAULTED);

        assertEquals(before.evidence(), after.evidence(),
                "R-5 is a diagnosis that can be explained and not only reported, so a case "
                        + "restored without its argument comes back named and unjustified");
        assertEquals(before.openedAt(), after.openedAt());
    }

    @Test
    void shouldStillOnlyLeaveARecoveredLatchThroughService() {
        MonitorHarness restarted = restartedHarness(confirmedStuckDamper());

        restarted.tickFor(Duration.ofMinutes(30));

        assertTrue(restarted.monitor().stateOf(FAULTED).isLatched(),
                "a recovered case is a latched case: a healthy-looking residual is what successful "
                        + "compensation looks like, which is exactly what must not clear it");
    }

    @Test
    void shouldClearARecoveredLatchWhenTheEquipmentIsServiced() {
        MonitorHarness restarted = restartedHarness(confirmedStuckDamper());

        restarted.monitor().serviced(FAULTED);
        restarted.tick();

        assertEquals(MonitorState.WARMUP, restarted.monitor().stateOf(FAULTED));
        assertFalse(restarted.monitor().flowModel().believesUnresponsive(FAULTED),
                "service is the one event that makes this damper's commands meaningful again, so "
                        + "it has to lift a pin the monitor recovered as readily as one it set");
    }

    // ----- The flow belief, which is R-7's fix -------------------------------------------------

    @Test
    void shouldKeepThePinnedDamperBeliefAcrossARestart() {
        FaultMonitor crashed = confirmedStuckDamper();
        DamperPosition pinned = crashed.flowModel().positionOf(FAULTED);

        FaultMonitor restarted = restarted(crashed);

        assertTrue(restarted.flowModel().believesUnresponsive(FAULTED),
                "a damper whose belief follows its commands again is credited with air it is not "
                        + "getting, which AhuFlowModel measures as 411 spurious fault events");
        assertEquals(pinned, restarted.flowModel().positionOf(FAULTED),
                "and the pin's value is the quantity the other five rooms' residuals are computed "
                        + "from, so a pin at the wrong opening is no better than no pin");
    }

    @Test
    void shouldLeaveTheHealthyRoomsBelievingExactlyWhatTheyBelievedBefore() {
        FaultMonitor crashed = confirmedStuckDamper();
        double before = crashed.flowModel().believedFlowTo(HEALTHY);

        double after = restarted(crashed).flowModel().believedFlowTo(HEALTHY);

        assertEquals(before, after, 1e-12,
                "ADR-0008's split is a ratio over all six openings, so every point of opening the "
                        + "restarted monitor gets wrong anywhere is air mis-credited everywhere");
    }

    @Test
    void shouldKeepTheBelievedFanSpeedTheSupervisorSetAndDoesNotRepeat() {
        FaultMonitor crashed = confirmedStuckDamper();
        crashed.fanSpeedBelieved(FanSpeed.of(0.85));

        FaultMonitor restarted = restarted(crashed);

        assertEquals(FanSpeed.of(0.85), restarted.flowModel().believedFanSpeed(),
                "the supervisor announces a changed speed once and de-duplicates, so a belief "
                        + "reset to nominal reads a raised fan as a blocked filter indefinitely");
    }

    // ----- What is deliberately not carried across --------------------------------------------

    @Test
    void shouldNotResumeTheCusumArmsBecauseARestartIsABlindGap() {
        FaultMonitor crashed = confirmedStuckDamper();
        assertTrue(crashed.cusumOf(FAULTED).value() > 0, "the crashed monitor had banked evidence");

        FaultMonitor restarted = restarted(crashed);

        assertEquals(0, restarted.cusumOf(FAULTED).value(), 1e-12,
                "Cusum states that a run of healthy samples must not bank credit, so an arm "
                        + "carried across a gap argues from a building nobody was watching");
        assertEquals(0, restarted.lowerCusumOf(FAULTED).value(), 1e-12);
    }

    @Test
    void shouldNotResumeAnUnlatchedRoomAtAll() {
        FaultMonitor crashed = confirmedStuckDamper();
        assertFalse(crashed.stateOf(HEALTHY).isLatched(), "the fixture leaves this room unlatched");

        FaultMonitor restarted = restarted(crashed);

        assertEquals(MonitorState.WARMUP, restarted.stateOf(HEALTHY),
                "an unlatched room has nothing a restart can lose: it starts where every cold "
                        + "start starts, and earns HEALTHY back from its own readings");
        assertNull(restarted.caseOf(HEALTHY));
    }

    @Test
    void shouldCarryNoOvernightFloorAndSaySoByAnsweringNoEvidence() {
        FaultMonitor restarted = restarted(confirmedStuckDamper());

        assertTrue(Double.isNaN(restarted.overnightFloorOf(FAULTED)),
                "the 24 h window is not stored, and NaN is the classifier's own word for no "
                        + "evidence either way — the state every cold start is in for a day");
    }

    // ----- The snapshot is a value, and a refused one changes nothing -------------------------

    @Test
    void shouldHandOverASnapshotALaterServiceCannotRewrite() {
        FaultMonitor crashed = confirmedStuckDamper();
        DiagnosisSnapshot handedOver = crashed.snapshot();

        crashed.serviced(FAULTED);

        assertEquals(1, handedOver.cases().size(),
                "the snapshot is an immutable value the owning thread published, so a release "
                        + "taken afterwards cannot reach back into a document being written");
        assertEquals(FAULTED, handedOver.cases().get(0).zone());
    }

    @Test
    void shouldRefuseASnapshotNamingARoomItDoesNotWatchWithoutMutatingAnything() {
        DiagnosisSnapshot foreign = withAnUnwatchedRoom(confirmedStuckDamper().snapshot());
        FaultMonitor restarting = MonitorHarness.standard(7).monitor();

        assertThrows(IllegalArgumentException.class, () -> restarting.recover(foreign));

        assertEquals(MonitorState.WARMUP, restarting.stateOf(FAULTED),
                "every zone is resolved before anything is mutated, so the alternative to a full "
                        + "recovery is a cold start and never half of one");
        assertFalse(restarting.flowModel().believesUnresponsive(FAULTED));
    }

    @Test
    void shouldLeaveAMonitorUntouchedByASnapshotWithNothingLatchedInIt() {
        DiagnosisSnapshot nothingLatched = settledHarness(7).monitor().snapshot();
        assertEquals(List.of(), nothingLatched.cases());

        FaultMonitor restarted = MonitorHarness.standard(7).monitor();
        restarted.recover(nothingLatched);

        assertEquals(MonitorState.WARMUP, restarted.stateOf(FAULTED));
        assertNull(restarted.caseOf(FAULTED));
    }

    // ----- Fixtures ----------------------------------------------------------------------------

    /** A monitor that has latched R-1's scenario: one damper frozen and the case confirmed. */
    private static FaultMonitor confirmedStuckDamper() {
        MonitorHarness harness = settledHarness(42);
        harness.physics().stick(FAULTED, Topology.MINIMUM_POSITION.value());
        harness.command(FAULTED, 0.80, "the first ignored command");
        int cycles = harness.tickUntil(BUDGET,
                () -> harness.monitor().stateOf(FAULTED) == MonitorState.CONFIRMED);
        assertNotEquals(-1, cycles, "the fixture did not confirm, so there is no latch to lose");
        return harness.monitor();
    }

    /**
     * A new process over the same building, taking the dead one's belief and nothing else: a
     * monitor that has never seen a reading, which is what a restarted container is.
     */
    private static MonitorHarness restartedHarness(FaultMonitor crashed) {
        MonitorHarness fresh = MonitorHarness.standard(7);
        fresh.monitor().recover(crashed.snapshot());
        return fresh;
    }

    private static FaultMonitor restarted(FaultMonitor crashed) {
        return restartedHarness(crashed).monitor();
    }

    /**
     * The document a process configured for a different zone set would have written. It keeps a
     * recoverable case, so what the refusal is about is the plant it names and not the case.
     */
    private static DiagnosisSnapshot withAnUnwatchedRoom(DiagnosisSnapshot written) {
        Map<ZoneId, DamperPosition> positions = new LinkedHashMap<>(written.believedPositions());
        positions.put(ZoneId.of("B999"), DamperPosition.of(0.5));
        return new DiagnosisSnapshot(written.cases(), written.believedFanSpeed(), positions,
                written.unresponsiveDampers());
    }

    /**
     * A building at the quasi-steady state the observer learns its generation term from, settled so
     * that the estimate has converged before anything is injected — the fixture
     * {@code StuckDamperDetectionTest} uses, for the same reason.
     */
    private static MonitorHarness settledHarness(long seed) {
        MonitorHarness harness = MonitorHarness.standard(seed);
        harness.innerLoopRunning(false);
        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
            harness.physics().settle(zone);
        }
        harness.tickFor(Duration.ofHours(1));
        return harness;
    }
}
