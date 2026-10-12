package se.ltu.d7065e.devices;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import se.ltu.d7065e.shared.control.BuildingStatePort;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanPowerObserved;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.Topics;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * One sample cycle of the six room CO2 sensors and the air handler's fan-power sensor: read
 * BuildSim, judge the reading, publish it.
 *
 * <p>Data quality (T-21) is judged here because this is the boundary that received the value;
 * no downstream component can tell a fresh 540 ppm from the same 540 ppm repeated for a
 * minute, and the observer may only consume {@code GOOD}.
 *
 * <p><strong>Failed read, chosen rule.</strong> A read that throws publishes
 * {@link Quality#MISSING} carrying the <em>last known</em> value, never a fabricated one: the
 * ppm field stays a value this sensor actually measured and the quality flag says it is not a
 * fresh measurement. Silence is kept for the cases that genuinely are silent — a dead sensor
 * process, a severed broker link — so R-4's BLIND path still has its trigger, and an explicit
 * MISSING gives the monitor a faster and attributable one. Before the first successful read
 * there is no honest value to carry, so nothing is published at all.
 *
 * <p><strong>Shutdown is silent.</strong> SIGTERM interrupts the sample loop, which makes every
 * remaining read of the cycle fail, so without {@link #stop()} a stopping container would sign
 * off with a MISSING for all seven signals. T-27 stops containers on purpose and R-4's evidence
 * is the silence that follows, so that parting burst would be a fabricated fault signal. Once
 * {@code stop()} has been called nothing is published at all, including from a cycle already in
 * flight on the sample thread — hence the volatile flag.
 */
public final class Co2Sampler {

    /** A value that stops changing for longer than this many sample intervals is STALE (R-4). */
    private static final int STALE_INTERVALS = 3;

    private static final Logger LOG = Logger.getLogger(Co2Sampler.class.getName());

    private final BuildingStatePort building;
    private final EventPublisherPort publisher;
    private final ClockPort clock;
    private final Duration sampleInterval;
    private final Map<String, SignalHistory> histories = new HashMap<>();

    /** Set from the shutdown hook thread and read on the sample thread, hence volatile. */
    private volatile boolean stopping;

    public Co2Sampler(BuildingStatePort building, EventPublisherPort publisher, ClockPort clock,
            Duration sampleInterval) {
        this.building = building;
        this.publisher = publisher;
        this.clock = clock;
        this.sampleInterval = sampleInterval;
    }

    /** Ends sampling and silences publication, for the duration of the process. */
    public void stop() {
        stopping = true;
    }

    public boolean isStopping() {
        return stopping;
    }

    /** Reads and publishes every signal once. One timestamp for the cycle: it is one sample. */
    public void sampleOnce() {
        if (stopping) {
            return;
        }
        ObservedAt at = ObservedAt.of(clock.now());
        for (ZoneId zone : Topology.ZONE_IDS) {
            sampleCo2(zone, at);
        }
        sampleFanPower(at);
    }

    private void sampleCo2(ZoneId zone, ObservedAt at) {
        String topic = Topics.co2Reading(zone);
        SignalHistory history = historyOf(Topology.co2SensorId(zone));
        double ppm;
        try {
            ppm = building.readCo2(zone).value();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cannot read CO2 in " + zone, e);
            if (history.hasValue()) {
                publish(topic, new ReadingObserved(
                        zone, Ppm.of(history.lastValue()), at, Quality.MISSING));
            }
            return;
        }
        Quality quality = history.judge(ppm, at.value(), staleAfter());
        publish(topic, new ReadingObserved(zone, Ppm.of(ppm), at, quality));
    }

    /** Fan power belongs to the air handler, not a room (ADR-0008). */
    private void sampleFanPower(ObservedAt at) {
        String topic = Topics.fanPower(Topology.AIR_HANDLER);
        SignalHistory history = historyOf(Topology.fanPowerSensorId());
        double watts;
        try {
            watts = building.readFanPower().watts();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cannot read fan power of " + Topology.AIR_HANDLER, e);
            if (history.hasValue()) {
                publish(topic, new FanPowerObserved(Topology.AIR_HANDLER,
                        FanPower.ofWatts(history.lastValue()), at, Quality.MISSING));
            }
            return;
        }
        Quality quality = history.judge(watts, at.value(), staleAfter());
        publish(topic, new FanPowerObserved(
                Topology.AIR_HANDLER, FanPower.ofWatts(watts), at, quality));
    }

    /**
     * The one publication point, so that a stop signalled part-way through a cycle silences the
     * signals that have not gone out yet as well as the ones that come after.
     */
    private void publish(String topic, DomainEvent event) {
        if (stopping) {
            return;
        }
        publisher.publish(topic, event);
    }

    private Duration staleAfter() {
        return sampleInterval.multipliedBy(STALE_INTERVALS);
    }

    private SignalHistory historyOf(String sensorId) {
        return histories.computeIfAbsent(sensorId, id -> new SignalHistory());
    }

    /**
     * What one sensor last returned and since when. BuildSim round-trips sensor values as
     * fixed-decimal strings, so an unchanged value comes back bit-identical and exact equality
     * is the right test — a tolerance here would classify slow genuine drift as duplicate.
     */
    private static final class SignalHistory {

        private double lastValue;
        private boolean seen;
        private Instant unchangedSince;

        Quality judge(double value, Instant now, Duration staleAfter) {
            if (!seen || value != lastValue) {
                seen = true;
                lastValue = value;
                unchangedSince = now;
                return Quality.GOOD;
            }
            return Duration.between(unchangedSince, now).compareTo(staleAfter) > 0
                    ? Quality.STALE
                    : Quality.DUPLICATE;
        }

        boolean hasValue() {
            return seen;
        }

        double lastValue() {
            return lastValue;
        }
    }
}
