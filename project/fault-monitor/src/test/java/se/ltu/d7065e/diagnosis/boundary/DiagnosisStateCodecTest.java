package se.ltu.d7065e.diagnosis.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.control.DiagnosisSnapshot;
import se.ltu.d7065e.diagnosis.control.LatchedCase;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.Evidence;
import se.ltu.d7065e.diagnosis.entity.FaultCase;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** The monitor's belief through the JSON document ADR-0012 stores, and back unchanged. */
@DisplayName("The stored diagnosis document")
class DiagnosisStateCodecTest {

    private static final ZoneId FAULTED = Topology.A115;
    private static final CaseId CASE = CaseId.of("case-A115-1");
    private static final Instant OPENED_AT = Instant.parse("2026-10-07T09:00:00Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-07T09:11:00Z");
    private static final DamperPosition PINNED = DamperPosition.of(0.15);

    @Test
    void shouldReturnEveryFieldOfTheBeliefUnchanged() {
        DiagnosisSnapshot before = latched();

        DiagnosisSnapshot after = DiagnosisStateCodec.decode(DiagnosisStateCodec.encode(before));

        assertEquals(before, after,
                "the document is the belief, so anything the round trip loses is something a "
                        + "restarted monitor would be wrong about");
    }

    @Test
    void shouldKeepTheLatchTheCaseIdentityAndTheDiagnosis() {
        LatchedCase open = roundTripped(latched()).cases().get(0);

        assertEquals(CASE, open.openCase().id());
        assertEquals(FAULTED, open.zone());
        assertEquals(MonitorState.CONFIRMED, open.openCase().state(),
                "CONFIRMED and COMPENSATING are different things to come back as: one has been "
                        + "acted on and the other has not");
        assertEquals(FaultClassification.STUCK_DAMPER, open.openCase().classification());
        assertEquals(CONFIRMED_AT, open.confirmedAt());
    }

    @Test
    void shouldKeepTheEvidenceWithTheSideEachObservationArguedFor() {
        FaultCase open = roundTripped(latched()).cases().get(0).openCase();

        assertEquals(2, open.evidence().size());
        assertTrue(open.evidence().get(0).supportsFault());
        assertFalse(open.evidence().get(1).supportsFault(),
                "evidence against a hypothesis is what lets R-5 argue with the result, so a "
                        + "document that flattened the sides would store a one-sided case");
        assertEquals(OPENED_AT, open.evidence().get(0).at().value());
    }

    @Test
    void shouldKeepThePinAndTheOpeningItIsPinnedAt() {
        DiagnosisSnapshot after = roundTripped(latched());

        assertEquals(Set.of(FAULTED), after.unresponsiveDampers());
        assertEquals(PINNED, after.believedPositions().get(FAULTED),
                "the pin is a flag and a value, and the value is what the other five rooms' "
                        + "residuals are computed from");
    }

    @Test
    void shouldKeepEveryRoomsBelievedOpeningAndTheBelievedFanSpeed() {
        DiagnosisSnapshot after = roundTripped(latched());

        assertEquals(Topology.ZONE_IDS.size(), after.believedPositions().size());
        assertEquals(DamperPosition.of(0.6), after.believedPositions().get(Topology.A125),
                "an unpinned opening is corrected by its next command, and the inner loop issues "
                        + "one only when a position changes — which on a typical tick is never");
        assertEquals(FanSpeed.of(0.85), after.believedFanSpeed());
    }

    @Test
    void shouldRoundTripABeliefWithNothingLatchedInIt() {
        DiagnosisSnapshot cold = new DiagnosisSnapshot(List.of(), FanSpeed.NOMINAL,
                positions(Topology.MINIMUM_POSITION.value()), Set.of());

        assertEquals(cold, roundTripped(cold),
                "a monitor with nothing latched still believes where the dampers are, so there is "
                        + "a document to write before anything is ever confirmed");
    }

    @Test
    void shouldRefuseATruncatedDocumentRatherThanDecodeHalfOfIt() {
        assertThrows(RuntimeException.class,
                () -> DiagnosisStateCodec.decode("{\"cases\": [{\"confirmedAt\": truncated"),
                "the boundary turns this into a cold start, which it can only do if the codec "
                        + "says the document is unusable instead of returning part of one");
    }

    private static DiagnosisSnapshot roundTripped(DiagnosisSnapshot before) {
        return DiagnosisStateCodec.decode(DiagnosisStateCodec.encode(before));
    }

    /** One room latched STUCK_DAMPER with its damper pinned, which is R-1's scenario. */
    private static DiagnosisSnapshot latched() {
        Map<ZoneId, DamperPosition> believed = positions(0.6);
        believed.put(FAULTED, PINNED);
        return new DiagnosisSnapshot(List.of(new LatchedCase(stuckDamper(), CONFIRMED_AT)),
                FanSpeed.of(0.85), believed, Set.of(FAULTED));
    }

    private static FaultCase stuckDamper() {
        return new FaultCase(CASE, FAULTED, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER,
                List.of(Evidence.supporting("upper-arm CUSUM 212.4 ppm passed h = 150 ppm",
                                ObservedAt.of(OPENED_AT)),
                        Evidence.against("the room answered the step, which is also what "
                                + "successful compensation looks like",
                                ObservedAt.of(CONFIRMED_AT))),
                ObservedAt.of(OPENED_AT), ObservedAt.of(CONFIRMED_AT));
    }

    private static Map<ZoneId, DamperPosition> positions(double opening) {
        Map<ZoneId, DamperPosition> believed = new LinkedHashMap<>();
        Topology.ZONE_IDS.forEach(zone -> believed.put(zone, DamperPosition.of(opening)));
        return believed;
    }
}
