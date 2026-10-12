package se.ltu.d7065e.evaluation.control;

import se.ltu.d7065e.ventilation.entity.Ppm;

/**
 * T-25's baseline: the detector a building-automation system ships with. CO₂ above a limit for a
 * sustained duration is a fault; nothing else is considered — no command record, no flow model, no
 * occupancy, no cross-room signal.
 *
 * <p>Written to be as strong as a fixed threshold can be made, because a baseline that is easy to
 * beat proves nothing. It takes the sensor series the model-based monitor takes, is tuned over a
 * grid on the same runs it is scored on (which flatters it — T-25 says so), and requires
 * persistence exactly as the monitor's own state machine does, so neither detector is allowed to
 * alarm on a single noisy sample.
 *
 * <p>What it cannot do is the whole point of the comparison: a room that is legitimately full sits
 * above any limit a stuck damper would push it above, and no choice of limit and duration separates
 * the two from the concentration alone.
 *
 * @param limitPpm the concentration above which the room is considered to be in trouble
 * @param sustainedMinutes how long it must stay there before the detector will say so
 */
public record ThresholdDetector(double limitPpm, int sustainedMinutes) {

    public ThresholdDetector {
        if (!(limitPpm > 0)) {
            throw new IllegalArgumentException("the limit must be positive, was: " + limitPpm);
        }
        if (sustainedMinutes < 1) {
            throw new IllegalArgumentException(
                    "the duration must be at least a minute, was: " + sustainedMinutes);
        }
    }

    /** The first minute at or after {@code from} at which the detector flags, or -1. */
    public int firstFlag(double[] series, int from, int until) {
        int above = 0;
        for (int minute = Math.max(0, from); minute < until; minute++) {
            above = series[minute] > limitPpm ? above + 1 : 0;
            if (above >= sustainedMinutes) {
                return minute;
            }
        }
        return -1;
    }

    /**
     * How many separate times the detector flagged. An episode ends when the concentration falls
     * back to the limit, so a room that stays high all afternoon is one alarm, not three hundred.
     */
    public int alarms(double[] series, int from, int until) {
        int above = 0;
        int alarms = 0;
        boolean flagged = false;
        for (int minute = Math.max(0, from); minute < until; minute++) {
            if (series[minute] > limitPpm) {
                above++;
                if (above >= sustainedMinutes && !flagged) {
                    alarms++;
                    flagged = true;
                }
            } else {
                above = 0;
                flagged = false;
            }
        }
        return alarms;
    }

    @Override
    public String toString() {
        return "%s for %d min".formatted(Ppm.of(limitPpm), sustainedMinutes);
    }
}
