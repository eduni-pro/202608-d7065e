package se.ltu.d7065e.physics.entity;

import java.time.Duration;
import java.time.Instant;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * One scheduled fault: what it is, where it bites, how hard, and from when. This is the
 * ground-truth marker the evaluation scores its detections against (T-14, R-1).
 *
 * <p>The meaning of {@code magnitude} is the fault's own: a frozen damper position for
 * {@link FaultKind#STUCK_DAMPER}, the blockage fraction beta for
 * {@link FaultKind#BLOCKED_FILTER}, a drift rate in ppm per hour for
 * {@link FaultKind#SENSOR_DRIFT}, and a fixed offset in ppm for
 * {@link FaultKind#SENSOR_OFFSET}.
 *
 * <p>Invariant: a plant-wide fault carries no zone and a per-room fault must carry one, and
 * the magnitude stays inside the range its kind can physically take.
 *
 * @param zone the room the fault bites in, or {@code null} for a plant-wide fault
 */
public record InjectedFault(FaultKind kind, ZoneId zone, double magnitude, Instant onset) {

    public InjectedFault {
        if (kind == null || onset == null) {
            throw new IllegalArgumentException("a fault needs a kind and an onset");
        }
        if (kind.isPlantWide() == (zone != null)) {
            throw new IllegalArgumentException(
                    "%s is %s and so takes %s zone, got: %s".formatted(kind,
                            kind.isPlantWide() ? "plant-wide" : "per-room",
                            kind.isPlantWide() ? "no" : "one", zone));
        }
        if (!Double.isFinite(magnitude)) {
            throw new IllegalArgumentException("fault magnitude must be finite, was: " + magnitude);
        }
        switch (kind) {
            case STUCK_DAMPER -> {
                if (magnitude < 0 || magnitude > 1) {
                    throw new IllegalArgumentException(
                            "a stuck damper freezes at a position within [0,1], was: " + magnitude);
                }
            }
            case BLOCKED_FILTER -> {
                if (magnitude <= 0 || magnitude >= 1) {
                    throw new IllegalArgumentException(
                            "filter blockage beta must be within (0,1), was: " + magnitude);
                }
            }
            case SENSOR_DRIFT -> {
                if (magnitude == 0) {
                    throw new IllegalArgumentException("a sensor drift of 0 ppm/h is no fault");
                }
            }
            // Signed, and unbounded on purpose: a sensor's zero can walk either way, and how far it
            // may walk is a property of the instrument rather than of the room. The one value that
            // is not a fault is zero.
            case SENSOR_OFFSET -> {
                if (magnitude == 0) {
                    throw new IllegalArgumentException("a sensor offset of 0 ppm is no fault");
                }
            }
        }
    }

    /** A damper frozen at {@code frozenAt}; the stuck-shut case is the zone's minimum. */
    public static InjectedFault stuckDamper(ZoneId zone, DamperPosition frozenAt, Instant onset) {
        return new InjectedFault(FaultKind.STUCK_DAMPER, zone, frozenAt.value(), onset);
    }

    /** Filter blockage beta on the shared air handler. */
    public static InjectedFault blockedFilter(double blockage, Instant onset) {
        return new InjectedFault(FaultKind.BLOCKED_FILTER, null, blockage, onset);
    }

    /** A sensor drifting at {@code ppmPerHour}; the room's air does not move with it. */
    public static InjectedFault sensorDrift(ZoneId zone, double ppmPerHour, Instant onset) {
        return new InjectedFault(FaultKind.SENSOR_DRIFT, zone, ppmPerHour, onset);
    }

    /**
     * A sensor whose zero jumps by {@code offsetPpm} at onset and then holds; the room's air does
     * not move with it. See {@link FaultKind#SENSOR_OFFSET} for why this is its own kind.
     */
    public static InjectedFault sensorOffset(ZoneId zone, double offsetPpm, Instant onset) {
        return new InjectedFault(FaultKind.SENSOR_OFFSET, zone, offsetPpm, onset);
    }

    public boolean isActiveAt(Instant now) {
        return !now.isBefore(onset);
    }

    public boolean affects(ZoneId candidate) {
        return zone != null && zone.equals(candidate);
    }

    /**
     * How many ppm this fault makes the sensor wrong by at {@code now}, and 0 before its onset or
     * for a fault that is not a sensor fault at all. A ramp integrates its rate over the elapsed
     * time; a step is its magnitude from the first instant and stays there.
     */
    public double sensorErrorPpmAt(Instant now) {
        if (!isActiveAt(now)) {
            return 0;
        }
        return switch (kind) {
            case SENSOR_DRIFT -> magnitude
                    * Duration.between(onset, now).toMillis() / Duration.ofHours(1).toMillis();
            case SENSOR_OFFSET -> magnitude;
            case STUCK_DAMPER, BLOCKED_FILTER -> 0;
        };
    }

    @Override
    public String toString() {
        return "%s@%s magnitude %.3f from %s".formatted(
                kind, zone == null ? "PLANT" : zone.value(), magnitude, onset);
    }
}
