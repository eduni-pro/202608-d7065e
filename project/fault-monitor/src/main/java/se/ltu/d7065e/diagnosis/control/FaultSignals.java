package se.ltu.d7065e.diagnosis.control;

import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;
import se.ltu.d7065e.diagnosis.entity.StepResponse;

/**
 * The signals ADR-0008's signature table is written over, gathered for one zone at one
 * instant, plus the direction of the excursion they describe (T-32 section 5). This is the classifier's whole input: a plain record, so the evaluation harness and
 * the confusion-matrix experiment (T-15) can drive the decision tree without a monitor, a
 * broker or a clock behind it.
 *
 * @param zone                      the room being diagnosed
 * @param measuredPpm               what its sensor is reading, which the low-side tree needs
 *                                  because 420 ppm is a physical boundary and not a threshold:
 *                                  a room cannot hold less CO2 than the air it is ventilated
 *                                  with, so a room reading below outdoor is reporting a number
 *                                  no ventilation could produce
 * @param residualPpm               its own residual, the first signal
 * @param cusumPpm                  its own upper-arm CUSUM statistic, for the record
 * @param cusumLowPpm               its own lower-arm CUSUM statistic, held as a magnitude
 * @param lowSideExcursion          whether the excursion being classified is the lower arm's —
 *                                  CO2 <em>below</em> what the commanded airflow predicts
 *                                  (T-32 section 5). It selects which half of the signature table
 *                                  applies, because the same three signals mean different
 *                                  things in the two directions: a damper stuck wide open
 *                                  mirrors one stuck shut, and a sensor drifting low shows an
 *                                  overnight floor that is wrong in the other direction
 * @param zonesWithElevatedResidual how many of the six rooms have a residual that is up, in
 *                                  the sense that their CUSUM has not decayed to zero — the
 *                                  common-mode signal, and the only reason the monitor watches
 *                                  all six rooms rather than running six independent monitors
 * @param zoneCount                 how many rooms the monitor is watching, so "five of six"
 *                                  survives R-6's scaling experiment changing the six
 * @param fanPowerRatio             observed AHU power over the power the affinity law predicts
 *                                  at the believed speed; 1.0 is flat, above 1.0 is power per
 *                                  unit of believed flow going up
 * @param stepResponse              what the last commanded step established: that the response
 *                                  arrived with its predicted amplitude, that it did not arrive,
 *                                  or that no step has been judged yet. The third is absent
 *                                  evidence and must not be read as the second
 * @param overnightFloorPpm         the trailing-day minimum, or {@code NaN} when the monitor
 *                                  has not been up for a full day. {@code NaN} is "no evidence",
 *                                  not "healthy"
 */
public record FaultSignals(
        ZoneId zone,
        double measuredPpm,
        double residualPpm,
        double cusumPpm,
        double cusumLowPpm,
        boolean lowSideExcursion,
        int zonesWithElevatedResidual,
        int zoneCount,
        double fanPowerRatio,
        StepResponse stepResponse,
        double overnightFloorPpm,
        ObservedAt at) {

    public FaultSignals {
        if (zone == null || at == null) {
            throw new IllegalArgumentException("signals need a zone and an instant");
        }
        if (zoneCount < 1 || zonesWithElevatedResidual < 0 || zonesWithElevatedResidual > zoneCount) {
            throw new IllegalArgumentException(
                    "elevated zones must lie within [0, zoneCount], was: "
                            + zonesWithElevatedResidual + " of " + zoneCount);
        }
    }

    public boolean overnightFloorKnown() {
        return !Double.isNaN(overnightFloorPpm);
    }

    /**
     * Whether the sensor is reading below the outdoor concentration. No tolerance is subtracted,
     * and that is not sloppiness: this is only ever asked about a room whose lower CUSUM arm has
     * already held above {@code h} for the persistence window, so the significance is in the
     * statistic and all this sample has to say is which side of the physical boundary the room is
     * on. Asking it of a single reading in isolation would need three sigma of slack.
     */
    public boolean readingBelowOutdoor() {
        return measuredPpm < Topology.OUTDOOR.value();
    }

    /** The arm that is driving this diagnosis, as a magnitude. */
    public double drivingCusumPpm() {
        return lowSideExcursion ? cusumLowPpm : cusumPpm;
    }
}
