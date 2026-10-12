package se.ltu.d7065e.evaluation.entity;

import java.time.Instant;
import se.ltu.d7065e.physics.entity.PhysicsSnapshot;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * One run's per-minute record of the six rooms: what the sensors published, what was true, who was
 * in the room and how much air it got.
 *
 * <p>Held as arrays rather than as a list of snapshots because T-25 sweeps a thirty-point
 * threshold grid over twenty simulated weeks, and re-running the physics once per grid point
 * would turn a baseline comparison into the harness's longest experiment for no extra information.
 * The measured series is what the baseline detector sees; the true series is what the comfort
 * requirement is written against.
 */
public final class RunTrace {

    private final int zones = Topology.ZONE_IDS.size();
    private final double[][] measuredPpm;
    private final double[][] truePpm;
    private final double[][] flowLitresPerSecond;
    private final int[][] occupants;
    private final Instant start;
    private int minutes;

    public RunTrace(Instant start, int capacityMinutes) {
        this.start = start;
        this.measuredPpm = new double[zones][capacityMinutes];
        this.truePpm = new double[zones][capacityMinutes];
        this.flowLitresPerSecond = new double[zones][capacityMinutes];
        this.occupants = new int[zones][capacityMinutes];
    }

    public void append(PhysicsSnapshot snapshot) {
        if (minutes >= measuredPpm[0].length) {
            throw new IllegalStateException(
                    "the trace was sized for " + measuredPpm[0].length + " minutes");
        }
        for (int zone = 0; zone < zones; zone++) {
            ZoneId id = Topology.ZONE_IDS.get(zone);
            measuredPpm[zone][minutes] = snapshot.measuredCo2(id).value();
            truePpm[zone][minutes] = snapshot.trueCo2(id).value();
            flowLitresPerSecond[zone][minutes] = snapshot.flow(id).litresPerSecond();
            occupants[zone][minutes] = snapshot.occupancy(id);
        }
        minutes++;
    }

    public int minutes() {
        return minutes;
    }

    public Instant start() {
        return start;
    }

    public double[] measured(ZoneId zone) {
        return measuredPpm[index(zone)];
    }

    public double[] trueSeries(ZoneId zone) {
        return truePpm[index(zone)];
    }

    public double[] flow(ZoneId zone) {
        return flowLitresPerSecond[index(zone)];
    }

    public int[] occupants(ZoneId zone) {
        return occupants[index(zone)];
    }

    public int occupiedMinutes(ZoneId zone, int from) {
        int[] series = occupants(index(zone));
        int occupied = 0;
        for (int minute = from; minute < minutes; minute++) {
            if (series[minute] > 0) {
                occupied++;
            }
        }
        return occupied;
    }

    /** R-2's number: the fraction of occupied minutes whose true CO₂ sits at or below the limit. */
    public double comfortFraction(ZoneId zone, int from, double limitPpm) {
        int[] people = occupants(zone);
        double[] truth = trueSeries(zone);
        int occupied = 0;
        int comfortable = 0;
        for (int minute = from; minute < minutes; minute++) {
            if (people[minute] > 0) {
                occupied++;
                if (truth[minute] <= limitPpm) {
                    comfortable++;
                }
            }
        }
        return occupied == 0 ? Double.NaN : (double) comfortable / occupied;
    }

    public double maxWhileOccupied(ZoneId zone, int from) {
        int[] people = occupants(zone);
        double[] truth = trueSeries(zone);
        double peak = 0;
        for (int minute = from; minute < minutes; minute++) {
            if (people[minute] > 0) {
                peak = Math.max(peak, truth[minute]);
            }
        }
        return peak;
    }

    public double max(double[] series, int from) {
        double peak = 0;
        for (int minute = from; minute < minutes; minute++) {
            peak = Math.max(peak, series[minute]);
        }
        return peak;
    }

    public double mean(double[] series, int from) {
        double total = 0;
        for (int minute = from; minute < minutes; minute++) {
            total += series[minute];
        }
        return minutes - from <= 0 ? Double.NaN : total / (minutes - from);
    }

    /** The largest relative gap between this run's series and another's, minute by minute. */
    public double worstRelativeGap(double[] mine, double[] theirs, int from) {
        double worst = 0;
        for (int minute = from; minute < minutes; minute++) {
            if (theirs[minute] > 0) {
                worst = Math.max(worst, Math.abs(mine[minute] / theirs[minute] - 1));
            }
        }
        return worst;
    }

    private int[] occupants(int index) {
        return occupants[index];
    }

    private int index(ZoneId zone) {
        int index = Topology.ZONE_IDS.indexOf(zone);
        if (index < 0) {
            throw new IllegalArgumentException("not one of the six simulated rooms: " + zone);
        }
        return index;
    }
}
