package se.ltu.d7065e.evaluation.control;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import se.ltu.d7065e.physics.control.OccupancySource;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Every room held at the design occupancy ADR-0008 sized its damper authority from, around the
 * clock. This is the quasi-steady condition the observer's generation estimate converges on, and
 * it is the condition the T-04 spike and {@code fault-monitor}'s own detection test both measured
 * at — so T-24's comparable arm has to reproduce it rather than draw a plausible day.
 *
 * <p>The head count is recovered from the room's own design flow rather than restated as a table,
 * by inverting the Swedish guideline ADR-0008 applied: {@code 7 l/s} per person plus
 * {@code 0.35 l/s} per m². That returns exactly the ADR's 1, 2, 4, 10, 8 and 20 people.
 */
public final class DesignOccupancy implements OccupancySource {

    private static final double LITRES_PER_SECOND_PER_PERSON = 7.0;
    private static final double LITRES_PER_SECOND_PER_SQUARE_METRE = 0.35;

    private final Map<ZoneId, Integer> headCounts = new LinkedHashMap<>();

    public DesignOccupancy() {
        for (VentilationZone zone : Topology.zones()) {
            headCounts.put(zone.id(), designOccupancy(zone));
        }
    }

    public static int designOccupancy(VentilationZone zone) {
        double perArea = LITRES_PER_SECOND_PER_SQUARE_METRE * zone.floorAreaSquareMetres();
        return (int) Math.round(
                Math.max(0, (zone.damperAuthority() - perArea) / LITRES_PER_SECOND_PER_PERSON));
    }

    @Override
    public Map<ZoneId, Integer> headCounts(Instant at) {
        return headCounts;
    }
}
