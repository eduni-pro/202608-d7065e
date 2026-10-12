package se.ltu.d7065e.diagnosis.boundary;

import se.ltu.d7065e.diagnosis.control.FaultMonitor;
import se.ltu.d7065e.diagnosis.entity.FaultCase;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.ServiceReported;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * D-10 — the inbound half of service, and with it the only route by which a latched case can be
 * cleared in a running deployment. {@code FaultCase.serviced()} used to be reachable from unit
 * tests and from nowhere else, which made a false confirmation permanent and quietly voided the
 * argument for latching at all: that argument is that a false positive costs one technician visit,
 * and a visit the system cannot be told about costs a restart.
 *
 * <p>This is where a service report is <em>validated</em>, because this is the boundary. Three
 * things can be wrong with one and each is refused rather than ignored, since an operator who has
 * just repaired a damper and been answered with silence has no way to tell a cleared case from a
 * dropped message:
 *
 * <ul>
 *   <li>a zone this monitor does not watch — {@link IllegalArgumentException};
 *   <li>a case id that is not the one open in that zone, which is what a stale dashboard or a
 *       redelivered report looks like — {@link IllegalArgumentException};
 *   <li>a case that exists but is not latched, so there is nothing to clear and
 *       {@link FaultCase#serviced} would refuse it one layer down — {@link IllegalStateException}.
 * </ul>
 *
 * <p>The refusal is thrown rather than logged here. MQTT has no reply, and {@code MqttAdapter}
 * already records a failing handler and keeps the subscription alive, so throwing puts the reason
 * in the monitor's log without a second logging path; the operator's synchronous answer comes from
 * the dashboard endpoint, which refuses the same three cases against its own view before it
 * publishes anything.
 */
public final class ServiceRequests {

    private final FaultMonitor monitor;

    public ServiceRequests(FaultMonitor monitor) {
        this.monitor = monitor;
    }

    /**
     * What the {@code service/+} subscription is wired to. Anything else arriving on the filter is
     * another component's business and is left alone, exactly as {@code FaultMonitor.accept} leaves
     * the events it does not read.
     */
    public void accept(DomainEvent event) {
        if (event instanceof ServiceReported report) {
            serviced(report);
        }
    }

    /** Clears the case the report names, or refuses the report. */
    public void serviced(ServiceReported report) {
        ZoneId zone = report.zone();
        if (!monitor.zones().contains(zone)) {
            throw new IllegalArgumentException("not a monitored zone: " + zone.value());
        }
        FaultCase open = monitor.caseOf(zone);
        if (open == null) {
            throw new IllegalArgumentException(
                    "no open case in %s, so case %s cannot be serviced"
                            .formatted(zone.value(), report.caseId().value()));
        }
        if (!open.id().equals(report.caseId())) {
            throw new IllegalArgumentException(
                    "case %s is not the case open in %s, which is %s"
                            .formatted(report.caseId().value(), zone.value(), open.id().value()));
        }
        if (!open.isLatched()) {
            throw new IllegalStateException(
                    "case %s in %s is %s, and only a latched case can be serviced"
                            .formatted(open.id().value(), zone.value(), open.state()));
        }
        monitor.serviced(zone);
    }
}
