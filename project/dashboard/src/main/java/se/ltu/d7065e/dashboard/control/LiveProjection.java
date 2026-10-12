package se.ltu.d7065e.dashboard.control;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanPowerObserved;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.ResidualComputed;
import se.ltu.d7065e.shared.event.ServiceReported;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.ventilation.entity.AhuId;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The latest value per room, held in memory. This is a live view and not a store: the journal
 * is the store (R-5), so nothing here is persisted and losing it costs one refresh.
 *
 * <p>Two properties matter more than anything else this class does.
 *
 * <p><strong>A latched fault state is never recomputed from measurements.</strong> Only a
 * {@link FaultStateChanged} moves a room's monitor state, so the arrival of small residuals —
 * which is precisely what the supervisor's compensation produces — cannot talk the view out of
 * a diagnosis the monitor latched. The view would otherwise contradict the monitor on exactly
 * the case the project exists to handle.
 *
 * <p><strong>A measurement that stops arriving goes stale rather than freezing.</strong> Each
 * room carries the instant of its last measured signal, and a room past the staleness window is
 * reported stale so no consumer renders an old number as if it were current.
 *
 * <p>Writes arrive on the broker's single callback thread while reads come from HTTP and
 * scheduler threads, so every public method is synchronised.
 */
public final class LiveProjection {

    private final ClockPort clock;
    private final Duration stalenessWindow;
    private final Map<ZoneId, RoomState> rooms = new LinkedHashMap<>();
    // Keyed by case so a room that opens a second one does not overwrite the first, and ordered
    // by first sighting so cases() can present a stable table rather than a reshuffling one.
    private final Map<CaseId, CaseState> cases = new LinkedHashMap<>();
    private final AirHandlerState airHandler = new AirHandlerState();

    public LiveProjection(ClockPort clock, Duration stalenessWindow) {
        if (clock == null || stalenessWindow == null || stalenessWindow.isNegative()) {
            throw new IllegalArgumentException("a projection needs a clock and a staleness window");
        }
        this.clock = clock;
        this.stalenessWindow = stalenessWindow;
        // Seeded with the configured topology so every layer and every table row covers all six
        // rooms from the first tick, including rooms that have never published.
        Topology.ZONE_IDS.forEach(zone -> rooms.put(zone, new RoomState()));
    }

    /** The one entry point for inbound events, whatever topic they arrived on. */
    public synchronized void observe(DomainEvent event) {
        switch (event) {
            case ReadingObserved e -> applyReading(e);
            case ResidualComputed e -> applyResidual(e);
            case FaultStateChanged e -> applyFaultState(e);
            case StrategyChanged e -> applyStrategy(e);
            case FanPowerObserved e -> applyFanPower(e);
            case FanSpeedCommanded e -> applyFanSpeed(e);
            // Damper commands are the inner loop's business; the view shows their effect
            // through the residual, not the command itself.
            case CommandIssued ignored -> { }
            // A service report is a request, not an outcome. The room moves when the monitor
            // answers it with FaultStateChanged(SERVICED), so a report the monitor goes on to
            // refuse cannot talk the view out of a latched case either.
            case ServiceReported ignored -> { }
        }
    }

    public synchronized List<RoomSnapshot> rooms() {
        Instant now = clock.now();
        List<RoomSnapshot> snapshots = new ArrayList<>(rooms.size());
        rooms.forEach((zone, state) -> snapshots.add(state.snapshotOf(zone, now)));
        return List.copyOf(snapshots);
    }

    /**
     * Every case this view has seen, open ones first and the newest first within each group.
     *
     * <p>Open before closed because an open case is work outstanding and a closed one is
     * history, and a table that sorted purely by time would bury a fault that is still running
     * under the repairs that followed it.
     */
    public synchronized List<CaseSnapshot> cases() {
        return cases.entrySet().stream()
                .map(entry -> entry.getValue().snapshotOf(entry.getKey()))
                .sorted(Comparator.comparing(CaseSnapshot::isOpen).reversed()
                        .thenComparing(Comparator.comparing(CaseSnapshot::updatedAt).reversed()))
                .toList();
    }

    public synchronized RoomSnapshot room(ZoneId zone) {
        RoomState state = rooms.get(zone);
        if (state == null) {
            throw new IllegalArgumentException("not one of the "
                    + Topology.ZONE_IDS.size() + " configured rooms: " + zone);
        }
        return state.snapshotOf(zone, clock.now());
    }

    public synchronized AirHandlerSnapshot airHandler() {
        return airHandler.snapshotAt(clock.now());
    }

    /** The complete value map one layer needs, all six rooms, in topology order. */
    public synchronized Map<ZoneId, Double> layerValues(RoomLayer layer) {
        Instant now = clock.now();
        Map<ZoneId, Double> values = new LinkedHashMap<>();
        rooms.forEach((zone, state) -> values.put(zone, layer.valueIn(state.snapshotOf(zone, now))));
        return values;
    }

    /**
     * The instant the view is rendered at, on the same timeline as every age it shows. The two
     * views ask for it here rather than calling {@code Instant.now()}: under {@code TIME_SCALE}
     * a wall-clock heading above simulated ages would be two notions of time on one page.
     */
    public Instant now() {
        return clock.now();
    }

    public Duration stalenessWindow() {
        return stalenessWindow;
    }

    private void applyReading(ReadingObserved event) {
        RoomState room = rooms.get(event.zone());
        if (room == null || !isNewer(event.at().value(), room.co2At)) {
            return;
        }
        room.co2Ppm = event.ppm().value();
        room.co2At = event.at().value();
    }

    private void applyResidual(ResidualComputed event) {
        RoomState room = rooms.get(event.zone());
        if (room == null || !isNewer(event.at().value(), room.residualAt)) {
            return;
        }
        room.residualPpm = event.residual().ppm();
        room.cusum = event.cusum().value();
        room.residualAt = event.at().value();
    }

    private void applyFaultState(FaultStateChanged event) {
        RoomState room = rooms.get(event.zone());
        if (room == null || !isNewer(event.at().value(), room.faultAt)) {
            return;
        }
        room.monitorState = event.state();
        room.classification = event.classification();
        room.caseId = event.caseId();
        room.faultAt = event.at().value();
        recordCase(event);
    }

    /**
     * Keeps the case itself, which outlives the room's hold on it.
     *
     * <p>Guarded on the event instant for the same reason {@link #applyFaultState} is: events
     * arrive on one subscription but are published by a monitor that may have republished an
     * older state, and a case that went backwards from SERVICED to COMPENSATING in this table
     * would be reporting a repair undone that never was.
     */
    private void recordCase(FaultStateChanged event) {
        if (event.caseId() == null) {
            return;
        }
        CaseState existing = cases.get(event.caseId());
        if (existing == null) {
            CaseState opened = new CaseState();
            opened.zone = event.zone();
            opened.openedAt = event.at().value();
            apply(opened, event);
            cases.put(event.caseId(), opened);
            return;
        }
        if (isNewer(event.at().value(), existing.updatedAt)) {
            apply(existing, event);
        }
    }

    private static void apply(CaseState state, FaultStateChanged event) {
        state.state = event.state();
        state.classification = event.classification();
        state.evidence = event.evidence();
        state.updatedAt = event.at().value();
    }

    private void applyStrategy(StrategyChanged event) {
        RoomState room = rooms.get(event.zone());
        if (room == null || !isNewer(event.at().value(), room.strategyAt)) {
            return;
        }
        room.controlMode = event.mode();
        room.strategyAt = event.at().value();
    }

    private void applyFanPower(FanPowerObserved event) {
        if (!isNewer(event.at().value(), airHandler.powerAt)) {
            return;
        }
        airHandler.ahu = event.ahu();
        airHandler.watts = event.watts().watts();
        airHandler.powerAt = event.at().value();
    }

    private void applyFanSpeed(FanSpeedCommanded event) {
        if (!isNewer(event.at().value(), airHandler.speedAt)) {
            return;
        }
        airHandler.ahu = event.ahu();
        airHandler.fanSpeed = event.speed().value();
        airHandler.speedAt = event.at().value();
    }

    /**
     * QoS 1 redelivers, and a publisher that restarts can repeat an older observation, so an
     * event that is not strictly newer than the one held is dropped: the latest value per room
     * wins and an earlier one cannot resurrect.
     */
    private static boolean isNewer(Instant candidate, Instant held) {
        return held == null || candidate.isAfter(held);
    }

    private boolean isStale(Instant lastMeasuredAt, Instant now) {
        return lastMeasuredAt == null
                || Duration.between(lastMeasuredAt, now).compareTo(stalenessWindow) > 0;
    }

    private static Instant later(Instant one, Instant other) {
        if (one == null) {
            return other;
        }
        return other == null || one.isAfter(other) ? one : other;
    }

    /** Mutable per-room accumulator; {@link RoomSnapshot} is the immutable view of it. */
    private final class RoomState {

        private Double co2Ppm;
        private Instant co2At;
        private Double residualPpm;
        private Double cusum;
        private Instant residualAt;
        private MonitorState monitorState = MonitorState.WARMUP;
        private FaultClassification classification = FaultClassification.NONE;
        private CaseId caseId;
        private Instant faultAt;
        private ControlMode controlMode = ControlMode.NORMAL;
        private Instant strategyAt;

        private RoomSnapshot snapshotOf(ZoneId zone, Instant now) {
            // Staleness follows the two periodic measured signals only. A fault or strategy
            // event arrives once and would otherwise make a room whose readings have stopped
            // look alive again.
            Instant lastMeasuredAt = later(co2At, residualAt);
            return new RoomSnapshot(zone, co2Ppm, residualPpm, cusum, monitorState, classification,
                    controlMode, caseId, lastMeasuredAt,
                    lastMeasuredAt == null ? null : Duration.between(lastMeasuredAt, now),
                    isStale(lastMeasuredAt, now));
        }
    }

    private final class AirHandlerState {

        private AhuId ahu = Topology.AIR_HANDLER;
        private Double watts;
        private Instant powerAt;
        private Double fanSpeed;
        private Instant speedAt;

        private AirHandlerSnapshot snapshotAt(Instant now) {
            return new AirHandlerSnapshot(ahu, watts, fanSpeed, powerAt,
                    powerAt == null ? null : Duration.between(powerAt, now),
                    isStale(powerAt, now));
        }
    }

    /** The mutable half of {@link CaseSnapshot}, held under the projection's own lock. */
    private static final class CaseState {

        private ZoneId zone;
        private MonitorState state;
        private FaultClassification classification;
        private List<String> evidence = List.of();
        private Instant openedAt;
        private Instant updatedAt;

        private CaseSnapshot snapshotOf(CaseId caseId) {
            return new CaseSnapshot(caseId, zone, state, classification, openedAt, updatedAt,
                    evidence);
        }
    }
}
