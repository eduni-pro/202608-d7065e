package se.ltu.d7065e.evaluation.control;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.evaluation.boundary.ResultFiles;
import se.ltu.d7065e.evaluation.entity.RunTrace;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.supervision.control.CompensationPlan;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-27 — kill each component in turn (R-4, resilience).
 *
 * <p><b>This is the in-JVM equivalent of killing a container, not a container-level chaos run.</b>
 * Killing a container also exercises Docker's restart policy, the broker's session state, the health
 * endpoints and the reconnect paths, and none of those is in this JVM. What is in this JVM is the
 * part the requirement is about: a component stops being fed or stops being asked, and the question
 * is what the others then do. The container-level run is a separate exercise against
 * {@code docker compose}, and the report says which of the two produced which number.
 *
 * <p>Killing a component here means ceasing to feed it and ceasing to deliver what it publishes —
 * the precise effect a dead subscriber has on an MQTT topic with no persistence.
 */
public final class ResilienceExperiment {

    private static final long SEED = 1;

    /**
     * The room whose damper the two supervisor scenarios freeze, and the reason it is the single
     * office rather than the open-plan office an earlier revision used.
     *
     * <p>A damper frozen at {@link Topology#MINIMUM_POSITION} is a fault only relative to a
     * command above it, and nothing commands one unless the inner loop's 800 ppm open threshold is
     * crossed. With every damper at the minimum the proportional split hands each room exactly its
     * design flow, so in a room that never crosses the threshold the frozen damper sits where a
     * healthy one would and the plant is bit for bit a plant with nothing wrong. Both scenarios
     * then assert over a confirmed case and a plan that were never established, which is a
     * scenario that failed to set itself up rather than a resilience defect.
     *
     * <p>That is what resizing the air handler did to A124. The open-plan office draws five to
     * eight of its eight desks, and at its design flow those settle at 721, 781, 841 and 901 ppm,
     * so half the draws sit under the threshold before the other five rooms' dampers take any
     * share at all. Swept over 100 seeds, a damper frozen in A124 reached a confirmed case and a
     * plan inside this scenario's 12 h horizon on 63 of them. A longer horizon does not fix that,
     * because the horizon needed is a geometric draw over working days — 36 h reached 88 seeds,
     * 60 h reached 96 and 84 h reached 100, with no bound to pick. Nor can the fault be made more
     * severe: {@link se.ltu.d7065e.ventilation.entity.VentilationZone} clamps every position to
     * the zone minimum, so 0.15 is already the floor, and freezing the damper <em>open</em>
     * instead confirms nothing in the faulted room and starves the innocent ones, which the same
     * sweep measured.
     *
     * <p>A125A settles at 794.5 ppm with the one person its schedule puts in it on every working
     * day — five ppm under the threshold, so the moment any other damper opens and takes a share
     * of the fan's air it crosses, and the inner loop asks this damper to move. Swept the same way
     * it established the precondition on 198 of 200 seeds, the worst at 471 min; the two misses
     * are R-3's false alarms arriving during the warm-up, which is a failure this project already
     * reports and not a property of this room.
     */
    private static final ZoneId FAULTED = Topology.A125A;

    private static final Duration TO_OCCUPIED = Duration.ofHours(9);
    private static final Duration WARMUP = Duration.ofHours(8);
    private static final Duration OBSERVE = Duration.ofMinutes(30);
    private static final Duration FAULT_DAY = Duration.ofHours(12);
    private static final Duration AFTER_KILL = Duration.ofHours(4);

    private ResilienceExperiment() {
    }

    public static String run(ResultFiles files) {
        List<Finding> findings = new ArrayList<>();
        findings.addAll(killSensor());
        findings.addAll(killMonitor());
        findings.addAll(killSupervisorBeforeLatch());
        findings.addAll(killSupervisorAfterCompensating());
        findings.addAll(killActuator());

        try (ResultFiles.Csv csv = files.csv("T-27-resilience.csv",
                "scenario", "killed_component", "observation", "measured", "required", "verdict")) {
            findings.forEach(f -> csv.row(f.scenario(), f.component(), f.observation(),
                    f.measured(), f.required(), f.pass() ? "pass" : "FAIL"));
        }

        List<List<String>> rows = new ArrayList<>();
        findings.forEach(f -> rows.add(List.of(f.component(), f.observation(), f.measured(),
                f.required(), f.pass() ? "pass" : "FAIL")));
        return "T-27 — kill each component in turn (R-4)\n"
                + "  seed %d, in-JVM equivalence: a killed component stops being fed and stops "
                        .formatted(SEED)
                + "being delivered to.\n"
                + "  The container-level chaos run against docker compose is separate and is not "
                + "this table.\n"
                + "  The two supervisor scenarios freeze %s's damper at %s; see FAULTED for why "
                        .formatted(FAULTED, Topology.MINIMUM_POSITION)
                + "that room and not another.\n"
                + ResultFiles.table(List.of("killed", "observation", "measured", "required",
                        "verdict"), rows);
    }

    /** R-4 itself: kill {@code co2-sensor} and the monitor must say so and accuse nobody. */
    private static List<Finding> killSensor() {
        SystemHarness harness = new SystemHarness(SEED);
        harness.tickFor(TO_OCCUPIED);
        harness.events().clear();
        int commandsBefore = harness.damperCommands().size();

        // A latched room cannot go BLIND by design: MonitorState.canGoBlind() excludes CONFIRMED
        // and COMPENSATING, because a case that has already been diagnosed is not withdrawn by the
        // sensor going quiet. R-4 is therefore asserted over the rooms that were not latched, and
        // the latched ones are reported rather than averaged away.
        List<ZoneId> observable = Topology.ZONE_IDS.stream()
                .filter(zone -> harness.monitor().stateOf(zone).canGoBlind()).toList();
        List<ZoneId> latched = Topology.ZONE_IDS.stream()
                .filter(zone -> harness.monitor().stateOf(zone).isLatched()).toList();

        harness.readingsFlowing(false);
        int blindAt = harness.tickUntil(OBSERVE, () -> observable.stream()
                .allMatch(zone -> harness.monitor().stateOf(zone) == MonitorState.BLIND));
        long faultEvents = Topology.ZONE_IDS.stream()
                .mapToLong(zone -> harness.events().faultEvents(zone).size()).sum();
        long blindReports = harness.events().states().stream()
                .filter(e -> e.state() == MonitorState.BLIND)
                .map(e -> e.zone()).distinct().count();
        harness.tickFor(OBSERVE);
        int commandsWhileBlind = harness.damperCommands().size() - commandsBefore;
        long freshRooms = harness.innerLoop().roomsWithFreshReadings();

        harness.readingsFlowing(true);
        int recoveredAt = harness.tickUntil(OBSERVE, () -> observable.stream()
                .allMatch(zone -> harness.monitor().stateOf(zone) == MonitorState.HEALTHY));

        String scenario = "co2-sensor stops publishing";
        return List.of(
                new Finding(scenario, "co2-sensor", "rooms that could go blind at the kill",
                        "%d of 6 (latched: %s)".formatted(observable.size(),
                                latched.isEmpty() ? "none" : latched.toString()),
                        "a latched room stays latched; R-4 is asserted over the rest",
                        !observable.isEmpty()),
                new Finding(scenario, "co2-sensor", "cycles until every observable room is BLIND",
                        String.valueOf(blindAt), "<= 4 (three sample intervals plus one cycle)",
                        blindAt > 0 && blindAt <= 4),
                new Finding(scenario, "co2-sensor", "rooms reporting unavailability",
                        String.valueOf(blindReports), "one per observable room",
                        blindReports >= observable.size()),
                new Finding(scenario, "co2-sensor", "fault events published while blind",
                        String.valueOf(faultEvents), "0", faultEvents == 0),
                new Finding(scenario, "co2-sensor", "rooms the controller still trusts",
                        String.valueOf(freshRooms), "0", freshRooms == 0),
                new Finding(scenario, "co2-sensor", "damper commands issued on stale data",
                        String.valueOf(commandsWhileBlind), "0", commandsWhileBlind == 0),
                new Finding(scenario, "co2-sensor", "cycles to HEALTHY once readings return",
                        String.valueOf(recoveredAt), "<= 5 (BLIND to WARMUP to HEALTHY)",
                        recoveredAt > 0 && recoveredAt <= 5));
    }

    /** Kill {@code fault-monitor}: diagnosis stops, ventilation does not. */
    private static List<Finding> killMonitor() {
        SystemHarness alive = new SystemHarness(SEED);
        SystemHarness dead = new SystemHarness(SEED);
        alive.tickFor(TO_OCCUPIED);
        dead.tickFor(TO_OCCUPIED);
        dead.events().clear();
        dead.monitorRunning(false);

        int aliveCommandsBefore = alive.damperCommands().size();
        int deadCommandsBefore = dead.damperCommands().size();
        RunTrace aliveTrace = new RunTrace(alive.now(), (int) AFTER_KILL.toMinutes() + 1);
        RunTrace deadTrace = new RunTrace(dead.now(), (int) AFTER_KILL.toMinutes() + 1);
        for (long minute = 0; minute < AFTER_KILL.toMinutes(); minute++) {
            aliveTrace.append(alive.tick());
            deadTrace.append(dead.tick());
        }
        int aliveCommands = alive.damperCommands().size() - aliveCommandsBefore;
        int deadCommands = dead.damperCommands().size() - deadCommandsBefore;
        double aliveComfort = comfort(aliveTrace);
        double deadComfort = comfort(deadTrace);
        long residuals = dead.events().countOn(se.ltu.d7065e.shared.event.ResidualComputed.class);

        String scenario = "fault-monitor stops cycling";
        return List.of(
                new Finding(scenario, "fault-monitor", "damper commands in the 4 h after the kill",
                        "%d (monitor alive: %d)".formatted(deadCommands, aliveCommands),
                        "> 0, ventilation continues", deadCommands > 0),
                new Finding(scenario, "fault-monitor",
                        "occupied minutes at or below 1000 ppm across the six rooms",
                        "%.3f (monitor alive: %.3f)".formatted(deadComfort, aliveComfort),
                        "not worse than the monitor-alive arm: losing diagnosis must not cost "
                                + "ventilation", deadComfort >= aliveComfort - 0.01),
                new Finding(scenario, "fault-monitor", "residuals published after the kill",
                        String.valueOf(residuals), "0, diagnosis is what was lost", residuals == 0));
    }

    /** Kill {@code supervisor} before the case latches: the case latches anyway and stays latched. */
    private static List<Finding> killSupervisorBeforeLatch() {
        SystemHarness harness = new SystemHarness(SEED);
        harness.tickFor(WARMUP);
        harness.inject(InjectedFault.stuckDamper(FAULTED, Topology.MINIMUM_POSITION, harness.now()));
        harness.supervisorRunning(false);
        harness.tickFor(FAULT_DAY);

        MonitorState state = harness.monitor().stateOf(FAULTED);
        CompensationPlan plan = harness.supervisor().currentPlan();
        long strategies = harness.events().countOn(StrategyChanged.class);
        long fanCommands = harness.events().countOn(FanSpeedCommanded.class);
        int workOrders = harness.overlay().raised().size();
        long otherModes = Topology.ZONE_IDS.stream()
                .filter(zone -> harness.innerLoop().mode(zone) != ControlMode.NORMAL).count();

        String scenario = "supervisor dead before the diagnosis";
        return List.of(
                new Finding(scenario, "supervisor",
                        "state of %s after %d h".formatted(FAULTED, FAULT_DAY.toHours()),
                        state.name(), "latched (CONFIRMED or COMPENSATING)", state.isLatched()),
                new Finding(scenario, "supervisor", "compensation plan in force",
                        plan == null ? "none" : plan.toString(), "none", plan == null),
                new Finding(scenario, "supervisor", "strategy and fan-speed commands published",
                        "%d strategies, %d fan commands".formatted(strategies, fanCommands),
                        "0 and 0", strategies == 0 && fanCommands == 0),
                new Finding(scenario, "supervisor", "work orders raised", String.valueOf(workOrders),
                        "0", workOrders == 0),
                new Finding(scenario, "supervisor", "rooms taken off NORMAL control",
                        String.valueOf(otherModes), "0, nothing degraded anything",
                        otherModes == 0));
    }

    /**
     * Kill {@code supervisor} while it is compensating: what it already did stays done.
     *
     * <p>What "compensating" amounts to on this plant is reported rather than assumed, because it
     * is less than the scenario's name suggests. The supervisor degrades the faulted damper,
     * raises a work order and recomputes the plan, but the plan itself almost always buys nothing:
     * the solver's own limitation is that every healthy damper is already sitting at its minimum,
     * so the proportional split has nothing left to shift, and the faulted room is still reading
     * comfortably, so no gain is asked for either — and of those two reasons it is the second that
     * binds here, because an exhausted trim is answered with fan speed rather than with a no-op.
     * A room reading under the comfort target asks for a gain of one, and the speed that delivers
     * a gain of one is the speed the plant is already at. The 100-seed sweep quoted below was
     * measured before the solver had that fan lever and has not been repeated; what this run
     * measures is that the plan in force at the kill still buys nothing, which the table states.
     *
     * <p>Swept over 100 seeds the plan raised the faulted room's flow on 0 of 100 for this room
     * and on at most 3 of 63 for any room, which is the resized plant's margin showing up a
     * second time. The two assertions below about the fan therefore hold over a fan the
     * supervisor never moved, which is still R-4's question — is anything new commanded after the
     * kill — but a weaker form of it than a run on a plant without margin would put.
     */
    private static List<Finding> killSupervisorAfterCompensating() {
        SystemHarness harness = new SystemHarness(SEED);
        harness.tickFor(WARMUP);
        harness.inject(InjectedFault.stuckDamper(FAULTED, Topology.MINIMUM_POSITION, harness.now()));
        // A plan for THIS room's case, not merely a plan: the supervisor solves over whichever
        // zones it currently holds faulted, and R-3's false alarms mean an unrelated room can put
        // a plan in force first. Killing on that plan would leave the three assertions below
        // talking about a room whose case had not been confirmed when the kill landed.
        int latchedAt = harness.tickUntil(FAULT_DAY,
                () -> harness.supervisor().currentPlan() != null
                        && harness.supervisor().faultedZones().contains(FAULTED));
        CompensationPlan plan = harness.supervisor().currentPlan();
        double fanAtKill = harness.latest().fanSpeed().value();

        harness.events().clear();
        harness.supervisorRunning(false);
        harness.tickFor(AFTER_KILL);

        MonitorState state = harness.monitor().stateOf(FAULTED);
        long strategies = harness.events().countOn(StrategyChanged.class);
        long fanCommands = harness.events().countOn(FanSpeedCommanded.class);
        double fanAfter = harness.latest().fanSpeed().value();

        String scenario = "supervisor dead while compensating";
        return List.of(
                new Finding(scenario, "supervisor",
                        "minutes from onset to a plan covering %s".formatted(FAULTED),
                        String.valueOf(latchedAt), "> 0, a plan was reached before the kill",
                        latchedAt > 0),
                new Finding(scenario, "supervisor",
                        "the plan in force at the kill, and what it bought",
                        plan == null ? "none"
                                : "trim %.2f, fan %.2f, gain %.2f (%s)".formatted(plan.trim(),
                                        plan.fanSpeed().value(), plan.achievedFaultedFlowGain(),
                                        plan.compensates() ? "raises the faulted flow"
                                                : "moves no air into it"),
                        "a plan", plan != null),
                new Finding(scenario, "supervisor", "fan speed 4 h after the kill",
                        "%.2f (at the kill: %.2f)".formatted(fanAfter, fanAtKill),
                        "unchanged: the plant holds what it was last told",
                        Math.abs(fanAfter - fanAtKill) < 1e-9),
                new Finding(scenario, "supervisor", "new strategy or fan commands after the kill",
                        "%d and %d".formatted(strategies, fanCommands), "0 and 0",
                        strategies == 0 && fanCommands == 0),
                new Finding(scenario, "supervisor", "state of %s after the kill".formatted(FAULTED),
                        state.name(), "still latched", state.isLatched()));
    }

    /** Kill {@code damper-actuator}: commands are issued and the air does not move. */
    private static List<Finding> killActuator() {
        SystemHarness harness = new SystemHarness(SEED);
        harness.tickFor(TO_OCCUPIED);
        harness.events().clear();
        int commandsBefore = harness.damperCommands().size();
        harness.actuatorApplying(false);
        harness.tickFor(Duration.ofHours(3));

        int commands = harness.damperCommands().size() - commandsBefore;
        long confirmations = harness.events().confirmedCases();
        List<String> diagnosed = new ArrayList<>();
        for (ZoneId zone : Topology.ZONE_IDS) {
            if (harness.monitor().stateOf(zone).isLatched()) {
                diagnosed.add(zone.value() + "=" + harness.monitor().caseOf(zone).classification());
            }
        }

        String scenario = "damper-actuator stops applying commands";
        return List.of(
                new Finding(scenario, "damper-actuator", "commands issued in the 3 h after the kill",
                        String.valueOf(commands), "> 0, the controller keeps trying", commands > 0),
                new Finding(scenario, "damper-actuator", "confirmations raised",
                        String.valueOf(confirmations), "> 0, an unapplied command is indetectable "
                                + "from a frozen damper", confirmations > 0),
                new Finding(scenario, "damper-actuator", "what the monitor called it",
                        diagnosed.isEmpty() ? "nothing" : String.join(" ", diagnosed),
                        "STUCK_DAMPER per affected room; the monitor cannot name the actuator",
                        !diagnosed.isEmpty()));
    }

    private static double comfort(RunTrace trace) {
        int occupied = 0;
        int comfortable = 0;
        for (ZoneId zone : Topology.ZONE_IDS) {
            int[] people = trace.occupants(zone);
            double[] truth = trace.trueSeries(zone);
            for (int minute = 0; minute < trace.minutes(); minute++) {
                if (people[minute] > 0) {
                    occupied++;
                    if (truth[minute] <= Topology.COMFORT_LIMIT.value()) {
                        comfortable++;
                    }
                }
            }
        }
        return occupied == 0 ? Double.NaN : (double) comfortable / occupied;
    }

    private record Finding(String scenario, String component, String observation, String measured,
            String required, boolean pass) {
    }
}
