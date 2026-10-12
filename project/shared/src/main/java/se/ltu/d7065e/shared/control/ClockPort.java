package se.ltu.d7065e.shared.control;

import java.time.Duration;
import java.time.Instant;

/**
 * Time, as a port. The evaluation harness drives thousands of simulated hours through the
 * same control classes the containers run, so nothing in {@code control} may call
 * {@code Instant.now()} or {@code Thread.sleep} directly.
 */
public interface ClockPort {

    Instant now();

    /** Advances to the next tick. A simulated clock returns immediately. */
    void waitFor(Duration duration) throws InterruptedException;
}
