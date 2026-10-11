package se.ltu.d7065e.ventilation.entity;

import java.util.List;
import java.util.Optional;

/**
 * The six level-0 rooms on corridor A120 and the air handler that serves them, as decided in
 * ADR-0008 (option B: one shared air-handling unit, one damper per room) — and, when
 * {@link #ZONE_COUNT} asks for one, a larger zone set built out of them for R-6.
 *
 * <p>Volumes are floor area times an assumed 3 m ceiling — the BuildSim floorplan carries no
 * height. Damper authority is the room's design flow in l/s from the Swedish guideline
 * (7 l/s per person plus 0.35 l/s per m²), so the flow split
 * {@code Q_i = Q_fan * a_i / sum(a_j)} delivers each room its design flow when every damper
 * is open and the fan runs at nominal speed. Every number here is invented rather than
 * measured, which the report's critique owns.
 */
public interface Topology {

    AhuId AIR_HANDLER = AhuId.of("AHU-A120");
    String AIR_HANDLER_ROOM = "A120";
    String LEVEL = "level0";

    /** Sum of the six rooms' design flows, l/s: the simultaneous design condition. */
    double DESIGN_FLOW_TOTAL_LITRES_PER_SECOND = 411.4;

    /**
     * The air handler's nominal flow, sized so that {@link #NORMAL_FAN_SPEED} delivers the whole
     * simultaneous design condition: 411.4 l/s / 0.7, rounded.
     *
     * <p>Sizing it at the design total instead — which an earlier revision did — meant the plant
     * could only meet that condition at full speed, leaving nothing for the compensation lever.
     * Throttling to 70 % for headroom then starved it: the evaluation measured that with nothing
     * broken at all, no room could be held below 1000 ppm at design occupancy and the monitor
     * confirmed a fault in all six. The headroom has to come from specifying a larger plant, not
     * from running a correctly sized one slowly, which is also how an air handler is actually
     * specified — with margin over the simultaneous peak.
     */
    AirflowRate NOMINAL_FLOW = AirflowRate.ofCubicMetresPerSecond(0.59);

    /** 0.59 m3/s against 400 Pa at 0.6 fan efficiency, rounded. */
    double NOMINAL_POWER_WATTS = 400.0;

    /**
     * The speed the air handler runs at while nothing is wrong.
     *
     * <p>Deliberately below {@link FanSpeed#MAXIMUM}, because raising fan speed is the
     * supervisor's compensation lever: starting at nominal would leave it no headroom and R-2
     * would rest on the damper trims alone. The design flow assumes every room at its design
     * occupancy at once, which the occupancy schedules make rare, so 70 % carries normal
     * operation and the remaining 43 % is what compensation spends. That the plant has spare
     * capacity at all is an assumption, and the report's critique owns it.
     */
    FanSpeed NORMAL_FAN_SPEED = FanSpeed.of(0.7);

    /** Ventilation never shuts entirely; this is the position a "stuck shut" damper sticks at. */
    DamperPosition MINIMUM_POSITION = DamperPosition.of(0.15);

    Ppm OUTDOOR = Ppm.of(420);
    Ppm COMFORT_LIMIT = Ppm.of(1000);

    /** CO2 generation per person, m3/s. */
    double GENERATION_PER_PERSON = 5e-6;

    /**
     * The detection constants, measured together in the T-04 spike and therefore kept together:
     * the CUSUM slack and limit are tuned to this noise level, so moving one without the others
     * invalidates the spike's result (16.7 sigma separation, 0 of 20 false alarms). They lived in
     * three separate modules as private literals and had already begun to drift.
     */
    double SENSOR_NOISE_SIGMA_PPM = 30.0;

    /** CUSUM slack k, one sigma: the per-sample allowance before the statistic accumulates. */
    double CUSUM_SLACK_PPM = 30.0;

    /** CUSUM limit h, five sigma. */
    double CUSUM_LIMIT_PPM = 150.0;

    /**
     * Measurement noise on the air handler's power reading, watts.
     *
     * <p>Fan power is exactly constant while nothing is wrong, and the sensor judges staleness
     * from whether a value changed, so a noiseless power reading is marked DUPLICATE on its second
     * sample and STALE on its fifth — which silently made the monitor's only cross-room signal
     * unusable, since the observer may consume only GOOD readings. Real power meters are noisy, so
     * modelling that is both the honest fix and the one that keeps the signal alive.
     *
     * <p>Kept small relative to the classifier's 5 % "flat power" tolerance: at the normal
     * operating point of about 137 W ({@code NOMINAL_POWER_WATTS} at {@code NORMAL_FAN_SPEED}
     * cubed) this is 1.1 %, so it cannot be mistaken for the power excess that marks a blocking
     * filter.
     */
    double FAN_POWER_NOISE_SIGMA_WATTS = 1.5;

    ZoneId A125A = ZoneId.of("A125A");
    ZoneId A125 = ZoneId.of("A125");
    ZoneId A116A = ZoneId.of("A116A");
    ZoneId A115 = ZoneId.of("A115");
    ZoneId A124 = ZoneId.of("A124");
    ZoneId A145 = ZoneId.of("A145");

    /**
     * The environment variable that sets how many zones this process runs, defaulting to the six
     * real rooms.
     *
     * <p>It is read here rather than injected because every one of the seven project-authored
     * processes has to agree on the zone set — the sensor publishes per zone, the controller
     * commands per zone and the monitor holds a model per zone, so a process running a different
     * count is a process computing residuals against a plant nobody else is simulating. That is the
     * same reason {@code REAL_EPOCH} and {@code TIME_SCALE} are one environment variable given
     * identically to all seven rather than a per-service setting, and {@code docker-compose.yml}
     * carries this one in the same way. Reading it in the entity layer is the price of that
     * agreement: the alternative is threading a zone set through seven composition roots, which
     * turns "the services agree" from a guarantee into a convention.
     *
     * <p>Unset, blank, or {@code 6} is the real corridor and nothing about this plant changes.
     */
    String ZONE_COUNT = "ZONE_COUNT";

    /** The six rooms ADR-0008 actually decided on, which is what the default is. */
    List<ZoneId> REAL_ZONE_IDS = List.of(A125A, A125, A116A, A115, A124, A145);

    /**
     * Further level-0 rooms of the A building a synthetic zone may be placed in, in the order they
     * are taken.
     *
     * <p>They exist because BuildSim will not host equipment in a room its floorplan does not have:
     * {@code POST /api/equipment/bulk} rejects the whole batch with "room does not exist on level"
     * for an invented id, so a zone set with generated ids registers nothing and the deployed system
     * cannot run it at all. These 52 are real rooms in {@code level0/floorplan_data.json}, of the
     * same order of size as the six (11 to 148 m²), excluding A120 because that is the air handler's
     * own room.
     *
     * <p>A synthetic zone therefore borrows a real room's <em>name and location</em> so BuildSim can
     * carry its sensor and its damper, while its <em>size</em> is a cycled copy of one of the six —
     * so it is representative in volume and time constant rather than a claim about that room. With
     * 6 + 52 = 58 the deployed ceiling is far below the thousands T-28 sweeps, so R-6's breaking
     * point stays a harness measurement; what this buys is a deployed system that can be scaled and
     * watched at all.
     */
    List<String> SPARE_LEVEL0_ROOMS = List.of(
            "A105", "A113", "A113C", "A114", "A119", "A122", "A126", "A129", "A132", "A133",
            "A134", "A135", "A136", "A137", "A138", "A139", "A140", "A142", "A143", "A144",
            "A146", "A147", "A148", "A149", "A150", "A151", "A152", "A153", "A154", "A160",
            "A162", "A163", "A164", "A165", "A166", "A167", "A168", "A169", "A171A", "A171B",
            "A171C", "A172", "A173", "A174", "A175", "A176", "A177", "A178", "A179", "A180",
            "A182", "A183");

    /** The largest zone count BuildSim can host, which is the real rooms plus the spare ones. */
    int DEPLOYABLE_ZONE_LIMIT = 6 + 52;

    /**
     * {@link #ZONE_COUNT} for this process, resolved once at class initialisation so that the zone
     * set cannot change under a running container.
     */
    int CONFIGURED_ZONE_COUNT = zoneCountFromEnvironment();

    List<ZoneId> ZONE_IDS = zones().stream().map(VentilationZone::id).toList();

    /**
     * The zones this process runs in a healthy initial state, dampers at their design-flow minimum:
     * the six real rooms, or a synthetic set of {@link #CONFIGURED_ZONE_COUNT} of them.
     */
    static List<VentilationZone> zones() {
        return CONFIGURED_ZONE_COUNT == REAL_ZONE_IDS.size()
                ? realZones()
                : syntheticZones(CONFIGURED_ZONE_COUNT);
    }

    /** The six rooms of corridor A120, which every zone set here is built out of. */
    static List<VentilationZone> realZones() {
        return List.of(
                zone(A125A, 18.0, 13.3),
                zone(A125, 29.8, 24.4),
                zone(A116A, 33.0, 39.6),
                zone(A115, 44.0, 85.4),
                zone(A124, 76.5, 82.8),
                zone(A145, 74.0, 165.9));
    }

    /**
     * The air handler serving this process's zones. At the default it is the unit ADR-0008 sized,
     * to the last decimal; above six it is resized by {@link #airHandlerFor} so each zone still
     * gets its design flow.
     */
    static AirHandlingUnit airHandler() {
        return CONFIGURED_ZONE_COUNT == REAL_ZONE_IDS.size()
                ? new AirHandlingUnit(
                        AIR_HANDLER, NOMINAL_FLOW, NOMINAL_POWER_WATTS, NORMAL_FAN_SPEED, ZONE_IDS)
                : airHandlerFor(zones());
    }

    static VentilationZone zone(ZoneId id) {
        return byId(id).orElseThrow(() -> new IllegalArgumentException(
                "not one of the " + ZONE_IDS.size() + " configured zones: " + id));
    }

    /**
     * {@link #ZONE_COUNT}, or the six real rooms when the environment does not set it. A count
     * above {@link #DEPLOYABLE_ZONE_LIMIT} is allowed and warned about rather than refused, because
     * the evaluation harness sweeps thousands of zones in one JVM with no BuildSim at all.
     */
    static int zoneCountFromEnvironment() {
        String value = System.getenv(ZONE_COUNT);
        if (value == null || value.isBlank()) {
            return REAL_ZONE_IDS.size();
        }
        int count;
        try {
            count = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    ZONE_COUNT + " must be a whole number of zones, was: " + value, e);
        }
        if (count < 1) {
            throw new IllegalStateException(
                    ZONE_COUNT + " must be at least one zone, was: " + count);
        }
        if (count > DEPLOYABLE_ZONE_LIMIT) {
            System.err.println(ZONE_COUNT + "=" + count + " exceeds the " + DEPLOYABLE_ZONE_LIMIT
                    + " rooms BuildSim's level-0 floorplan has, so zones beyond that take generated"
                    + " ids and BuildSim will refuse to host them; this zone set is for the"
                    + " evaluation harness, not for the containers");
        }
        return count;
    }

    static Optional<VentilationZone> byId(ZoneId id) {
        return zones().stream().filter(z -> z.id().equals(id)).findFirst();
    }

    /** BuildSim's floor-qualified room key, {@code level0/A115}. */
    static String buildSimKey(ZoneId zone) {
        return LEVEL + "/" + zone.value();
    }

    /** BuildSim sensor id for a room's CO2 sensor, {@code A115-co2}. */
    static String co2SensorId(ZoneId zone) {
        return zone.value() + "-co2";
    }

    /** BuildSim actuator id for a room's damper, {@code A115-damper}. */
    static String damperActuatorId(ZoneId zone) {
        return zone.value() + "-damper";
    }

    static String fanPowerSensorId() {
        return AIR_HANDLER.value() + "-fan-power";
    }

    static String fanSpeedActuatorId() {
        return AIR_HANDLER.value() + "-fan-speed";
    }

    /**
     * A zone set of arbitrary size for R-6, which asks for the zone count at which the monitor
     * misses its 60 s interval.
     *
     * <p>It exists because the six real rooms are compile-time constants, so the scaling experiment
     * could not otherwise express the counts it has to sweep. Beyond the sixth, zones repeat the six
     * real rooms' geometry cyclically, so added zones are representative in size and time constant
     * rather than identical.
     *
     * <p>{@link #ZONE_COUNT} now points {@link #zones()} here too, so this is no longer
     * harness-only, and the ids are what that cost. A synthetic zone takes the name of a real
     * further level-0 room from {@link #SPARE_LEVEL0_ROOMS} while it lasts, because BuildSim refuses
     * to host equipment in a room its floorplan does not have and a generated id therefore registers
     * nothing. Past {@link #DEPLOYABLE_ZONE_LIMIT} the ids are generated and such a set is for the
     * harness alone — where a scaling result is a statement about how many zones the monitor can
     * process, not a claim about a building that has them.
     *
     * @param count how many zones to produce, at least one
     */
    static List<VentilationZone> syntheticZones(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("a zone set needs at least one zone, asked: " + count);
        }
        List<VentilationZone> real = realZones();
        List<VentilationZone> scaled = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            VentilationZone template = real.get(i % real.size());
            scaled.add(new VentilationZone(syntheticZoneId(i, template, real.size()),
                    template.volumeCubicMetres(),
                    template.floorAreaSquareMetres(), template.ahuId(), template.damperAuthority(),
                    template.minimumPosition(), template.mode(), template.commandedPosition()));
        }
        return List.copyOf(scaled);
    }

    /**
     * The real room whose geometry — and therefore whose room type — a configured zone is a copy
     * of. For one of the six it is the room itself.
     *
     * <p>{@link #syntheticZones} gives position {@code i} the geometry of real room {@code i % 6},
     * so the mapping is positional and lives here next to the factory that creates it rather than
     * being re-derived by every caller that needs it. The occupancy schedules need it: a room's head
     * count follows its type, and a synthetic zone that is a copy of the seminar room should be
     * occupied like one.
     */
    static ZoneId templateOf(ZoneId zone) {
        int index = ZONE_IDS.indexOf(zone);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "not one of the " + ZONE_IDS.size() + " configured zones: " + zone);
        }
        return REAL_ZONE_IDS.get(index % REAL_ZONE_IDS.size());
    }

    /**
     * The real room first, then a spare real room, then a generated id. The suffix counts from the
     * template rather than from the spare rooms so that two generated ids cannot collide: beyond the
     * spares, {@code (i % 6, i / 6)} is unique for every {@code i}, and no spare room's name carries
     * an {@code S} suffix.
     */
    private static ZoneId syntheticZoneId(int index, VentilationZone template, int realCount) {
        if (index < realCount) {
            return template.id();
        }
        int spare = index - realCount;
        return spare < SPARE_LEVEL0_ROOMS.size()
                ? ZoneId.of(SPARE_LEVEL0_ROOMS.get(spare))
                : ZoneId.of(template.id().value() + "S" + (index / realCount));
    }

    /** The air handler serving a synthetic zone set, sized so each zone keeps its design flow. */
    static AirHandlingUnit airHandlerFor(List<VentilationZone> zoneSet) {
        double totalAuthority = zoneSet.stream().mapToDouble(VentilationZone::damperAuthority).sum();
        return new AirHandlingUnit(AIR_HANDLER,
                AirflowRate.ofLitresPerSecond(
                        totalAuthority * NOMINAL_FLOW.litresPerSecond()
                                / DESIGN_FLOW_TOTAL_LITRES_PER_SECOND),
                NOMINAL_POWER_WATTS * totalAuthority / DESIGN_FLOW_TOTAL_LITRES_PER_SECOND,
                NORMAL_FAN_SPEED,
                zoneSet.stream().map(VentilationZone::id).toList());
    }

    private static VentilationZone zone(ZoneId id, double area, double designFlowLitresPerSecond) {
        return new VentilationZone(id, area * 3.0, area, AIR_HANDLER,
                designFlowLitresPerSecond, MINIMUM_POSITION, ControlMode.NORMAL, MINIMUM_POSITION);
    }
}
