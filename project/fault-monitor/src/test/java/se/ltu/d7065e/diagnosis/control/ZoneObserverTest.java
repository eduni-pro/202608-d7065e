package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** T-11 — the observer predicts a healthy step response without being told the occupancy. */
class ZoneObserverTest {

    private static final ZoneId ZONE = Topology.A115;
    private static final Instant START = Instant.parse("2026-10-05T08:00:00Z");

    @Test
    void shouldRecoverEachRoomsDesignOccupancyFromItsDesignFlow() {
        assertEquals(1, Math.round(ZoneObserver.designOccupancy(Topology.zone(Topology.A125A))));
        assertEquals(2, Math.round(ZoneObserver.designOccupancy(Topology.zone(Topology.A125))));
        assertEquals(4, Math.round(ZoneObserver.designOccupancy(Topology.zone(Topology.A116A))));
        assertEquals(10, Math.round(ZoneObserver.designOccupancy(Topology.zone(Topology.A115))));
        assertEquals(8, Math.round(ZoneObserver.designOccupancy(Topology.zone(Topology.A124))));
        assertEquals(20, Math.round(ZoneObserver.designOccupancy(Topology.zone(Topology.A145))));
    }

    @Test
    void shouldPredictAHealthyStepResponseWithinTolerance() {
        MonitorParameters parameters = MonitorParameters.defaults();
        BuildingPhysics physics = new BuildingPhysics(42, 0);
        AhuFlowModel flow = new AhuFlowModel(Topology.airHandler(), physics.zones());
        VentilationZone zone = Topology.zone(ZONE);
        ZoneObserver observer = new ZoneObserver(zone, flow, parameters);

        for (ZoneId other : Topology.ZONE_IDS) {
            physics.occupyToDesign(other);
            physics.settle(other);
        }
        Instant now = START;
        observer.reset(physics.truePpm(ZONE), now);
        for (int cycle = 0; cycle < 20; cycle++) {
            now = now.plus(parameters.cycleInterval());
            physics.advance(parameters.cycleInterval());
            observer.advance(now);
            observer.learnGeneration(physics.truePpm(ZONE));
            observer.correctTowards(physics.truePpm(ZONE), now);
        }

        double expectedGeneration = ZoneObserver.designOccupancy(zone)
                * Topology.GENERATION_PER_PERSON * 1e6;
        assertEquals(expectedGeneration, observer.generationEstimate(), expectedGeneration * 0.05,
                "the quasi-steady estimate did not converge on the room's actual generation");

        CommandIssued open = new CommandIssued(ZONE, DamperPosition.of(0.80),
                ObservedAt.of(now), "open up", CommandId.newId());
        observer.stepCommanded(open, physics.truePpm(ZONE), now);
        physics.command(ZONE, 0.80);

        // Cycle by cycle, because the step-amplitude comparison is accumulated over the cycles of
        // the window rather than read off one sample — see ZoneObserver#stepAmplitudeRatio.
        ZoneObserver.Prediction prediction = null;
        for (int cycle = 0; cycle < 10; cycle++) {
            now = now.plus(parameters.cycleInterval());
            physics.advance(parameters.cycleInterval());
            prediction = observer.advance(now);
            observer.recordStepAmplitude(physics.truePpm(ZONE));
        }
        double residual = physics.truePpm(ZONE) - prediction.predictedPpm();

        assertTrue(Math.abs(residual) < 30,
                "a healthy step response left a residual of %.1f ppm, which is model error, not noise"
                        .formatted(residual));
        double amplitudeRatio = observer.stepAmplitudeRatio();
        assertTrue(amplitudeRatio > 0.8 && amplitudeRatio < 1.2,
                "a healthy step response delivered %.2f of the predicted amplitude"
                        .formatted(amplitudeRatio));
        assertEquals(0.80, observer.agreedPosition().value(), 1e-9,
                "a step the room answered did not move the agreed position to where it was sent");
    }

    @Test
    void shouldLeaveTheWindowOpenForTenMinutesAndThenClose() {
        MonitorParameters parameters = MonitorParameters.defaults();
        BuildingPhysics physics = new BuildingPhysics(42, 0);
        AhuFlowModel flow = new AhuFlowModel(Topology.airHandler(), physics.zones());
        ZoneObserver observer = new ZoneObserver(Topology.zone(ZONE), flow, parameters);
        observer.reset(500, START);

        observer.stepCommanded(new CommandIssued(ZONE, DamperPosition.of(0.80),
                ObservedAt.of(START), "open up", CommandId.newId()), 500, START);

        assertTrue(observer.windowOpen(START.plus(Duration.ofMinutes(9))));
        assertTrue(!observer.windowOpen(START.plus(Duration.ofMinutes(11))));
        assertTrue(observer.windowJustClosed(START.plus(Duration.ofMinutes(11)),
                parameters.cycleInterval()));
    }

    @Test
    void shouldIgnoreANudgeTooSmallToBeAStepResponse() {
        MonitorParameters parameters = MonitorParameters.defaults();
        BuildingPhysics physics = new BuildingPhysics(42, 0);
        AhuFlowModel flow = new AhuFlowModel(Topology.airHandler(), physics.zones());
        ZoneObserver observer = new ZoneObserver(Topology.zone(ZONE), flow, parameters);
        observer.reset(500, START);

        double nudged = Topology.MINIMUM_POSITION.value() + 0.01;
        observer.stepCommanded(new CommandIssued(ZONE, DamperPosition.of(nudged),
                ObservedAt.of(START), "a nudge", CommandId.newId()), 500, START);

        assertTrue(!observer.windowOpen(START), "a one-point nudge armed a judgement window");
        assertEquals(nudged, flow.positionOf(ZONE).value(), 1e-9,
                "the believed position must follow the command even when the step is not judged");
    }
}
