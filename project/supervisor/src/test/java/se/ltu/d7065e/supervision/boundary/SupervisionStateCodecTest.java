package se.ltu.d7065e.supervision.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.supervision.control.PlantSnapshot;
import se.ltu.d7065e.supervision.control.PlantState;
import se.ltu.d7065e.supervision.control.SupervisedCase;
import se.ltu.d7065e.supervision.control.SupervisionSnapshot;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** The supervisor's belief through the JSON document ADR-0012 stores, and back unchanged. */
@DisplayName("The stored supervision document")
class SupervisionStateCodecTest {

    private static final ZoneId FAULTED = Topology.A145;
    private static final Instant PROBED_AT = Instant.parse("2026-10-07T09:14:00Z");

    @Test
    void shouldReturnEveryFieldOfTheBeliefUnchanged() {
        SupervisionSnapshot before = compensating();

        SupervisionSnapshot after = SupervisionStateCodec.decode(
                SupervisionStateCodec.encode(before));

        assertEquals(before, after,
                "the document is the belief, so anything the round trip loses is something a "
                        + "restarted supervisor would be wrong about");
    }

    @Test
    void shouldKeepTheFrozenDamperPositionTheCompensationGainIsComputedFrom() {
        SupervisionSnapshot after = roundTripped(compensating());

        SupervisedCase open = after.cases().get(0);
        assertEquals(FAULTED, open.frozenZone().id());
        assertEquals(DamperPosition.of(0.15), open.frozenZone().commandedPosition());
        assertEquals(CaseId.of("case-A145-1"), open.caseId());
        assertEquals(FaultClassification.STUCK_DAMPER, open.classification());
    }

    @Test
    void shouldKeepTheBaselineDamperPositionsAndTheBaselineFanSpeed() {
        SupervisionSnapshot after = roundTripped(compensating());

        PlantSnapshot baseline = after.baseline();
        assertEquals(Topology.ZONE_IDS.size(), baseline.zones().size());
        assertEquals(DamperPosition.of(0.6), baseline.zone(Topology.A125).commandedPosition());
        assertEquals(FanSpeed.of(0.7), baseline.airHandler().commandedSpeed(),
                "the baseline fan speed is what the compensating speed is a multiple of");
        assertEquals(Topology.AIR_HANDLER, baseline.airHandler().id());
        assertEquals(Topology.ZONE_IDS, baseline.airHandler().servedZones());
    }

    @Test
    void shouldKeepTheRoomGeometryTheSolverComputedFromRatherThanOnlyTheRoomsName() {
        VentilationZone before = compensating().baseline().zone(Topology.A145);

        VentilationZone after = roundTripped(compensating()).baseline().zone(Topology.A145);

        assertEquals(before.damperAuthority(), after.damperAuthority(), 0.0,
                "the design flow is what the fallback gain is read against");
        assertEquals(before.volumeCubicMetres(), after.volumeCubicMetres(), 0.0);
        assertEquals(before.floorAreaSquareMetres(), after.floorAreaSquareMetres(), 0.0);
        assertEquals(before.minimumPosition(), after.minimumPosition());
        assertEquals(ControlMode.NORMAL, after.mode(),
                "the baseline is the plant before the episode, so its modes are the normal ones");
    }

    @Test
    void shouldKeepWhenEachZoneWasLastProbed() {
        Map<ZoneId, Instant> after = roundTripped(compensating()).lastProbes();

        assertEquals(Map.of(FAULTED, PROBED_AT), after,
                "to the instant, because the guard is an interval measured from it");
    }

    @Test
    void shouldCarryNoBaselineWhenNothingWasLatched() {
        SupervisionSnapshot nothingLatched = new SupervisionSnapshot(List.of(), null, Map.of());

        SupervisionSnapshot after = roundTripped(nothingLatched);

        assertTrue(after.cases().isEmpty());
        assertNull(after.baseline(),
                "a released episode has to be stored as released, or a restart would resume a "
                        + "case the technician closed");
    }

    @Test
    void shouldStoreNoneOfTheWorkOrderTextItWillRecompute() {
        String document = SupervisionStateCodec.encode(compensating());

        assertFalse(document.contains("Service the damper"),
                "the narrative is derived from the frozen zone, the diagnosis and the plan, so a "
                        + "copy of it in the document would be a second truth to keep in step: "
                        + document);
        assertFalse(document.contains("critical") || document.contains("warning"),
                "and the severity with it: " + document);
    }

    @Test
    void shouldRefuseADocumentThatIsNotADocument() {
        assertThrows(RuntimeException.class,
                () -> SupervisionStateCodec.decode("{\"cases\": truncated"),
                "a damaged document has to be refused here so the caller can start cold, rather "
                        + "than silently decode into a belief nobody held");
    }

    private static SupervisionSnapshot roundTripped(SupervisionSnapshot snapshot) {
        return SupervisionStateCodec.decode(SupervisionStateCodec.encode(snapshot));
    }

    /** One latched case over R-2's pinned scenario, with a probe already granted. */
    private static SupervisionSnapshot compensating() {
        List<VentilationZone> zones = Topology.zones().stream()
                .map(zone -> zone.id().equals(FAULTED) ? zone
                        : zone.withSupervisoryCommand(DamperPosition.of(0.6)))
                .toList();
        PlantSnapshot baseline = new PlantState(zones, Topology.airHandler()).snapshot();
        return new SupervisionSnapshot(
                List.of(new SupervisedCase(CaseId.of("case-A145-1"),
                        FaultClassification.STUCK_DAMPER, baseline.zone(FAULTED))),
                baseline,
                Map.of(FAULTED, PROBED_AT));
    }
}
