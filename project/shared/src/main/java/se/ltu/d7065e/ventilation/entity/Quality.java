package se.ltu.d7065e.ventilation.entity;

/**
 * Data quality of a reading as judged at the boundary that received it (T-21). Only
 * {@code GOOD} readings may feed the observer; the rest drive the monitor towards BLIND.
 */
public enum Quality {
    GOOD,
    STALE,
    MISSING,
    DUPLICATE;

    public boolean isUsable() {
        return this == GOOD;
    }
}
