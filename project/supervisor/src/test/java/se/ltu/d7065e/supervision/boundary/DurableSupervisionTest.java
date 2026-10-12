package se.ltu.d7065e.supervision.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.boundary.FileServiceStateAdapter;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.control.ServiceStatePort;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.supervision.control.CompensationPlan;
import se.ltu.d7065e.supervision.control.PlantSnapshot;
import se.ltu.d7065e.supervision.control.PlantState;
import se.ltu.d7065e.supervision.control.SupervisionService;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Crash recovery end to end, which ADR-0012 claims is testable without a database: run a
 * supervisor, let it write its document to a real file, throw the process away, and bring a new
 * one up over the plant the old one left actuated.
 *
 * <p>The reading is fed again after every restart because it is: readings arrive every second and
 * a restarted supervisor has one within one. What it does <em>not</em> get back by itself is the
 * plant as it was before it trimmed, and that is the difference this class is about.
 */
@DisplayName("A supervisor restarted mid-compensation")
class DurableSupervisionTest {

    private static final Instant START = Instant.parse("2026-10-07T09:00:00Z");
    private static final ZoneId FAULTED = Topology.A145;
    private static final ZoneId HEALTHY = Topology.A125;
    private static final CaseId CASE = CaseId.of("case-A145-1");
    private static final double OCCUPIED_POSITION = 0.6;

    /** Well over the comfort limit, so the gain is read off the room and not off the plant. */
    private static final Ppm UNCOMFORTABLE = Ppm.of(1800);

    /** The supervisor's outputs are not what this class is about; the document is. */
    private static final EventPublisherPort DISCARDED = (topic, event) -> {
    };

    @TempDir
    Path volume;

    private final SimulatedClock clock = new SimulatedClock(START);

    // ----- The defect this exists to remove ---------------------------------------------------

    @Test
    void shouldNotTrimOnTopOfItsOwnTrimAfterARestart() {
        SupervisionService crashed = runUntilCompensating();
        CompensationPlan inForce = crashed.currentPlan();

        SupervisionService restarted = supervising(asTheLastRunLeftIt(crashed));
        DurableSupervision resumed = new DurableSupervision(restarted, stateOnDisk());
        resumed.resume();
        resumed.accept(reading());
        resumed.accept(latched(MonitorState.COMPENSATING));

        assertEquals(inForce.trimmedPositions(), restarted.currentPlan().trimmedPositions(),
                "the healthy dampers are already at the trim, so the recomputed plan has to be "
                        + "the plan already in force and move nothing");
        assertEquals(inForce.fanSpeed(), restarted.currentPlan().fanSpeed());
        assertEquals(inForce.achievedFaultedFlowGain(),
                restarted.currentPlan().achievedFaultedFlowGain(), 1e-9);
    }

    @Test
    void shouldShowThatARestartWithoutTheDocumentClosesTheHealthyDampersASecondTime() {
        SupervisionService crashed = runUntilCompensating();
        CompensationPlan inForce = crashed.currentPlan();

        SupervisionService cold = supervising(asTheLastRunLeftIt(crashed));
        DurableSupervision keepsNothing = new DurableSupervision(cold, new SkippedState());
        keepsNothing.resume();
        keepsNothing.accept(reading());
        keepsNothing.accept(latched(MonitorState.COMPENSATING));

        DamperPosition compounded = cold.currentPlan().trimmedPositions().get(HEALTHY);
        assertTrue(compounded.value() < inForce.trimmedPositions().get(HEALTHY).value() - 1e-9,
                "this is the defect: with no document the first republished event re-snapshots a "
                        + "plant that is already trimmed and solves the same gain again from "
                        + "there, taking the healthy dampers down to %s".formatted(compounded));
        assertTrue(compounded.value() <= Topology.MINIMUM_POSITION.value() + 1e-9,
                "and it does not stop until they are on their own floor, at " + compounded);
        assertEquals(FanSpeed.of(FanSpeed.MAXIMUM), cold.currentPlan().fanSpeed(),
                "while the fan goes to its ceiling for a room already at its design flow");
    }

    // ----- What the document holds, and when ---------------------------------------------------

    @Test
    void shouldComeBackHoldingTheCaseAndTheBaselineItWroteToTheFile() {
        SupervisionService crashed = runUntilCompensating();
        PlantSnapshot baseline = crashed.episodeBaseline();

        SupervisionService restarted = supervising(PlantState.ofTopology());
        new DurableSupervision(restarted, stateOnDisk()).resume();

        assertEquals(Set.of(FAULTED), restarted.faultedZones());
        assertEquals(baseline, restarted.episodeBaseline());
        assertEquals(CASE, restarted.snapshot().cases().get(0).caseId());
    }

    @Test
    void shouldWriteTheBeliefOnAFaultAndNotOnEveryReading() throws IOException {
        DurableSupervision supervisor =
                new DurableSupervision(supervising(occupiedPlant()), stateOnDisk());

        supervisor.accept(reading());

        assertFalse(Files.exists(document()),
                "a write per reading is six a second to say that nothing changed");

        supervisor.accept(latched(MonitorState.CONFIRMED));

        assertTrue(Files.readString(document()).contains(CASE.value()),
                "and the one input that can change the belief writes it: " + document());
    }

    @Test
    void shouldStopResumingACaseOnceItHasBeenServiced() {
        DurableSupervision supervisor =
                new DurableSupervision(supervising(occupiedPlant()), stateOnDisk());
        supervisor.accept(reading());
        supervisor.accept(latched(MonitorState.CONFIRMED));

        supervisor.accept(serviced());

        SupervisionService restarted = supervising(PlantState.ofTopology());
        new DurableSupervision(restarted, stateOnDisk()).resume();
        assertTrue(restarted.faultedZones().isEmpty(),
                "the release has to be stored as a release, or a restart would take a damper back "
                        + "off the inner loop that a technician had just handed over");
        assertNull(restarted.episodeBaseline());
    }

    // ----- The store is never a startup dependency --------------------------------------------

    @Test
    void shouldStartColdWhenNothingWasEverWritten() {
        SupervisionService cold = supervising(occupiedPlant());

        new DurableSupervision(cold, stateOnDisk()).resume();

        assertTrue(cold.faultedZones().isEmpty());
        assertNull(cold.episodeBaseline());
    }

    @Test
    void shouldStartColdWhenTheStoredDocumentIsDamaged() throws IOException {
        Files.writeString(document(), "{\"cases\": [{\"caseId\": truncated");
        SupervisionService cold = supervising(occupiedPlant());

        new DurableSupervision(cold, stateOnDisk()).resume();

        assertTrue(cold.faultedZones().isEmpty(),
                "a document that cannot be read is a document that is not there, so the "
                        + "supervisor degrades to the behaviour it had before it could persist "
                        + "anything rather than failing to boot");
    }

    @Test
    void shouldStartColdWhenTheDocumentDescribesAPlantThisProcessDoesNotHave() throws IOException {
        SupervisionService crashed = runUntilCompensating();
        Files.writeString(document(),
                Files.readString(document()).replace(FAULTED.value(), "B999"));

        SupervisionService restarted = supervising(asTheLastRunLeftIt(crashed));
        new DurableSupervision(restarted, stateOnDisk()).resume();

        assertTrue(restarted.faultedZones().isEmpty(),
                "a document naming a room this zone set does not have is refused whole, because a "
                        + "half-recovered supervisor is worse than a cold one");
        assertNull(restarted.episodeBaseline());
    }

    @Test
    void shouldKeepNothingWhenNoStatePathIsConfigured() throws IOException {
        DurableSupervision supervisor =
                new DurableSupervision(supervising(occupiedPlant()), new SkippedState());

        supervisor.accept(reading());
        supervisor.accept(latched(MonitorState.CONFIRMED));

        try (var files = Files.list(volume)) {
            assertEquals(List.of(), files.toList(),
                    "an unset state path is the evaluation harness and the unit tests, where "
                            + "nothing should be wired at all");
        }
    }

    // ----- Fixtures ----------------------------------------------------------------------------

    /** A supervisor that has latched R-2's scenario and written its belief to the real file. */
    private SupervisionService runUntilCompensating() {
        SupervisionService supervisor = supervising(occupiedPlant());
        DurableSupervision durable = new DurableSupervision(supervisor, stateOnDisk());
        durable.accept(reading());
        durable.accept(latched(MonitorState.CONFIRMED));
        return supervisor;
    }

    /**
     * The plant a restarted supervisor finds: the healthy dampers at the trim the inner loop is
     * honouring, the frozen one where it froze, the fan where the last run raised it, and every
     * mode back to NORMAL because the mode lived in the dead process's heap.
     */
    private static PlantState asTheLastRunLeftIt(SupervisionService crashed) {
        PlantSnapshot left = crashed.currentPlan().appliedTo(crashed.episodeBaseline());
        return new PlantState(left.zones(), left.airHandler());
    }

    private SupervisionService supervising(PlantState plant) {
        return new SupervisionService(plant, DISCARDED, new SkippedWorkOrders(),
                new SkippedJournal(), clock);
    }

    private ServiceStatePort stateOnDisk() {
        return new FileServiceStateAdapter(document());
    }

    private Path document() {
        return volume.resolve("state.json");
    }

    private static PlantState occupiedPlant() {
        List<VentilationZone> zones = Topology.zones().stream()
                .map(zone -> zone.id().equals(FAULTED) ? zone
                        : zone.withSupervisoryCommand(DamperPosition.of(OCCUPIED_POSITION)))
                .toList();
        return new PlantState(zones, Topology.airHandler());
    }

    private ReadingObserved reading() {
        return new ReadingObserved(FAULTED, UNCOMFORTABLE, ObservedAt.of(clock.now()),
                Quality.GOOD);
    }

    private FaultStateChanged latched(MonitorState state) {
        return new FaultStateChanged(FAULTED, state, FaultClassification.STUCK_DAMPER,
                List.of("cusum above h"), ObservedAt.of(clock.now()), CASE);
    }

    private FaultStateChanged serviced() {
        return new FaultStateChanged(FAULTED, MonitorState.SERVICED, FaultClassification.NONE,
                List.of("technician cleared the latch"), ObservedAt.of(clock.now()), CASE);
    }
}
