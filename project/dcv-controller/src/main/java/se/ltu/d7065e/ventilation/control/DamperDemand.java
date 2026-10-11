package se.ltu.d7065e.ventilation.control;

import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.Ppm;

/**
 * The inner loop's rule and the reason it gives: demand-controlled ventilation, CO2 up means
 * damper open.
 *
 * <p><strong>This is deliberately a plain rule and not a clever one.</strong> It is a
 * proportional ramp with a hysteresis band — no PID, no feed-forward on occupancy, no
 * model-predictive horizon, nothing learned. The contribution of this project is the
 * supervisory loop <em>above</em> the inner loop: detection, diagnosis and graceful degradation.
 * A sophisticated inner controller would blur exactly what is being evaluated, in two ways.
 * First, a regulator with integral action quietly works around a stuck damper by pushing harder,
 * which masks the very fault R-1 must confirm and turns the residual into a measure of the
 * controller rather than of the plant. Second, the observer in T-11 predicts the CO2 response
 * from the commanded step, so the inner loop's value here is that its steps are few,
 * large and predictable. The report owns this as a scoping decision, not as an oversight.
 *
 * <p>The band: above {@code openAbove} the position ramps proportionally from the minimum at the
 * threshold to fully open at the comfort limit; below {@code closeBelow} it throttles back to
 * the minimum; in between the last position is held, which is what stops the damper chattering
 * when CO2 hovers around one threshold.
 */
public record DamperDemand(DamperPosition position, String reason) {

    public static final String HOLDING_IN_BAND = "inside the hysteresis band, holding position";

    public DamperDemand {
        if (position == null) {
            throw new IllegalArgumentException("a demand must carry a position");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a demand must carry a reason");
        }
    }

    /**
     * The position this reading asks for, given the position currently held.
     *
     * @param ppm      the room's newest trusted CO2 reading
     * @param held     the position currently commanded, returned unchanged inside the band
     * @param settings the thresholds, the comfort limit and the minimum position
     */
    public static DamperDemand from(Ppm ppm, DamperPosition held, ControlSettings settings) {
        if (ppm.exceeds(settings.openAbove())) {
            return new DamperDemand(opening(ppm, settings),
                    "%s is above the open threshold %s".formatted(ppm, settings.openAbove()));
        }
        if (ppm.value() < settings.closeBelow().value()) {
            return new DamperDemand(settings.minimumPosition(),
                    "%s is below the close threshold %s".formatted(ppm, settings.closeBelow()));
        }
        return new DamperDemand(held, HOLDING_IN_BAND);
    }

    /**
     * Linear between the open threshold and the comfort limit: the minimum position at the
     * threshold, fully open at 1000 ppm and anywhere above it. Proportional rather than
     * bang-bang so that the damper settles instead of slamming between two end stops, which
     * would both waste fan energy and make the step response the observer fits useless.
     */
    private static DamperPosition opening(Ppm ppm, ControlSettings settings) {
        double span = settings.comfortLimit().value() - settings.openAbove().value();
        double fraction = (ppm.value() - settings.openAbove().value()) / span;
        if (fraction >= 1.0) {
            return DamperPosition.OPEN;
        }
        double minimum = settings.minimumPosition().value();
        return DamperPosition.of(minimum + fraction * (DamperPosition.OPEN.value() - minimum));
    }
}
