package se.ltu.d7065e.diagnosis.control;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The lowest CO2 a room reached over a trailing window — the third signal T-15 needs, and the
 * only one that needs more than a cycle of history.
 *
 * <p>It is the occupancy-free way to catch a drifting sensor: nobody is in the room at 03:00,
 * so a healthy sensor reads the outdoor concentration, about 420 ppm. A sensor whose zero has
 * walked reads its offset instead, and it reads it whatever the dampers do, which is exactly
 * what separates drift from a damper fault. No schedule and no head count is needed — the
 * minimum over a full day <em>is</em> the empty-room reading, because an office is empty for
 * part of every day.
 */
public final class OvernightFloor {

    private final Duration window;
    private final Deque<Sample> samples = new ArrayDeque<>();

    public OvernightFloor(Duration window) {
        this.window = window;
    }

    public void record(Instant at, double ppm) {
        samples.addLast(new Sample(at, ppm));
        Instant cutoff = at.minus(window);
        while (!samples.isEmpty() && samples.peekFirst().at().isBefore(cutoff)) {
            samples.removeFirst();
        }
    }

    /**
     * The floor, or {@code NaN} while the history is shorter than the window. {@code NaN} means
     * "no evidence either way", and {@link FaultClassifier} treats it as such rather than as a
     * healthy floor: a monitor that has been up for an hour has not seen a night.
     */
    public double floorPpm() {
        if (samples.size() < 2) {
            return Double.NaN;
        }
        Duration span = Duration.between(samples.peekFirst().at(), samples.peekLast().at());
        if (span.compareTo(window) < 0) {
            return Double.NaN;
        }
        double floor = Double.MAX_VALUE;
        for (Sample sample : samples) {
            floor = Math.min(floor, sample.ppm());
        }
        return floor;
    }

    public void clear() {
        samples.clear();
    }

    private record Sample(Instant at, double ppm) {}
}
