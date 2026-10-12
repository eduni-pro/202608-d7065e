package se.ltu.d7065e.diagnosis.control;

import java.util.ArrayList;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.Evidence;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.diagnosis.entity.StepResponse;

/**
 * T-15 — the decision tree over ADR-0008's signature table.
 *
 * <pre>
 *   upper arm (CO2 above the commanded airflow's prediction)
 *     five of six residuals up AND fan power per unit of believed flow up  -> BLOCKED_FILTER
 *     one residual up, step-response shape unchanged, overnight floor high -> SENSOR_DRIFT
 *     one residual up, fan power flat, step response missing               -> STUCK_DAMPER
 *   lower arm (CO2 below it — T-32 section 5)
 *     overnight floor BELOW outdoor, which is physically impossible        -> SENSOR_DRIFT
 *     fan power flat, step response missing after a command to close       -> DAMPER_STUCK_OPEN
 *   anything else that got confirmed                                       -> UNCLASSIFIED
 * </pre>
 *
 * <p>Three deliberate choices in the tree:
 *
 * <ul>
 * <li><b>Common mode is tested first.</b> A blocking filter starves every room, so it also
 *     looks like a stuck damper in each of them one room at a time. Testing the plant-level
 *     signature before the room-level one is what stops six simultaneous stuck-damper work
 *     orders.
 * <li><b>Drift is tested before a stuck damper.</b> Both show one room's residual up with the
 *     fan flat, so fan power cannot separate them. The step-response shape can: a sensor offset
 *     moves the level and leaves the amplitude of the response alone, a jammed damper removes
 *     the response. The overnight floor confirms it from a second direction.
 * <li><b>The lower arm reuses the same three signals, read in the other direction.</b> A damper
 *     stuck wide open is the mirror of one stuck shut: the room's residual is negative after a
 *     command to close, the fan is flat because the plant is doing what it was asked, and the
 *     commanded step produced no matching response, because the room's flow never changed. A
 *     sensor drifting low is the cleanest signature in the whole system, cleaner than anything on
 *     the high side: with nobody in the room the CO2 settles <em>below</em> the 420 ppm outdoor
 *     concentration, which no amount of ventilation can achieve, so the reading is not merely
 *     unusual but impossible. A blocked filter has no low-side form at all — a filter that blocks
 *     starves rooms, it cannot over-ventilate them — so on this side it is evidence against
 *     rather than a branch.
 * <li><b>{@code UNCLASSIFIED} is a leaf, not a failure.</b> A confirmed residual that matches
 *     no signature is still a room whose airflow the model cannot account for, and it is still
 *     worth a work order. The plan's risk register forbids the alternative — inventing a fourth
 *     signal to rescue a three-way taxonomy — and a tree with no honest "don't know" would hide
 *     exactly the cases the report should be discussing.
 * </ul>
 *
 * <h2>What this tree cannot do, measured</h2>
 *
 * The {@code BLOCKED_FILTER} branch is reachable on paper and, with this project's physics, not
 * reachable from damper commands: under ADR-0008's proportional split the six openings only
 * redistribute a fixed total flow, so no command the monitor can watch pushes all six residuals
 * the same way, and the quasi-steady generation estimate absorbs the blockage into the predicted
 * steady state. {@code CrossRoomSignatureTest} measures this and the report owns it. What survives
 * is that the fan-power excess is recorded as evidence on whatever case does confirm, so the work
 * order points at the plant even when the classification comes out {@code UNCLASSIFIED}.
 */
public final class FaultClassifier {

    private final MonitorParameters parameters;

    public FaultClassifier(MonitorParameters parameters) {
        this.parameters = parameters;
    }

    public Diagnosis classify(FaultSignals signals) {
        boolean commonMode = signals.zonesWithElevatedResidual()
                >= Math.min(parameters.commonModeZones(), signals.zoneCount());
        boolean powerUp = signals.fanPowerRatio() > 1 + parameters.fanPowerRiseThreshold();
        boolean powerFlat = Math.abs(signals.fanPowerRatio() - 1) <= parameters.fanPowerFlatTolerance();
        // Room-level means "not plant-level", rather than "this room alone". At six rooms and
        // sigma = 30 ppm one neighbour's residual crosses the reference value often enough on
        // noise alone that demanding strict isolation would throw away a true stuck-damper
        // diagnosis whenever it happened.
        boolean localised = !commonMode;
        boolean floorHigh = signals.overnightFloorKnown() && signals.overnightFloorPpm()
                > Topology.OUTDOOR.value() + parameters.overnightFloorTolerancePpm();
        // Twice the tolerance, mirrored, and the factor is arithmetic rather than taste. The floor
        // is a *minimum* over a day of 1440 samples, so a healthy room sitting at outdoor already
        // reads about 3.3 sigma — very nearly 100 ppm — below it from sensor noise alone: the
        // healthy value of this statistic is outdoor minus one tolerance, not outdoor. The
        // high-side test therefore carries 200 ppm of margin between the healthy floor and its
        // limit, and the low-side test gets the same 200 ppm by subtracting the tolerance twice —
        // once to reach the healthy floor and once for the margin. No new parameter: sweeping
        // overnightFloorTolerancePpm still moves both sides together.
        boolean floorImpossible = signals.overnightFloorKnown() && signals.overnightFloorPpm()
                < Topology.OUTDOOR.value() - 2 * parameters.overnightFloorTolerancePpm();

        List<Evidence> evidence = new ArrayList<>();
        evidence.add(reading(signals));
        if (signals.lowSideExcursion()) {
            gatherLowSide(signals, commonMode, powerFlat, floorImpossible, evidence);
        } else {
            gatherBlockedFilter(signals, commonMode, powerUp, evidence);
            gatherSensorDrift(signals, localised, floorHigh, evidence);
            gatherStuckDamper(signals, localised, powerFlat, evidence);
        }

        FaultClassification decided = signals.lowSideExcursion()
                ? decideLowSide(signals, powerFlat, floorImpossible)
                : decide(signals, commonMode, powerUp, localised, floorHigh, powerFlat);
        if (decided == FaultClassification.UNCLASSIFIED) {
            evidence.add(Evidence.supporting(
                    "no signature matched; the residual is confirmed and unexplained, which is a "
                            + "work order with an open question, not a clean diagnosis",
                    signals.at()));
        }
        return new Diagnosis(decided, evidence);
    }

    private FaultClassification decide(FaultSignals signals, boolean commonMode, boolean powerUp,
            boolean localised, boolean floorHigh, boolean powerFlat) {
        if (commonMode && powerUp) {
            return FaultClassification.BLOCKED_FILTER;
        }
        if (localised && signals.stepResponse().matched() && floorHigh) {
            return FaultClassification.SENSOR_DRIFT;
        }
        // Deliberately !matched() rather than missing(), and the difference is measured. Requiring
        // positive evidence of an unanswered step is the correct reading of the signal, and it
        // empties this leaf: a probe is requested *through* the evidence on FaultStateChanged, so a
        // case must already be open before any *probe* step is commanded, which means the monitor
        // has never judged a step it asked for at the moment it classifies. What it may have is an
        // ordinary control command that happened to arm a window by then, and that is luck rather
        // than design. Demanding MISSING here took the one cell on T-15's diagonal to UNCLASSIFIED
        // and left the tree classifying nothing at all. So the leaf still accepts NOT_JUDGED, and
        // gatherStuckDamper records which of the three it saw — the weakness is carried in the work
        // order rather than hidden in a boolean.
        if (localised && powerFlat && !signals.stepResponse().matched()) {
            return FaultClassification.STUCK_DAMPER;
        }
        return FaultClassification.UNCLASSIFIED;
    }

    /**
     * The lower arm's half of the table (T-32 section 5). The impossible floor is tested first and
     * needs nothing else: a room that settles below the outdoor concentration with nobody in it is
     * reporting a number ventilation cannot produce, whatever its dampers did, so no fan-power or
     * step-shape agreement can outvote it.
     *
     * <p>The damper leaf returns {@link FaultClassification#DAMPER_STUCK_OPEN} and not
     * {@code STUCK_DAMPER} (D-11). Both are dampers that stopped answering commands, which is why
     * {@link FaultClassification#isUnresponsiveDamper()} still treats them alike where that is all
     * that matters — pinning the shared flow belief — but the responses diverge: a stuck-shut
     * damper starves its room and needs the other five trimmed so the split pushes air in, while a
     * stuck-open one leaves its room over-ventilated and safe and takes its extra air out of the
     * other five rooms' share. Returning one constant for both made the supervisor answer the
     * second with the first's work order, down to advising that the one room with the most air in
     * the building be evacuated. The extreme is still named in the evidence as well, because the
     * text is what a technician reads.
     */
    private FaultClassification decideLowSide(FaultSignals signals, boolean powerFlat,
            boolean floorImpossible) {
        if (signals.readingBelowOutdoor() || floorImpossible) {
            return FaultClassification.SENSOR_DRIFT;
        }
        if (powerFlat && !signals.stepResponse().matched()) {
            return FaultClassification.DAMPER_STUCK_OPEN;
        }
        return FaultClassification.UNCLASSIFIED;
    }

    private Evidence reading(FaultSignals signals) {
        return Evidence.supporting(
                "zone %s residual %+.1f ppm, %s CUSUM %.1f ppm against h = %.0f ppm"
                        .formatted(signals.zone().value(), signals.residualPpm(),
                                signals.lowSideExcursion() ? "lower-arm" : "upper-arm",
                                signals.drivingCusumPpm(), parameters.cusumH()),
                signals.at());
    }

    /**
     * The lower arm's evidence. All three hypotheses are still argued, as R-5 requires — a
     * diagnosis that only recorded the branch it took could not be argued with — but each is
     * argued in the direction the excursion actually has.
     */
    private void gatherLowSide(FaultSignals signals, boolean commonMode, boolean powerFlat,
            boolean floorImpossible, List<Evidence> evidence) {
        evidence.add(Evidence.supporting(
                ("low-side excursion: the room holds %.1f ppm LESS CO2 than the commanded airflow "
                        + "predicts, so it is being over-ventilated or its sensor under-reads — the "
                        + "direction an upper CUSUM alone cannot see")
                        .formatted(Math.abs(signals.residualPpm())), signals.at()));
        evidence.add(Evidence.against(
                ("blocked filter: a blocking filter starves rooms and cannot over-ventilate one, so "
                        + "the plant-level signature has no low-side form; %d of %d rooms elevated")
                        .formatted(signals.zonesWithElevatedResidual(), signals.zoneCount()),
                signals.at()));
        if (signals.readingBelowOutdoor()) {
            evidence.add(Evidence.supporting(
                    ("sensor drifting low: the room is reading %.0f ppm, BELOW the %.0f ppm outdoor "
                            + "concentration — a room cannot hold less CO2 than the air it is "
                            + "ventilated with, so this reading is impossible rather than merely low, "
                            + "and the controller is acting on it by under-ventilating an occupied "
                            + "room")
                            .formatted(signals.measuredPpm(), Topology.OUTDOOR.value()),
                    signals.at()));
        } else {
            evidence.add(Evidence.against(
                    ("sensor drifting low: the room is reading %.0f ppm, at or above outdoor %.0f ppm, "
                            + "so the level itself is physically possible")
                            .formatted(signals.measuredPpm(), Topology.OUTDOOR.value()), signals.at()));
        }
        if (!signals.overnightFloorKnown()) {
            evidence.add(Evidence.against(
                    "sensor drifting low: no overnight floor yet — less than a day of history, so "
                            + "the empty-room reading is unknown rather than impossible", signals.at()));
        } else if (floorImpossible) {
            evidence.add(Evidence.supporting(
                    ("sensor drifting low: overnight floor %.0f ppm, BELOW the %.0f ppm outdoor "
                            + "concentration — no amount of ventilation can take a room below the air "
                            + "it is ventilated with, so the sensor's zero has walked down. The "
                            + "controller therefore believes this room is cleaner than it is and "
                            + "under-ventilates it while occupied")
                            .formatted(signals.overnightFloorPpm(), Topology.OUTDOOR.value()),
                    signals.at()));
        } else {
            evidence.add(Evidence.against(
                    ("sensor drifting low: overnight floor %.0f ppm is not below outdoor %.0f ppm by "
                            + "more than %.0f ppm, so the reading is low but not impossible")
                            .formatted(signals.overnightFloorPpm(), Topology.OUTDOOR.value(),
                                    parameters.overnightFloorTolerancePpm()), signals.at()));
        }
        if (powerFlat && !signals.stepResponse().matched()) {
            evidence.add(Evidence.supporting(
                    ("damper stuck WIDE OPEN: AHU power flat within %.0f%% and the commanded step "
                            + "produced no matching response, while the room keeps losing CO2 — the "
                            + "mirror of a damper stuck shut, at the other extreme of travel. This "
                            + "room is safe and the other five are not: the shared fan delivers a "
                            + "fixed total and the six openings only divide it, so the extra air "
                            + "this damper is taking is the shortfall the others are left with, and "
                            + "the inner loop cannot close it to give the air back")
                            .formatted(parameters.fanPowerFlatTolerance() * 100), signals.at()));
        } else if (!powerFlat) {
            evidence.add(Evidence.against(
                    ("damper stuck wide open: AHU power is not flat (%.0f%% of prediction), which "
                            + "points at the plant rather than at one damper")
                            .formatted(signals.fanPowerRatio() * 100), signals.at()));
        } else {
            evidence.add(Evidence.against(
                    "damper stuck wide open: the commanded step did produce its response, so the "
                            + "damper moved and the level is what is wrong", signals.at()));
        }
    }

    private void gatherBlockedFilter(FaultSignals signals, boolean commonMode, boolean powerUp,
            List<Evidence> evidence) {
        String zones = "%d of %d rooms elevated".formatted(
                signals.zonesWithElevatedResidual(), signals.zoneCount());
        String power = "AHU power %.0f%% of the affinity-law prediction at the believed speed"
                .formatted(signals.fanPowerRatio() * 100);
        if (commonMode) {
            evidence.add(Evidence.supporting("blocked filter: " + zones + ", a common-mode residual", signals.at()));
        } else {
            evidence.add(Evidence.against("blocked filter: " + zones + ", so the residual is not common mode", signals.at()));
        }
        if (powerUp) {
            evidence.add(Evidence.supporting("blocked filter: " + power + ", more power for less air", signals.at()));
        } else {
            evidence.add(Evidence.against("blocked filter: " + power + ", power per unit of believed flow is not up", signals.at()));
        }
    }

    private void gatherSensorDrift(FaultSignals signals, boolean localised, boolean floorHigh,
            List<Evidence> evidence) {
        if (localised && signals.stepResponse().matched()) {
            evidence.add(Evidence.supporting(
                    "sensor drift: the step response kept its predicted amplitude, so the room "
                            + "still answers its damper and only the level is wrong", signals.at()));
        } else if (signals.stepResponse() == StepResponse.NOT_JUDGED) {
            evidence.add(Evidence.against(
                    "sensor drift: no commanded step has been judged yet, so whether the room "
                            + "still answers its damper is unknown rather than wrong", signals.at()));
        } else {
            evidence.add(Evidence.against(
                    "sensor drift: the step-response amplitude did not match the prediction, so "
                            + "the room is not merely reading high", signals.at()));
        }
        if (!signals.overnightFloorKnown()) {
            evidence.add(Evidence.against(
                    "sensor drift: no overnight floor yet — less than a day of history, so the "
                            + "empty-room reading is unknown rather than correct", signals.at()));
        } else if (floorHigh) {
            evidence.add(Evidence.supporting(
                    ("sensor drift: overnight floor %.0f ppm against outdoor %.0f ppm, so the room "
                            + "never returns to outdoor with nobody in it")
                            .formatted(signals.overnightFloorPpm(), Topology.OUTDOOR.value()),
                    signals.at()));
        } else {
            evidence.add(Evidence.against(
                    ("sensor drift: overnight floor %.0f ppm is within %.0f ppm of outdoor, so the "
                            + "sensor's zero is where it should be")
                            .formatted(signals.overnightFloorPpm(),
                                    parameters.overnightFloorTolerancePpm()), signals.at()));
        }
    }

    private void gatherStuckDamper(FaultSignals signals, boolean localised, boolean powerFlat,
            List<Evidence> evidence) {
        if (localised) {
            evidence.add(Evidence.supporting(
                    ("stuck damper: %d of %d rooms are elevated, so this is not the plant starving "
                            + "all of them and a damper is where it can be")
                            .formatted(signals.zonesWithElevatedResidual(), signals.zoneCount()),
                    signals.at()));
        } else {
            evidence.add(Evidence.against(
                    "stuck damper: %d rooms are elevated, and one damper cannot starve the others"
                            .formatted(signals.zonesWithElevatedResidual()), signals.at()));
        }
        if (powerFlat) {
            evidence.add(Evidence.supporting(
                    ("stuck damper: AHU power flat within %.0f%%, so the plant is doing what it was "
                            + "asked and the fault is downstream of it")
                            .formatted(parameters.fanPowerFlatTolerance() * 100), signals.at()));
        } else {
            evidence.add(Evidence.against(
                    ("stuck damper: AHU power is not flat (%.0f%% of prediction), which points at "
                            + "the plant rather than one damper")
                            .formatted(signals.fanPowerRatio() * 100), signals.at()));
        }
        switch (signals.stepResponse()) {
            case MISSING -> evidence.add(Evidence.supporting(
                    "stuck damper: the commanded step produced no matching response", signals.at()));
            case MATCHED -> evidence.add(Evidence.against(
                    "stuck damper: the commanded step did produce its response, so the damper moved",
                    signals.at()));
            case NOT_JUDGED -> evidence.add(Evidence.against(
                    "stuck damper: NO COMMANDED STEP HAS BEEN JUDGED, so this diagnosis rests on "
                            + "localisation and flat fan power alone. The room has not been asked "
                            + "to move and therefore has not failed to answer; a probe is requested "
                            + "so the third signal can arrive and either confirm or overturn this",
                    signals.at()));
        }
    }
}
