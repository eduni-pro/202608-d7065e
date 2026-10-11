package se.ltu.d7065e.diagnosis.entity;

/**
 * The amplitude of a diagnostic probe step, owned here because two components command one damper
 * for it and the quantity must mean the same thing to both.
 *
 * <p>The monitor decides a probe is warranted and names the step in its evidence; the supervisor
 * executes it, because the monitor holds no actuator (ADR-0005). Those are two processes in two
 * modules, and {@code supervisor} depends only on {@code shared} — so neither the monitor's
 * {@code MonitorParameters} nor the supervisor's own constant can be the single owner without one
 * of them compiling against the other. The diagnosis component's entity layer is what both already
 * see.
 */
public interface DiagnosticProbe {

    /**
     * How far a probe steps the damper, as travel.
     *
     * <p>Tuned in T-18: large enough that the response clears the step-amplitude match fraction
     * the classifier judges it by, small enough that a 30-minute probe is a test rather than a
     * control action in its own right. The supervisor clamps it to the actuator's range and may
     * step the other way when there is no room up; that bound belongs to whoever commands, not to
     * the amplitude.
     */
    double STEP_INCREASE = 0.20;
}
