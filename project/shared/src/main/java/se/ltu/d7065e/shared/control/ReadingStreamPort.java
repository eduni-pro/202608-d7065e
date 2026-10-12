package se.ltu.d7065e.shared.control;

import java.util.function.Consumer;
import se.ltu.d7065e.shared.event.DomainEvent;

/**
 * Inbound events from the broker. A dead publisher goes silent rather than blocking a caller,
 * which is what lets the monitor reach BLIND instead of hanging (ADR-0002, R-4).
 */
public interface ReadingStreamPort extends AutoCloseable {

    void subscribe(String topicFilter, Consumer<DomainEvent> handler);

    @Override
    void close();
}
