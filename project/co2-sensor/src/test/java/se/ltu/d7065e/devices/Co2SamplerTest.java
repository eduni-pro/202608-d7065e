package se.ltu.d7065e.devices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.shared.control.BuildingStatePort;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanPowerObserved;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.Topics;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

class Co2SamplerTest {

    /**
     * Several tests below inject a failure the device is supposed to log, so the device logs it.
     * Silencing it here keeps an expected warning out of the build output, where it reads as a
     * broken test rather than as a passing one.
     */
    @BeforeAll
    static void silenceExpectedWarnings() {
        Logger.getLogger(Co2Sampler.class.getName()).setLevel(Level.OFF);
    }

    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final ZoneId ROOM = Topology.A115;

    private final StubBuilding building = new StubBuilding();
    private final StubPublisher publisher = new StubPublisher();
    private final StubClock clock = new StubClock(Instant.parse("2026-10-07T08:00:00Z"));
    private final Co2Sampler sampler = new Co2Sampler(building, publisher, clock, INTERVAL);

    @Test
    void shouldPublishOneReadingPerRoomPlusFanPower() {
        sampler.sampleOnce();

        assertEquals(Topology.ZONE_IDS.size() + 1, publisher.published.size());
        for (ZoneId zone : Topology.ZONE_IDS) {
            assertEquals(1, publisher.on(Topics.co2Reading(zone)).size(), "readings for " + zone);
        }
    }

    @Test
    void shouldMarkFirstReadingGood() {
        sampler.sampleOnce();

        assertEquals(List.of(Quality.GOOD), co2Qualities());
    }

    @Test
    void shouldMarkRepeatedIdenticalReadingDuplicate() {
        sampleEveryInterval(2);

        assertEquals(List.of(Quality.GOOD, Quality.DUPLICATE), co2Qualities());
    }

    @Test
    void shouldMarkUnchangingReadingStaleAfterThreeIntervals() {
        sampleEveryInterval(5);

        assertEquals(
                List.of(Quality.GOOD, Quality.DUPLICATE, Quality.DUPLICATE, Quality.DUPLICATE,
                        Quality.STALE),
                co2Qualities());
    }

    @Test
    void shouldMarkChangedReadingGoodAgain() {
        sampleEveryInterval(2);
        building.co2 = 700;
        sampleEveryInterval(1);

        assertEquals(List.of(Quality.GOOD, Quality.DUPLICATE, Quality.GOOD), co2Qualities());
    }

    @Test
    void shouldPublishFanPowerOnAirHandlerTopicRatherThanARoomTopic() {
        sampler.sampleOnce();

        List<DomainEvent> fanPower = publisher.on(Topics.fanPower(Topology.AIR_HANDLER));
        assertEquals(1, fanPower.size());
        FanPowerObserved observed = (FanPowerObserved) fanPower.getFirst();
        assertEquals(Topology.AIR_HANDLER, observed.ahu());
        // Whatever the stub plant reports: this test is about the topic the reading lands on, not
        // about the plant's rating, which Topology owns and has already changed once.
        assertEquals(Topology.NOMINAL_POWER_WATTS, observed.watts().watts());
        assertEquals(Quality.GOOD, observed.quality());
    }

    @Test
    void shouldPublishMissingCarryingLastKnownValueWhenReadFails() {
        sampler.sampleOnce();
        building.failing = true;
        clock.advance(INTERVAL);
        sampler.sampleOnce();

        List<ReadingObserved> readings = co2Readings();
        assertEquals(2, readings.size());
        assertEquals(Quality.MISSING, readings.get(1).quality());
        assertEquals(readings.getFirst().ppm(), readings.get(1).ppm(), "no fabricated value");
    }

    @Test
    void shouldPublishNothingWhenTheFirstReadFails() {
        building.failing = true;

        sampler.sampleOnce();

        assertTrue(publisher.published.isEmpty(), "silence is what the monitor must detect");
    }

    @Test
    void shouldPublishNothingOnceStopped() {
        sampler.sampleOnce();
        int publishedWhileRunning = publisher.published.size();

        sampler.stop();
        clock.advance(INTERVAL);
        sampler.sampleOnce();

        assertEquals(publishedWhileRunning, publisher.published.size(),
                "a stopping container says nothing at all");
    }

    /**
     * The SIGTERM burst T-27 would otherwise produce: the shutdown hook interrupts the sample
     * thread, every remaining read of the cycle fails, and seven MISSING readings go out as the
     * container's parting word. R-4's evidence is the silence after a deliberate kill, so that
     * burst would be a fabricated fault signal.
     */
    @Test
    void shouldPublishNoMissingBurstWhenShutdownBreaksEveryRead() {
        sampler.sampleOnce();
        int publishedWhileRunning = publisher.published.size();

        sampler.stop();
        building.failing = true;
        clock.advance(INTERVAL);
        sampler.sampleOnce();

        assertEquals(publishedWhileRunning, publisher.published.size());
        assertFalse(co2Qualities().contains(Quality.MISSING), "no parting MISSING");
    }

    /**
     * The stop arrives while a cycle is already in flight, which is the actual race: the hook
     * runs on its own thread and the interrupt lands mid-read. Everything from that moment on,
     * including the signals of this same cycle, has to stay unpublished.
     */
    @Test
    void shouldPublishNothingFromACycleStoppedPartWayThrough() {
        building.onReadOf(Topology.ZONE_IDS.get(2), () -> {
            sampler.stop();
            building.failing = true;
        });

        sampler.sampleOnce();

        assertEquals(2, publisher.published.size(), "the two rooms read before the stop");
        assertFalse(publisher.published.stream()
                        .map(Publication::event)
                        .map(ReadingObserved.class::cast)
                        .anyMatch(reading -> reading.quality() == Quality.MISSING),
                "the interrupted reads published nothing");
    }

    /**
     * The sampler's staleness judgement is a proxy, and this pins what the proxy actually does.
     *
     * <p>BuildSim exposes no timestamp on a sensor value, so the only evidence available here that
     * a value is no longer being refreshed is that it stopped changing. For CO2 that is sound,
     * because a room's concentration is never bit-identical twice. For a signal that is genuinely
     * constant it is wrong, and it was: the air handler's power is exactly {@code P_nom * s^3} on a
     * healthy plant, so a noiseless reading was judged DUPLICATE on its second sample and STALE on
     * its fifth, and the observer — which may consume only GOOD readings — discarded the monitor's
     * only cross-room signal on a perfectly healthy system.
     *
     * <p>The fix belongs upstream and is made there: the simulator publishes a measured power, with
     * meter noise, which is what a real power meter reports. See
     * {@code FaultInjectionTest.shouldPublishAMovingFanPowerOnAHealthyPlant}, which asserts that
     * every sample differs. This test keeps the proxy's limitation visible, because it is a
     * limitation and not a bug: give this sampler a genuinely constant signal and it will still
     * call it stale.
     */
    @Test
    @DisplayName("T-21: an unchanging value is judged stale, which is why the plant's reading must"
            + " be a measurement rather than an ideal")
    void shouldJudgeAnUnchangingFanPowerStaleBecauseItHasNoSourceTimestamp() {
        sampleEveryInterval(6);

        List<Quality> qualities = publisher.on(Topics.fanPower(Topology.AIR_HANDLER)).stream()
                .map(FanPowerObserved.class::cast)
                .map(FanPowerObserved::quality)
                .toList();
        assertEquals(List.of(Quality.GOOD, Quality.DUPLICATE, Quality.DUPLICATE, Quality.DUPLICATE,
                Quality.STALE, Quality.STALE), qualities,
                "with no source timestamp, an unchanging value is the only staleness evidence there"
                        + " is — so an ideal, noiseless plant reading silently kills the signal");
    }

    private void sampleEveryInterval(int samples) {
        for (int i = 0; i < samples; i++) {
            if (!publisher.published.isEmpty()) {
                clock.advance(INTERVAL);
            }
            sampler.sampleOnce();
        }
    }

    private List<Quality> co2Qualities() {
        return co2Readings().stream().map(ReadingObserved::quality).toList();
    }

    private List<ReadingObserved> co2Readings() {
        return publisher.on(Topics.co2Reading(ROOM)).stream()
                .map(ReadingObserved.class::cast)
                .toList();
    }

    private static final class StubPublisher implements EventPublisherPort {

        private final List<Publication> published = new ArrayList<>();

        @Override
        public void publish(String topic, DomainEvent event) {
            published.add(new Publication(topic, event));
        }

        List<DomainEvent> on(String topic) {
            return published.stream()
                    .filter(p -> p.topic().equals(topic))
                    .map(Publication::event)
                    .toList();
        }
    }

    private record Publication(String topic, DomainEvent event) {
    }

    /** Every room reads the same value, which is what makes duplicate and stale reproducible. */
    private static final class StubBuilding implements BuildingStatePort {

        private double co2 = 540.0;
        private boolean failing;
        private ZoneId interruptAt;
        private Runnable interruption;

        /** Stands in for the shutdown hook firing while a sample cycle is already in flight. */
        void onReadOf(ZoneId zone, Runnable interruption) {
            this.interruptAt = zone;
            this.interruption = interruption;
        }

        @Override
        public Ppm readCo2(ZoneId zone) {
            if (zone.equals(interruptAt)) {
                interruption.run();
            }
            if (failing) {
                throw new IllegalStateException("BuildSim unreachable");
            }
            return Ppm.of(co2);
        }

        @Override
        public FanPower readFanPower() {
            if (failing) {
                throw new IllegalStateException("BuildSim unreachable");
            }
            return FanPower.ofWatts(Topology.NOMINAL_POWER_WATTS);
        }

        @Override
        public DamperPosition readDamperPosition(ZoneId zone) {
            throw new UnsupportedOperationException("a sensor does not read actuators");
        }

        @Override
        public FanSpeed readFanSpeed() {
            throw new UnsupportedOperationException("a sensor does not read actuators");
        }

        @Override
        public void writeCo2(ZoneId zone, Ppm ppm) {
            throw new UnsupportedOperationException("the simulator writes sensor values");
        }

        @Override
        public void writeFanPower(FanPower power) {
            throw new UnsupportedOperationException("the simulator writes sensor values");
        }

        @Override
        public void applyDamperPosition(ZoneId zone, DamperPosition position) {
            throw new UnsupportedOperationException("the actuator applies commands");
        }

        @Override
        public void applyFanSpeed(FanSpeed speed) {
            throw new UnsupportedOperationException("the actuator applies commands");
        }
    }

    private static final class StubClock implements ClockPort {

        private Instant now;

        StubClock(Instant start) {
            now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public void waitFor(Duration duration) {
            advance(duration);
        }
    }
}
