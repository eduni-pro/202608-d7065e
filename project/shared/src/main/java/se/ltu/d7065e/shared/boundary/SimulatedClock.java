package se.ltu.d7065e.shared.boundary;

import java.time.Duration;
import java.time.Instant;
import se.ltu.d7065e.shared.control.ClockPort;

/**
 * Advances instantly, so the evaluation harness can replay a simulated week through the same
 * control classes the containers run. Without this, R-3's seven-day fault-free replay and
 * R-1's twenty injection runs could not be measured at all.
 */
public final class SimulatedClock implements ClockPort {

    private Instant now;

    public SimulatedClock(Instant start) {
        this.now = start;
    }

    @Override
    public Instant now() {
        return now;
    }

    @Override
    public void waitFor(Duration duration) {
        now = now.plus(duration);
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }
}
