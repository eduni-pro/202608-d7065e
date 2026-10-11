package se.ltu.d7065e.evaluation.boundary;

import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.event.DomainEvent;

/**
 * The broker for the scaling sweep: counts what was published and keeps none of it.
 *
 * <p>T-28 drives tens of thousands of zones and the monitor publishes two events per zone per
 * cycle, so a publisher that remembered them would make the heap the breaking point instead of the
 * cycle time — and the number R-6 asks for would be a measurement of this class.
 */
public final class CountingPublisher implements EventPublisherPort {

    private long published;

    @Override
    public void publish(String topic, DomainEvent event) {
        published++;
    }

    public long published() {
        return published;
    }
}
