package se.ltu.d7065e.shared.control;

import se.ltu.d7065e.shared.event.DomainEvent;

/**
 * Outbound events to the broker. Deliberately one port rather than one per event kind: the
 * authority rule the design cares about — the monitor raises events and never commands — is
 * enforced by which events a component constructs, not by which interface it holds.
 */
public interface EventPublisherPort {

    void publish(String topic, DomainEvent event);
}
