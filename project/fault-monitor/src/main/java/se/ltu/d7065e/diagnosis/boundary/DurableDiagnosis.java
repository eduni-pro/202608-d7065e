package se.ltu.d7065e.diagnosis.boundary;

import java.util.logging.Level;
import java.util.logging.Logger;
import se.ltu.d7065e.diagnosis.control.FaultMonitor;
import se.ltu.d7065e.shared.control.ServiceStatePort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;

/**
 * The monitor's belief, kept across a restart (ADR-0012). Every path into {@link FaultMonitor}
 * other than the health check's read passes through here, and the belief each one produces is
 * written back to the store before the next is taken.
 *
 * <p>This is the whole reason recovery lives in {@code boundary} and not in a constructor: the
 * evaluation harness builds the control classes directly against a simulated clock and never
 * reaches this class, so every published R-1, R-3, R-6 and R-7 figure comes off a monitor that
 * neither loads nor saves anything.
 *
 * <h2>Why this class is synchronised and the monitor is not</h2>
 *
 * <p>{@link FaultMonitor} holds plain maps and plain fields, and it got away with that only because
 * so few threads ever reached it: Paho dispatches every subscribed message on one callback thread,
 * and the scheduler's {@code SKIP} policy keeps a cycle from overlapping itself. It was never
 * actually safe — the callback thread and the scheduler thread are two writers to one state
 * machine, and a reading landing in the middle of a cycle's two phases could move a residual
 * between the phase that computes it and the phase that decides on it. Recovery adds a third
 * writer on the startup thread and makes the assumption indefensible rather than merely lucky.
 *
 * <p>The choice is this object's own monitor across every path in, rather than locks inside
 * {@link FaultMonitor}. The monitor is the class the published figures are measured on, and a lock
 * there is a cost and a risk on code that has no concurrency of its own; the race, by contrast,
 * belongs to the boundary that created the threads, so it is answered here. Holding the monitor
 * across both the decision and the write also means the document on disk can never describe a
 * belief the monitor does not hold.
 *
 * <p>Every path is taken, not just the ones that write. Readings and fan-power observations change
 * nothing that is stored, and the cycle is the only path that can latch anything, but serialising
 * the writing paths alone would leave the state machine exactly as exposed to a reading arriving
 * mid-cycle as it was before — which is narrowing the hole rather than closing it. Service reports
 * are taken here as a whole for a second reason: {@link ServiceRequests} reads the open case,
 * checks three things about it and then clears it, and that is a read-modify-write that was never
 * atomic against a cycle running beside it.
 *
 * <p>What the subscriptions rely on is stronger than the lock and is not provided by it: recovery
 * runs <em>before</em> the first subscribe, so there is no message for it to race.
 * {@code MqttAdapter} connects with a clean session and a random client id, so there is no backlog
 * waiting — but equally no second chance at a {@code ServiceReported} published during the gap,
 * which is why the gap is closed by ordering rather than by locking.
 *
 * <p>{@link MonitorHealthCheck} still reads the monitor off an HTTP worker thread without this
 * monitor. That is left as it was: it is a reader of six display values, it raced the callback
 * thread before this class existed, and this change neither creates nor widens that race. Taking
 * the monitor there would let a readiness probe block behind a diagnosis cycle.
 */
final class DurableDiagnosis {

    private static final Logger LOG = Logger.getLogger(DurableDiagnosis.class.getName());

    private final FaultMonitor monitor;
    private final ServiceRequests requests;
    private final ServiceStatePort state;

    DurableDiagnosis(FaultMonitor monitor, ServiceStatePort state) {
        this.monitor = monitor;
        this.requests = new ServiceRequests(monitor);
        this.state = state;
    }

    /**
     * Restores what the last run believed. Called once, before anything is subscribed.
     *
     * <p>A document that cannot be read is treated exactly as a document that is not there: the
     * monitor starts cold, which is what it did before it could persist anything at all.
     * {@link ServiceStatePort#load()} already answers {@code ""} to an unreadable file; a document
     * that parses into nothing this building recognises is the same answer one layer up, and it is
     * caught here rather than allowed to fail a boot. The store is never a startup dependency, and
     * resolving the zones before any mutation is what keeps the alternative to a full recovery a
     * cold start rather than half of one.
     */
    synchronized void resume() {
        String document = state.load();
        if (document.isBlank()) {
            return;
        }
        try {
            monitor.recover(DiagnosisStateCodec.decode(document));
        } catch (RuntimeException unusable) {
            LOG.log(Level.WARNING,
                    "starting cold: the stored state cannot be resumed from: " + document,
                    unusable);
        }
    }

    /**
     * One subscribed observation, and the belief it produced written back before the next is taken.
     *
     * <p>Only a command can change what is stored on this path. A damper command moves a believed
     * opening and a fan-speed command moves the believed speed, and neither re-derives on its own:
     * both publishers de-duplicate, so a belief lost here stays wrong until the position next
     * changes rather than until the next message. A reading and a fan-power observation move
     * transients the next sample replaces, so the write sits on the command path alone — a write
     * per reading would be six a second to say that nothing changed.
     *
     * <p>A failed write is deliberately not caught. {@code MqttAdapter} already wraps every handler
     * call, logs what escapes and keeps the subscription, so the monitor goes on monitoring and the
     * failure is on the record instead of being swallowed — which is the decision
     * {@link ServiceStatePort#save} says belongs to the caller, taken rather than ducked.
     */
    synchronized void accept(DomainEvent event) {
        monitor.accept(event);
        if (event instanceof CommandIssued || event instanceof FanSpeedCommanded) {
            save();
        }
    }

    /**
     * An operator's repair report (D-10), which is the one input that <em>clears</em> a latch, and
     * therefore the one whose loss would resurrect a case a technician had just closed.
     *
     * <p>A refused report throws out of here without writing, which is correct in both directions:
     * nothing changed, so there is nothing to store, and the refusal reaches the log by the route
     * {@link ServiceRequests} documents instead of being turned into a write.
     */
    synchronized void serviced(DomainEvent event) {
        requests.accept(event);
        save();
    }

    /**
     * One monitor cycle, and the belief it produced written back before the next message is taken.
     *
     * <p>This is the path that can latch, re-classify and clear, so it is the path the whole record
     * exists for, and it writes unconditionally rather than working out whether this particular
     * cycle changed anything. One small whole-object write a minute is beneath measuring, and a
     * cycle has five different ways to move the belief — a confirmation, a deferred diagnosis being
     * named, a probe outcome, a flow belief re-anchored by another room's pin, and a serviced case
     * restarting — so the alternative is five conditions to keep true rather than one write to
     * explain.
     */
    synchronized void runCycle() {
        monitor.runCycle();
        save();
    }

    private void save() {
        state.save(DiagnosisStateCodec.encode(monitor.snapshot()));
    }
}
