package se.ltu.d7065e.ventilation.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The {@link VentilationZone} aggregate invariant: a degraded zone is no longer the inner
 * loop's to command. This is what makes R-2 and R-7 correct — degradation exists to take the
 * damper away from {@code dcv-controller}, so a stale inner-loop command arriving afterwards
 * must be refused rather than silently undoing the supervisor's decision.
 */
class VentilationZoneTest {

    private static final DamperPosition MINIMUM = DamperPosition.of(0.15);

    private static VentilationZone zone(ControlMode mode) {
        return new VentilationZone(ZoneId.of("A115"), 132.0, 44.0, AhuId.of("AHU-A120"),
                85.4, MINIMUM, mode, MINIMUM);
    }

    @Test
    @DisplayName("R-2/R-7: a DEGRADED zone refuses an inner-loop control command")
    void shouldRejectControlCommandWhileDegraded() {
        VentilationZone degraded = zone(ControlMode.DEGRADED);
        var thrown = assertThrows(IllegalStateException.class,
                () -> degraded.withControlCommand(DamperPosition.of(0.5)));
        assertEquals(true, thrown.getMessage().contains("A115"));
        assertEquals(MINIMUM, degraded.commandedPosition());
    }

    @Test
    @DisplayName("R-2: a supervisory command moves the same DEGRADED zone")
    void shouldAcceptSupervisoryCommandWhileDegraded() {
        VentilationZone degraded = zone(ControlMode.DEGRADED);
        VentilationZone moved = degraded.withSupervisoryCommand(DamperPosition.of(0.9));
        assertEquals(DamperPosition.of(0.9), moved.commandedPosition());
        assertEquals(ControlMode.DEGRADED, moved.mode());
    }

    @ParameterizedTest
    @EnumSource(value = ControlMode.class, names = {"NORMAL", "COMPENSATING"})
    @DisplayName("a zone that is not degraded still accepts the inner loop")
    void shouldAcceptControlCommandWhenNotDegraded(ControlMode mode) {
        assertEquals(DamperPosition.of(0.6),
                zone(mode).withControlCommand(DamperPosition.of(0.6)).commandedPosition());
    }

    @ParameterizedTest
    @EnumSource(ControlMode.class)
    @DisplayName("ADR-0008: a command below the zone minimum is clamped up, not stored")
    void shouldClampCommandBelowZoneMinimumUp(ControlMode mode) {
        VentilationZone commanded = zone(mode).withSupervisoryCommand(DamperPosition.SHUT);
        assertEquals(MINIMUM, commanded.commandedPosition());
        assertNotEquals(DamperPosition.SHUT, commanded.commandedPosition());
    }

    @Test
    @DisplayName("ADR-0008: the clamp also applies on the inner-loop path")
    void shouldClampControlCommandBelowZoneMinimumUp() {
        assertEquals(MINIMUM,
                zone(ControlMode.NORMAL).withControlCommand(DamperPosition.of(0.01))
                        .commandedPosition());
    }

    @Test
    @DisplayName("plan §3: opening area in the flow split is position x damperAuthority")
    void shouldComputeOpeningAreaAsPositionTimesAuthority() {
        VentilationZone open = zone(ControlMode.NORMAL).withSupervisoryCommand(DamperPosition.OPEN);
        assertEquals(85.4, open.openingArea(), 1e-12);
        assertEquals(0.5 * 85.4,
                zone(ControlMode.NORMAL).withSupervisoryCommand(DamperPosition.of(0.5)).openingArea(),
                1e-12);
        assertEquals(0.15 * 85.4, zone(ControlMode.NORMAL).openingArea(), 1e-12);
    }

    @Test
    void shouldChangeModeWithoutMovingTheDamper() {
        VentilationZone commanded =
                zone(ControlMode.NORMAL).withSupervisoryCommand(DamperPosition.of(0.7));
        VentilationZone degraded = commanded.inMode(ControlMode.DEGRADED);
        assertEquals(ControlMode.DEGRADED, degraded.mode());
        assertEquals(DamperPosition.of(0.7), degraded.commandedPosition());
    }

    @Test
    void shouldRejectNullZoneFields() {
        assertThrows(IllegalArgumentException.class, () -> new VentilationZone(
                null, 132.0, 44.0, AhuId.of("AHU-A120"), 85.4, MINIMUM, ControlMode.NORMAL, MINIMUM));
        assertThrows(IllegalArgumentException.class, () -> new VentilationZone(
                ZoneId.of("A115"), 132.0, 44.0, null, 85.4, MINIMUM, ControlMode.NORMAL, MINIMUM));
        assertThrows(IllegalArgumentException.class, () -> new VentilationZone(
                ZoneId.of("A115"), 132.0, 44.0, AhuId.of("AHU-A120"), 85.4, MINIMUM, null, MINIMUM));
    }

    @Test
    void shouldRejectNonPositiveGeometryAndAuthority() {
        assertThrows(IllegalArgumentException.class, () -> new VentilationZone(
                ZoneId.of("A115"), 0, 44.0, AhuId.of("AHU-A120"), 85.4, MINIMUM,
                ControlMode.NORMAL, MINIMUM));
        assertThrows(IllegalArgumentException.class, () -> new VentilationZone(
                ZoneId.of("A115"), 132.0, 0, AhuId.of("AHU-A120"), 85.4, MINIMUM,
                ControlMode.NORMAL, MINIMUM));
        assertThrows(IllegalArgumentException.class, () -> new VentilationZone(
                ZoneId.of("A115"), 132.0, 44.0, AhuId.of("AHU-A120"), 0, MINIMUM,
                ControlMode.NORMAL, MINIMUM));
    }

    @Test
    @DisplayName("ADR-0008: a zone cannot be constructed already below its own minimum")
    void shouldRejectCommandedPositionBelowMinimumAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new VentilationZone(
                ZoneId.of("A115"), 132.0, 44.0, AhuId.of("AHU-A120"), 85.4, MINIMUM,
                ControlMode.NORMAL, DamperPosition.of(0.05)));
    }
}
