package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.Evidence;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.diagnosis.entity.StepResponse;

/** T-15 — the decision tree over ADR-0008's signature table, and its honest "don't know". */
class FaultClassifierTest {

    private static final ObservedAt AT = ObservedAt.of(Instant.parse("2026-10-05T09:00:00Z"));

    private final MonitorParameters parameters = MonitorParameters.defaults();
    private final FaultClassifier classifier = new FaultClassifier(parameters);

    @Test
    void shouldClassifyOneRoomsResidualWithFlatFanPowerAsAStuckDamper() {
        Diagnosis diagnosis = classifier.classify(new FaultSignals(Topology.A115, 900, 420, 900, 0, false,
                1, 6, 1.0, StepResponse.MISSING, Topology.OUTDOOR.value(), AT));

        assertEquals(FaultClassification.STUCK_DAMPER, diagnosis.classification());
        assertSupports(diagnosis, "stuck damper: AHU power flat");
        assertArguesAgainst(diagnosis, "blocked filter: 1 of 6 rooms elevated");
    }

    @Test
    void shouldClassifyAllSixResidualsWithPowerPerFlowUpAsABlockedFilter() {
        Diagnosis diagnosis = classifier.classify(new FaultSignals(Topology.A124, 900, 310, 700, 0, false,
                6, 6, 1.30, StepResponse.MISSING, Topology.OUTDOOR.value(), AT));

        assertEquals(FaultClassification.BLOCKED_FILTER, diagnosis.classification());
        assertSupports(diagnosis, "a common-mode residual");
        assertSupports(diagnosis, "more power for less air");
        assertArguesAgainst(diagnosis, "one damper cannot starve the others");
    }

    @Test
    void shouldClassifyAnUnchangedStepShapeWithAWrongOvernightFloorAsSensorDrift() {
        Diagnosis diagnosis = classifier.classify(new FaultSignals(Topology.A125, 900, 260, 600, 0, false,
                1, 6, 1.0, StepResponse.MATCHED, 780, AT));

        assertEquals(FaultClassification.SENSOR_DRIFT, diagnosis.classification());
        assertSupports(diagnosis, "never returns to outdoor with nobody in it");
        assertArguesAgainst(diagnosis, "the commanded step did produce its response");
    }

    @Test
    void shouldNotCallDriftWhenTheOvernightFloorIsStillUnknown() {
        Diagnosis diagnosis = classifier.classify(new FaultSignals(Topology.A125, 900, 260, 600, 0, false,
                1, 6, 1.0, StepResponse.MATCHED, Double.NaN, AT));

        assertEquals(FaultClassification.UNCLASSIFIED, diagnosis.classification());
        assertArguesAgainst(diagnosis, "less than a day of history");
    }

    @Test
    void shouldReturnUnclassifiedWhenNoSignatureMatches() {
        Diagnosis diagnosis = classifier.classify(new FaultSignals(Topology.A145, 900, 300, 500, 0, false,
                1, 6, 1.20, StepResponse.MISSING, Topology.OUTDOOR.value(), AT));

        assertEquals(FaultClassification.UNCLASSIFIED, diagnosis.classification(),
                "one room elevated with the fan neither flat nor common-mode matches nothing");
        assertSupports(diagnosis, "no signature matched");
    }

    @Test
    void shouldArgueBothSidesOfEveryHypothesisSoADiagnosisCanBeExplained() {
        Diagnosis diagnosis = classifier.classify(new FaultSignals(Topology.A115, 900, 420, 900, 0, false,
                1, 6, 1.0, StepResponse.MISSING, Topology.OUTDOOR.value(), AT));

        assertTrue(diagnosis.evidence().stream().anyMatch(Evidence::supportsFault), "no evidence for");
        assertTrue(diagnosis.evidence().stream().anyMatch(e -> !e.supportsFault()), "no evidence against");
        for (String hypothesis : new String[] {"blocked filter", "sensor drift", "stuck damper"}) {
            assertTrue(diagnosis.evidence().stream().anyMatch(e -> e.observation().contains(hypothesis)),
                    "nothing was recorded about " + hypothesis + ", so R-5 cannot explain the choice");
        }
    }

    @Test
    void shouldStillNameAStuckDamperWithoutAJudgedStepButSayTheStepIsMissingFromTheReasoning() {
        // The signals are otherwise a textbook stuck damper: one room elevated, fan power flat,
        // but no commanded step has been judged. The leaf still fires, because a probe is requested
        // through the evidence on FaultStateChanged and so no step exists at classification time —
        // demanding one empties the leaf entirely. What must not happen is the work order claiming
        // a step test it never ran.
        Diagnosis diagnosis = classifier.classify(new FaultSignals(Topology.A115, 900, 420, 900, 0, false,
                1, 6, 1.0, StepResponse.NOT_JUDGED, Topology.OUTDOOR.value(), AT));

        assertEquals(FaultClassification.STUCK_DAMPER, diagnosis.classification());
        assertArguesAgainst(diagnosis, "NO COMMANDED STEP HAS BEEN JUDGED");
        assertTrue(diagnosis.evidence().stream().noneMatch(
                        e -> e.observation().contains("produced no matching response")),
                "a room that was never commanded to move must not be reported as one that failed "
                        + "to answer — that is the claim this type exists to prevent");
    }

    @Test
    void shouldReportAnUnansweredStepDifferentlyFromAnUnjudgedOne() {
        Diagnosis judged = classifier.classify(new FaultSignals(Topology.A115, 900, 420, 900, 0, false,
                1, 6, 1.0, StepResponse.MISSING, Topology.OUTDOOR.value(), AT));
        Diagnosis unjudged = classifier.classify(new FaultSignals(Topology.A115, 900, 420, 900, 0, false,
                1, 6, 1.0, StepResponse.NOT_JUDGED, Topology.OUTDOOR.value(), AT));

        assertEquals(judged.classification(), unjudged.classification(),
                "the diagnosis is the same; only the strength of the reasoning differs");
        assertSupports(judged, "the commanded step produced no matching response");
        assertArguesAgainst(unjudged, "rests on localisation and flat fan power alone");
    }

    @Test
    void shouldClassifyALowSideExcursionWithAnUnansweredCloseAsADamperStuckOpen() {
        Diagnosis diagnosis = classifier.classify(lowSideStuckOpen());

        assertEquals(FaultClassification.DAMPER_STUCK_OPEN, diagnosis.classification(),
                "the two extremes of travel need opposite responses, so they cannot share a name");
        assertSupports(diagnosis, "damper stuck WIDE OPEN");
        assertArguesAgainst(diagnosis, "a blocking filter starves rooms and cannot over-ventilate");
    }

    @Test
    void shouldNameTheOtherExtremeStuckDamperSoTheTwoDirectionsStayDistinct() {
        Diagnosis shut = classifier.classify(new FaultSignals(Topology.A115, 900, 420, 900, 0, false,
                1, 6, 1.0, StepResponse.MISSING, Topology.OUTDOOR.value(), AT));

        assertEquals(FaultClassification.STUCK_DAMPER, shut.classification());
        assertNotEquals(shut.classification(),
                classifier.classify(lowSideStuckOpen()).classification(),
                "a starved room and an over-ventilated one must not arrive under one name");
    }

    @Test
    void shouldCallBothExtremesOfTravelAnUnresponsiveDamper() {
        // What the shared flow model branches on: either extreme has stopped following commands, so
        // the believed opening has to be pinned for both or the other five rooms' residuals are
        // computed against a building the model no longer describes (ADR-0010, R-7).
        assertTrue(FaultClassification.STUCK_DAMPER.isUnresponsiveDamper());
        assertTrue(FaultClassification.DAMPER_STUCK_OPEN.isUnresponsiveDamper());
        for (FaultClassification other : new FaultClassification[] {FaultClassification.NONE,
                FaultClassification.BLOCKED_FILTER, FaultClassification.SENSOR_DRIFT,
                FaultClassification.UNCLASSIFIED}) {
            assertFalse(other.isUnresponsiveDamper(), other + " is not a damper that stopped moving");
        }
    }

    /**
     * A room over-ventilated after an unanswered command to close: its residual is negative, the
     * lower arm carries the excursion, the fan is flat, and the reading is above outdoor so the
     * sensor-drift leaf is not the one that fires.
     */
    private static FaultSignals lowSideStuckOpen() {
        return new FaultSignals(Topology.A115, 630, -110, 0, 320, true,
                1, 6, 1.0, StepResponse.MISSING, Topology.OUTDOOR.value(), AT);
    }

    private void assertSupports(Diagnosis diagnosis, String fragment) {
        assertTrue(diagnosis.evidence().stream()
                        .anyMatch(e -> e.supportsFault() && e.observation().contains(fragment)),
                "expected supporting evidence containing '%s', got %s"
                        .formatted(fragment, diagnosis.explanation()));
    }

    private void assertArguesAgainst(Diagnosis diagnosis, String fragment) {
        assertTrue(diagnosis.evidence().stream()
                        .anyMatch(e -> !e.supportsFault() && e.observation().contains(fragment)),
                "expected evidence against containing '%s', got %s"
                        .formatted(fragment, diagnosis.explanation()));
    }
}
