package se.ltu.d7065e.shared.boundary;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import se.ltu.d7065e.shared.control.ClockPort;

/**
 * Simulated time running {@code timeScale} times faster than the wall clock, anchored on a real
 * instant every process is given, so that a scenario taking an hour of building time can be
 * watched inside a 15-minute oral slot and all seven of the processes that hold a simulated clock
 * still agree on what time it is. The other three Compose services -- BuildSim, the broker and the
 * journal -- hold no simulated clock and are not given one.
 *
 * <p>It is a third {@link ClockPort} beside the two the project already has: {@code SystemClock}
 * runs the plant in real time, the evaluation harness drives {@code SimulatedClock} as fast as
 * the CPU allows, and this one sits between them — paced by real time like the first, reporting
 * simulated instants like the second.
 *
 * <h2>The anchoring, and why it is not optional</h2>
 *
 * Simulated time is a pure function of real time:
 *
 * <pre>    now() = START_AT + (realTime.now() - REAL_EPOCH) x TIME_SCALE</pre>
 *
 * {@code REAL_EPOCH} is one real instant handed identically to every process and {@code START_AT}
 * is the simulated instant the scenario begins at. Nothing in that expression depends on when
 * this object was constructed, which is the whole point: seven processes started at seven different
 * real instants compute the same simulated time, and a container restarted half-way through
 * rejoins the same timeline instead of opening a second one. A clock that counted from its own
 * construction would put every process on a timeline of its own, and the disagreement would
 * surface as residuals and staleness indistinguishable from a real fault.
 *
 * <p>At {@code timeScale = 1} with {@code START_AT = REAL_EPOCH} this is {@code SystemClock}
 * exactly: {@code now()} is the wall clock to the nanosecond and {@link #waitFor(Duration)}
 * sleeps the span it was given. The containers do not even construct one in that case — see
 * {@link #fromEnvironment(ClockPort)} — so the unscaled profile is untouched by construction.
 *
 * <h2>Pacing</h2>
 *
 * {@link #waitFor(Duration)} takes a span of <em>simulated</em> time and sleeps until real time
 * reaches the instant at which simulated time will have advanced by that span, which is the span
 * divided by the scale. It sleeps towards an absolute deadline on the shared timeline rather than
 * for a relative span, so whatever the caller spent between two waits — a BuildSim round trip, a
 * JDBC commit — comes off the next sleep instead of accumulating as lag. A caller that has fallen
 * further behind than one span does not sleep at all and runs its loop again immediately, which is
 * how the simulator traverses the simulated time that passed while its container was starting
 * instead of silently skipping it.
 *
 * <p>Only a process whose loop <em>is</em> the simulated timeline should pace on this clock. A
 * sampler or a scheduler that merely has to keep up with the timeline paces on real time and uses
 * this clock for {@code now()} alone; otherwise the catch-up above would make it replay its own
 * backlog at full speed.
 */
public final class ScaledClock implements ClockPort {

    /** Simulated seconds per real second. 1, the default, is wall-clock behaviour. */
    public static final String TIME_SCALE = "TIME_SCALE";

    /** The simulated instant the scenario begins at, ISO-8601. */
    public static final String START_AT = "START_AT";

    /** The one real instant, ISO-8601, that every process anchors its simulated time on. */
    public static final String REAL_EPOCH = "REAL_EPOCH";

    private final ClockPort realTime;
    private final Instant startAt;
    private final Instant realEpoch;
    private final long timeScale;

    /** Where {@link #waitFor} has paced to. Only a process pacing on this clock touches it. */
    private Instant paced;

    /**
     * @param realTime  the clock that actually sleeps, normally {@code SystemClock}
     * @param startAt   the simulated instant at {@code realEpoch}
     * @param realEpoch the real instant every process anchors on, identical in all of them
     * @param timeScale simulated seconds per real second, at least 1
     */
    public ScaledClock(ClockPort realTime, Instant startAt, Instant realEpoch, long timeScale) {
        if (timeScale < 1) {
            throw new IllegalArgumentException(
                    "TIME_SCALE must be at least 1 simulated second per real second, was: "
                            + timeScale);
        }
        this.realTime = realTime;
        this.startAt = startAt;
        this.realEpoch = realEpoch;
        this.timeScale = timeScale;
        this.paced = startAt;
    }

    /**
     * The single-process form, for a test or a run with nothing else to agree with: the epoch is
     * this object's construction, and {@code firstTick} is the simulated instant that the first
     * {@code waitFor(simulatedTick)} reports.
     *
     * @param firstTick     the simulated instant the first {@code waitFor(simulatedTick)} reports
     * @param simulatedTick the simulated span of one tick, {@code TICK_SECONDS x TIME_SCALE}
     */
    public ScaledClock(
            ClockPort realTime, Instant firstTick, Duration simulatedTick, long timeScale) {
        this(realTime, startOf(firstTick, simulatedTick), realTime.now(), timeScale);
    }

    /**
     * The clock the containers run on, from {@link #TIME_SCALE}, {@link #START_AT} and
     * {@link #REAL_EPOCH} in the environment. Every one of the seven project-authored processes
     * calls this with its own {@code SystemClock}, so that they read the three variables the same
     * way and cannot drift apart by reading them differently.
     *
     * <p>Returns {@code realTime} unchanged when {@code TIME_SCALE} is absent or 1: the unscaled
     * profile keeps the clock it always had rather than an equivalent wrapper around it.
     *
     * <p>A scale above 1 without a {@code REAL_EPOCH} is refused rather than defaulted. There is
     * no default that could be right: an epoch per container is precisely the bug this class
     * exists to remove, and it would fail silently as a wrong diagnosis rather than loudly as a
     * container that will not start. The demo override makes compose demand the variable for the
     * same reason.
     */
    public static ClockPort fromEnvironment(ClockPort realTime) {
        long timeScale = timeScaleFromEnvironment();
        if (timeScale == 1) {
            return realTime;
        }
        Instant realEpoch = instant(REAL_EPOCH);
        if (realEpoch == null) {
            throw new IllegalStateException(
                    TIME_SCALE + "=" + timeScale + " needs " + REAL_EPOCH + ", one real ISO-8601 "
                            + "instant shared by every container, or each would run a simulated "
                            + "timeline of its own: REAL_EPOCH=\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\" "
                            + "docker compose ... up -d");
        }
        Instant startAt = instant(START_AT);
        return new ScaledClock(
                realTime, startAt == null ? realEpoch : startAt, realEpoch, timeScale);
    }

    /**
     * {@link #START_AT}, or {@code fallback} when the environment does not set it. The simulator
     * needs the parts rather than {@link #fromEnvironment(ClockPort)}'s whole, because its first
     * tick has to land on {@code START_AT} itself and not one tick after it.
     */
    public static Instant startAtFromEnvironment(Instant fallback) {
        Instant startAt = instant(START_AT);
        return startAt == null ? fallback : startAt;
    }

    /** {@link #REAL_EPOCH}, or {@code fallback} when the environment does not set it. */
    public static Instant realEpochFromEnvironment(Instant fallback) {
        Instant realEpoch = instant(REAL_EPOCH);
        return realEpoch == null ? fallback : realEpoch;
    }

    /**
     * {@link #TIME_SCALE}, for the periods that have to be converted with it. A loop period
     * configured in real seconds spans {@code period x TIME_SCALE} of simulated time, and it is
     * the simulated span that every model, deadline and staleness window is written against.
     */
    public static long timeScaleFromEnvironment() {
        String value = System.getenv(TIME_SCALE);
        if (value == null || value.isBlank()) {
            return 1;
        }
        long timeScale = Long.parseLong(value.trim());
        if (timeScale < 1) {
            throw new IllegalStateException(
                    TIME_SCALE + " must be at least 1 simulated second per real second, was: "
                            + timeScale);
        }
        return timeScale;
    }

    @Override
    public Instant now() {
        return startAt.plus(
                Duration.between(realEpoch, realTime.now()).multipliedBy(timeScale));
    }

    @Override
    public void waitFor(Duration simulatedSpan) throws InterruptedException {
        paced = paced.plus(simulatedSpan);
        Duration remaining = Duration.between(realTime.now(), realInstantOf(paced));
        if (remaining.isNegative() || remaining.isZero()) {
            return;
        }
        realTime.waitFor(remaining);
    }

    public long timeScale() {
        return timeScale;
    }

    /** The simulated instant this clock is anchored to {@link #realEpoch()} at. */
    public Instant startAt() {
        return startAt;
    }

    public Instant realEpoch() {
        return realEpoch;
    }

    /**
     * The real instant at which simulated time reaches {@code simulated}. Computed from the epoch
     * every time rather than accumulated, so the integer division truncates by at most a
     * nanosecond once instead of once per tick.
     */
    private Instant realInstantOf(Instant simulated) {
        return realEpoch.plusNanos(Duration.between(startAt, simulated).toNanos() / timeScale);
    }

    private static Instant startOf(Instant firstTick, Duration simulatedTick) {
        if (simulatedTick.isZero() || simulatedTick.isNegative()) {
            throw new IllegalArgumentException("a tick must be a positive span, was: " + simulatedTick);
        }
        // The caller reads the clock after waiting, so the first read lands on firstTick.
        return firstTick.minus(simulatedTick);
    }

    private static Instant instant(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    name + " must be an ISO-8601 instant such as 2026-10-09T07:45:00Z, was: "
                            + value, e);
        }
    }
}
