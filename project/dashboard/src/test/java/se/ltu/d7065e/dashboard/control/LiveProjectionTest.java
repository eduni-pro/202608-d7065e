package se.ltu.d7065e.dashboard.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.CusumStatistic;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.diagnosis.entity.Residual;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.ResidualComputed;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

class LiveProjectionTest {

    private static final Instant START = Instant.parse("2026-10-07T08:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofSeconds(180);

    private SimulatedClock clock;
    private LiveProjection projection;

    @BeforeEach
    void setUp() {
        clock = new SimulatedClock(START);
        projection = new LiveProjection(clock, STALE_AFTER);
    }

    @Test
    void shouldKeepTheLatestReadingPerRoom() {
        projection.observe(reading(Topology.A115, 600, START));
        projection.observe(reading(Topology.A115, 950, START.plusSeconds(60)));

        assertEquals(950.0, projection.room(Topology.A115).co2Ppm());
    }

    @Test
    void shouldNotLetAnEarlierReadingResurrect() {
        projection.observe(reading(Topology.A115, 950, START.plusSeconds(60)));
        projection.observe(reading(Topology.A115, 600, START));

        assertEquals(950.0, projection.room(Topology.A115).co2Ppm(),
                "a redelivered earlier observation must not displace the current one");
    }

    @Test
    void shouldReportRoomStaleWhenNoRecentEventArrived() {
        projection.observe(reading(Topology.A115, 700, START));
        clock.advance(STALE_AFTER.plusSeconds(1));

        RoomSnapshot room = projection.room(Topology.A115);
        assertTrue(room.stale());
        assertEquals(181, room.age().toSeconds());
    }

    @Test
    void shouldReportRoomFreshWhileInsideTheStalenessWindow() {
        projection.observe(reading(Topology.A115, 700, START));
        clock.advance(STALE_AFTER);

        assertFalse(projection.room(Topology.A115).stale());
    }

    @Test
    void shouldReportRoomStaleWhenNothingEverArrived() {
        RoomSnapshot room = projection.room(Topology.A124);

        assertTrue(room.stale());
        assertNull(room.co2Ppm());
        assertNull(room.age());
    }

    @Test
    void shouldRetainLatchedFaultStateWhenLaterResidualsAreSmall() {
        CaseId caseId = CaseId.of("case-1");
        projection.observe(residual(Topology.A115, 480, 900, START));
        projection.observe(fault(Topology.A115, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER, caseId, START.plusSeconds(30)));

        // What the supervisor's compensation does to the evidence: the residual collapses.
        projection.observe(residual(Topology.A115, 4, 0, START.plusSeconds(90)));

        RoomSnapshot room = projection.room(Topology.A115);
        assertEquals(MonitorState.CONFIRMED, room.monitorState());
        assertEquals(FaultClassification.STUCK_DAMPER, room.classification());
        assertEquals(caseId, room.caseId());
        assertEquals(4.0, room.residualPpm());
        assertTrue(room.isLatched());
        assertEquals(RoomLayer.STATE.maximum(), RoomLayer.STATE.valueIn(room),
                "a latched room must stay at the top of the state layer after compensation");
    }

    @Test
    void shouldIncludeAllSixRoomsInEveryLayer() {
        projection.observe(reading(Topology.A115, 820, START));

        for (RoomLayer layer : RoomLayer.values()) {
            Map<ZoneId, Double> values = projection.layerValues(layer);
            assertEquals(6, values.size(), layer + " must carry a value for every room");
            assertEquals(Topology.ZONE_IDS, List.copyOf(values.keySet()));
            values.forEach((zone, value) -> assertNotNullValue(layer, zone, value));
        }
        assertEquals(820.0, projection.layerValues(RoomLayer.CO2).get(Topology.A115));
        assertEquals(RoomLayer.CO2.minimum(), projection.layerValues(RoomLayer.CO2).get(Topology.A124),
                "a room that has never published renders at the bottom of the scale, not frozen");
    }

    @Test
    void shouldRenderStaleRoomAtTheLayerMinimumRatherThanItsLastValue() {
        projection.observe(reading(Topology.A115, 1400, START));
        clock.advance(STALE_AFTER.plusSeconds(1));

        assertEquals(RoomLayer.CO2.minimum(), projection.layerValues(RoomLayer.CO2).get(Topology.A115));
    }

    @Test
    void shouldApplyAStrategyChangeToThatRoomOnly() {
        projection.observe(strategy(Topology.A115, ControlMode.DEGRADED, START));

        assertEquals(ControlMode.DEGRADED, projection.room(Topology.A115).controlMode());
        for (ZoneId other : Topology.ZONE_IDS) {
            if (!other.equals(Topology.A115)) {
                assertEquals(ControlMode.NORMAL, projection.room(other).controlMode(),
                        other + " must keep its own control mode");
            }
        }
    }

    private static void assertNotNullValue(RoomLayer layer, ZoneId zone, Double value) {
        assertTrue(value != null && !value.isNaN(), layer + " has no value for " + zone);
    }

    private static ReadingObserved reading(ZoneId zone, double ppm, Instant at) {
        return new ReadingObserved(zone, Ppm.of(ppm), ObservedAt.of(at), Quality.GOOD);
    }

    private static ResidualComputed residual(ZoneId zone, double ppm, double cusum, Instant at) {
        return new ResidualComputed(zone, Residual.of(ppm), CusumStatistic.of(cusum),
                ObservedAt.of(at), null);
    }

    private static FaultStateChanged fault(ZoneId zone, MonitorState state,
            FaultClassification classification, CaseId caseId, Instant at) {
        return new FaultStateChanged(zone, state, classification,
                List.of("residual above h"), ObservedAt.of(at), caseId);
    }

    private static StrategyChanged strategy(ZoneId zone, ControlMode mode, Instant at) {
        return new StrategyChanged(zone, mode, null, ObservedAt.of(at), null);
    }
}
