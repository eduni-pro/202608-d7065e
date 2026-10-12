package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.diagnosis.entity.StepResponse;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-35 — the mechanics of {@link ClassifyWhen#AFTER_PROBE}, which {@code ProbeOrderExperiment}
 * then measures end to end against {@link ClassifyWhen#ON_CONFIRMATION}.
 *
 * <p>The property every test here protects is that the reorder moves the <em>naming</em> of a fault
 * and nothing else. The case has to latch on the cycle the CUSUM says so, because a confirmed
 * residual is a room that needs the supervisor's response whether or not the fault has a name — and
 * because the probe cannot be commanded at all until a latched case exists for the supervisor to act
 * on.
 *
 * <p>Every fixture here confirms on a <b>sensor offset and no command</b>, which is how a
 * confirmation with nothing to compare a step against is reached: a stuck damper in this harness is
 * found by commanding it, and the command that finds it is also a judged step, so that route never
 * reaches the deferral at all. The harness has no supervisor, so a probe request reaches nobody; the
 * tests that need a judged step stand in for it with the command {@code SupervisionService.probe}
 * publishes.
 */
class ProbeOrderedClassificationTest {

    private static final ZoneId FAULTED = Topology.A115;
    private static final Duration BUDGET = Duration.ofMinutes(15);

    /** Enough offset to clear the occupancy ceiling and h = 150 ppm within the budget. */
    private static final double OFFSET_PPM = 600;

    @Test
    void shouldLatchOnTheSameCycleUnderBothOrderings() {
        assertEquals(cyclesToLatch(ClassifyWhen.ON_CONFIRMATION),
                cyclesToLatch(ClassifyWhen.AFTER_PROBE),
                "R-1: deferring the diagnosis must not defer the detection — the case opens on the "
                        + "cycle the CUSUM confirms under both orderings");
    }

    @Test
    void shouldLatchWithoutANameWhenNoStepHasBeenJudged() {
        MonitorHarness harness = deferredHarness();

        assertEquals(MonitorState.CONFIRMED, harness.monitor().stateOf(FAULTED));
        assertEquals(FaultClassification.UNCLASSIFIED,
                harness.monitor().caseOf(FAULTED).classification());
        assertTrue(harness.monitor().classificationDeferredOf(FAULTED));
        assertTrue(evidence(harness).stream().anyMatch(line -> line.contains("diagnosis deferred")),
                "R-5: a work order raised without a diagnosis must say it was withheld and why");
    }

    @Test
    void shouldAskForTheProbeOnTheCycleItLatchesRatherThanOneProbeIntervalLater() {
        MonitorHarness harness = deferredHarness();

        assertTrue(evidence(harness).stream()
                        .anyMatch(line -> line.contains(ZoneMonitor.PROBE_REQUEST)),
                "the probe is what the deferral waits for, so it cannot itself wait a probe interval");
        assertTrue(evidence(harness).stream()
                        .anyMatch(line -> line.contains("withholding its diagnosis for")),
                "the request must say it is the diagnosis that is waiting, not a re-test");
    }

    @Test
    void shouldNameAStuckDamperWhenTheProbeStepGoesUnanswered() {
        MonitorHarness harness = deferredHarness();
        harness.physics().stick(FAULTED, Topology.MINIMUM_POSITION.value());

        harness.command(FAULTED, 1.0, "supervisor: the probe the monitor asked for");
        int cycles = harness.tickUntil(Duration.ofMinutes(20),
                () -> !harness.monitor().classificationDeferredOf(FAULTED));

        assertNotEquals(-1, cycles, "the judged probe never named the case");
        assertTrue(cycles < MonitorParameters.defaults().probeInterval().toMinutes(),
                "the case was named at the deadline rather than by the probe, so the probe's step "
                        + "was never judged and this test is not exercising the reorder");
        assertEquals(StepResponse.MISSING, harness.monitor().stepResponseOf(FAULTED),
                "a damper frozen at its minimum cannot answer a step to 1.00");
        assertEquals(FaultClassification.STUCK_DAMPER,
                harness.monitor().caseOf(FAULTED).classification());
        assertTrue(harness.monitor().caseOf(FAULTED).evidence().stream()
                        .anyMatch(e -> e.observation().contains("on step response MISSING")),
                "R-5: the work order must record that its diagnosis rests on a judged step");
    }

    /**
     * The reorder's purpose, and the measured reason it cannot serve it. This room's damper is
     * perfectly healthy and only its sensor is wrong: it answers the probe, its CO2 falls by some
     * 300 ppm over the window, and the stuck-damper leaf should be denied. It is not — the step
     * response comes back {@code MISSING}, and so the deferral buys the wrong answer at the price of
     * a wait.
     *
     * <p>The cause is in the amplitude itself and predates the ordering.
     * {@link ZoneObserver#recordStepAmplitude} accumulates the measured leg as
     * {@code measured - predictedAtStep} while the predicted leg is
     * {@code predicted - predictedAtStep}, so the measured leg carries the <em>standing residual</em>
     * as a constant offset. Between commands the prediction is pulled towards the readings, which is
     * what makes that baseline legitimate — but the pull is switched off for as long as a window is
     * open or a case is open, so by the time a case confirms the prediction is stale by the whole
     * residual that confirmed it. Measured on this fixture: the offset is 600 ppm, the room answers,
     * and the ratio runs from -7.0 to -0.8 — negative, because a positive measured leg is being
     * divided by a prediction that is falling. A ratio that is a statement about the residual cannot
     * also be a statement about the response, so after any confirmation this signal reads
     * {@code MISSING} whatever the damper did. {@code ZoneObserver.measuredAtStep} is assigned for
     * this purpose and never read, which is where the fix belongs — and it is a change to a published
     * signal, so it needs its own measurement rather than being folded in here.
     *
     * <p>Written as the finding rather than the wish: if this starts reading {@code MATCHED}, the
     * amplitude has been fixed and the ordering question in {@code ProbeOrderExperiment} is worth
     * asking again, because its answer turned on exactly this.
     */
    @Test
    void shouldReportAProbeTheDamperAnsweredAsMissingBecauseTheAmplitudeCarriesTheResidual() {
        MonitorHarness harness = deferredHarness();
        double beforeProbe = harness.monitor().lastMeasuredOf(FAULTED);

        harness.command(FAULTED, 1.0, "supervisor: the probe the monitor asked for");
        assertNotEquals(-1, harness.tickUntil(Duration.ofMinutes(20),
                        () -> !harness.monitor().classificationDeferredOf(FAULTED)),
                "the judged probe never named the case");

        assertTrue(harness.monitor().lastMeasuredOf(FAULTED) < beforeProbe - 100,
                "the fixture needs a room that visibly answered its probe, or there is nothing to "
                        + "mis-read: it went from %.0f to %.0f ppm"
                                .formatted(beforeProbe, harness.monitor().lastMeasuredOf(FAULTED)));
        assertTrue(harness.monitor().observerOf(FAULTED).stepAmplitudeRatio() < 0,
                "the amplitude ratio is negative because the measured leg carries the standing "
                        + "residual while the predicted leg falls; a positive ratio means the "
                        + "baseline has been fixed");
        assertEquals(StepResponse.MISSING, harness.monitor().stepResponseOf(FAULTED),
                "a damper that answered its probe is still read as one that stopped answering — if "
                        + "this now reads MATCHED the amplitude has been fixed and T-35's verdict "
                        + "has to be re-measured, which would be good news rather than a regression");
    }

    @Test
    void shouldNameTheFaultAnywayWhenNoProbeEverArrives() {
        MonitorHarness harness = deferredHarness();

        harness.tickFor(MonitorParameters.defaults().probeInterval().plusMinutes(1));

        assertFalse(harness.monitor().classificationDeferredOf(FAULTED),
                "a probe the supervisor refuses or never sees must not leave a case unnamed for ever");
        assertEquals(StepResponse.NOT_JUDGED, harness.monitor().stepResponseOf(FAULTED));
        assertEquals(FaultClassification.STUCK_DAMPER,
                harness.monitor().caseOf(FAULTED).classification(),
                "past the deadline the tree runs on the signals it has, which is exactly what "
                        + "ON_CONFIRMATION would have said half an hour earlier");
    }

    @Test
    void shouldNotDeferWhenAStepHasAlreadyBeenJudgedAtConfirmation() {
        MonitorHarness harness = settledHarness(ClassifyWhen.AFTER_PROBE);
        harness.physics().stick(FAULTED, Topology.MINIMUM_POSITION.value());
        harness.command(FAULTED, 0.80, "the first ignored command");
        assertNotEquals(-1, harness.tickUntil(BUDGET,
                () -> harness.monitor().stateOf(FAULTED).isLatched()));

        assertEquals(StepResponse.MISSING, harness.monitor().stepResponseOf(FAULTED),
                "the fixture needs a judged step before the confirmation");
        assertFalse(harness.monitor().classificationDeferredOf(FAULTED),
                "there is nothing to wait for when the third signal already exists");
        assertEquals(FaultClassification.STUCK_DAMPER,
                harness.monitor().caseOf(FAULTED).classification());
    }

    @Test
    void shouldStillLeaveALatchedCaseOnlyThroughService() {
        MonitorHarness harness = deferredHarness();
        harness.physics().drift(FAULTED, 0);
        harness.tickFor(Duration.ofHours(1));
        assertTrue(harness.monitor().stateOf(FAULTED).isLatched(),
                "T-18: deferring the name must not make the latch negotiable");

        harness.monitor().serviced(FAULTED);
        harness.tick();

        assertEquals(MonitorState.WARMUP, harness.monitor().stateOf(FAULTED));
        harness.physics().drift(FAULTED, OFFSET_PPM);
        assertNotEquals(-1, harness.tickUntil(Duration.ofMinutes(30),
                        () -> harness.monitor().stateOf(FAULTED).isLatched()),
                "a serviced room must be able to open a second deferred case");
    }

    private List<String> evidence(MonitorHarness harness) {
        return harness.events().states(FAULTED).stream()
                .flatMap(event -> event.evidence().stream())
                .toList();
    }

    private int cyclesToLatch(ClassifyWhen when) {
        MonitorHarness harness = settledHarness(when);
        harness.physics().drift(FAULTED, OFFSET_PPM);
        int cycles = harness.tickUntil(BUDGET,
                () -> harness.monitor().stateOf(FAULTED).isLatched());
        assertNotEquals(-1, cycles, when + " never latched a case");
        return cycles;
    }

    /** A latched case with no step to compare against, which is the only case the reorder sees. */
    private MonitorHarness deferredHarness() {
        MonitorHarness harness = settledHarness(ClassifyWhen.AFTER_PROBE);
        harness.physics().drift(FAULTED, OFFSET_PPM);
        assertNotEquals(-1, harness.tickUntil(BUDGET,
                        () -> harness.monitor().stateOf(FAULTED).isLatched()),
                "the fixture needs a latched case");
        assertEquals(StepResponse.NOT_JUDGED, harness.monitor().stepResponseOf(FAULTED),
                "the fixture needs a confirmation with no judged step, or it tests nothing");
        return harness;
    }

    private MonitorHarness settledHarness(ClassifyWhen when) {
        MonitorHarness harness = MonitorHarness.withClassifyWhen(42, when);
        harness.innerLoopRunning(false);
        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
            harness.physics().settle(zone);
        }
        harness.tickFor(Duration.ofHours(1));
        harness.events().clear();
        return harness;
    }
}
