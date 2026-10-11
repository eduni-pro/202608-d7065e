package se.ltu.d7065e.ventilation.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * The rule on its own, with no loop, no clock and no zone state around it. This is T-09's
 * demand curve — the open threshold, the ramp, the band and the floor. R-1 and R-2 are not
 * asserted anywhere below: a demanded damper position is not a confirmation delay and not a
 * zone's CO2 over occupied minutes.
 */
class DamperDemandTest {

    private static final ControlSettings SETTINGS = ControlSettings.standard();
    private static final DamperPosition HELD = DamperPosition.of(0.4);

    /** T-09's ramp: the position rises with CO2 so the room reaches full flow by 1000 ppm. */
    @Test
    void shouldMapCo2ProportionallyBetweenOpenThresholdAndComfortLimit() {
        assertEquals(HELD, DamperDemand.from(Ppm.of(800), HELD, SETTINGS).position(),
                "at the threshold itself nothing has opened yet");
        assertEquals(0.15425, demand(801).value(), 1e-9, "just past it, the ramp starts at 0.15");
        assertEquals(0.3625, demand(850).value(), 1e-9);
        assertEquals(0.575, demand(900).value(), 1e-9);
        assertEquals(1.0, demand(1000).value(), 1e-9, "fully open at the comfort limit");
        assertEquals(1.0, demand(1800).value(), 1e-9, "and no further, there is nowhere to go");
    }

    /** ADR-0008's floor: ventilation never shuts entirely, whatever the reading says. */
    @Test
    void shouldNeverDemandBelowTheMinimumPosition() {
        for (double ppm = 0; ppm <= 2000; ppm += 5) {
            DamperPosition position = DamperDemand.from(Ppm.of(ppm), HELD, SETTINGS).position();
            assertTrue(position.value() >= Topology.MINIMUM_POSITION.value(),
                    "%.0f ppm demanded %s, below the minimum".formatted(ppm, position));
        }
    }

    /** T-09's band: inside it the rule is a no-op, which is what keeps command steps meaningful. */
    @Test
    void shouldHoldThePositionInsideTheHysteresisBand() {
        for (double ppm = 650; ppm <= 800; ppm += 1) {
            DamperDemand demand = DamperDemand.from(Ppm.of(ppm), HELD, SETTINGS);
            assertEquals(HELD, demand.position(), "%.0f ppm moved the damper".formatted(ppm));
            assertEquals(DamperDemand.HOLDING_IN_BAND, demand.reason());
        }
    }

    private static DamperPosition demand(double ppm) {
        return DamperDemand.from(Ppm.of(ppm), HELD, SETTINGS).position();
    }
}
