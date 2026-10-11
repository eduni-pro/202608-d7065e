package se.ltu.d7065e.shared.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import se.ltu.d7065e.shared.event.Topics;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The topic contract of plan §3 and the one pure function in an adapter,
 * {@link MqttAdapter#matches}. A wildcard that catches one topic too many is worse than one
 * that catches too few: {@code fault-monitor} subscribes to several filters at once, so a
 * filter that also matched fan-power would feed room logic an AHU event and break R-7's
 * per-room isolation. This test lives in {@code se.ltu.d7065e.shared.boundary} because
 * {@code matches} is package-private.
 */
class TopicContractTest {

    private static final ZoneId A115 = Topology.A115;

    @Nested
    @DisplayName("Topics builders (plan §3)")
    class BuilderTest {

        @Test
        void shouldBuildTheDocumentedTopics() {
            assertEquals("readings/A115/co2", Topics.co2Reading(A115));
            assertEquals("readings/AHU-A120/fan-power", Topics.fanPower(Topology.AIR_HANDLER));
            assertEquals("commands/A115/damper", Topics.damperCommand(A115));
            assertEquals("commands/AHU-A120/fan-speed", Topics.fanSpeedCommand(Topology.AIR_HANDLER));
            assertEquals("residuals/A115", Topics.residual(A115));
            assertEquals("faults/A115", Topics.fault(A115));
            assertEquals("strategy/A115", Topics.strategy(A115));
        }

        /** ZoneId carries no slash, so every built topic has exactly the documented depth. */
        @Test
        void shouldKeepEveryZoneTopicAtTheDocumentedDepth() {
            for (ZoneId zone : Topology.ZONE_IDS) {
                assertEquals(3, Topics.co2Reading(zone).split("/", -1).length);
                assertEquals(3, Topics.damperCommand(zone).split("/", -1).length);
                assertEquals(2, Topics.residual(zone).split("/", -1).length);
                assertEquals(2, Topics.fault(zone).split("/", -1).length);
                assertEquals(2, Topics.strategy(zone).split("/", -1).length);
            }
        }

        @Test
        @DisplayName("the seven wildcard constants are the documented filters")
        void shouldDeclareTheDocumentedWildcards() {
            assertEquals("readings/+/co2", Topics.ALL_CO2_READINGS);
            assertEquals("readings/+/fan-power", Topics.ALL_FAN_POWER);
            assertEquals("commands/+/damper", Topics.ALL_DAMPER_COMMANDS);
            assertEquals("commands/+/fan-speed", Topics.ALL_FAN_SPEED_COMMANDS);
            assertEquals("residuals/+", Topics.ALL_RESIDUALS);
            assertEquals("faults/+", Topics.ALL_FAULTS);
            assertEquals("strategy/+", Topics.ALL_STRATEGIES);
        }
    }

    @Nested
    @DisplayName("MqttAdapter.matches — each wildcard catches its own topics and nothing else")
    class WildcardTest {

        /** Every topic the seven builders can produce, as the live system would publish them. */
        private static List<String> everyPublishedTopic() {
            var topics = new java.util.ArrayList<String>();
            for (ZoneId zone : Topology.ZONE_IDS) {
                topics.add(Topics.co2Reading(zone));
                topics.add(Topics.damperCommand(zone));
                topics.add(Topics.residual(zone));
                topics.add(Topics.fault(zone));
                topics.add(Topics.strategy(zone));
            }
            topics.add(Topics.fanPower(Topology.AIR_HANDLER));
            topics.add(Topics.fanSpeedCommand(Topology.AIR_HANDLER));
            return List.copyOf(topics);
        }

        /** filter, the one topic family it must catch — everything else must miss. */
        static List<org.junit.jupiter.params.provider.Arguments> filtersAndTheirFamily() {
            return List.of(
                    org.junit.jupiter.params.provider.Arguments.of(
                            Topics.ALL_CO2_READINGS, "co2"),
                    org.junit.jupiter.params.provider.Arguments.of(
                            Topics.ALL_FAN_POWER, "fan-power"),
                    org.junit.jupiter.params.provider.Arguments.of(
                            Topics.ALL_DAMPER_COMMANDS, "damper"),
                    org.junit.jupiter.params.provider.Arguments.of(
                            Topics.ALL_FAN_SPEED_COMMANDS, "fan-speed"),
                    org.junit.jupiter.params.provider.Arguments.of(
                            Topics.ALL_RESIDUALS, "residuals"),
                    org.junit.jupiter.params.provider.Arguments.of(
                            Topics.ALL_FAULTS, "faults"),
                    org.junit.jupiter.params.provider.Arguments.of(
                            Topics.ALL_STRATEGIES, "strategy"));
        }

        private static boolean belongsTo(String topic, String family) {
            return switch (family) {
                case "co2", "fan-power", "damper", "fan-speed" -> topic.endsWith("/" + family);
                default -> topic.startsWith(family + "/");
            };
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("filtersAndTheirFamily")
        @DisplayName("R-7: a wildcard matches its own family over every published topic, and no other")
        void shouldMatchOnlyItsOwnFamily(String filter, String family) {
            for (String topic : everyPublishedTopic()) {
                assertEquals(belongsTo(topic, family), MqttAdapter.matches(filter, topic),
                        "%s against %s".formatted(filter, topic));
            }
        }

        @Test
        @DisplayName("every wildcard catches all six zones, or the AHU")
        void shouldCatchEveryZoneOrTheAirHandler() {
            for (ZoneId zone : Topology.ZONE_IDS) {
                assertTrue(MqttAdapter.matches(Topics.ALL_CO2_READINGS, Topics.co2Reading(zone)));
                assertTrue(MqttAdapter.matches(Topics.ALL_DAMPER_COMMANDS, Topics.damperCommand(zone)));
                assertTrue(MqttAdapter.matches(Topics.ALL_RESIDUALS, Topics.residual(zone)));
                assertTrue(MqttAdapter.matches(Topics.ALL_FAULTS, Topics.fault(zone)));
                assertTrue(MqttAdapter.matches(Topics.ALL_STRATEGIES, Topics.strategy(zone)));
            }
            assertTrue(MqttAdapter.matches(Topics.ALL_FAN_POWER,
                    Topics.fanPower(Topology.AIR_HANDLER)));
            assertTrue(MqttAdapter.matches(Topics.ALL_FAN_SPEED_COMMANDS,
                    Topics.fanSpeedCommand(Topology.AIR_HANDLER)));
        }

        @Test
        @DisplayName("ADR-0008: a damper filter must never catch the supervisor's fan-speed command")
        void shouldNotMatchFanSpeedCommandWithDamperFilter() {
            assertFalse(MqttAdapter.matches(Topics.ALL_DAMPER_COMMANDS,
                    Topics.fanSpeedCommand(Topology.AIR_HANDLER)));
            assertFalse(MqttAdapter.matches(Topics.ALL_FAN_SPEED_COMMANDS,
                    Topics.damperCommand(A115)));
        }

        @Test
        @DisplayName("ADR-0008: a co2 filter must never catch the AHU fan-power reading")
        void shouldNotMatchFanPowerReadingWithCo2Filter() {
            assertFalse(MqttAdapter.matches(Topics.ALL_CO2_READINGS,
                    Topics.fanPower(Topology.AIR_HANDLER)));
            assertFalse(MqttAdapter.matches(Topics.ALL_FAN_POWER, Topics.co2Reading(A115)));
        }

        @ParameterizedTest
        @CsvSource({
                "readings/+/co2,          readings/A115/co2,          true",
                "readings/+/co2,          readings/A125A/co2,         true",
                "readings/+/co2,          readings/AHU-A120/fan-power,false",
                "readings/+/co2,          readings/A115/co2/extra,    false",
                "readings/+/co2,          readings/A115,              false",
                "readings/+/co2,          readings,                   false",
                "commands/+/damper,       commands/A115/damper,       true",
                "commands/+/damper,       commands/AHU-A120/fan-speed,false",
                "commands/+/damper,       commands/A115,              false",
                "residuals/+,             residuals/A115,             true",
                "residuals/+,             residuals/A115/detail,      false",
                "residuals/+,             faults/A115,                false",
                "faults/+,                faults/A145,                true",
                "faults/+,                faults,                     false",
                "strategy/+,              strategy/A124,              true",
                "strategy/+,              strategy/A124/trim,         false"
        })
        @DisplayName("single-level + matches exactly one level")
        void shouldTreatPlusAsExactlyOneLevel(String filter, String topic, boolean expected) {
            assertEquals(expected, MqttAdapter.matches(filter.trim(), topic.trim()),
                    "%s against %s".formatted(filter.trim(), topic.trim()));
        }

        @ParameterizedTest
        @CsvSource({
                "#,            readings/A115/co2,           true",
                "#,            faults/A115,                 true",
                "readings/#,   readings/A115/co2,           true",
                "readings/#,   readings/AHU-A120/fan-power, true",
                "readings/#,   commands/A115/damper,        false",
                "readings/+/#, readings/A115/co2,           true",
                "commands/#,   commands/AHU-A120/fan-speed, true"
        })
        @DisplayName("multi-level # matches the rest of the topic")
        void shouldTreatHashAsTheRestOfTheTopic(String filter, String topic, boolean expected) {
            assertEquals(expected, MqttAdapter.matches(filter.trim(), topic.trim()),
                    "%s against %s".formatted(filter.trim(), topic.trim()));
        }

        @ParameterizedTest
        @CsvSource({
                "readings/+/co2,            readings/A115,  false",
                "readings/A115/co2/extra,   readings/A115/co2, false",
                "residuals/+,               residuals,      false",
                "a/b/c/d,                   a/b,            false"
        })
        @DisplayName("a filter longer than the topic never matches")
        void shouldNotMatchWhenTheFilterIsLongerThanTheTopic(
                String filter, String topic, boolean expected) {
            assertEquals(expected, MqttAdapter.matches(filter.trim(), topic.trim()));
        }

        @Test
        @DisplayName("an exact filter matches only its own topic")
        void shouldMatchAnExactFilterOnlyAgainstItself() {
            assertTrue(MqttAdapter.matches(Topics.co2Reading(A115), Topics.co2Reading(A115)));
            assertFalse(MqttAdapter.matches(Topics.co2Reading(A115),
                    Topics.co2Reading(Topology.A124)));
        }
    }
}
