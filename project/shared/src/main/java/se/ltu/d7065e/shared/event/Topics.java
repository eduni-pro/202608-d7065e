package se.ltu.d7065e.shared.event;

import se.ltu.d7065e.ventilation.entity.AhuId;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The MQTT topic contract from the implementation plan section 3, as applied by ADR-0008, plus
 * {@link #service(ZoneId)}, which the plan does not name: closing a latched case had no transport
 * at all (D-10) and one was added rather than left as a restart.
 */
public interface Topics {

    static String co2Reading(ZoneId zone) {
        return "readings/" + zone.value() + "/co2";
    }

    static String fanPower(AhuId ahu) {
        return "readings/" + ahu.value() + "/fan-power";
    }

    static String damperCommand(ZoneId zone) {
        return "commands/" + zone.value() + "/damper";
    }

    static String fanSpeedCommand(AhuId ahu) {
        return "commands/" + ahu.value() + "/fan-speed";
    }

    static String residual(ZoneId zone) {
        return "residuals/" + zone.value();
    }

    static String fault(ZoneId zone) {
        return "faults/" + zone.value();
    }

    static String strategy(ZoneId zone) {
        return "strategy/" + zone.value();
    }

    /**
     * Where an operator reports a repair. Not under {@code commands/}: see
     * {@link ServiceReported} for why the actuator topics stay closed to everyone but the inner
     * loop and the supervisor.
     */
    static String service(ZoneId zone) {
        return "service/" + zone.value();
    }

    String ALL_CO2_READINGS = "readings/+/co2";
    String ALL_FAN_POWER = "readings/+/fan-power";
    String ALL_DAMPER_COMMANDS = "commands/+/damper";
    String ALL_FAN_SPEED_COMMANDS = "commands/+/fan-speed";
    String ALL_RESIDUALS = "residuals/+";
    String ALL_FAULTS = "faults/+";
    String ALL_STRATEGIES = "strategy/+";
    String ALL_SERVICE_REPORTS = "service/+";
}
