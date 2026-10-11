package se.ltu.d7065e.diagnosis.entity;

/**
 * The supervisory monitor's state per zone.
 *
 * <p>{@code WARMUP -> HEALTHY -> SUSPECT -> CONFIRMED -> COMPENSATING -> SERVICED ->
 * WARMUP}, with {@link #BLIND} reachable from WARMUP, HEALTHY and SUSPECT on stale data.
 * BLIND is <em>not</em> a fault: the monitor reports unavailable and issues no diagnosis
 * (R-4).
 *
 * <p>WARMUP earns its place: a monitor that starts with no data at all, or that has just come
 * back from a gap, has nothing to report but unavailability, and calling that HEALTHY would be
 * a claim it cannot support. It is a BLIND source for the same reason — readings that go stale
 * before there has ever been a prediction leave even less to say, not more.
 */
public enum MonitorState {
    WARMUP,
    HEALTHY,
    SUSPECT,
    CONFIRMED,
    COMPENSATING,
    SERVICED,
    BLIND;

    /** States in which a diagnosis is latched and survives a shrinking residual. */
    public boolean isLatched() {
        return this == CONFIRMED || this == COMPENSATING;
    }

    public boolean canGoBlind() {
        return this == HEALTHY || this == SUSPECT || this == WARMUP;
    }
}
