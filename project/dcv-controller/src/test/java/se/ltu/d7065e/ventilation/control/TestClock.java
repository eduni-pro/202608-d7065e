package se.ltu.d7065e.ventilation.control;

import java.time.Duration;
import java.time.Instant;
import se.ltu.d7065e.shared.control.ClockPort;

/** A clock the test moves by hand, which is the whole reason {@code ClockPort} exists. */
final class TestClock implements ClockPort {

    private Instant now;

    TestClock(Instant start) {
        this.now = start;
    }

    @Override
    public Instant now() {
        return now;
    }

    @Override
    public void waitFor(Duration duration) {
        advance(duration);
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }
}
