package se.ltu.d7065e.supervision.boundary;

import jakarta.inject.Singleton;
import java.util.logging.Level;
import java.util.logging.Logger;
import se.ltu.d7065e.shared.control.ServiceStatePort;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.supervision.control.SupervisionService;

/**
 * The supervisor's belief, kept across a restart (ADR-0012). Every event reaches
 * {@link SupervisionService} through here, and the belief each one produces is written back to the
 * store before the next is taken.
 *
 * <p>This is the whole reason recovery lives in {@code boundary} and not in a constructor: the
 * evaluation harness builds the control classes directly against a simulated clock and never
 * reaches this class, so every published R-1, R-3 and R-6 figure comes off a supervisor that
 * neither loads nor saves anything.
 *
 * <h2>Why this class is synchronised and the service is not</h2>
 *
 * <p>{@link SupervisionService} holds plain maps and plain fields, and it got away with that only
 * because exactly one thread ever reached it: Paho dispatches every subscribed message on one
 * callback thread, and the scheduler's {@code SKIP} policy keeps a cycle from overlapping itself.
 * Recovery breaks the assumption — {@link #resume()} runs on the startup thread, which is a second
 * writer, and a boot-time restore interleaved with a first message would publish half of one
 * belief and half of another.
 *
 * <p>The choice is this object's own monitor across every path into the service, rather than locks
 * inside it. The service is the class the published figures are measured on, and a lock there is a
 * cost and a risk on code that has no concurrency of its own; the race, by contrast, is created
 * here, by the boundary, so it is answered here. Holding the monitor across both the decision and
 * the write also means the document on disk can never describe a belief the service does not hold.
 *
 * <p>Readings and inner-loop commands pass through the same monitor even though neither changes
 * what is stored, because serialising only the fault path would leave those maps exactly as
 * exposed as they were before. What {@link FaultSubscription} relies on is stronger than the lock
 * and is not provided by it: recovery runs <em>before</em> the first subscribe, so there is no
 * message for it to race. {@code MqttAdapter} connects with a clean session and a random client
 * id, so there is no backlog waiting — but equally no second chance at a {@code SERVICED}
 * published during the gap, which is why the gap is closed by ordering rather than by locking.
 *
 * <p>{@link SupervisorHealthCheck} still reads the service off an HTTP worker thread without this
 * monitor. That is left as it was: it is a reader of three display values, it raced the callback
 * thread before this class existed, and this change neither creates nor widens that race. Taking
 * the monitor there would let a health probe block behind a compensation cycle.
 */
@Singleton
public class DurableSupervision {

    private static final Logger LOG = Logger.getLogger(DurableSupervision.class.getName());

    private final SupervisionService supervision;
    private final ServiceStatePort state;

    public DurableSupervision(SupervisionService supervision, ServiceStatePort state) {
        this.supervision = supervision;
        this.state = state;
    }

    /**
     * Restores what the last run believed. Called once, before anything is subscribed.
     *
     * <p>A document that cannot be read is treated exactly as a document that is not there: the
     * supervisor starts cold, which is what it did before it could persist anything at all.
     * {@link ServiceStatePort#load()} already answers {@code ""} to an unreadable file; a document
     * that parses into nothing this plant recognises is the same answer one layer up, and it is
     * caught here rather than allowed to fail a boot. The store is never a startup dependency, and
     * resolving the zones before any mutation is what keeps the alternative to a full recovery a
     * cold start rather than half of one.
     */
    public synchronized void resume() {
        String document = state.load();
        if (document.isBlank()) {
            return;
        }
        try {
            supervision.recover(SupervisionStateCodec.decode(document));
        } catch (RuntimeException unusable) {
            LOG.log(Level.WARNING,
                    "starting cold: the stored state cannot be resumed from: " + document,
                    unusable);
        }
    }

    /**
     * One subscribed event, and the belief it produced written back before the next is taken.
     *
     * <p>Only a {@link FaultStateChanged} can change what is stored. A reading moves a transient
     * the next reading replaces, and an inner-loop command moves the running plant picture that
     * recovery and the cycle after it rebuild, so the write sits on the fault path alone — a write
     * per reading would be six a second to say that nothing changed.
     *
     * <p>A failed write is deliberately not caught. {@code MqttAdapter} already wraps every
     * handler call, logs what escapes and keeps the subscription, so the supervisor goes on
     * supervising and the failure is on the record instead of being swallowed — which is the
     * decision {@link ServiceStatePort#save} says belongs to the caller, taken rather than ducked.
     */
    public synchronized void accept(DomainEvent event) {
        supervision.accept(event);
        if (event instanceof FaultStateChanged) {
            state.save(SupervisionStateCodec.encode(supervision.snapshot()));
        }
    }
}
