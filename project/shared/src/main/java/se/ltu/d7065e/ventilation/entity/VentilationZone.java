package se.ltu.d7065e.ventilation.entity;

/**
 * One room served by a damper off the shared air handler (ADR-0008, option B).
 *
 * <p>Invariant: a zone in {@link ControlMode#DEGRADED} never accepts a normal control
 * command. Degradation exists precisely to take the damper away from the inner loop, so
 * letting a stale controller command through would undo the supervisor's decision.
 */
public record VentilationZone(
        ZoneId id,
        double volumeCubicMetres,
        double floorAreaSquareMetres,
        AhuId ahuId,
        double damperAuthority,
        DamperPosition minimumPosition,
        ControlMode mode,
        DamperPosition commandedPosition) {

    public VentilationZone {
        if (id == null || ahuId == null || minimumPosition == null
                || mode == null || commandedPosition == null) {
            throw new IllegalArgumentException("zone fields must not be null");
        }
        if (volumeCubicMetres <= 0) {
            throw new IllegalArgumentException("zone volume must be positive, was: " + volumeCubicMetres);
        }
        if (floorAreaSquareMetres <= 0) {
            throw new IllegalArgumentException("floor area must be positive, was: " + floorAreaSquareMetres);
        }
        if (damperAuthority <= 0) {
            throw new IllegalArgumentException("damper authority must be positive, was: " + damperAuthority);
        }
        if (commandedPosition.value() < minimumPosition.value()) {
            throw new IllegalArgumentException(
                    "commanded position %s is below the zone minimum %s"
                            .formatted(commandedPosition, minimumPosition));
        }
    }

    /** Applies an inner-loop command. Refused while degraded. */
    public VentilationZone withControlCommand(DamperPosition position) {
        if (mode == ControlMode.DEGRADED) {
            throw new IllegalStateException(
                    "zone " + id + " is degraded and does not accept normal control commands");
        }
        return withPosition(position);
    }

    /** Applies a supervisory command, which may move a degraded or compensating zone. */
    public VentilationZone withSupervisoryCommand(DamperPosition position) {
        return withPosition(position);
    }

    private VentilationZone withPosition(DamperPosition position) {
        return new VentilationZone(id, volumeCubicMetres, floorAreaSquareMetres, ahuId,
                damperAuthority, minimumPosition, mode, position.clampedTo(minimumPosition));
    }

    public VentilationZone inMode(ControlMode newMode) {
        return new VentilationZone(id, volumeCubicMetres, floorAreaSquareMetres, ahuId,
                damperAuthority, minimumPosition, newMode, commandedPosition);
    }

    /** Effective opening in the air handler's flow split: position times authority. */
    public double openingArea() {
        return commandedPosition.value() * damperAuthority;
    }
}
