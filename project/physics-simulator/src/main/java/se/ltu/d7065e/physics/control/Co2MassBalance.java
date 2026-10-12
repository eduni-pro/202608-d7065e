package se.ltu.d7065e.physics.control;

import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * The well-mixed CO2 balance of one room (ADR-0008, Part 2):
 * {@code V * dC/dt = n * g * 1e6 - Q * (C - C_out)}, with C in ppm, V in m3, Q in m3/s and
 * {@code g = }{@link Topology#GENERATION_PER_PERSON} in m3/s per person. The factor 1e6 is
 * what turns a volumetric generation rate into ppm.
 *
 * <p>Integrated with forward Euler at the simulator's 1 s tick, which is three orders of
 * magnitude below the shortest room time constant V/Q, so the explicit scheme is stable.
 *
 * <p>Deliberately without a floor: with nobody in the room the only steady state is outdoor
 * concentration, and that overnight decay to 420 ppm is the reference the sensor-drift
 * classifier depends on.
 */
public interface Co2MassBalance {

    static double nextPpm(
            double currentPpm,
            double volumeCubicMetres,
            int occupants,
            double flowCubicMetresPerSecond,
            double outdoorPpm,
            double stepSeconds) {
        double generation = occupants * Topology.GENERATION_PER_PERSON * 1e6;
        double removal = flowCubicMetresPerSecond * (currentPpm - outdoorPpm);
        return currentPpm + stepSeconds * (generation - removal) / volumeCubicMetres;
    }

    /** The concentration the room settles at, for reasoning about a tick's direction. */
    static double steadyStatePpm(
            int occupants, double flowCubicMetresPerSecond, double outdoorPpm) {
        return outdoorPpm
                + occupants * Topology.GENERATION_PER_PERSON * 1e6 / flowCubicMetresPerSecond;
    }
}
