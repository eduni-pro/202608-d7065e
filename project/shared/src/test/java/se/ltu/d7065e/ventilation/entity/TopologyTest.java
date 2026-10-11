package se.ltu.d7065e.ventilation.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The frozen room topology of ADR-0008 (plan §3 table). Every container and every test reads
 * its geometry from here, so a silent edit to one number would move the CO2 balance, the flow
 * split and the R-7 isolation band at once. These assertions are the numbers' lock.
 */
class TopologyTest {

    /** ADR-0008 §Parameters, in {@link Topology#ZONE_IDS} order. */
    private static final double[] AREAS = {18.0, 29.8, 33.0, 44.0, 76.5, 74.0};
    private static final double[] VOLUMES = {54.0, 89.4, 99.0, 132.0, 229.5, 222.0};
    private static final double[] DESIGN_FLOWS = {13.3, 24.4, 39.6, 85.4, 82.8, 165.9};

    @Test
    @DisplayName("ADR-0008: exactly six zones on corridor A120")
    void shouldDeclareExactlySixZones() {
        assertEquals(6, Topology.ZONE_IDS.size());
        assertEquals(6, Topology.zones().size());
        assertEquals(List.of("A125A", "A125", "A116A", "A115", "A124", "A145"),
                Topology.ZONE_IDS.stream().map(ZoneId::value).toList());
    }

    @Test
    @DisplayName("ADR-0008 §Parameters: the six floor areas")
    void shouldCarryTheAdr0008FloorAreas() {
        List<VentilationZone> zones = Topology.zones();
        for (int i = 0; i < AREAS.length; i++) {
            assertEquals(AREAS[i], zones.get(i).floorAreaSquareMetres(), 1e-9,
                    "area of " + zones.get(i).id());
        }
    }

    @Test
    @DisplayName("ADR-0008 §Parameters: the six volumes")
    void shouldCarryTheAdr0008Volumes() {
        List<VentilationZone> zones = Topology.zones();
        for (int i = 0; i < VOLUMES.length; i++) {
            assertEquals(VOLUMES[i], zones.get(i).volumeCubicMetres(), 1e-9,
                    "volume of " + zones.get(i).id());
        }
    }

    /** Plan §3: BuildSim's floorplan carries no height, so volume is area times a 3 m ceiling. */
    @Test
    void shouldDeriveEveryVolumeFromAreaTimesThreeMetres() {
        for (VentilationZone zone : Topology.zones()) {
            assertEquals(zone.floorAreaSquareMetres() * 3.0, zone.volumeCubicMetres(),
                    "volume of " + zone.id() + " must be area x 3 m");
        }
    }

    @Test
    @DisplayName("ADR-0008 §Parameters: damper authority is the room's design flow in l/s")
    void shouldUseDesignFlowsAsDamperAuthorities() {
        List<VentilationZone> zones = Topology.zones();
        for (int i = 0; i < DESIGN_FLOWS.length; i++) {
            assertEquals(DESIGN_FLOWS[i], zones.get(i).damperAuthority(), 1e-9,
                    "authority of " + zones.get(i).id());
        }
    }

    @Test
    @DisplayName("plan §3: the six design flows sum to 411.4 l/s")
    void shouldSumDesignFlowsTo411Point4() {
        double sum = Topology.zones().stream()
                .mapToDouble(VentilationZone::damperAuthority).sum();
        assertEquals(411.4, sum, 1e-9);
    }

    /**
     * The property, not the number: the plant must meet the whole simultaneous design condition at
     * its NORMAL operating speed, leaving the rest for compensation. Sizing it at the design total
     * instead means it only meets that condition at full speed, and the evaluation measured what
     * that costs — with nothing broken, no room could be held below 1000 ppm at design occupancy
     * and the monitor confirmed a fault in all six.
     */
    @Test
    @DisplayName("ADR-0008: the plant covers the design condition at its normal speed, with"
            + " headroom left for compensation")
    void shouldSizeTheFanToCoverDesignFlowAtNormalSpeed() {
        double atNormalSpeed =
                Topology.NOMINAL_FLOW.litresPerSecond() * Topology.NORMAL_FAN_SPEED.value();

        assertTrue(atNormalSpeed >= Topology.DESIGN_FLOW_TOTAL_LITRES_PER_SECOND,
                "at the normal speed the plant delivered %.1f l/s against a design total of %.1f"
                        .formatted(atNormalSpeed, Topology.DESIGN_FLOW_TOTAL_LITRES_PER_SECOND));
        assertTrue(Topology.NOMINAL_FLOW.litresPerSecond()
                        > Topology.DESIGN_FLOW_TOTAL_LITRES_PER_SECOND,
                "and full speed must still be more than the design total, or there is no lever");
        assertEquals(Topology.DESIGN_FLOW_TOTAL_LITRES_PER_SECOND,
                Topology.zones().stream().mapToDouble(VentilationZone::damperAuthority).sum(), 1e-9,
                "the design total must stay the sum of the six authorities it is derived from");
    }

    @Test
    @DisplayName("ADR-0008: minimumDamperPosition is 0.15, the occupied-hours floor (R-2 bound)")
    void shouldFixTheMinimumDamperPositionAtZeroPointOneFive() {
        assertEquals(0.15, Topology.MINIMUM_POSITION.value(), 1e-12);
        for (VentilationZone zone : Topology.zones()) {
            assertEquals(Topology.MINIMUM_POSITION, zone.minimumPosition());
        }
    }

    @Test
    @DisplayName("plan §3: every zone starts healthy, at its minimum, on the one air handler")
    void shouldStartEveryZoneHealthyAtItsMinimum() {
        for (VentilationZone zone : Topology.zones()) {
            assertEquals(ControlMode.NORMAL, zone.mode());
            assertEquals(Topology.MINIMUM_POSITION, zone.commandedPosition());
            assertEquals(Topology.AIR_HANDLER, zone.ahuId());
        }
    }

    @Test
    @DisplayName("plan §3: the air handler is AHU-A120 serving all six zones")
    void shouldDescribeTheOneAirHandler() {
        AirHandlingUnit ahu = Topology.airHandler();
        assertEquals("AHU-A120", ahu.id().value());
        assertEquals(Topology.NOMINAL_POWER_WATTS, ahu.nominalPowerWatts(), 1e-12);
        assertEquals(Topology.NORMAL_FAN_SPEED, ahu.commandedSpeed());
        assertEquals(Topology.ZONE_IDS, ahu.servedZones());
    }

    /**
     * T-16 and R-2: raising fan speed is the supervisor's compensation lever, so the air handler
     * must not already be at maximum while nothing is wrong. Without this headroom compensation
     * would rest on the damper trims alone.
     */
    @Test
    @DisplayName("ADR-0008: normal fan speed leaves headroom for compensation")
    void shouldLeaveFanSpeedHeadroomForCompensation() {
        assertTrue(Topology.NORMAL_FAN_SPEED.value() < FanSpeed.MAXIMUM,
                "normal speed must be below maximum or compensation has no lever");
        assertTrue(Topology.NORMAL_FAN_SPEED.value() >= FanSpeed.MINIMUM);
        assertTrue(Topology.airHandler().deliveredFlow(0).cubicMetresPerSecond()
                        < Topology.airHandler().atSpeed(FanSpeed.NOMINAL)
                                .deliveredFlow(0).cubicMetresPerSecond(),
                "raising the fan to nominal must actually deliver more air");
    }

    /** ADR-0006: the BuildSim key is the adapter's business and is composed, not carried. */
    @Test
    void shouldComposeBuildSimKeyFromFloorAndRoom() {
        assertEquals("level0/A115", Topology.buildSimKey(Topology.A115));
        assertEquals("level0/A125A", Topology.buildSimKey(Topology.A125A));
        for (ZoneId zone : Topology.ZONE_IDS) {
            assertEquals("level0/" + zone.value(), Topology.buildSimKey(zone));
        }
    }

    @Test
    @DisplayName("plan §3: one co2 sensor and one damper actuator per room")
    void shouldNameRoomLevelSensorsAndActuators() {
        assertEquals("A115-co2", Topology.co2SensorId(Topology.A115));
        assertEquals("A115-damper", Topology.damperActuatorId(Topology.A115));
        assertEquals("A145-co2", Topology.co2SensorId(Topology.A145));
    }

    @Test
    @DisplayName("plan §3: the AHU carries fan-power and fan-speed, not a room")
    void shouldNameAirHandlerSensorAndActuator() {
        assertEquals("AHU-A120-fan-power", Topology.fanPowerSensorId());
        assertEquals("AHU-A120-fan-speed", Topology.fanSpeedActuatorId());
    }

    @Test
    void shouldFindEveryConfiguredZoneById() {
        for (ZoneId id : Topology.ZONE_IDS) {
            assertTrue(Topology.byId(id).isPresent(), "byId must find " + id);
            assertEquals(id, Topology.byId(id).orElseThrow().id());
            assertEquals(id, Topology.zone(id).id());
        }
    }

    @Test
    @DisplayName("a zone outside the six is a configuration error, not an empty result")
    void shouldThrowForUnknownZone() {
        ZoneId unknown = ZoneId.of("B999");
        assertEquals(false, Topology.byId(unknown).isPresent());
        var thrown = assertThrows(IllegalArgumentException.class, () -> Topology.zone(unknown));
        assertTrue(thrown.getMessage().contains("B999"));
    }

    @Test
    @DisplayName("plan §3: the CO2 balance constants")
    void shouldFixTheCarbonDioxideBalanceConstants() {
        assertEquals(420.0, Topology.OUTDOOR.value(), 1e-12);
        assertEquals(1000.0, Topology.COMFORT_LIMIT.value(), 1e-12);
        assertEquals(5e-6, Topology.GENERATION_PER_PERSON, 1e-18);
        assertEquals("level0", Topology.LEVEL);
        assertEquals("A120", Topology.AIR_HANDLER_ROOM);
    }

    /**
     * R-6's zone set, and the default it must not disturb. {@link Topology#ZONE_COUNT} is read at
     * class initialisation, so a test cannot set it for this JVM; what is asserted here is the
     * factory the variable selects, and that selecting six lands back on the real corridor exactly.
     */
    @Test
    @DisplayName("R-6: the default zone set is the real corridor, unchanged")
    void shouldLeaveTheDefaultZoneSetAsTheRealCorridor() {
        assertEquals(Topology.REAL_ZONE_IDS.size(), Topology.CONFIGURED_ZONE_COUNT,
                "ZONE_COUNT is set in this JVM, so the assertions below are not about the default");
        assertEquals(Topology.realZones(), Topology.zones());
        assertEquals(Topology.realZones(), Topology.syntheticZones(6));
        assertEquals(Topology.REAL_ZONE_IDS, Topology.ZONE_IDS);
    }

    /**
     * The air handler the synthetic path builds has to be the one ADR-0008 sized when the zone set
     * is the real six, or the deployed plant would change size the moment the configuration was
     * wired in at all.
     */
    @Test
    @DisplayName("R-6: resizing the air handler for the real six returns the ADR-0008 unit")
    void shouldResizeTheAirHandlerToItselfForTheRealSix() {
        AirHandlingUnit resized = Topology.airHandlerFor(Topology.realZones());

        assertEquals(Topology.AIR_HANDLER, resized.id());
        assertEquals(Topology.NOMINAL_FLOW.cubicMetresPerSecond(),
                resized.nominalFlow().cubicMetresPerSecond(), 1e-9);
        assertEquals(Topology.NOMINAL_POWER_WATTS, resized.nominalPowerWatts(), 1e-9);
        assertEquals(Topology.REAL_ZONE_IDS, resized.servedZones());
    }

    /**
     * Why the spare rooms exist at all: BuildSim's {@code /api/equipment/bulk} rejects a batch
     * carrying a room its floorplan does not have, so a deployable zone has to be named after a real
     * one. These are read from {@code level0/floorplan_data.json} and the assertions are that they
     * are usable — distinct, not already a zone, and not the air handler's own room.
     */
    @Test
    @DisplayName("R-6: the spare rooms are distinct real rooms that are not already zones")
    void shouldOfferSpareRoomsThatAreNotAlreadyZones() {
        List<String> spares = Topology.SPARE_LEVEL0_ROOMS;

        assertEquals(spares.size(), Set.copyOf(spares).size(), "a room is listed twice");
        assertEquals(Topology.REAL_ZONE_IDS.size() + spares.size(),
                Topology.DEPLOYABLE_ZONE_LIMIT);
        for (ZoneId real : Topology.REAL_ZONE_IDS) {
            assertFalse(spares.contains(real.value()), real + " is already one of the six");
        }
        assertFalse(spares.contains(Topology.AIR_HANDLER_ROOM),
                "the air handler's own room cannot also be a ventilated zone");
    }

    @Test
    @DisplayName("R-6: a deployable zone set names only rooms BuildSim's floorplan has")
    void shouldNameOnlyRealRoomsUpToTheDeployableLimit() {
        List<VentilationZone> deployable = Topology.syntheticZones(Topology.DEPLOYABLE_ZONE_LIMIT);

        assertEquals(Topology.DEPLOYABLE_ZONE_LIMIT, deployable.size());
        assertEquals(Topology.REAL_ZONE_IDS,
                deployable.subList(0, Topology.REAL_ZONE_IDS.size()).stream()
                        .map(VentilationZone::id).toList(),
                "the real six must stay first and unrenamed");
        for (VentilationZone zone : deployable) {
            String name = zone.id().value();
            assertTrue(Topology.REAL_ZONE_IDS.contains(zone.id())
                            || Topology.SPARE_LEVEL0_ROOMS.contains(name),
                    name + " is not a room BuildSim can host equipment in");
        }
    }

    /**
     * Past the floorplan the ids are generated, which is the harness-only regime T-28 sweeps. The
     * assertion that matters is uniqueness: two zones sharing an id would silently collapse the
     * monitor's per-zone model and make a scaling point measure fewer zones than it claims.
     */
    @Test
    @DisplayName("R-6: a zone set past the deployable limit still has unique ids")
    void shouldKeepZoneIdsUniquePastTheDeployableLimit() {
        List<VentilationZone> scaled = Topology.syntheticZones(400);

        assertEquals(400, scaled.size());
        assertEquals(400, scaled.stream().map(VentilationZone::id).collect(Collectors.toSet()).size(),
                "two synthetic zones share an id");
    }

    /**
     * The geometry is cycled from the six on purpose — a synthetic zone is representative in volume
     * and time constant rather than a claim about the room it borrows a name from.
     */
    @Test
    @DisplayName("R-6: a synthetic zone cycles the real rooms' geometry")
    void shouldCycleTheRealRoomsGeometryAcrossSyntheticZones() {
        List<VentilationZone> scaled = Topology.syntheticZones(13);
        List<VentilationZone> real = Topology.realZones();

        for (int i = 0; i < scaled.size(); i++) {
            VentilationZone template = real.get(i % real.size());
            assertEquals(template.volumeCubicMetres(), scaled.get(i).volumeCubicMetres(), 1e-12);
            assertEquals(template.damperAuthority(), scaled.get(i).damperAuthority(), 1e-12);
        }
    }

    @Test
    @DisplayName("R-6: a zone set needs at least one zone")
    void shouldRefuseAnEmptyZoneSet() {
        assertThrows(IllegalArgumentException.class, () -> Topology.syntheticZones(0));
    }

    /**
     * The mapping the occupancy schedules dispatch on. It is positional, so it can only answer for
     * zones the process is configured with — a synthetic zone is not resolvable from a six-zone JVM,
     * and the 12-zone path is covered by bringing the stack up rather than from here, because
     * {@link Topology#ZONE_COUNT} is read once per process.
     */
    @Test
    @DisplayName("R-6: each of the six real rooms is its own room type")
    void shouldMapEachRealRoomToItself() {
        for (ZoneId zone : Topology.REAL_ZONE_IDS) {
            assertEquals(zone, Topology.templateOf(zone));
        }
    }

    @Test
    @DisplayName("R-6: a zone this process is not configured with has no room type")
    void shouldRefuseARoomTypeForAnUnconfiguredZone() {
        assertThrows(IllegalArgumentException.class,
                () -> Topology.templateOf(ZoneId.of("A105")));
    }

    /**
     * The room type and the geometry must come from the same template, or a zone would be shaped
     * like the seminar room and occupied like a single office.
     */
    @Test
    @DisplayName("R-6: the room type a zone is given is the room its geometry came from")
    void shouldTakeTheRoomTypeFromTheSameTemplateAsTheGeometry() {
        List<VentilationZone> real = Topology.realZones();
        List<VentilationZone> configured = Topology.zones();

        for (int i = 0; i < configured.size(); i++) {
            VentilationZone template = real.get(i % real.size());
            assertEquals(template.id(), Topology.templateOf(configured.get(i).id()));
            assertEquals(template.volumeCubicMetres(),
                    configured.get(i).volumeCubicMetres(), 1e-12);
        }
    }
}
