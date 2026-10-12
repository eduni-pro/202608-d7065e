package se.ltu.d7065e.ventilation.entity;

/** How a zone is being controlled. */
public enum ControlMode {
    /** The inner loop owns the damper. */
    NORMAL,
    /** A fault is latched here; the inner loop no longer commands this zone. */
    DEGRADED,
    /** Healthy zone whose damper is trimmed to push flow to a degraded neighbour. */
    COMPENSATING
}
