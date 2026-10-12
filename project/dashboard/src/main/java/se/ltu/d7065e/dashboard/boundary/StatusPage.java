package se.ltu.d7065e.dashboard.boundary;

import jakarta.json.Json;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import se.ltu.d7065e.dashboard.control.AirHandlerSnapshot;
import se.ltu.d7065e.dashboard.control.CaseSnapshot;
import se.ltu.d7065e.dashboard.control.LiveProjection;
import se.ltu.d7065e.dashboard.control.RoomSnapshot;
import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * The page a demo shows next to BuildSim's 3D view, and the one that proves the system is alive
 * when BuildSim is not in frame. Server-rendered HTML with a meta refresh: no framework, no CDN,
 * no JavaScript, nothing to build, and nothing that can fail separately from the JSON endpoint
 * beside it.
 */
@Path("/")
public class StatusPage {

    private final DashboardRuntime runtime;
    private final ServiceResource service;
    private final int refreshSeconds;

    public StatusPage(DashboardRuntime runtime, ServiceResource service,
            @ConfigProperty(name = "dashboard.layer-seconds") int refreshSeconds) {
        this.runtime = runtime;
        this.service = service;
        this.refreshSeconds = refreshSeconds;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String page(@QueryParam("notice") String notice) {
        LiveProjection projection = runtime.projection();
        StringBuilder rows = new StringBuilder();
        for (RoomSnapshot room : projection.rooms()) {
            rows.append(rowOf(room));
        }
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta http-equiv="refresh" content="%d">
                <title>Ventilation supervisory view</title>
                <style>
                 body { font: 14px/1.5 system-ui, sans-serif; margin: 2rem; color: #111; background: #fff; }
                 h1 { font-size: 1.2rem; margin: 0 0 .2rem; }
                 p.meta { color: #555; margin: 0 0 1rem; }
                 table { border-collapse: collapse; margin-bottom: 1.4rem; }
                 th, td { padding: .35rem .7rem; border-bottom: 1px solid #ddd; text-align: right; }
                 th:first-child, td:first-child { text-align: left; }
                 thead th { border-bottom: 2px solid #999; font-weight: 600; }
                 tr.stale td { color: #999; font-style: italic; }
                 tr.latched td { background: #fdeaea; }
                 tr.latched td:first-child { font-weight: 700; }
                 td.over { color: #b00; font-weight: 600; }
                 .pill { display: inline-block; padding: .1rem .5rem; border-radius: .7rem;
                         margin-right: .4rem; background: #eee; }
                 .up { background: #dff3df; } .down { background: #fdeaea; }
                 footer { color: #666; font-size: .85rem; }
                 h2 { font-size: 1rem; margin: 1.4rem 0 .3rem; }
                 p.note { color: #555; margin: 0 0 .5rem; font-size: .85rem; }
                 tr.open td { background: #fdeaea; }
                 tr.closed td { color: #555; }
                 td.evidence { text-align: left; max-width: 34rem; font-size: .85rem;
                               color: #333; }
                 td.caseid { text-align: left; font-family: ui-monospace, monospace;
                             font-size: .8rem; color: #555; }
                 form.serviced { display: flex; gap: .3rem; justify-content: flex-end; }
                 form.serviced input { font: inherit; padding: .15rem .4rem; width: 13rem;
                                       border: 1px solid #bbb; border-radius: .2rem; }
                 form.serviced button { font: inherit; padding: .15rem .7rem; cursor: pointer;
                                        border: 1px solid #777; border-radius: .2rem;
                                        background: #f3f3f3; }
                 p.notice { padding: .5rem .8rem; border-radius: .3rem; background: #eef4ff;
                            border: 1px solid #b9cdf0; margin: 0 0 1rem; }
                </style>
                </head>
                <body>
                <h1>Ventilation supervisory view &mdash; corridor %s, level 0</h1>
                <p class="meta">%s &middot; refreshing every %d s &middot; a room is stale after %d s
                without a reading or residual</p>
                <p>%s</p>
                %s
                %s
                <h2>Rooms</h2>
                <table>
                <thead><tr>
                 <th>Room</th><th>CO2 (ppm)</th><th>Residual (ppm)</th><th>CUSUM (ppm)</th>
                 <th>Monitor state</th><th>Classification</th><th>Control mode</th><th>Age (s)</th>
                </tr></thead>
                <tbody>
                %s</tbody>
                </table>
                %s
                <footer>A dash means no live value: either nothing has arrived yet, or what did
                arrive is older than the staleness window and is not shown as if it were current.
                Machine-readable: <a href="/api/state">/api/state</a>; one reconstructed case:
                <code>/api/cases/{caseId}</code>.</footer>
                </body>
                </html>
                """.formatted(refreshSeconds, Topology.AIR_HANDLER_ROOM, projection.now(),
                refreshSeconds, projection.stalenessWindow().toSeconds(), statusPills(),
                noticeBanner(notice), casesTable(projection.cases()),
                rows, airHandlerTable(projection.airHandler()));
    }

    /**
     * Reports a repair from the page and comes back to it.
     *
     * <p>A form post and a redirect rather than a script, because this page has no JavaScript at
     * all and that is the property worth keeping: there is nothing to build, nothing from a CDN,
     * and nothing that can fail separately from the JSON beside it. The redirect is what stops a
     * browser reload re-reporting the repair.
     *
     * <p>The validation is {@link ServiceResource}'s and is not repeated here. Two entry points
     * agreeing about which cases may be serviced only by both being given the same rules is the
     * kind of agreement that lasts until one of them is edited, so this calls the other.
     */
    @POST
    @Path("/serviced")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response reportServiced(@FormParam("zone") String zone,
            @FormParam("caseId") String caseId, @FormParam("reason") String reason) {
        Response answer = service.serviced(nullToEmpty(zone), nullToEmpty(caseId), reason);
        return Response.seeOther(URI.create("/?notice=" + encoded(detailOf(answer)))).build();
    }

    /** Pulls the human sentence out of the JSON the API answered with, whatever its status. */
    private static String detailOf(Response answer) {
        Object entity = answer.getEntity();
        if (entity == null) {
            return "the repair report was answered with " + answer.getStatus();
        }
        try (var reader = Json.createReader(new StringReader(entity.toString()))) {
            return reader.readObject().getString("detail",
                    "the repair report was answered with " + answer.getStatus());
        } catch (RuntimeException notJson) {
            return "the repair report was answered with " + answer.getStatus();
        }
    }

    private String noticeBanner(String notice) {
        return notice == null || notice.isBlank()
                ? ""
                : "<p class=\"notice\">%s</p>".formatted(escaped(notice));
    }

    /**
     * Open cases first, because an open case is work outstanding and a closed one is history.
     *
     * <p>Closed cases stay on the page rather than vanishing: the thing this view is for is
     * watching a fault be diagnosed, compensated and then cleared, and a row that disappeared at
     * the moment of the repair would take the evidence of the recovery with it.
     */
    private String casesTable(List<CaseSnapshot> cases) {
        if (cases.isEmpty()) {
            return """
                    <h2>Cases</h2>
                    <p class="note">No case has been opened since this view started. A case
                    appears here when the monitor confirms a fault.</p>
                    """;
        }
        StringBuilder rows = new StringBuilder();
        cases.forEach(one -> rows.append(rowOf(one)));
        return """
                <h2>Cases</h2>
                <p class="note">Open first, then closed, newest first within each. Opened is when
                this view first saw the case, not when the monitor opened it &mdash; the
                authoritative timeline of one case is at <code>/api/cases/{caseId}</code>.</p>
                <table>
                <thead><tr>
                 <th>Room</th><th>State</th><th>Classification</th><th>Opened</th><th>Updated</th>
                 <th>Case</th><th>Latest evidence</th><th>Repair</th>
                </tr></thead>
                <tbody>
                %s</tbody>
                </table>
                """.formatted(rows);
    }

    private static String rowOf(CaseSnapshot one) {
        return """
                 <tr class="%s"><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td>
                 <td class="caseid">%s</td><td class="evidence">%s</td><td>%s</td></tr>
                """.formatted(one.isOpen() ? "open" : "closed", escaped(one.zone().value()),
                one.state(), one.classification(), time(one.openedAt()), time(one.updatedAt()),
                escaped(one.caseId().value()), escaped(one.latestEvidence()),
                one.isOpen() ? servicedForm(one) : "&mdash;");
    }

    /** Only an open case gets the control, because only a latched case can be serviced. */
    private static String servicedForm(CaseSnapshot one) {
        return """
                <form class="serviced" method="post" action="/serviced">
                 <input type="hidden" name="zone" value="%s">
                 <input type="hidden" name="caseId" value="%s">
                 <input type="text" name="reason" placeholder="what was repaired">
                 <button type="submit">Serviced</button>
                </form>
                """.formatted(escaped(one.zone().value()), escaped(one.caseId().value()));
    }

    /** Simulated instants are long; the date repeats on every row and the time does not. */
    private static String time(Instant at) {
        if (at == null) {
            return "&mdash;";
        }
        return at.truncatedTo(ChronoUnit.SECONDS).toString().replace("T", " ").replace("Z", "");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String statusPills() {
        return pill("broker", runtime.isBrokerConnected(), runtime.isBrokerConfigured()
                        ? (runtime.isBrokerConnected() ? "connected" : "unreachable")
                        : "not configured")
                + pill("BuildSim layers", runtime.isOverlayConfigured(),
                        runtime.isOverlayConfigured() ? "publishing" : "not configured")
                + pill("journal", runtime.journal() != null, runtime.isJournalConfigured()
                        ? (runtime.journal() != null ? "available" : "unreachable")
                        : "not configured");
    }

    private static String pill(String name, boolean healthy, String detail) {
        return "<span class=\"pill %s\">%s: %s</span>"
                .formatted(healthy ? "up" : "down", escaped(name), escaped(detail));
    }

    private static String rowOf(RoomSnapshot room) {
        String classes = (room.isLatched() ? "latched " : "") + (room.stale() ? "stale" : "");
        boolean overLimit = !room.stale() && room.co2Ppm() != null
                && room.co2Ppm() > Topology.COMFORT_LIMIT.value();
        return """
                 <tr class="%s"><td>%s</td><td class="%s">%s</td><td>%s</td><td>%s</td>
                 <td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>
                """.formatted(classes, escaped(room.zone().value()), overLimit ? "over" : "",
                number(room.co2Ppm(), room.stale()), number(room.residualPpm(), room.stale()),
                number(room.cusum(), room.stale()), room.monitorState(), room.classification(),
                room.controlMode(), seconds(room.age()));
    }

    private static String airHandlerTable(AirHandlerSnapshot ahu) {
        return """
                <table>
                <thead><tr><th>Air handler</th><th>Fan power (W)</th><th>Fan speed</th>
                <th>Age (s)</th></tr></thead>
                <tbody><tr class="%s"><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr></tbody>
                </table>
                """.formatted(ahu.stale() ? "stale" : "", escaped(ahu.ahu().value()),
                number(ahu.watts(), ahu.stale()),
                ahu.fanSpeed() == null ? "&mdash;" : "%.0f%%".formatted(ahu.fanSpeed() * 100),
                seconds(ahu.age()));
    }

    /** A stale or absent measurement renders as a dash, never as its last live number. */
    private static String number(Double value, boolean stale) {
        return value == null || stale ? "&mdash;" : "%.1f".formatted(value);
    }

    private static String seconds(Duration age) {
        return age == null ? "&mdash;" : Long.toString(age.toSeconds());
    }

    /**
     * Zone ids reach this page from MQTT payloads, which makes them untrusted input at an HTML
     * boundary however well-behaved the publishers are.
     */
    private static String escaped(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
