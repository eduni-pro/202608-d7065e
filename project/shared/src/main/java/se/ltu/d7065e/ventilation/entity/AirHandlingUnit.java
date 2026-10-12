package se.ltu.d7065e.ventilation.entity;

import java.util.List;

/**
 * The single air handler serving all six zones (ADR-0008, option B). Fan flow follows
 * {@code Q = Q_nom * s * (1 - beta)} and power the affinity law {@code P = P_nom * s^3 *
 * (1 + beta)}, so a blocked filter costs more power for less air.
 *
 * <p>Invariant: commanded speed stays within the plant's range, which {@link FanSpeed}
 * enforces at construction.
 */
public record AirHandlingUnit(
        AhuId id,
        AirflowRate nominalFlow,
        double nominalPowerWatts,
        FanSpeed commandedSpeed,
        List<ZoneId> servedZones) {

    public AirHandlingUnit {
        if (id == null || nominalFlow == null || commandedSpeed == null) {
            throw new IllegalArgumentException("air handler fields must not be null");
        }
        if (nominalPowerWatts <= 0) {
            throw new IllegalArgumentException("nominal power must be positive, was: " + nominalPowerWatts);
        }
        if (servedZones == null || servedZones.isEmpty()) {
            throw new IllegalArgumentException("an air handler must serve at least one zone");
        }
        servedZones = List.copyOf(servedZones);
    }

    public AirHandlingUnit atSpeed(FanSpeed speed) {
        return new AirHandlingUnit(id, nominalFlow, nominalPowerWatts, speed, servedZones);
    }

    /** Total flow the fan delivers at the commanded speed, given filter blockage. */
    public AirflowRate deliveredFlow(double filterBlockage) {
        return AirflowRate.ofCubicMetresPerSecond(
                nominalFlow.cubicMetresPerSecond() * commandedSpeed.value() * (1 - filterBlockage));
    }

    /** Power drawn at the commanded speed, which rises as the filter blocks. */
    public FanPower power(double filterBlockage) {
        double speed = commandedSpeed.value();
        return FanPower.ofWatts(nominalPowerWatts * speed * speed * speed * (1 + filterBlockage));
    }
}
