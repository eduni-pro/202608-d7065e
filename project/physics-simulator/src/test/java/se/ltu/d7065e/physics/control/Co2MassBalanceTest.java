package se.ltu.d7065e.physics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;

/** T-07: the room balance itself, where ADR-0008's numbers have to add up. */
class Co2MassBalanceTest {

    @Test
    @DisplayName("T-07: an empty room sits at outdoor concentration and is not held above it")
    void shouldRestAtOutdoorConcentrationWhenEmpty() {
        double outdoor = Topology.OUTDOOR.value();
        VentilationZone seminarRoom = Topology.zone(Topology.A145);

        double next = Co2MassBalance.nextPpm(
                outdoor, seminarRoom.volumeCubicMetres(), 0, 0.17, outdoor, 1);

        assertEquals(outdoor, next, 1e-12, "outdoor is the only steady state of an empty room");
    }

    @Test
    @DisplayName("T-07: an empty room decays towards outdoor and does not stop short of it")
    void shouldDecayTowardsOutdoorWithoutAFloorAboveIt() {
        double outdoor = Topology.OUTDOOR.value();
        VentilationZone seminarRoom = Topology.zone(Topology.A145);

        double ppm = 1200;
        for (int second = 0; second < 4 * 3600; second++) {
            ppm = Co2MassBalance.nextPpm(
                    ppm, seminarRoom.volumeCubicMetres(), 0, 0.17, outdoor, 1);
        }

        assertEquals(outdoor, ppm, 1.0, "four hours of ventilation reach outdoor, was: " + ppm);
    }

    @Test
    @DisplayName("T-07: design occupancy at design flow settles at the 1000 ppm comfort limit")
    void shouldSettleNearTheComfortLimitAtDesignFlow() {
        double settled = Co2MassBalance.steadyStatePpm(
                20, Topology.zone(Topology.A145).damperAuthority() / 1000.0,
                Topology.OUTDOOR.value());

        assertTrue(Math.abs(settled - Topology.COMFORT_LIMIT.value()) < 50,
                "ADR-0008 claims A145's 165.9 l/s is also the flow that holds 1000 ppm at 20"
                        + " people; the balance says " + settled);
    }
}
