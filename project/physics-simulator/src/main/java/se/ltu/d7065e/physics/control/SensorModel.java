package se.ltu.d7065e.physics.control;

import java.util.Random;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * What a CO2 sensor publishes: the room's true concentration plus any drift the sensor has
 * acquired plus Gaussian measurement noise.
 *
 * <p>Sigma is {@link Topology#SENSOR_NOISE_SIGMA_PPM}, the T-04 spike's 30 ppm, read from there
 * rather than restated here: the detector's CUSUM thresholds (k = 30 ppm, h = 150 ppm, ADR-0008)
 * are tuned to exactly this value, so it is not a free parameter and it may not have a second
 * home to drift away from.
 *
 * <p>The noise stream is seeded and drawn in a fixed room order per tick, so a run replays
 * identically — which is what R-3's seven-day replay needs.
 */
public final class SensorModel {

    private final Random noise;

    public SensorModel(long seed) {
        this.noise = new Random(seed);
    }

    public Ppm publish(double trueCo2Ppm, double driftPpm) {
        return Ppm.of(Math.max(0, trueCo2Ppm + driftPpm
                + noise.nextGaussian() * Topology.SENSOR_NOISE_SIGMA_PPM));
    }

    /**
     * What the air handler's power meter publishes. The plant's true power is exactly constant
     * while nothing is wrong, so without measurement noise the reading never changes, the sensor
     * judges it stale, and the monitor's only cross-room signal is discarded before it is used.
     */
    public FanPower publishFanPower(double truePowerWatts) {
        return FanPower.ofWatts(Math.max(0, truePowerWatts
                + noise.nextGaussian() * Topology.FAN_POWER_NOISE_SIGMA_WATTS));
    }
}
