package se.ltu.d7065e.shared.boundary;

import java.time.Duration;
import java.time.Instant;
import se.ltu.d7065e.shared.control.ClockPort;

/** Wall-clock time, for the containers. */
public final class SystemClock implements ClockPort {

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public void waitFor(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }
}
