package se.ltu.d7065e.diagnosis.control;

import se.ltu.d7065e.diagnosis.entity.CusumStatistic;
import se.ltu.d7065e.diagnosis.entity.Residual;

/**
 * The two-sided CUSUM. The upper arm is lifted from the T-04 spike's
 * {@code ZoneSimulation.Cusum} — the validated core of the detector (T-12). The spike had it
 * nested in a class in the default package, which is why it is extracted here rather than
 * re-derived: the arithmetic in {@link #next(CusumStatistic, double)} is the arithmetic that
 * measured 16.7 sigma separation, 0 of 20 false alarms and a 5–11 min detection delay, and
 * changing it would invalidate that evidence. It is not changed.
 *
 * <p>{@code S_0 = 0}, {@code S_t = max(0, S_(t-1) + r_t - k)}, alarm when {@code S_t > h}.
 * The flooring at zero is the point of the statistic: a run of healthy samples cannot bank
 * credit against a later fault, so the detector has no memory of being right.
 *
 * <h2>The lower arm (T-32 section 5)</h2>
 *
 * An upper arm can only see CO2 <em>higher</em> than predicted, so two faults are invisible to
 * it because they produce the opposite sign: a damper stuck wide open, which over-ventilates
 * and wastes fan energy while the controller never learns the damper is dead, and a sensor
 * drifting low, which is the dangerous one — the inner loop believes the room is cleaner than
 * it is and under-ventilates an occupied room, so a safety-relevant fault reads as perfect
 * health. {@code physics-simulator}'s {@code InjectedFault.sensorDrift} already accepts a
 * negative rate, so the generator could inject that before the detector could see it.
 *
 * <p>The lower arm is the exact mirror, {@code S_low_t = max(0, S_low_(t-1) - r_t - k)}, kept as
 * a non-negative magnitude so it can be carried in {@link CusumStatistic} and compared against
 * the same {@code h}. Sharing {@code k} and {@code h} is deliberate: the statistic becomes
 * symmetric without a single new tuning parameter, so ADR-0008's parameter table does not grow
 * and nothing about R-1's measured delay is renegotiated. The price is that the in-control
 * false-alarm exposure roughly doubles — two arms, each with the spike's run length — which is
 * a statement about R-3 and is measured in {@code FalseAlarmWeekTest} rather than argued here.
 */
public record Cusum(double kPpm, double hPpm) {

    public Cusum {
        if (kPpm < 0 || !Double.isFinite(kPpm)) {
            throw new IllegalArgumentException("cusum k must be finite and non-negative, was: " + kPpm);
        }
        if (hPpm <= 0 || !Double.isFinite(hPpm)) {
            throw new IllegalArgumentException("cusum h must be finite and positive, was: " + hPpm);
        }
    }

    public static Cusum of(MonitorParameters parameters) {
        return new Cusum(parameters.cusumK(), parameters.cusumH());
    }

    public CusumStatistic next(CusumStatistic previous, Residual residual) {
        return next(previous, residual.ppm());
    }

    public CusumStatistic next(CusumStatistic previous, double residualPpm) {
        return CusumStatistic.of(Math.max(0.0, previous.value() + residualPpm - kPpm));
    }

    public CusumStatistic nextLower(CusumStatistic previous, Residual residual) {
        return nextLower(previous, residual.ppm());
    }

    /**
     * The lower arm, {@code S_low = max(0, S_low - r - k)}. It accumulates on residuals more
     * negative than {@code -k} and is held as a magnitude, so {@link #alarms(CusumStatistic)}
     * and the suspicion threshold apply to it unchanged — one limit, two directions.
     */
    public CusumStatistic nextLower(CusumStatistic previous, double residualPpm) {
        return CusumStatistic.of(Math.max(0.0, previous.value() - residualPpm - kPpm));
    }

    /**
     * The arm that is currently further along. {@code ResidualComputed} carries a single
     * {@code cusum} field and the wire schema is frozen (ADR-0006), so the published series is
     * the maximum of the two arms: whichever side is accumulating is what the dashboard's CUSUM
     * layer and the journal show. The cost is that the published number no longer says which
     * direction it came from — a real limitation, owned in the report. The sign of the residual
     * published alongside it is the only clue a consumer gets.
     */
    public CusumStatistic published(CusumStatistic upper, CusumStatistic lower) {
        return upper.value() >= lower.value() ? upper : lower;
    }

    public boolean alarms(CusumStatistic statistic) {
        return statistic.exceeds(hPpm);
    }

    /**
     * Siegmund's approximation of the in-control average run length, in samples, for a
     * zero-mean residual with the given standard deviation. Kept from the spike because it is
     * how {@code h} was chosen against R-3 in the first place, and the report quotes it.
     */
    public double averageRunLengthInControl(double sigmaPpm) {
        double k = kPpm / sigmaPpm;
        double b = hPpm / sigmaPpm + 1.166;
        return (Math.exp(2 * k * b) - 2 * k * b - 1) / (2 * k * k);
    }
}
