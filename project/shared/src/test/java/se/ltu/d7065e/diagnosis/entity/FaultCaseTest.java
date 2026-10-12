package se.ltu.d7065e.diagnosis.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The hardest property in the design: a latched case leaves CONFIRMED or COMPENSATING only
 * through {@link FaultCase#serviced}. Compensation shrinks the very residual that confirmed
 * the fault (R-2), so a monitor that re-evaluated freely would clear a fault that is still
 * there and hand the zone back to the inner loop — breaking R-2 and, through the restored
 * control mode, R-7.
 */
class FaultCaseTest {

    private static final ZoneId ZONE = ZoneId.of("A115");
    private static final ObservedAt OPENED = ObservedAt.of(Instant.parse("2026-10-05T08:00:00Z"));
    private static final ObservedAt LATER = ObservedAt.of(Instant.parse("2026-10-05T08:05:00Z"));
    private static final ObservedAt LATEST = ObservedAt.of(Instant.parse("2026-10-05T08:10:00Z"));

    private static FaultCase suspected() {
        return FaultCase.suspected(ZONE, Evidence.supporting("cusum over h=150 ppm", OPENED), OPENED);
    }

    private static FaultCase confirmed() {
        return suspected().confirmed(FaultClassification.STUCK_DAMPER, LATER);
    }

    @Test
    @DisplayName("R-1: a new case opens as SUSPECT with no classification yet")
    void shouldOpenAsSuspectWithoutClassification() {
        FaultCase open = suspected();
        assertEquals(MonitorState.SUSPECT, open.state());
        assertEquals(FaultClassification.NONE, open.classification());
        assertEquals(ZONE, open.zone());
        assertEquals(OPENED, open.openedAt());
        assertEquals(OPENED, open.updatedAt());
        assertEquals(1, open.evidence().size());
        assertFalse(open.isLatched());
    }

    @Test
    @DisplayName("R-2/R-7: compensating() is refused from a non-latched state")
    void shouldRejectCompensatingFromNonLatchedState() {
        FaultCase open = suspected();
        var thrown = assertThrows(IllegalStateException.class, () -> open.compensating(LATER));
        assertTrue(thrown.getMessage().contains("SUSPECT"));
        assertThrows(IllegalStateException.class, () -> confirmed().serviced(LATER).compensating(LATEST));
    }

    @Test
    @DisplayName("R-2/R-7: serviced() is refused from a non-latched state")
    void shouldRejectServicedFromNonLatchedState() {
        FaultCase open = suspected();
        var thrown = assertThrows(IllegalStateException.class, () -> open.serviced(LATER));
        assertTrue(thrown.getMessage().contains("SUSPECT"));
        assertThrows(IllegalStateException.class, () -> confirmed().serviced(LATER).serviced(LATEST));
    }

    @Test
    @DisplayName("T-15: NONE is not a diagnosis, so a case cannot be confirmed as NONE")
    void shouldRejectConfirmationAsNone() {
        FaultCase open = suspected();
        assertThrows(IllegalArgumentException.class,
                () -> open.confirmed(FaultClassification.NONE, LATER));
    }

    @Test
    @DisplayName("T-15: UNCLASSIFIED is a first-class outcome and may be confirmed")
    void shouldAllowConfirmationAsUnclassified() {
        FaultCase latched = suspected().confirmed(FaultClassification.UNCLASSIFIED, LATER);
        assertEquals(MonitorState.CONFIRMED, latched.state());
        assertEquals(FaultClassification.UNCLASSIFIED, latched.classification());
        assertTrue(latched.isLatched());
    }

    @Test
    @DisplayName("R-2: a CONFIRMED case stays CONFIRMED when evidence against it arrives")
    void shouldStayConfirmedWhenMoreEvidenceArrives() {
        FaultCase latched = confirmed();
        FaultCase withMore = latched
                .withEvidence(Evidence.against("residual shrank after compensation", LATEST), LATEST);
        assertEquals(MonitorState.CONFIRMED, withMore.state());
        assertEquals(FaultClassification.STUCK_DAMPER, withMore.classification());
        assertTrue(withMore.isLatched());
        assertEquals(2, withMore.evidence().size());
        // R-5: the case id and opening instant survive so the diagnosis stays reconstructable.
        assertEquals(latched.id(), withMore.id());
        assertEquals(OPENED, withMore.openedAt());
        assertEquals(LATEST, withMore.updatedAt());
    }

    @Test
    @DisplayName("R-2: a COMPENSATING case stays latched when evidence against it arrives")
    void shouldStayCompensatingWhenMoreEvidenceArrives() {
        FaultCase compensating = confirmed().compensating(LATER);
        FaultCase withMore = compensating
                .withEvidence(Evidence.against("co2 back below 1000 ppm", LATEST), LATEST);
        assertEquals(MonitorState.COMPENSATING, withMore.state());
        assertTrue(withMore.isLatched());
    }

    @Test
    @DisplayName("R-2/R-7: serviced() is the only exit from CONFIRMED")
    void shouldLeaveConfirmedOnlyThroughServiced() {
        FaultCase latched = confirmed();
        assertTrue(latched.withEvidence(Evidence.against("residual shrank", LATEST), LATEST)
                .isLatched());
        assertTrue(latched.compensating(LATEST).isLatched());
        assertTrue(latched.confirmed(FaultClassification.BLOCKED_FILTER, LATEST).isLatched());
        assertEquals(MonitorState.SERVICED, latched.serviced(LATEST).state());
        assertFalse(latched.serviced(LATEST).isLatched());
    }

    @Test
    @DisplayName("R-2/R-7: serviced() is the only exit from COMPENSATING")
    void shouldLeaveCompensatingOnlyThroughServiced() {
        FaultCase compensating = confirmed().compensating(LATER);
        assertTrue(compensating.withEvidence(Evidence.against("residual gone", LATEST), LATEST)
                .isLatched());
        assertTrue(compensating.compensating(LATEST).isLatched());
        assertTrue(compensating.confirmed(FaultClassification.STUCK_DAMPER, LATEST).isLatched());
        assertEquals(MonitorState.SERVICED, compensating.serviced(LATEST).state());
        assertFalse(compensating.serviced(LATEST).isLatched());
    }

    @Test
    @DisplayName("T-35: reclassified() names a deferred case without moving its state")
    void shouldReplaceTheClassificationOfALatchedCaseWithoutChangingItsState() {
        FaultCase deferred = suspected().confirmed(FaultClassification.UNCLASSIFIED, LATER);
        FaultCase named = deferred.reclassified(FaultClassification.STUCK_DAMPER, LATEST);

        assertEquals(FaultClassification.STUCK_DAMPER, named.classification());
        assertEquals(MonitorState.CONFIRMED, named.state());
        assertEquals(deferred.id(), named.id(), "R-5: renaming a case must not open a new one");
        assertEquals(OPENED, named.openedAt());
        assertEquals(LATEST, named.updatedAt());
    }

    @Test
    @DisplayName("T-35: a case already compensating may still be named, and stays compensating")
    void shouldNameACompensatingCaseWithoutTakingItOutOfCompensation() {
        FaultCase compensating = suspected()
                .confirmed(FaultClassification.UNCLASSIFIED, LATER).compensating(LATER);
        FaultCase named = compensating.reclassified(FaultClassification.STUCK_DAMPER, LATEST);

        assertEquals(MonitorState.COMPENSATING, named.state(),
                "naming a case must not quietly restart it as CONFIRMED");
        assertTrue(named.isLatched());
    }

    @Test
    @DisplayName("T-35: reclassified() cannot un-name a case or reach a non-latched one")
    void shouldRefuseToReclassifyAsNoneOrFromANonLatchedState() {
        FaultCase latched = confirmed();
        assertThrows(IllegalArgumentException.class,
                () -> latched.reclassified(FaultClassification.NONE, LATEST));
        FaultCase open = suspected();
        var thrown = assertThrows(IllegalStateException.class,
                () -> open.reclassified(FaultClassification.STUCK_DAMPER, LATER));
        assertTrue(thrown.getMessage().contains("SUSPECT"));
    }

    @Test
    @DisplayName("R-5: the classification survives compensation and service")
    void shouldKeepClassificationThroughCompensationAndService() {
        FaultCase serviced = confirmed().compensating(LATER).serviced(LATEST);
        assertEquals(FaultClassification.STUCK_DAMPER, serviced.classification());
        assertEquals(ZONE, serviced.zone());
        assertEquals(OPENED, serviced.openedAt());
        assertEquals(LATEST, serviced.updatedAt());
    }

    @Test
    @DisplayName("a latched case must carry a classification, even UNCLASSIFIED")
    void shouldRejectLatchedCaseWithoutClassification() {
        assertThrows(IllegalArgumentException.class, () -> new FaultCase(
                CaseId.newId(), ZONE, MonitorState.CONFIRMED, FaultClassification.NONE,
                List.of(), OPENED, OPENED));
        assertThrows(IllegalArgumentException.class, () -> new FaultCase(
                CaseId.newId(), ZONE, MonitorState.COMPENSATING, FaultClassification.NONE,
                List.of(), OPENED, OPENED));
    }

    @ParameterizedTest
    @EnumSource(value = MonitorState.class,
            names = {"WARMUP", "HEALTHY", "SUSPECT", "SERVICED", "BLIND"})
    @DisplayName("a non-latched state may legitimately carry no classification")
    void shouldAllowNonLatchedCaseWithoutClassification(MonitorState state) {
        assertEquals(state, new FaultCase(CaseId.newId(), ZONE, state, FaultClassification.NONE,
                List.of(), OPENED, OPENED).state());
    }

    @Test
    void shouldRejectNullCaseFields() {
        assertThrows(IllegalArgumentException.class, () -> new FaultCase(
                null, ZONE, MonitorState.SUSPECT, FaultClassification.NONE, List.of(), OPENED, OPENED));
        assertThrows(IllegalArgumentException.class, () -> new FaultCase(
                CaseId.newId(), null, MonitorState.SUSPECT, FaultClassification.NONE, List.of(),
                OPENED, OPENED));
        assertThrows(IllegalArgumentException.class, () -> new FaultCase(
                CaseId.newId(), ZONE, null, FaultClassification.NONE, List.of(), OPENED, OPENED));
    }

    @Test
    @DisplayName("R-5: the evidence list is defensively copied so a case cannot be rewritten")
    void shouldCopyEvidenceList() {
        var mutable = new ArrayList<Evidence>(List.of(Evidence.supporting("first", OPENED)));
        FaultCase open = new FaultCase(CaseId.newId(), ZONE, MonitorState.SUSPECT,
                FaultClassification.NONE, mutable, OPENED, OPENED);
        mutable.add(Evidence.supporting("sneaked in", LATER));
        assertEquals(1, open.evidence().size());
        assertThrows(UnsupportedOperationException.class,
                () -> open.evidence().add(Evidence.supporting("sneaked in", LATER)));
    }

    @Test
    void shouldTreatANullEvidenceListAsEmpty() {
        FaultCase open = new FaultCase(CaseId.newId(), ZONE, MonitorState.HEALTHY,
                FaultClassification.NONE, null, OPENED, OPENED);
        assertEquals(List.of(), open.evidence());
    }

    @Nested
    @DisplayName("MonitorState (plan §3 state machine)")
    class MonitorStateTest {

        @ParameterizedTest
        @EnumSource(MonitorState.class)
        @DisplayName("isLatched() is true exactly for CONFIRMED and COMPENSATING")
        void shouldLatchExactlyConfirmedAndCompensating(MonitorState state) {
            boolean expected =
                    state == MonitorState.CONFIRMED || state == MonitorState.COMPENSATING;
            assertEquals(expected, state.isLatched(), "isLatched() of " + state);
        }

        @ParameterizedTest
        @EnumSource(value = MonitorState.class, names = {"CONFIRMED", "COMPENSATING"})
        @DisplayName("R-2/R-4: a latched case can never go BLIND")
        void shouldNotGoBlindOnceLatched(MonitorState latched) {
            assertTrue(latched.isLatched());
            assertFalse(latched.canGoBlind(), latched + " must not be able to go BLIND");
        }

        /**
         * R-4: BLIND is reachable from the states in which no diagnosis is held. The class
         * javadoc and plan §3 name HEALTHY and SUSPECT; the code also allows WARMUP, which is
         * the wider, defensible reading (a warming-up monitor with stale data is also blind).
         */
        @ParameterizedTest
        @EnumSource(MonitorState.class)
        void shouldAllowBlindOnlyFromWarmupHealthyAndSuspect(MonitorState state) {
            boolean expected = state == MonitorState.WARMUP
                    || state == MonitorState.HEALTHY
                    || state == MonitorState.SUSPECT;
            assertEquals(expected, state.canGoBlind(), "canGoBlind() of " + state);
        }

        @Test
        @DisplayName("R-4: BLIND is not a fault, so it is not latched")
        void shouldNotLatchBlind() {
            assertFalse(MonitorState.BLIND.isLatched());
            assertFalse(MonitorState.BLIND.canGoBlind());
        }
    }

    @Nested
    @DisplayName("CusumStatistic (T-04)")
    class CusumStatisticTest {

        @ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(doubles = {-0.0001, -1, -150})
        @DisplayName("T-04: S = max(0, S + r - k) never banks credit, so it is never negative")
        void shouldRejectNegativeCusum(double negative) {
            assertThrows(IllegalArgumentException.class, () -> CusumStatistic.of(negative));
        }

        @ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(
                doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void shouldRejectNonFiniteCusum(double nonFinite) {
            assertThrows(IllegalArgumentException.class, () -> CusumStatistic.of(nonFinite));
        }

        @Test
        @DisplayName("T-04: the alarm limit h = 150 ppm is a strict exceedance")
        void shouldExceedOnlyStrictlyAboveTheLimit() {
            assertEquals(0.0, CusumStatistic.RESET.value());
            assertFalse(CusumStatistic.of(150).exceeds(150));
            assertTrue(CusumStatistic.of(150.1).exceeds(150));
        }
    }

    @Nested
    @DisplayName("Residual and Evidence")
    class ResidualAndEvidenceTest {

        @Test
        @DisplayName("a residual may be negative: the room can be cleaner than predicted")
        void shouldAcceptNegativeResidual() {
            assertEquals(-42.5, Residual.of(-42.5).ppm(), 1e-12);
        }

        @ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(
                doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void shouldRejectNonFiniteResidual(double nonFinite) {
            assertThrows(IllegalArgumentException.class, () -> Residual.of(nonFinite));
        }

        @Test
        @DisplayName("R-5: evidence needs an observation and an instant to be explainable")
        void shouldRejectEvidenceWithoutObservationOrInstant() {
            assertThrows(IllegalArgumentException.class, () -> Evidence.supporting(null, OPENED));
            assertThrows(IllegalArgumentException.class, () -> Evidence.supporting("  ", OPENED));
            assertThrows(IllegalArgumentException.class, () -> Evidence.against("drift", null));
        }

        @Test
        void shouldRecordWhetherEvidenceSupportsOrOpposesTheFault() {
            assertTrue(Evidence.supporting("cusum over h", OPENED).supportsFault());
            assertFalse(Evidence.against("flow recovered", OPENED).supportsFault());
        }
    }

    @Nested
    @DisplayName("CaseId (R-5)")
    class CaseIdTest {

        @ParameterizedTest
        @org.junit.jupiter.params.provider.NullAndEmptySource
        @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "\t"})
        void shouldRejectBlankCaseId(String blank) {
            assertThrows(IllegalArgumentException.class, () -> CaseId.of(blank));
        }

        @Test
        void shouldMintDistinctCaseIds() {
            assertFalse(CaseId.newId().equals(CaseId.newId()));
        }
    }
}
