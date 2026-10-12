package se.ltu.d7065e.ventilation.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The inner loop over the six rooms of corridor A120: readings and strategies in, damper
 * commands out, once per control interval.
 *
 * <p>Nothing here knows about MQTT, JDBC or Quarkus (ADR-0005). The loop is driven by three
 * calls — {@link #observe}, {@link #applyStrategy} and {@link #tick()} — and gets time from a
 * {@link ClockPort}, so the evaluation harness can push thousands of simulated hours through the
 * same class the container runs, with no broker, no database and no wall clock.
 *
 * <p><strong>Freshness is not the monitor's BLIND state.</strong> Both count three sample
 * intervals, and that is where the resemblance stops. {@code BLIND} is a diagnostic verdict:
 * R-4 requires the monitor to report its own unavailability and publish no diagnosis, because a
 * diagnosis from data it cannot see would be a guess dressed as a finding. What happens here is
 * narrower and has no verdict in it: the controller simply refuses to compute a new position
 * from a reading it does not trust, holds whatever it last commanded, and issues nothing. It
 * raises no event, enters no state and tells nobody — saying the data is stale is R-4's business
 * and the monitor's, and a controller that also announced it would be two components claiming
 * one judgement. The two are deliberately not wired together: the controller does not wait for
 * {@code BLIND} before going quiet, and the monitor does not wait for the controller.
 *
 * <p>The loop never commands fan speed. Raising the fan is the supervisor's compensation lever
 * and {@code commands/{ahu}/fan-speed} is the supervisor's topic alone (ADR-0008, T-16); the
 * inner loop writes {@code commands/{zone}/damper} and nothing else.
 *
 * <p>Readings arrive on the broker's callback thread while the scheduler ticks on its own, so
 * every entry point is synchronised. The critical section is six map lookups and some
 * arithmetic, which at a one-second interval is not worth anything finer-grained.
 */
public final class DcvControlLoop {

    private final ControlSettings settings;
    private final ClockPort clock;
    private final Map<ZoneId, ZoneControl> rooms;

    public DcvControlLoop(ControlSettings settings, ClockPort clock) {
        if (settings == null || clock == null) {
            throw new IllegalArgumentException("the loop needs settings and a clock");
        }
        this.settings = settings;
        this.clock = clock;
        Map<ZoneId, ZoneControl> byId = new LinkedHashMap<>();
        for (VentilationZone zone : Topology.zones()) {
            byId.put(zone.id(), new ZoneControl(zone));
        }
        this.rooms = byId;
    }

    /**
     * Takes a CO2 reading. A reading for a room outside the configured six is dropped: the
     * topology is fixed by ADR-0008, and a stray publisher is not a reason for the inner loop
     * of six real rooms to fail.
     */
    public synchronized void observe(ReadingObserved reading) {
        ZoneControl room = rooms.get(reading.zone());
        if (room != null) {
            room.observe(reading);
        }
    }

    /** Takes the supervisor's strategy for one room. */
    public synchronized void applyStrategy(StrategyChanged strategy) {
        ZoneControl room = rooms.get(strategy.zone());
        if (room != null) {
            room.apply(strategy);
        }
    }

    /**
     * One pass over the six rooms. Returns the commands the caller must publish, in topology
     * order, which on a typical tick is none: a command is produced only when the position
     * actually moves by more than the deadband.
     */
    public synchronized List<CommandIssued> tick() {
        Instant now = clock.now();
        List<CommandIssued> commands = new ArrayList<>();
        for (ZoneControl room : rooms.values()) {
            CommandIssued command = room.decide(now, settings);
            if (command != null) {
                commands.add(command);
            }
        }
        return commands;
    }

    public synchronized DamperPosition commandedPosition(ZoneId zone) {
        return room(zone).commandedPosition();
    }

    public synchronized ControlMode mode(ZoneId zone) {
        return room(zone).mode();
    }

    /** How many of the six rooms the loop is currently willing to act on, for the health check. */
    public synchronized long roomsWithFreshReadings() {
        Instant now = clock.now();
        return rooms.values().stream().filter(room -> room.hasFreshReading(now, settings)).count();
    }

    public ControlSettings settings() {
        return settings;
    }

    private ZoneControl room(ZoneId zone) {
        ZoneControl room = rooms.get(zone);
        if (room == null) {
            throw new IllegalArgumentException("not one of the six configured zones: " + zone);
        }
        return room;
    }
}
