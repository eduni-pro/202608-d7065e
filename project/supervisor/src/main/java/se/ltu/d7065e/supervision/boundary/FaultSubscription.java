package se.ltu.d7065e.supervision.boundary;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import se.ltu.d7065e.shared.control.ReadingStreamPort;
import se.ltu.d7065e.shared.event.Topics;

/**
 * The supervisor's inbound side. Three subscriptions, and the reason for each:
 *
 * <ul>
 *   <li>{@link Topics#ALL_FAULTS} — the only input it acts on, and only when latched.
 *   <li>{@link Topics#ALL_CO2_READINGS} — how much airflow the faulted room needs is read off
 *       its own CO2, so the compensation is sized by measurement rather than by a guessed
 *       occupancy.
 *   <li>{@link Topics#ALL_DAMPER_COMMANDS} — the supervisor has no damper feedback on the
 *       broker, so the inner loop's commands are how it learns where each damper is sitting, and
 *       therefore what position a frozen one froze at. It <em>listens</em> here; the only damper
 *       command it ever <em>publishes</em> is the T-18 probe.
 * </ul>
 *
 * Nothing is published on a reading or a residual topic from anywhere in this component. The
 * supervisor's outputs are {@code strategy/{zone}}, {@code commands/{ahu}/fan-speed} and the
 * per-room probe on {@code commands/{zone}/damper}.
 *
 * <p>What the last run believed is restored first, in this method and not in a second startup
 * observer, because the order matters and nothing about {@code @Observes} guarantees it: the
 * broker connects with a clean session and a random client id, so no backlog is waiting to be
 * raced — but there is also no second chance at a message published in the gap, and a recovery
 * that ran after the first {@code SERVICED} arrived would resurrect a case a technician had just
 * closed.
 */
@Singleton
public class FaultSubscription {

    private final ReadingStreamPort stream;
    private final DurableSupervision supervision;

    public FaultSubscription(ReadingStreamPort stream, DurableSupervision supervision) {
        this.stream = stream;
        this.supervision = supervision;
    }

    void subscribeOnStartup(@Observes StartupEvent startup) {
        supervision.resume();
        stream.subscribe(Topics.ALL_FAULTS, supervision::accept);
        stream.subscribe(Topics.ALL_CO2_READINGS, supervision::accept);
        stream.subscribe(Topics.ALL_DAMPER_COMMANDS, supervision::accept);
    }
}
