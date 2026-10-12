package se.ltu.d7065e.dashboard.control;

import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * The per-room value layers painted on BuildSim's existing 3D floorplan. BuildSim already
 * renders the building, so the dashboard's job is to feed it one number per room per layer and
 * let the viewer switch layers — not to draw a second floorplan.
 *
 * <p>Each layer's scale is chosen so the decision the layer exists to show lands inside it.
 */
public enum RoomLayer {

    /** Outdoor air at the bottom, 1500 ppm at the top, so the 1000 ppm comfort limit sits inside. */
    CO2("co2", "CO2", "ppm", Topology.OUTDOOR.value(), 1500),

    /**
     * The T-04 spike measured a healthy residual as pure sensor noise — mean 3.5 ppm,
     * sigma 31.8 ppm, range -76.5 to +80.3 — against a post-command mean of 507.8 ppm for a
     * stuck damper. So -100 (just past the healthy minimum) to +500 (the stuck-damper mean)
     * puts every healthy room in the bottom sixth of the scale and saturates a faulted one.
     * A symmetric scale around zero, or one sized for the stuck run's 856 ppm peak, would
     * compress all six healthy rooms into one indistinguishable colour.
     */
    RESIDUAL("residual", "Residual", "ppm", -100, 500),

    /** Zero to twice the CUSUM limit h = 150 ppm (T-04), so crossing h sits mid-scale. */
    CUSUM("cusum", "CUSUM", "ppm", 0, 2 * Topology.CUSUM_LIMIT_PPM),

    /**
     * Monitor state as a severity, which is how this dashboard makes a latched room obvious
     * without writing BuildSim alerts: those belong to the supervisor alone (see
     * {@code RoomLayerPublisher}). Ordered so the hottest rooms are the latched ones, and so a
     * latched room stays hot after the supervisor's compensation has shrunk its residual back
     * towards zero — the masking property the whole design turns on.
     */
    STATE("state", "Monitor state", "severity", 0, 3);

    private final String id;
    private final String label;
    private final String unit;
    private final double minimum;
    private final double maximum;

    RoomLayer(String id, String label, String unit, double minimum, double maximum) {
        this.id = id;
        this.label = label;
        this.unit = unit;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String unit() {
        return unit;
    }

    public double minimum() {
        return minimum;
    }

    public double maximum() {
        return maximum;
    }

    /**
     * The value this layer paints for one room.
     *
     * <p>A stale measurement is rendered at the layer minimum rather than frozen at its last
     * value: BuildSim's layer API carries numbers and a range with no no-data sentinel, so the
     * bottom of the scale is the only honest encoding available, and showing the last live
     * number would claim a measurement the system no longer has. {@link #STATE} is exempt
     * because it is not a measurement — a room whose data has dried up reads as BLIND there,
     * which is exactly what it is.
     */
    public double valueIn(RoomSnapshot room) {
        return switch (this) {
            case STATE -> severityOf(room);
            case CO2 -> onScale(room.co2Ppm(), room.stale());
            case RESIDUAL -> onScale(room.residualPpm(), room.stale());
            case CUSUM -> onScale(room.cusum(), room.stale());
        };
    }

    private double onScale(Double measured, boolean stale) {
        if (stale || measured == null) {
            return minimum;
        }
        return Math.min(maximum, Math.max(minimum, measured));
    }

    private static double severityOf(RoomSnapshot room) {
        if (room.isLatched()) {
            return 3;
        }
        if (room.stale() || room.monitorState() == MonitorState.BLIND) {
            return 2;
        }
        return room.monitorState() == MonitorState.SUSPECT ? 1 : 0;
    }
}
