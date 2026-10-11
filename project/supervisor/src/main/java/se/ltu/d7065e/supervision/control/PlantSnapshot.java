package se.ltu.d7065e.supervision.control;

import java.util.Collection;
import java.util.List;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * An immutable picture of the six zones and the air handler at one instant. The compensation
 * solver is a pure function of this, which is what lets the evaluation harness solve a plan
 * without a broker, a database or a clock.
 */
public record PlantSnapshot(List<VentilationZone> zones, AirHandlingUnit airHandler) {

    public PlantSnapshot {
        if (zones == null || zones.isEmpty() || airHandler == null) {
            throw new IllegalArgumentException("a plant snapshot needs zones and an air handler");
        }
        zones = List.copyOf(zones);
    }

    public VentilationZone zone(ZoneId id) {
        return zones.stream().filter(z -> z.id().equals(id)).findFirst().orElseThrow(
                () -> new IllegalArgumentException("not a zone of this plant: " + id));
    }

    public List<VentilationZone> zonesOtherThan(Collection<ZoneId> excluded) {
        return zones.stream().filter(z -> !excluded.contains(z.id())).toList();
    }
}
