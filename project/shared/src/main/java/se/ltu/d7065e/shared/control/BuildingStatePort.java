package se.ltu.d7065e.shared.control;

import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * BuildSim as the single source of building state. The simulator writes what the sensors
 * should read; the devices read it back. Nothing bypasses this to talk to a peer directly —
 * otherwise a command could not affect the next measurement.
 */
public interface BuildingStatePort {

    Ppm readCo2(ZoneId zone);

    DamperPosition readDamperPosition(ZoneId zone);

    FanSpeed readFanSpeed();

    FanPower readFanPower();

    void writeCo2(ZoneId zone, Ppm ppm);

    void writeFanPower(FanPower power);

    void applyDamperPosition(ZoneId zone, DamperPosition position);

    void applyFanSpeed(FanSpeed speed);
}
