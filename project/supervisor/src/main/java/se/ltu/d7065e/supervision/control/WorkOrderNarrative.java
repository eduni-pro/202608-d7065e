package se.ltu.d7065e.supervision.control;

import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The text an operator reads off the 3D view (T-17). It has to answer two questions at once —
 * what is broken, and what the supervisor already did about it — because a work order that only
 * names the fault leaves the operator unable to tell a compensated room from an abandoned one.
 *
 * <p>Three outcomes rather than two, since D-11 split the damper diagnosis in two. A plan that
 * raises the faulted room's airflow is a compensated room; a plan that cannot is an abandoned one,
 * and that is the only critical case. A damper stuck <em>wide open</em> produces neither: the plan
 * does nothing because there is nothing the room needs, and reading that as "compensation cannot
 * reach this room" is how the supervisor came to advise evacuating the one room in the building with
 * the most air in it.
 */
public interface WorkOrderNarrative {

    String CRITICAL = "critical";
    String WARNING = "warning";

    /**
     * Critical only when the room has no ventilation path left; anything else is a warning.
     *
     * <p>An over-ventilated room is a warning even though nothing was compensated, which is why the
     * classification is asked for and not only the plan: {@code compensates()} answers "did this
     * plan raise the room's airflow", and for a damper stuck open the answer is no because the room
     * needed nothing — not because the supervisor failed.
     */
    static String severity(FaultClassification classification, CompensationPlan plan) {
        if (classification == FaultClassification.DAMPER_STUCK_OPEN) {
            return WARNING;
        }
        return plan.compensates() ? WARNING : CRITICAL;
    }

    static String summary(
            VentilationZone zone, FaultClassification classification, CompensationPlan plan) {
        String head = "Room %s: %s. Damper frozen at %s. ".formatted(
                zone.id().value(), classification.name(), zone.commandedPosition());
        if (classification == FaultClassification.DAMPER_STUCK_OPEN) {
            return head + overVentilated(zone.id(), plan);
        }
        return head + (plan.compensates() ? compensating(zone.id(), plan) : abandoned(zone.id(), plan));
    }

    /**
     * The mirror of {@link #abandoned}, and deliberately not it. The room is safe: a damper frozen
     * open over-ventilates it, so there is no comfort to restore, no occupancy to relocate and
     * nothing for the supervisor to buy by disturbing the other five. What the fault costs is named
     * instead, because that cost is the reason to send anybody.
     *
     * <p>The cost is stated as misallocation and not as fan energy, which is what the physics here
     * actually says. Under ADR-0008 the shared fan delivers a fixed total at a given speed and the
     * six openings only divide it, so a damper frozen open does not make the plant work harder — it
     * takes a larger share of the same air, and the other five get the shortfall. The inner loop
     * answers that on its own by opening them as their CO2 rises, which is why they are left on it;
     * what it cannot do is close the one damper that is taking the air.
     */
    private static String overVentilated(ZoneId zone, CompensationPlan plan) {
        return ("This room is OVER-ventilated, not starved: the damper is frozen open, so %s takes"
                + " more air than it is asked for and its CO2 is low. Nothing is compensated and"
                + " nothing needs to be. The cost falls on the other rooms: the shared fan delivers"
                + " a fixed total at %s and the six openings only divide it, so the extra air %s is"
                + " taking is the shortfall the other five are left with. They stay on the inner"
                + " loop, which opens them as their CO2 rises — the one thing it cannot do is close"
                + " this damper. Service it to give the air back; no occupant needs to be moved.")
                .formatted(zone.value(), plan.fanSpeed(), zone.value());
    }

    private static String compensating(ZoneId zone, CompensationPlan plan) {
        String lever = plan.trimmedPositions().isEmpty()
                ? "left the healthy dampers where the inner loop has them — none of them could be"
                        + " trimmed — and set the air handler to %s".formatted(plan.fanSpeed())
                : ("trimmed the %d healthy dampers to %.0f%% of their position and set the air"
                        + " handler to %s").formatted(plan.trimmedPositions().size(),
                        plan.trim() * 100, plan.fanSpeed());
        String text = ("Supervisor degraded %s so the inner loop no longer commands it, %s."
                + " That raises %s's airflow by %.0f%% and moves every healthy room's own airflow"
                + " by at most %.1f%%. Service the damper.").formatted(
                zone.value(), lever, zone.value(),
                (plan.achievedFaultedFlowGain() - 1) * 100,
                plan.worstHealthyFlowDeviation() * 100);
        return plan.meetsRequest() ? text
                : text + " Short of what the room needs: " + plan.limitation() + ".";
    }

    private static String abandoned(ZoneId zone, CompensationPlan plan) {
        return ("Compensation cannot reach this room: %s. Supervisor degraded %s and raised this"
                + " work order rather than promising comfort it cannot deliver. Occupancy advice:"
                + " reduce the head-count in %s and relocate occupants to a ventilated room until"
                + " the damper is serviced.").formatted(
                plan.limitation(), zone.value(), zone.value());
    }
}
