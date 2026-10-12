package se.ltu.d7065e.diagnosis.boundary;

import se.ltu.d7065e.shared.control.ServiceStatePort;

/**
 * What the monitor gets when {@code SERVICE_STATE_PATH} is unset: it keeps nothing and starts cold,
 * which is exactly what it did before ADR-0012.
 *
 * <p>The store holds an output of the monitor's own belief and is an input to nothing it diagnoses,
 * so a deployment without a volume has to degrade the restart behaviour rather than prevent a boot
 * — the same rule {@link DiscardingJournal} already follows for the journal, and R-4's "run without
 * a database" argument applied to a second optional store. {@code ""} is the port's own word for
 * "nothing to resume from", so a monitor wired to this one reads as a first boot for ever.
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
