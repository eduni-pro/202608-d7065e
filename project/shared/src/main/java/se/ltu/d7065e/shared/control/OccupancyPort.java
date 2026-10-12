package se.ltu.d7065e.shared.control;

import java.util.Map;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** Publishes the simulated head-count per room, which BuildSim renders and nothing else owns. */
public interface OccupancyPort {

    void publishOccupancy(Map<ZoneId, Integer> headCountPerZone);
}
