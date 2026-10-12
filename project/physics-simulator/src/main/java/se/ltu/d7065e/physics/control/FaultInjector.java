package se.ltu.d7065e.physics.control;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import se.ltu.d7065e.physics.entity.FaultKind;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Holds the scheduled faults and answers, for a given instant, what the plant actually does
 * (T-14). The simulator asks it between reading a command and applying its physical effect,
 * which is the whole point: the command record says one thing and the air does another.
 *
 * <p>A fault is per room or plant-wide as its {@link FaultKind} says, so injecting one leaves
 * the other five rooms running on their own commands (R-7).
 *
 * <p>Where two faults of the same kind contend for the same target, the most recently injected
 * one wins, which is what makes re-injecting a fault at a new magnitude mean what it looks
 * like. Sensor errors on one sensor accumulate instead, because two wrong things about one
 * instrument would: a drifting element that was also miscalibrated reads both errors at once.
 *
 * <p>A fault ends by being <em>removed</em> — {@link #remove} or {@link #clear} — rather than by
 * growing an end instant on {@link InjectedFault}. That is deliberate and not merely economical:
 * the record is the ground truth every published R-1/R-3/R-6 figure was scored against, and
 * {@link InjectedFault#isActiveAt} must keep meaning for an archived run exactly what it meant
 * when the run was scored. Removal leaves that arithmetic untouched and still lets an operator
 * repair the plant mid-demo.
 */
public final class FaultInjector {

    /**
     * Copy-on-write, and that is load-bearing rather than cautious. The read methods below
     * iterate this list several times per simulated second on the main thread, while
     * {@code FaultControlSurface} adds to it and removes from it on an HTTP handler thread the
     * moment an operator curls it — so a plain {@code ArrayList} would hand the tick loop a
     * {@link java.util.ConcurrentModificationException}, or a torn read, the first time a fault
     * was injected mid-tick. Copying on write puts the whole cost on the writer, of which a demo
     * sees a handful, and gives every iteration a snapshot that cannot change under it with no
     * lock anywhere on the per-tick path.
     */
    private final List<InjectedFault> faults = new CopyOnWriteArrayList<>();

    public void inject(InjectedFault fault) {
        faults.add(fault);
    }

    public void injectAll(Collection<InjectedFault> injected) {
        injected.forEach(this::inject);
    }

    /** Every scheduled fault, biting or not, in the order they were injected. */
    public List<InjectedFault> scheduled() {
        return List.copyOf(faults);
    }

    /** Drops every scheduled fault and reports how many there were to drop. */
    public int clear() {
        int scheduled = faults.size();
        faults.clear();
        return scheduled;
    }

    /**
     * Drops the scheduled faults of one kind on one target and reports how many went, leaving
     * every other room's fault in place — which is what makes repairing one damper mid-demo a
     * repair rather than a reset.
     *
     * @param zone the room to clear, or {@code null} for a plant-wide kind
     */
    public int remove(FaultKind kind, ZoneId zone) {
        int before = faults.size();
        faults.removeIf(fault -> fault.kind() == kind && Objects.equals(fault.zone(), zone));
        return before - faults.size();
    }

    /** The ground-truth marker for a tick: which faults were biting while it was computed. */
    public List<InjectedFault> activeAt(Instant now) {
        return faults.stream().filter(fault -> fault.isActiveAt(now)).toList();
    }

    /** The position the damper physically holds, which a stuck damper holds against the command. */
    public DamperPosition appliedPosition(ZoneId zone, DamperPosition commanded, Instant now) {
        DamperPosition applied = commanded;
        for (InjectedFault fault : faults) {
            if (fault.kind() == FaultKind.STUCK_DAMPER && fault.affects(zone) && fault.isActiveAt(now)) {
                applied = DamperPosition.of(fault.magnitude());
            }
        }
        return applied;
    }

    /** Filter blockage beta on the shared air handler: 0 when no filter fault is active. */
    public double filterBlockage(Instant now) {
        double blockage = 0;
        for (InjectedFault fault : faults) {
            if (fault.kind() == FaultKind.BLOCKED_FILTER && fault.isActiveAt(now)) {
                blockage = fault.magnitude();
            }
        }
        return blockage;
    }

    /**
     * How many ppm the sensor adds to the room's true concentration: a ramp's accumulated drift, a
     * step offset's fixed error, and both together when both are injected. The room's air is
     * untouched either way — that is what makes these sensor faults rather than airflow faults.
     */
    public double sensorErrorPpm(ZoneId zone, Instant now) {
        double error = 0;
        for (InjectedFault fault : faults) {
            if (fault.affects(zone)) {
                error += fault.sensorErrorPpmAt(now);
            }
        }
        return error;
    }
}
