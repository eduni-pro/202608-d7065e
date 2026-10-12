package se.ltu.d7065e.diagnosis.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.ltu.d7065e.diagnosis.control.DiagnosisSnapshot;
import se.ltu.d7065e.diagnosis.control.FaultMonitor;
import se.ltu.d7065e.diagnosis.control.LatchedCase;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.Evidence;
import se.ltu.d7065e.diagnosis.entity.FaultCase;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.boundary.FileServiceStateAdapter;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.control.ServiceStatePort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.ServiceReported;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Crash recovery end to end, which ADR-0012 claims is testable without a database: run a monitor,
 * let it write its document to a real file, throw the process away, and bring a new one up with
 * nothing but the file.
 *
 * <p>The latch is what the file is for. Everything a monitor concludes is reconstructable from its
 * next few readings except the one thing it is not allowed to conclude twice: that a confirmed
 * fault is over. Here that is a round trip through a real filesystem rather than a handover of an
 * object, because the failure being survived is a process dying, and the document is the only thing
 * that crosses it.
 */
@DisplayName("A fault monitor restarted while a diagnosis is latched")
class DurableDiagnosisTest {

    private static final Instant START = Instant.parse("2026-10-07T09:00:00Z");
    private static final ZoneId FAULTED = Topology.A115;
    private static final ZoneId HEALTHY = Topology.A125;
    private static final CaseId CASE = CaseId.of("case-A115-1");
    private static final DamperPosition PINNED = DamperPosition.of(0.15);

    /** The monitor's outputs are not what this class is about; the document is. */
    private static final EventPublisherPort DISCARDED = (topic, event) -> {
    };

    @TempDir
    Path volume;

    private final SimulatedClock clock = new SimulatedClock(START);

    // ----- The defect this exists to remove ---------------------------------------------------

    @Test
    void shouldComeBackHoldingTheLatchedDiagnosisItWroteToTheFile() {
        runUntilLatched();

        FaultMonitor restarted = monitoring();
        new DurableDiagnosis(restarted, stateOnDisk()).resume();

        assertEquals(MonitorState.CONFIRMED, restarted.stateOf(FAULTED),
                "a restart that comes back HEALTHY is a second exit from a latch the design gives "
                        + "exactly one, and the supervisor would stop compensating a real fault");
        assertEquals(CASE, restarted.caseOf(FAULTED).id());
        assertEquals(FaultClassification.STUCK_DAMPER,
                restarted.caseOf(FAULTED).classification());
    }

    @Test
    void shouldShowThatARestartWithoutTheDocumentForgetsTheLatchEntirely() {
        runUntilLatched();

        FaultMonitor cold = monitoring();
        new DurableDiagnosis(cold, new SkippedState()).resume();

        assertEquals(MonitorState.WARMUP, cold.stateOf(FAULTED),
                "this is the defect: with no document the confirmed room comes back with no case "
                        + "at all, and has to re-earn a diagnosis from a residual the "
                        + "supervisor's own compensation has already flattened");
        assertNull(cold.caseOf(FAULTED));
        assertFalse(cold.flowModel().believesUnresponsive(FAULTED),
                "and the damper goes back to being believed wherever it was last commanded, "
                        + "which AhuFlowModel measures as 411 spurious fault events");
    }

    @Test
    void shouldComeBackWithThePinnedDamperAndTheRestOfTheFlowBelief() {
        runUntilLatched();

        FaultMonitor restarted = monitoring();
        new DurableDiagnosis(restarted, stateOnDisk()).resume();

        assertTrue(restarted.flowModel().believesUnresponsive(FAULTED));
        assertEquals(PINNED, restarted.flowModel().positionOf(FAULTED));
        assertEquals(DamperPosition.of(0.6), restarted.flowModel().positionOf(HEALTHY),
                "the healthy rooms' openings are in the document too, because the inner loop "
                        + "commands only on a change and would not restate them for hours");
        assertEquals(FanSpeed.of(0.85), restarted.flowModel().believedFanSpeed());
    }

    // ----- What the document is written on, and what it is not --------------------------------

    @Test
    void shouldWriteTheBeliefOnACycleAndNotOnEveryReading() throws IOException {
        DurableDiagnosis monitor = new DurableDiagnosis(monitoring(), stateOnDisk());

        monitor.accept(reading());

        assertFalse(Files.exists(document()),
                "a write per reading is six a second to say that nothing changed");

        monitor.runCycle();

        assertTrue(Files.exists(document()),
                "and the one path that can latch a diagnosis writes it: " + document());
    }

    @Test
    void shouldWriteTheBeliefWhenACommandMovesIt() throws IOException {
        DurableDiagnosis monitor = new DurableDiagnosis(monitoring(), stateOnDisk());

        monitor.accept(command(HEALTHY, 0.75));

        assertTrue(Files.readString(document()).contains("0.75"),
                "a damper command is the only thing that moves an unpinned belief, and the next "
                        + "one may be hours away, so losing it leaves the monitor wrong until then");
    }

    @Test
    void shouldStopResumingACaseOnceItHasBeenServiced() {
        DurableDiagnosis crashed = runUntilLatched();

        crashed.serviced(new ServiceReported(FAULTED, CASE, ObservedAt.of(clock.now()),
                "the actuator linkage was replaced"));

        FaultMonitor restarted = monitoring();
        new DurableDiagnosis(restarted, stateOnDisk()).resume();
        assertNull(restarted.caseOf(FAULTED),
                "the release has to be stored as a release, or a restart would resurrect a case a "
                        + "technician had just closed — which is the one exit the design reserves");
        assertEquals(MonitorState.WARMUP, restarted.stateOf(FAULTED));
    }

    // ----- The store is never a startup dependency --------------------------------------------

    @Test
    void shouldStartColdWhenNothingWasEverWritten() {
        FaultMonitor cold = monitoring();

        new DurableDiagnosis(cold, stateOnDisk()).resume();

        assertEquals(MonitorState.WARMUP, cold.stateOf(FAULTED));
        assertNull(cold.caseOf(FAULTED));
    }

    @Test
    void shouldStartColdWhenTheStoredDocumentIsDamaged() throws IOException {
        Files.writeString(document(), "{\"cases\": [{\"confirmedAt\": truncated");
        FaultMonitor cold = monitoring();

        new DurableDiagnosis(cold, stateOnDisk()).resume();

        assertNull(cold.caseOf(FAULTED),
                "a document that cannot be read is a document that is not there, so a damaged "
                        + "one degrades the monitor to the behaviour it had before it could "
                        + "persist anything rather than failing to boot");
    }

    @Test
    void shouldStartColdWhenTheDocumentDescribesABuildingThisProcessDoesNotWatch() throws IOException {
        runUntilLatched();
        Files.writeString(document(),
                Files.readString(document()).replace(HEALTHY.value(), "B999"));

        FaultMonitor cold = monitoring();
        new DurableDiagnosis(cold, stateOnDisk()).resume();

        assertNull(cold.caseOf(FAULTED),
                "a document naming a room this zone set does not have is refused whole, because a "
                        + "half-recovered monitor is worse than a cold one");
        assertFalse(cold.flowModel().believesUnresponsive(FAULTED));
    }

    @Test
    void shouldKeepNothingWhenNoStatePathIsConfigured() throws IOException {
        DurableDiagnosis monitor = new DurableDiagnosis(monitoring(), new SkippedState());

        monitor.accept(command(HEALTHY, 0.75));
        monitor.runCycle();

        try (var files = Files.list(volume)) {
            assertEquals(List.of(), files.toList(),
                    "an unset state path is the evaluation harness and the unit tests, where "
                            + "nothing should be wired at all");
        }
    }

    // ----- Fixtures ----------------------------------------------------------------------------

    /**
     * A monitor holding R-1's latched case and its pin, with the belief on the real file.
     *
     * <p>The latch is installed through {@code recover} rather than detected, because what this
     * class is about is the document: that the detector reaches this state from CO2 alone is
     * {@code StuckDamperDetectionTest}'s result, and that the state survives a handover in memory
     * is {@code MonitorRestartTest}'s. The cycle is what writes it, which is the path a deployed
     * monitor would also have written it on.
     */
    private DurableDiagnosis runUntilLatched() {
        FaultMonitor crashed = monitoring();
        crashed.recover(latchedBelief());
        DurableDiagnosis durable = new DurableDiagnosis(crashed, stateOnDisk());
        durable.runCycle();
        return durable;
    }

    private FaultMonitor monitoring() {
        return new FaultMonitor(Topology.zones(), Topology.airHandler(),
                MonitorParameters.defaults(), clock, DISCARDED, new DiscardingJournal());
    }

    private static DiagnosisSnapshot latchedBelief() {
        Map<ZoneId, DamperPosition> believed = new LinkedHashMap<>();
        Topology.ZONE_IDS.forEach(zone -> believed.put(zone, DamperPosition.of(0.6)));
        believed.put(FAULTED, PINNED);
        FaultCase stuck = new FaultCase(CASE, FAULTED, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER,
                List.of(Evidence.supporting("upper-arm CUSUM 212.4 ppm passed h = 150 ppm",
                        ObservedAt.of(START))),
                ObservedAt.of(START), ObservedAt.of(START));
        return new DiagnosisSnapshot(List.of(new LatchedCase(stuck, START)), FanSpeed.of(0.85),
                believed, Set.of(FAULTED));
    }

    private ServiceStatePort stateOnDisk() {
        return new FileServiceStateAdapter(document());
    }

    private Path document() {
        return volume.resolve("state.json");
    }

    private ReadingObserved reading() {
        return new ReadingObserved(FAULTED, Ppm.of(900), ObservedAt.of(clock.now()), Quality.GOOD);
    }

    private CommandIssued command(ZoneId zone, double position) {
        return new CommandIssued(zone, DamperPosition.of(position), ObservedAt.of(clock.now()),
                "dcv hysteresis", CommandId.newId());
    }
}
