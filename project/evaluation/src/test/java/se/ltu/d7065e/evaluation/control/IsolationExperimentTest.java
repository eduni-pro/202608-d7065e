package se.ltu.d7065e.evaluation.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.ltu.d7065e.evaluation.boundary.ResultFiles;
import se.ltu.d7065e.supervision.control.CompensationSolver;
import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * T-34b's two occupancy arms, pinned so that neither verdict can move without a test saying so.
 *
 * <p>Two of these assertions record a result the project would rather not have, and the failure
 * messages say what it would mean if they stopped failing — so that a future run which finally holds
 * R-7 at design occupancy is read as a finding to write up rather than as a broken test to relax.
 *
 * <p>The experiment is run once for the whole class because it is 126 simulated runs; every
 * assertion below reads that one fixed-seed result and none depends on another's outcome.
 */
class IsolationExperimentTest {

    @TempDir
    static Path results;

    private static String printed;
    private static List<Map<String, String>> rows;

    @BeforeAll
    static void runTheExperimentOnce() throws IOException {
        printed = IsolationExperiment.run(new ResultFiles(results));
        List<String> lines = Files.readAllLines(results.resolve("T-34b-room-isolation.csv"));
        List<String> header = cells(lines.getFirst());
        List<Map<String, String>> parsed = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            List<String> values = cells(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < header.size(); column++) {
                row.put(header.get(column), values.get(column));
            }
            parsed.add(row);
        }
        rows = List.copyOf(parsed);
    }

    /** One CSV line, honouring the quoting {@link ResultFiles.Csv} applies to {@code plan_limitation}. */
    private static List<String> cells(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int at = 0; at < line.length(); at++) {
            char character = line.charAt(at);
            if (character == '"') {
                quoted = !quoted;
            } else if (character == ',' && !quoted) {
                values.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(character);
            }
        }
        values.add(cell.toString());
        return values;
    }

    @Test
    void shouldReportBothOccupancyArmsOverTheSameSeedsAndInjections() {
        Map<String, List<Map<String, String>>> byArm =
                rows.stream().collect(Collectors.groupingBy(row -> row.get("occupancy")));

        assertEquals(Set.of("drawn", "design"), byArm.keySet(),
                "R-7's airflow criterion is only exercised at design occupancy, so both arms have "
                        + "to be in the deliverable");
        assertEquals(byArm.get("drawn").size(), byArm.get("design").size(),
                "the two arms are compared row for row, so they must cover the same injections");
        for (List<Map<String, String>> arm : byArm.values()) {
            assertEquals(Set.copyOf(Topology.ZONE_IDS.stream().map(zone -> zone.value()).toList()),
                    arm.stream().map(row -> row.get("injected_zone")).collect(Collectors.toSet()),
                    "every room takes its turn as the injected room in both arms");
        }
    }

    @Test
    void shouldHoldR7OnTheDrawnWeekday() {
        List<Map<String, String>> drawn = arm("drawn");

        assertTrue(drawn.stream().allMatch(row -> "pass".equals(row.get("r7_verdict"))),
                "the drawn-weekday arm is the published R-7 pass and it has moved");
        assertEquals(0, drawn.stream().mapToInt(row -> Integer.parseInt(row.get("fault_events")))
                .sum(), "a room with no injected fault was accused on the drawn weekday");
    }

    /**
     * The result the design-occupancy arm was added to find. It is asserted rather than described
     * because it is the only place R-7's airflow clause is testable at all: the drawn weekday leaves
     * the trim at exactly 1.00, which commands no healthy damper and costs no healthy room anything.
     */
    @Test
    void shouldBreachTheHealthyAirflowBandAtDesignOccupancy() {
        List<Map<String, String>> breaches = arm("design").stream()
                .filter(row -> "false".equals(row.get("airflow_within_band"))).toList();

        assertFalse(breaches.isEmpty(),
                "R-7's airflow criterion now holds at design occupancy. That is a finding to "
                        + "measure and write up, not a test to relax: check what changed in the "
                        + "solver's reference plant before deleting this assertion");
        assertTrue(breaches.stream().allMatch(row ->
                        Double.parseDouble(row.get("split_healthy_flow_change")) > 0),
                "the breach has changed direction: it was an over-supply, which costs fan energy, "
                        + "and a starved healthy room costs comfort instead");
        assertTrue(breaches.stream().allMatch(row -> "false".equals(row.get("observed_latched"))),
                "a breach was counted in a room the monitor had itself latched, where the solver "
                        + "excludes the room from the healthy set and the claim is vacuous");
        assertTrue(printed.contains("the airflow clause FAILS at design"),
                "the printed verdict no longer states which way R-7's airflow clause went");
    }

    /**
     * Why the plan's own figure is reported but never believed: the solver caps the fan at the speed
     * that makes it exactly the tolerance, so the column cannot falsify the requirement it is quoted
     * against, whatever the regime.
     */
    @Test
    void shouldNeverSeeThePlansOwnClaimExceedTheToleranceItIsBoundedBy() {
        assertTrue(rows.stream().allMatch(row ->
                        Double.parseDouble(row.get("plan_worst_healthy_deviation"))
                                <= CompensationSolver.HEALTHY_FLOW_TOLERANCE + 1e-9),
                "the supervisor claimed to have moved a healthy room's airflow by more than its "
                        + "own tolerance, which the solver is written to make impossible");
    }

    private static List<Map<String, String>> arm(String occupancy) {
        return rows.stream().filter(row -> occupancy.equals(row.get("occupancy"))).toList();
    }
}
