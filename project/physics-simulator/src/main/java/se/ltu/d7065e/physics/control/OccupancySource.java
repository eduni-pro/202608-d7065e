package se.ltu.d7065e.physics.control;

import java.time.Instant;
import java.util.Map;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Where the simulation gets its head counts. Two genuinely different strategies use it
 * (ADR-0005's test for an interface inside the core): {@link OccupancyScheduler}, which draws a
 * plausible day per room type, and a scripted profile, which the evaluation harness and the
 * unit tests use when a scenario needs a known occupant step rather than a plausible one.
 */
public interface OccupancySource {

    Map<ZoneId, Integer> headCounts(Instant at);
}
