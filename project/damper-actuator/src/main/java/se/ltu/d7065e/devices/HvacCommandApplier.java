package se.ltu.d7065e.devices;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import se.ltu.d7065e.shared.control.BuildingStatePort;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.ventilation.entity.AhuId;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Validates a command and writes it to BuildSim. This is the only process that writes damper and
 * fan-speed actuator state: a controller that wrote the actuator itself would be impersonating
 * the device, and the residual it then computed would be measuring its own arithmetic.
 *
 * <p>The container is named {@code damper-actuator} and also applies the air handler's fan speed.
 * ADR-0008 settled that rather than adding a ninth folder and compose service for a single PUT;
 * the name stays while the responsibility is "apply validated HVAC actuator commands to BuildSim".
 *
 * <p>Validation is expressed over the raw {@code double} that crossed the wire rather than over
 * the typed value object. {@link DamperPosition} and {@link FanSpeed} already refuse an
 * out-of-range number in their constructors, so by the time a command exists as a record the
 * range has been enforced — by the codec, not by the device. The device checks the number itself
 * so that its refusal is its own, observable, and independent of which codec version produced it.
 */
public final class HvacCommandApplier {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BACKOFF = Duration.ofMillis(200);

    private static final Logger LOG = Logger.getLogger(HvacCommandApplier.class.getName());

    private final BuildingStatePort building;
    private final ClockPort clock;

    public HvacCommandApplier(BuildingStatePort building, ClockPort clock) {
        this.building = building;
        this.clock = clock;
    }

    public void onDamperCommand(CommandIssued command) {
        applyDamperPosition(command.zone(), command.position().value(), command.commandId());
    }

    public void onFanSpeedCommand(FanSpeedCommanded command) {
        applyFanSpeed(command.ahu(), command.speed().value(), command.commandId());
    }

    /** Clamps up to the minimum rather than rejecting: ventilation never shuts (ADR-0008). */
    public void applyDamperPosition(ZoneId zone, double requested, CommandId commandId) {
        if (!Topology.ZONE_IDS.contains(zone)) {
            reject(commandId, "zone " + zone + " is not one of the "
                    + Topology.ZONE_IDS.size() + " configured rooms");
            return;
        }
        if (!(requested >= 0 && requested <= 1)) {
            reject(commandId, "damper position " + requested + " is outside [0,1]");
            return;
        }
        DamperPosition position =
                DamperPosition.of(requested).clampedTo(Topology.MINIMUM_POSITION);
        if (position.value() != requested) {
            LOG.info("clamping damper command " + commandId + " for " + zone + " from "
                    + requested + " up to " + position);
        }
        write("damper " + zone + " to " + position, commandId,
                () -> building.applyDamperPosition(zone, position));
    }

    public void applyFanSpeed(AhuId ahu, double requested, CommandId commandId) {
        if (!Topology.AIR_HANDLER.equals(ahu)) {
            reject(commandId, "ahu " + ahu + " is not " + Topology.AIR_HANDLER);
            return;
        }
        if (!(requested >= FanSpeed.MINIMUM && requested <= FanSpeed.MAXIMUM)) {
            reject(commandId, "fan speed " + requested + " is outside ["
                    + FanSpeed.MINIMUM + "," + FanSpeed.MAXIMUM + "]");
            return;
        }
        FanSpeed speed = FanSpeed.of(requested);
        write("fan speed of " + ahu + " to " + speed, commandId,
                () -> building.applyFanSpeed(speed));
    }

    /**
     * A write that keeps failing is dropped, not retried forever: the next command carries the
     * controller's current intent, so blocking here would only make the device the stale one.
     */
    private void write(String what, CommandId commandId, Runnable write) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                write.run();
                return;
            } catch (RuntimeException e) {
                if (attempt == MAX_ATTEMPTS) {
                    LOG.log(Level.SEVERE, "dropping command " + commandId + " after " + attempt
                            + " attempts to set " + what, e);
                    return;
                }
                LOG.log(Level.WARNING, "attempt " + attempt + " to set " + what + " failed", e);
                try {
                    clock.waitFor(BACKOFF.multipliedBy(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    LOG.warning("interrupted retrying command " + commandId + "; dropping it");
                    return;
                }
            }
        }
    }

    private static void reject(CommandId commandId, String why) {
        LOG.warning("rejected command " + commandId + ": " + why);
    }
}
