package se.ltu.d7065e.evaluation.boundary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ResidualComputed;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The broker, replaced by a list and a queue. Every component in the harness publishes here;
 * what the harness routes onwards is decided by {@link #drain()}, not by the publisher, so a
 * component publishing while another component is mid-cycle cannot re-enter it.
 *
 * <p>Queueing rather than calling straight through is also the faithful choice: on MQTT the
 * supervisor sees a fault event after the monitor's cycle has returned, not inside it.
 */
public final class RecordingPublisher implements EventPublisherPort {

    private final List<Published> published = new ArrayList<>();
    private final Deque<Published> pending = new ArrayDeque<>();
    private final BiConsumer<String, DomainEvent> route;
    private boolean draining;
    private boolean recording = true;

    public RecordingPublisher(BiConsumer<String, DomainEvent> route) {
        this.route = route;
    }

    @Override
    public void publish(String topic, DomainEvent event) {
        if (recording) {
            published.add(new Published(topic, event));
        }
        pending.add(new Published(topic, event));
    }

    /** Delivers everything published since the last drain, including anything that produces. */
    public void drain() {
        if (draining) {
            return;
        }
        draining = true;
        try {
            while (!pending.isEmpty()) {
                Published next = pending.poll();
                route.accept(next.topic(), next.event());
            }
        } finally {
            draining = false;
        }
    }

    /** Drops what is queued without delivering it: a subscriber that is no longer there. */
    public void discardPending() {
        pending.clear();
    }

    /** Off for the long scaling runs, where keeping every event would be the memory bound. */
    public void recording(boolean on) {
        this.recording = on;
    }

    public List<Published> all() {
        return List.copyOf(published);
    }

    public void clear() {
        published.clear();
    }

    public List<FaultStateChanged> states() {
        return published.stream()
                .map(Published::event)
                .filter(FaultStateChanged.class::isInstance)
                .map(FaultStateChanged.class::cast)
                .toList();
    }

    /**
     * R-4 forbids fault events while BLIND but requires the monitor to report unavailability, so
     * the two are told apart the way {@code fault-monitor}'s own tests tell them apart: a state
     * report carrying a classification is a fault event, one carrying NONE is the report.
     */
    public List<FaultStateChanged> faultEvents(ZoneId zone) {
        return states().stream()
                .filter(e -> e.zone().equals(zone))
                .filter(e -> e.classification() != FaultClassification.NONE)
                .toList();
    }

    public List<FaultStateChanged> confirmations(ZoneId zone) {
        return states().stream()
                .filter(e -> e.zone().equals(zone))
                .filter(e -> e.state() == MonitorState.CONFIRMED)
                .toList();
    }

    public long confirmations() {
        return states().stream().filter(e -> e.state() == MonitorState.CONFIRMED).count();
    }

    /**
     * Distinct fault cases that reached CONFIRMED, which is what R-3's "one false alarm per week"
     * counts. {@link #confirmations()} counts published CONFIRMED <em>events</em>, and a latched
     * case republishes its state on every probe request and probe outcome, so one false alarm shows
     * up there as a dozen events.
     */
    public long confirmedCases() {
        return states().stream()
                .filter(e -> e.state() == MonitorState.CONFIRMED)
                .map(FaultStateChanged::caseId)
                .distinct()
                .count();
    }

    /**
     * Distinct confirmed cases in one zone. This is the count that is comparable with a threshold
     * detector's alarm episodes: a latched case republishes its state on every probe, so counting
     * events would charge the monitor a dozen alarms for one accusation.
     */
    public long confirmedCases(ZoneId zone) {
        return states().stream()
                .filter(e -> e.zone().equals(zone))
                .filter(e -> e.state() == MonitorState.CONFIRMED)
                .map(FaultStateChanged::caseId)
                .distinct()
                .count();
    }

    /** The zones that latched a case, with the classification each one first carried. */
    public java.util.Map<ZoneId, FaultClassification> latchedZones() {
        java.util.Map<ZoneId, FaultClassification> latched = new java.util.LinkedHashMap<>();
        states().stream()
                .filter(e -> e.state() == MonitorState.CONFIRMED)
                .forEach(e -> latched.putIfAbsent(e.zone(), e.classification()));
        return latched;
    }

    public List<ResidualComputed> residuals(ZoneId zone) {
        return published.stream()
                .map(Published::event)
                .filter(ResidualComputed.class::isInstance)
                .map(ResidualComputed.class::cast)
                .filter(e -> e.zone().equals(zone))
                .toList();
    }

    public long countOn(Class<? extends DomainEvent> type) {
        return published.stream().filter(p -> type.isInstance(p.event())).count();
    }

    public record Published(String topic, DomainEvent event) {}
}
