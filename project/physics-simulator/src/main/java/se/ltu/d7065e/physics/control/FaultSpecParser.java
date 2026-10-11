package se.ltu.d7065e.physics.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import se.ltu.d7065e.physics.entity.FaultKind;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Reads the containerised demo's fault specification, {@code KIND:TARGET:MAGNITUDE:ONSET},
 * several of them separated by {@code ;} or by newlines, with {@code #} comments ignored:
 *
 * <pre>
 * STUCK_DAMPER:A115:0.15:T+1800
 * BLOCKED_FILTER:PLANT:0.4:T+7200
 * SENSOR_DRIFT:A124:150:2026-10-05T09:00:00Z
 * </pre>
 *
 * <p>{@code TARGET} is a room name, or {@code PLANT} for a plant-wide fault. {@code ONSET} is
 * either {@code T+<seconds>} from the start of the run or an ISO-8601 instant. What
 * {@code MAGNITUDE} means is the fault's own business and is documented on
 * {@link InjectedFault}.
 */
public interface FaultSpecParser {

    String PLANT_TARGET = "PLANT";

    static List<InjectedFault> parse(String spec, Instant start) {
        List<InjectedFault> faults = new ArrayList<>();
        for (String line : spec.split("[;\n]")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                faults.add(parseOne(trimmed, start));
            }
        }
        return faults;
    }

    private static InjectedFault parseOne(String spec, Instant start) {
        String[] fields = spec.split(":", 4);
        if (fields.length != 4) {
            throw new IllegalArgumentException(
                    "a fault spec is KIND:TARGET:MAGNITUDE:ONSET, was: " + spec);
        }
        FaultKind kind = FaultKind.valueOf(fields[0].trim().toUpperCase(Locale.ROOT));
        String target = fields[1].trim();
        ZoneId zone = kind.isPlantWide() ? null : ZoneId.of(target);
        if (kind.isPlantWide() && !PLANT_TARGET.equalsIgnoreCase(target)) {
            throw new IllegalArgumentException(
                    "%s is plant-wide, so its target is %s, was: %s".formatted(kind, PLANT_TARGET, target));
        }
        return new InjectedFault(
                kind, zone, Double.parseDouble(fields[2].trim()), onset(fields[3].trim(), start));
    }

    private static Instant onset(String field, Instant start) {
        return field.startsWith("T+")
                ? start.plusSeconds(Long.parseLong(field.substring(2)))
                : Instant.parse(field);
    }
}
