package se.ltu.d7065e.physics.control;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * A scripted occupancy profile: every room empty but the ones the test names. An occupant step
 * has to be exact to assert a monotone response to it, and the schedules are deliberately not.
 */
final class FixedOccupancy implements OccupancySource {

    private final Map<ZoneId, Integer> headCounts = new LinkedHashMap<>();

    FixedOccupancy() {
        Topology.ZONE_IDS.forEach(zone -> headCounts.put(zone, 0));
    }

    FixedOccupancy with(ZoneId zone, int occupants) {
        headCounts.put(zone, occupants);
        return this;
    }

    @Override
    public Map<ZoneId, Integer> headCounts(Instant at) {
        return headCounts;
    }
}
