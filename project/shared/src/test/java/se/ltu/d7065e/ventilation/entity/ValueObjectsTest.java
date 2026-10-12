package se.ltu.d7065e.ventilation.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Invariants of the value objects in the shared vocabulary (plan §3, ADR-0006). Every
 * container decodes wire payloads straight into these types, so a value that is nonsense has
 * to be refused here or it travels the whole system unchallenged.
 */
class ValueObjectsTest {

    @Nested
    @DisplayName("ZoneId — the bare room name, never a BuildSim key (plan §3, ADR-0006)")
    class ZoneIdTest {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t", "\n"})
        void shouldRejectBlankZoneId(String blank) {
            assertThrows(IllegalArgumentException.class, () -> ZoneId.of(blank));
        }

        /**
         * A slash is the MQTT level separator, so a floor-qualified key would silently turn
         * {@code readings/{zone}/co2} into a four-level topic (plan §3).
         */
        @ParameterizedTest
        @ValueSource(strings = {"level0/A115", "/A115", "A115/", "a/b/c"})
        void shouldRejectZoneIdContainingSlash(String buildSimKey) {
            assertThrows(IllegalArgumentException.class, () -> ZoneId.of(buildSimKey));
        }

        @Test
        void shouldAcceptBareRoomName() {
            assertEquals("A115", ZoneId.of("A115").value());
            assertEquals("A115", ZoneId.of("A115").toString());
        }
    }

    @Nested
    @DisplayName("Ppm — CO2 concentration (R-2 comfort limit is stated in ppm)")
    class PpmTest {

        @ParameterizedTest
        @ValueSource(doubles = {-0.0001, -1, -1000})
        void shouldRejectNegativePpm(double negative) {
            assertThrows(IllegalArgumentException.class, () -> Ppm.of(negative));
        }

        @ParameterizedTest
        @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void shouldRejectNonFinitePpm(double nonFinite) {
            assertThrows(IllegalArgumentException.class, () -> Ppm.of(nonFinite));
        }

        @Test
        void shouldAcceptZeroAndCompareAgainstTheComfortLimit() {
            assertEquals(0.0, Ppm.of(0).value());
            // R-2: the acceptance criterion is "<= 1000 ppm", so 1000 must not count as exceeding.
            assertEquals(false, Ppm.of(1000).exceeds(Ppm.of(1000)));
            assertEquals(true, Ppm.of(1000.1).exceeds(Ppm.of(1000)));
        }
    }

    @Nested
    @DisplayName("DamperPosition — 0 shut to 1 open (ADR-0008)")
    class DamperPositionTest {

        @ParameterizedTest
        @ValueSource(doubles = {-0.0001, -1, 1.0001, 2, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void shouldRejectPositionOutsideUnitInterval(double outside) {
            assertThrows(IllegalArgumentException.class, () -> DamperPosition.of(outside));
        }

        @Test
        void shouldAcceptBothEndsOfTheInterval() {
            assertEquals(0.0, DamperPosition.SHUT.value());
            assertEquals(1.0, DamperPosition.OPEN.value());
        }

        /** ADR-0008: ventilation never shuts entirely, so a low command is raised to the floor. */
        @Test
        void shouldRaiseBelowMinimumPositionWhenClamped() {
            DamperPosition minimum = DamperPosition.of(0.15);
            assertEquals(minimum, DamperPosition.of(0.02).clampedTo(minimum));
            assertEquals(minimum, DamperPosition.SHUT.clampedTo(minimum));
        }

        @Test
        void shouldLeaveAtOrAboveMinimumPositionUntouchedWhenClamped() {
            DamperPosition minimum = DamperPosition.of(0.15);
            DamperPosition atMinimum = DamperPosition.of(0.15);
            DamperPosition above = DamperPosition.of(0.80);
            assertEquals(atMinimum, atMinimum.clampedTo(minimum));
            assertSame(above, above.clampedTo(minimum));
        }

        @Test
        void shouldReportDifferenceOnlyBeyondTolerance() {
            assertEquals(false, DamperPosition.of(0.50).differsFrom(DamperPosition.of(0.51), 0.02));
            assertEquals(true, DamperPosition.of(0.50).differsFrom(DamperPosition.of(0.60), 0.02));
        }
    }

    @Nested
    @DisplayName("FanSpeed — the plant range [0.2, 1.0] (ADR-0008, plan §3)")
    class FanSpeedTest {

        @ParameterizedTest
        @ValueSource(doubles = {0, 0.1, 0.1999, 1.0001, 1.5, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void shouldRejectSpeedOutsidePlantRange(double outside) {
            assertThrows(IllegalArgumentException.class, () -> FanSpeed.of(outside));
        }

        @Test
        void shouldAcceptBothEndsOfThePlantRange() {
            assertEquals(0.2, FanSpeed.of(FanSpeed.MINIMUM).value());
            assertEquals(1.0, FanSpeed.NOMINAL.value());
        }

        /** Control output may overshoot; the compensation lever must saturate, not fail (T-16). */
        @Test
        void shouldSaturateInsteadOfThrowingWhenClamped() {
            assertEquals(FanSpeed.MINIMUM, FanSpeed.clamped(-3).value());
            assertEquals(FanSpeed.MINIMUM, FanSpeed.clamped(0).value());
            assertEquals(FanSpeed.MAXIMUM, FanSpeed.clamped(1.4).value());
            assertEquals(0.55, FanSpeed.clamped(0.55).value());
        }
    }

    @Nested
    @DisplayName("AirflowRate — m3/s on the inside, l/s in the design tables (plan §3)")
    class AirflowRateTest {

        @Test
        void shouldConvertLitresPerSecondToCubicMetresPerSecond() {
            assertEquals(0.0854, AirflowRate.ofLitresPerSecond(85.4).cubicMetresPerSecond(), 1e-12);
        }

        @Test
        void shouldConvertCubicMetresPerSecondToLitresPerSecond() {
            assertEquals(420.0, AirflowRate.ofCubicMetresPerSecond(0.42).litresPerSecond(), 1e-9);
        }

        /** The six ADR-0008 design flows must survive a round trip through the internal unit. */
        @ParameterizedTest
        @ValueSource(doubles = {13.3, 24.4, 39.6, 85.4, 82.8, 165.9, 411.4})
        void shouldRoundTripDesignFlowsConsistently(double litresPerSecond) {
            AirflowRate rate = AirflowRate.ofLitresPerSecond(litresPerSecond);
            assertEquals(litresPerSecond, rate.litresPerSecond(), 1e-9);
            assertEquals(litresPerSecond / 1000.0, rate.cubicMetresPerSecond(), 1e-12);
            assertEquals(rate.cubicMetresPerSecond(),
                    AirflowRate.ofCubicMetresPerSecond(rate.cubicMetresPerSecond())
                            .cubicMetresPerSecond());
        }

        @ParameterizedTest
        @ValueSource(doubles = {-0.0001, -1, Double.NaN, Double.POSITIVE_INFINITY})
        void shouldRejectNegativeOrNonFiniteAirflow(double invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> AirflowRate.ofCubicMetresPerSecond(invalid));
        }
    }

    @Nested
    @DisplayName("FanPower — the monitor's one cross-room signal (ADR-0008)")
    class FanPowerTest {

        @ParameterizedTest
        @ValueSource(doubles = {-0.0001, -300, Double.NaN, Double.POSITIVE_INFINITY})
        void shouldRejectNegativeOrNonFinitePower(double invalid) {
            assertThrows(IllegalArgumentException.class, () -> FanPower.ofWatts(invalid));
        }

        @Test
        void shouldAcceptZeroWatts() {
            assertEquals(0.0, FanPower.ofWatts(0).watts());
        }
    }

    @Nested
    @DisplayName("Identifiers and instants")
    class IdentifierTest {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t"})
        void shouldRejectBlankAhuId(String blank) {
            assertThrows(IllegalArgumentException.class, () -> AhuId.of(blank));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t"})
        void shouldRejectBlankCommandId(String blank) {
            assertThrows(IllegalArgumentException.class, () -> CommandId.of(blank));
        }

        /** R-5: a residual must be tied to the command it judges, so ids must be distinct. */
        @Test
        void shouldMintDistinctCommandIds() {
            assertEquals(false, CommandId.newId().equals(CommandId.newId()));
        }

        @Test
        void shouldRejectNullObservationInstant() {
            assertThrows(IllegalArgumentException.class, () -> ObservedAt.of(null));
        }

        /** R-4: staleness beyond three intervals is what drives the monitor BLIND. */
        @Test
        void shouldReportStalenessAgainstALimit() {
            var taken = ObservedAt.of(java.time.Instant.parse("2026-10-05T08:00:00Z"));
            var now = java.time.Instant.parse("2026-10-05T08:00:31Z");
            assertEquals(true, taken.isStalerThan(java.time.Duration.ofSeconds(30), now));
            assertEquals(false, taken.isStalerThan(java.time.Duration.ofSeconds(60), now));
            assertEquals(java.time.Duration.ofSeconds(300),
                    ObservedAt.of(java.time.Instant.parse("2026-10-05T08:05:00Z")).since(taken));
        }
    }

    @Nested
    @DisplayName("Quality — only GOOD may feed the observer (T-21, R-4)")
    class QualityTest {

        @Test
        void shouldTreatOnlyGoodReadingsAsUsable() {
            assertEquals(true, Quality.GOOD.isUsable());
            assertEquals(false, Quality.STALE.isUsable());
            assertEquals(false, Quality.MISSING.isUsable());
            assertEquals(false, Quality.DUPLICATE.isUsable());
        }
    }
}
