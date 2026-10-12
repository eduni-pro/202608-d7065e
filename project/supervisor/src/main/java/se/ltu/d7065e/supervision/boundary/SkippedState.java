package se.ltu.d7065e.supervision.boundary;

import se.ltu.d7065e.shared.control.ServiceStatePort;

/**
 * What the supervisor gets when {@code SERVICE_STATE_PATH} is unset: it keeps nothing and starts
 * cold, which is exactly what it did before ADR-0012.
 *
 * <p>The store holds an output of the supervisor's own belief and is an input to nothing it
 * decides, so a deployment without a volume has to degrade the restart behaviour rather than
 * prevent a boot — the same rule {@link SkippedJournal} and {@link SkippedWorkOrders} already
 * follow for the journal and the operator's 3D view. {@code ""} is the port's own word for
 * "nothing to resume from", so a supervisor wired to this one reads as a first boot for ever.
 */
final class SkippedState implements ServiceStatePort {

    @Override
    public void save(String document) {
    }

    @Override
    public String load() {
        return "";
    }
}
