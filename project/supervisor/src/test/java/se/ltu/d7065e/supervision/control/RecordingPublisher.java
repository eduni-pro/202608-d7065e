package se.ltu.d7065e.supervision.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.shared.event.StrategyChanged;

/** Every publish the supervisor made, with the topic it went to. */
final class RecordingPublisher implements EventPublisherPort {

    private final List<Map.Entry<String, DomainEvent>> published = new ArrayList<>();

    @Override
    public void publish(String topic, DomainEvent event) {
        published.add(Map.entry(topic, event));
    }

    List<String> topics() {
        return published.stream().map(Map.Entry::getKey).toList();
    }

    boolean isEmpty() {
        return published.isEmpty();
    }

    List<StrategyChanged> strategies() {
        return of(StrategyChanged.class);
    }

    List<FanSpeedCommanded> fanSpeeds() {
        return of(FanSpeedCommanded.class);
    }

    List<CommandIssued> damperCommands() {
        return of(CommandIssued.class);
    }

    String topicOf(DomainEvent event) {
        return published.stream().filter(entry -> entry.getValue() == event)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    void clear() {
        published.clear();
    }

    private <T extends DomainEvent> List<T> of(Class<T> type) {
        return published.stream().map(Map.Entry::getValue).filter(type::isInstance)
                .map(type::cast).toList();
    }
}
