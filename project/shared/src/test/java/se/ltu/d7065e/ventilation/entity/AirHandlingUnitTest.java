package se.ltu.d7065e.ventilation.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one shared air handler (ADR-0008, option B). The property that matters for diagnosis is
 * the <em>simultaneity</em>: as filter blockage beta rises, delivered flow falls while power
 * rises. That pair is the blocked-filter signature the classifier keys on (T-15), and it is
 * also why fan power is the monitor's only cross-room signal.
 */
class AirHandlingUnitTest {

    private static AirHandlingUnit ahu() {
        return new AirHandlingUnit(AhuId.of("AHU-A120"),
                AirflowRate.ofCubicMetresPerSecond(0.42), 300.0, FanSpeed.NOMINAL,
                List.of(ZoneId.of("A115"), ZoneId.of("A124")));
    }

    @Test
    @DisplayName("T-15: flow falls and power rises together as blockage grows")
    void shouldLoseFlowAndGainPowerAsFilterBlocks() {
        AirHandlingUnit ahu = ahu();
        double[] betas = {0.0, 0.1, 0.25, 0.5, 0.75};
        for (int i = 1; i < betas.length; i++) {
            double flowBefore = ahu.deliveredFlow(betas[i - 1]).cubicMetresPerSecond();
            double flowAfter = ahu.deliveredFlow(betas[i]).cubicMetresPerSecond();
            double powerBefore = ahu.power(betas[i - 1]).watts();
            double powerAfter = ahu.power(betas[i]).watts();
            assertTrue(flowAfter < flowBefore,
                    "flow must fall from beta=%s to beta=%s".formatted(betas[i - 1], betas[i]));
            assertTrue(powerAfter > powerBefore,
                    "power must rise from beta=%s to beta=%s".formatted(betas[i - 1], betas[i]));
        }
    }

    @Test
    @DisplayName("plan §3: Q = Q_nom * s * (1 - beta)")
    void shouldDeliverNominalFlowWhenCleanAndAtNominalSpeed() {
        assertEquals(0.42, ahu().deliveredFlow(0).cubicMetresPerSecond(), 1e-12);
        assertEquals(0.42 * 0.5, ahu().deliveredFlow(0.5).cubicMetresPerSecond(), 1e-12);
        assertEquals(0.0, ahu().deliveredFlow(1.0).cubicMetresPerSecond(), 1e-12);
    }

    @Test
    @DisplayName("plan §3: power follows the affinity law, P ~ s^3")
    void shouldFollowTheCubeOfSpeedInPower() {
        AirHandlingUnit full = ahu();
        AirHandlingUnit half = full.atSpeed(FanSpeed.of(0.5));
        assertEquals(300.0, full.power(0).watts(), 1e-9);
        assertEquals(300.0 * 0.125, half.power(0).watts(), 1e-9);
        assertEquals(8.0, full.power(0).watts() / half.power(0).watts(), 1e-9);

        AirHandlingUnit quarter = full.atSpeed(FanSpeed.of(0.25));
        assertEquals(64.0, full.power(0).watts() / quarter.power(0).watts(), 1e-9);
    }

    @Test
    @DisplayName("plan §3: P = P_nom * s^3 * (1 + beta)")
    void shouldScalePowerWithBlockage() {
        assertEquals(300.0 * 1.4, ahu().power(0.4).watts(), 1e-9);
    }

    @Test
    void shouldFlowProportionallyToSpeed() {
        assertEquals(0.42 * 0.6,
                ahu().atSpeed(FanSpeed.of(0.6)).deliveredFlow(0).cubicMetresPerSecond(), 1e-12);
    }

    @Test
    @DisplayName("ADR-0008: an air handler without a served zone is not a topology")
    void shouldRejectEmptyServedZoneList() {
        assertThrows(IllegalArgumentException.class, () -> new AirHandlingUnit(
                AhuId.of("AHU-A120"), AirflowRate.ofCubicMetresPerSecond(0.42), 300.0,
                FanSpeed.NOMINAL, List.of()));
    }

    @Test
    void shouldRejectNullServedZoneList() {
        assertThrows(IllegalArgumentException.class, () -> new AirHandlingUnit(
                AhuId.of("AHU-A120"), AirflowRate.ofCubicMetresPerSecond(0.42), 300.0,
                FanSpeed.NOMINAL, null));
    }

    @Test
    void shouldRejectNonPositiveNominalPower() {
        assertThrows(IllegalArgumentException.class, () -> new AirHandlingUnit(
                AhuId.of("AHU-A120"), AirflowRate.ofCubicMetresPerSecond(0.42), 0.0,
                FanSpeed.NOMINAL, List.of(ZoneId.of("A115"))));
    }

    @Test
    void shouldRejectNullRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> new AirHandlingUnit(
                null, AirflowRate.ofCubicMetresPerSecond(0.42), 300.0, FanSpeed.NOMINAL,
                List.of(ZoneId.of("A115"))));
        assertThrows(IllegalArgumentException.class, () -> new AirHandlingUnit(
                AhuId.of("AHU-A120"), null, 300.0, FanSpeed.NOMINAL, List.of(ZoneId.of("A115"))));
        assertThrows(IllegalArgumentException.class, () -> new AirHandlingUnit(
                AhuId.of("AHU-A120"), AirflowRate.ofCubicMetresPerSecond(0.42), 300.0, null,
                List.of(ZoneId.of("A115"))));
    }

    @Test
    @DisplayName("the served-zone list is defensively copied")
    void shouldCopyServedZoneList() {
        var mutable = new java.util.ArrayList<ZoneId>(List.of(ZoneId.of("A115")));
        AirHandlingUnit ahu = new AirHandlingUnit(AhuId.of("AHU-A120"),
                AirflowRate.ofCubicMetresPerSecond(0.42), 300.0, FanSpeed.NOMINAL, mutable);
        mutable.add(ZoneId.of("A124"));
        assertEquals(1, ahu.servedZones().size());
    }
}
