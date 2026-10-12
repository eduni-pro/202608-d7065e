package se.ltu.d7065e.diagnosis.control;

import java.util.ArrayList;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ResidualComputed;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** Captures what the monitor published, so the tests can assert on the topic as well as the event. */
final class RecordingPublisher implements EventPublisherPort {

    private final List<Published> published = new ArrayList<>();

    @Override
    public void publish(String topic, DomainEvent event) {
        published.add(new Published(topic, event));
    }

    List<Published> all() {
        return List.copyOf(published);
    }

    List<String> topics() {
        return published.stream().map(Published::topic).distinct().toList();
    }

    void clear() {
        published.clear();
    }

    List<ResidualComputed> residuals(ZoneId zone) {
        return published.stream()
                .map(Published::event)
                .filter(ResidualComputed.class::isInstance)
                .map(ResidualComputed.class::cast)
                .filter(e -> e.zone().equals(zone))
                .toList();
    }

    List<FaultStateChanged> states(ZoneId zone) {
        return published.stream()
                .map(Published::event)
                .filter(FaultStateChanged.class::isInstance)
                .map(FaultStateChanged.class::cast)
                .filter(e -> e.zone().equals(zone))
                .toList();
    }

    /**
     * R-4 forbids fault events while BLIND but requires the monitor to report unavailable, so the
     * two have to be told apart: a state report carrying no classification is the report, and a
     * state report carrying one is the fault event.
     */
    List<FaultStateChanged> faultEvents(ZoneId zone) {
        return states(zone).stream()
                .filter(e -> e.classification() != FaultClassification.NONE)
                .toList();
    }

    long confirmations() {
        return published.stream()
                .map(Published::event)
                .filter(FaultStateChanged.class::isInstance)
                .map(FaultStateChanged.class::cast)
                .filter(e -> e.state() == se.ltu.d7065e.diagnosis.entity.MonitorState.CONFIRMED)
                .count();
    }

    /**
     * Distinct fault cases that reached CONFIRMED, which is what R-3 counts. {@link #confirmations()}
     * counts published CONFIRMED <em>events</em>, and a latched case republishes its state on every
     * probe request and probe outcome, so one false alarm shows up there as a dozen events.
     */
    long confirmedCases() {
        return published.stream()
                .map(Published::event)
                .filter(FaultStateChanged.class::isInstance)
                .map(FaultStateChanged.class::cast)
                .filter(e -> e.state() == se.ltu.d7065e.diagnosis.entity.MonitorState.CONFIRMED)
                .map(FaultStateChanged::caseId)
                .distinct()
                .count();
    }

    /** The zones that latched a case, with the classification each one carried. */
    java.util.Map<ZoneId, FaultClassification> latchedZones() {
        java.util.Map<ZoneId, FaultClassification> latched = new java.util.LinkedHashMap<>();
        published.stream()
                .map(Published::event)
                .filter(FaultStateChanged.class::isInstance)
                .map(FaultStateChanged.class::cast)
                .filter(e -> e.state() == se.ltu.d7065e.diagnosis.entity.MonitorState.CONFIRMED)
                .forEach(e -> latched.putIfAbsent(e.zone(), e.classification()));
        return latched;
    }

    long suspicions() {
        return published.stream()
                .map(Published::event)
                .filter(FaultStateChanged.class::isInstance)
                .map(FaultStateChanged.class::cast)
                .filter(e -> e.state() == se.ltu.d7065e.diagnosis.entity.MonitorState.SUSPECT)
                .count();
    }

    record Published(String topic, DomainEvent event) {}
}
