package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.AirflowRate;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-15 end to end: the two signatures that need more than one room's own residual — the blocked
 * filter's common mode plus fan power, and the drifting sensor's overnight floor. The decision tree
 * itself is tested in {@link FaultClassifierTest}; these tests check that the signals the monitor
 * actually gathers from six rooms and one air handler land where the tree expects them.
 */
class CrossRoomSignatureTest {

    /**
     * The blockage this test injects, and the reason it is 40 % rather than the 35 % an earlier
     * revision used: 35 % no longer starves the rooms enough to raise a residual on this seed.
     * That is not the test drifting, it is the plant being resized — the air handler now carries
     * the whole simultaneous design condition at 70 % fan speed instead of at 100 %, so the same
     * fractional blockage leaves the rooms more air than they need.
     * {@link #shouldMeasureWhatTheResizedPlantCostsBlockedFilterDetection()} measures the cost
     * exactly and is the test that owns the number; this one only has to inject a blockage the
     * detector can still see, and the smallest such step on this seed is 40 %.
     */
    private static final double BLOCKAGE = 0.40;

    /** The blockages the sensitivity sweep walks, and the step it reports its answer to. */
    private static final double[] BLOCKAGE_SWEEP =
            {0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50};

    private static final long[] SWEEP_SEEDS = {42, 1, 2};

    /** The plant as specified before T-06 resized it: the design total, not the design total / 0.7. */
    private static final AirflowRate FORMER_NOMINAL_FLOW = AirflowRate.ofCubicMetresPerSecond(0.42);
    private static final double FORMER_NOMINAL_POWER_WATTS = 300.0;

    /**
     * A finding, not a wish: under ADR-0008's proportional split a blocked filter does <em>not</em>
     * produce a common-mode positive residual that damper commands can reveal, so the monitor
     * reaches {@code UNCLASSIFIED} and the work order has to carry the fan-power excess as its
     * evidence instead. Two reasons, both structural:
     *
     * <ul>
     * <li><b>The dampers cannot give every room more air.</b> {@code Q_fan} does not depend on
     *     them, so the six openings only redistribute a fixed total. Opening all six to the same
     *     position leaves every share exactly where it was and produces no step response at all;
     *     opening some necessarily closes others, so the rooms that gain air show a positive
     *     residual and the rooms that lose air show a negative one. There is no command the
     *     monitor can watch that pushes all six residuals the same way. Only fan speed does that,
     *     and the supervisor owns it.
     * <li><b>The quasi-steady generation estimate absorbs the blockage.</b> {@code G_est} is
     *     learned as {@code Q_believed * (C - C_out)} from a room whose CO2 is already inflated by
     *     the missing air, so it comes out too high by exactly {@code 1/(1-beta)} — and
     *     {@code Q_believed} is too high by the same factor. The two errors cancel in the
     *     predicted steady state, which lands on the measurement. All that survives is the time
     *     constant: the real room responds {@code (1-beta)} times as fast as the model says, so
     *     the blockage shows up as a response that is late rather than one that is wrong.
     * </ul>
     *
     * <p>This is the risk register's "two fault classes collapse" arriving, and it is reported
     * rather than rescued: the plan forbids inventing a fourth signal to save a three-way
     * taxonomy. What the monitor does get right is the thing that matters operationally — the
     * confirmed case names the AHU power excess in its evidence, so the work order sends a
     * technician to the plant and not to a damper.
     */
    @Test
    void shouldNameTheFanPowerExcessEvenWhenABlockedFilterCannotBeClassified() {
        MonitorHarness harness = settled(42);

        harness.physics().blockFilter(BLOCKAGE);
        harness.tickFor(Duration.ofHours(2));
        double[] positions = {0.55, 0.65, 0.75, 0.85, 0.95, 1.00};
        List<ZoneId> zones = Topology.ZONE_IDS;
        for (int index = 0; index < zones.size(); index++) {
            harness.command(zones.get(index), positions[index], "dcv: open up on a blocked filter");
        }

        int cycles = harness.tickUntil(Duration.ofHours(1), () -> Topology.ZONE_IDS.stream()
                .anyMatch(zone -> harness.monitor().stateOf(zone).isLatched()));
        assertNotEquals(-1, cycles,
                "a filter blocking %.0f %% never confirmed anywhere".formatted(BLOCKAGE * 100));

        ZoneId confirmed = Topology.ZONE_IDS.stream()
                .filter(zone -> harness.monitor().stateOf(zone).isLatched())
                .findFirst()
                .orElseThrow();
        List<String> evidence = harness.monitor().caseOf(confirmed).evidence().stream()
                .map(e -> e.observation())
                .toList();
        String powerLine = "AHU power %.0f%%".formatted((1 + BLOCKAGE) * 100);
        assertTrue(evidence.stream().anyMatch(line -> line.contains(powerLine)),
                "the work order did not name the power excess that points at the plant: " + evidence);
        assertTrue(evidence.stream().anyMatch(line -> line.contains("not common mode")),
                "the absence of a common mode was not recorded, so the gap cannot be explained");
        assertEquals(FaultClassification.UNCLASSIFIED,
                harness.monitor().caseOf(confirmed).classification(),
                "if this now classifies, the residual gained a plant-level common mode and this "
                        + "test's finding is out of date — check why before relaxing it");
    }

    @Test
    void shouldClassifyADriftingSensorOnceTheOvernightFloorIsKnown() {
        MonitorHarness harness = MonitorHarness.standard(42);
        harness.innerLoopRunning(false);
        ZoneId drifting = Topology.A115;

        harness.physics().drift(drifting, 350);
        // A full day with no commands, so that nothing can be confirmed before the empty-room
        // reading exists. Occupied by day and empty by night: it is the night that tells the
        // monitor the sensor's zero has walked, with no schedule and no head count.
        for (int hour = 0; hour < 26; hour++) {
            boolean occupied = hour % 24 >= 8 && hour % 24 < 17;
            for (ZoneId zone : Topology.ZONE_IDS) {
                if (occupied) {
                    harness.physics().occupyToDesign(zone);
                } else {
                    harness.physics().occupy(zone, 0);
                }
            }
            harness.tickFor(Duration.ofHours(1));
        }
        for (ZoneId zone : Topology.ZONE_IDS) {
            assertTrue(!harness.monitor().stateOf(zone).isLatched(),
                    "a drifting sensor confirmed something before any command was judged");
        }

        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
        }
        harness.tickFor(Duration.ofHours(2));
        harness.command(drifting, 0.90, "dcv: open up");

        int cycles = harness.tickUntil(Duration.ofMinutes(20),
                () -> harness.monitor().stateOf(drifting) == MonitorState.CONFIRMED);
        assertNotEquals(-1, cycles, "the drifting sensor's step response was never judged a fault");
        assertEquals(FaultClassification.SENSOR_DRIFT,
                harness.monitor().caseOf(drifting).classification(),
                "drift lost to another signature: " + harness.monitor().caseOf(drifting).evidence());
    }

    /**
     * What a correctly sized plant costs blocked-filter detection, measured rather than asserted.
     *
     * <p>The trade is structural and worth stating plainly: a blocked filter is only visible here
     * because it starves rooms, and a plant with margin does not starve rooms until the blockage
     * is large. Sizing the air handler so {@link Topology#NORMAL_FAN_SPEED} delivers the whole
     * simultaneous design condition fixed a worse problem — the evaluation had measured that with
     * nothing broken at all, no room could be held below 1000 ppm at design occupancy and the
     * monitor confirmed a fault in all six — but it bought that with margin, and margin masks
     * faults. This test is where the project pays for the fix in the currency of R-1-style
     * sensitivity instead of hiding it.
     *
     * <p>Both plants are swept on the same three seeds through the same scenario, and the figure
     * reported is the smallest blockage any of the three seeds confirms anywhere, to the sweep's
     * 5 % step. The bound asserted is the one measured.
     */
    @Test
    void shouldMeasureWhatTheResizedPlantCostsBlockedFilterDetection() {
        AirHandlingUnit resized = Topology.airHandler();
        AirHandlingUnit former = new AirHandlingUnit(Topology.AIR_HANDLER, FORMER_NOMINAL_FLOW,
                FORMER_NOMINAL_POWER_WATTS, Topology.NORMAL_FAN_SPEED, Topology.ZONE_IDS);

        double smallestResized = smallestDetectableBlockage(resized);
        double smallestFormer = smallestDetectableBlockage(former);

        System.out.println(("T-06 blocked-filter detection sensitivity, seeds %s, smallest blockage "
                + "confirmed anywhere within an hour of the commands: plant at %.2f m3/s (as "
                + "specified before the resizing) = %.0f %%, plant at %.2f m3/s (as specified now) "
                + "= %.0f %%. The resizing cost %.0f percentage points of blockage sensitivity, "
                + "because %.0f %% of %.2f m3/s is still more air than the rooms need.")
                .formatted(java.util.Arrays.toString(SWEEP_SEEDS),
                        former.nominalFlow().cubicMetresPerSecond(), smallestFormer * 100,
                        resized.nominalFlow().cubicMetresPerSecond(), smallestResized * 100,
                        (smallestResized - smallestFormer) * 100,
                        (1 - smallestResized) * 100,
                        resized.nominalFlow().cubicMetresPerSecond()));

        assertEquals(0.30, smallestFormer, 1e-9,
                "the pre-resizing figure this project reports moved; it is a fixed historical "
                        + "measurement and nothing in this module should be able to change it");
        assertEquals(0.35, smallestResized, 1e-9,
                "the resized plant's blocked-filter sensitivity moved. If it improved, say so in "
                        + "the report; if it got worse, R-1's blocked-filter story needs re-writing "
                        + "before this bound is relaxed");
        assertTrue(smallestResized > smallestFormer,
                "margin no longer masks a blocked filter, which contradicts the whole reason the "
                        + "plant was resized — re-measure before believing it");
    }

    /**
     * The smallest swept blockage that any seed confirms somewhere, or
     * {@link Double#POSITIVE_INFINITY} when none does. "Anywhere" rather than "in a named room"
     * because which room gets there first is decided by the noise, as the sweep's own output
     * shows.
     */
    private double smallestDetectableBlockage(AirHandlingUnit ahu) {
        for (double blockage : BLOCKAGE_SWEEP) {
            for (long seed : SWEEP_SEEDS) {
                if (confirmsSomewhere(seed, ahu, blockage)) {
                    return blockage;
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private boolean confirmsSomewhere(long seed, AirHandlingUnit ahu, double blockage) {
        MonitorHarness harness = MonitorHarness.withPlant(seed, ahu);
        harness.innerLoopRunning(false);
        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
            harness.physics().settle(zone);
        }
        harness.tickFor(Duration.ofHours(1));
        harness.physics().blockFilter(blockage);
        harness.tickFor(Duration.ofHours(2));
        double[] positions = {0.55, 0.65, 0.75, 0.85, 0.95, 1.00};
        for (int index = 0; index < Topology.ZONE_IDS.size(); index++) {
            harness.command(Topology.ZONE_IDS.get(index), positions[index],
                    "dcv: open up on a blocked filter");
        }
        return harness.tickUntil(Duration.ofHours(1), () -> Topology.ZONE_IDS.stream()
                .anyMatch(zone -> harness.monitor().stateOf(zone).isLatched())) != -1;
    }

    private MonitorHarness settled(long seed) {
        MonitorHarness harness = MonitorHarness.standard(seed);
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
