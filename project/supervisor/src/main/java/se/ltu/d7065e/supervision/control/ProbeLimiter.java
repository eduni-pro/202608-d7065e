package se.ltu.d7065e.supervision.control;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * At most one active probe per zone per interval (T-18).
 *
 * <p>A probe is a deliberate disturbance of a room that is already in trouble, and the monitor
 * may ask for one on every cycle while a case stays open. The rate limit is per zone and never
 * plant-wide, which matches the authority split: the probe itself is per room, because a
 * fan-speed probe would perturb all six rooms and is out (ADR-0008 consequence 8).
 */
public final class ProbeLimiter {

    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(15);

    private final ClockPort clock;
    private final Duration minimumInterval;
    private final Map<ZoneId, Instant> lastProbe = new HashMap<>();

    public ProbeLimiter(ClockPort clock) {
        this(clock, DEFAULT_INTERVAL);
    }

    public ProbeLimiter(ClockPort clock, Duration minimumInterval) {
        this.clock = clock;
        this.minimumInterval = minimumInterval;
    }

    /** Grants a probe slot for this zone, consuming it. False means the zone probed too recently. */
    public boolean claim(ZoneId zone) {
        Instant now = clock.now();
        Instant previous = lastProbe.get(zone);
        if (previous != null && Duration.between(previous, now).compareTo(minimumInterval) < 0) {
            return false;
        }
        lastProbe.put(zone, now);
        return true;
    }

    /** Forgets a zone's history, so a serviced and re-faulted damper may be probed at once. */
    public void forget(ZoneId zone) {
        lastProbe.remove(zone);
    }

    /**
     * When each zone was last probed, as an immutable value rather than a view of this map, so
     * that what a caller stores is what was true when it asked.
     */
    public Map<ZoneId, Instant> lastProbes() {
        return Map.copyOf(lastProbe);
    }

    /**
     * Takes back the history an earlier run of this process left behind (ADR-0012), so the first
     * republished latched event after a restart cannot step a damper inside the interval. The
     * guard is about the room and not about the process: a probe disturbs a room that is already
     * in trouble, and the room does not stop being in trouble because the supervisor restarted.
     */
    public void resume(Map<ZoneId, Instant> granted) {
        lastProbe.putAll(granted);
    }
}
