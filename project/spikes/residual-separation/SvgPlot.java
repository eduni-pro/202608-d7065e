import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

/// Hand-written SVG: stacked panels over one shared time axis, no plotting library.
class SvgPlot {

    static final int WIDTH = 960;
    static final int PANEL_HEIGHT = 190;
    static final int LEFT = 80;
    static final int RIGHT = 30;
    static final int TOP = 90;
    static final int GAP = 70;
    static final int BOTTOM = 45;
    static final int X_TICK_MINUTES = 30;
    static final int Y_TICK_TARGET = 5;
    static final double APPROX_CHAR_WIDTH_PX = 6.6;
    static final double AUTO = Double.NaN;

    static final String SURFACE = "#fcfcfb";
    static final String TEXT = "#0b0b0b";
    static final String MUTED = "#52514e";
    static final String GRID = "#e6e5e1";
    static final String AXIS = "#b3b2ad";
    static final String HEALTHY = "#2a78d6";
    static final String STUCK = "#eb6834";
    static final String PREDICTED = "#52514e";
    static final String THRESHOLD = "#d03b3b";
    static final String SOLID = "none";
    static final String DASHED = "6 4";
    static final String DOTTED = "2 3";
    static final String FONT = "font-family=\"system-ui, Helvetica, Arial, sans-serif\"";

    record Series(String label, String color, String dashArray, List<ZoneSimulation.Sample> samples,
                  ToDoubleFunction<ZoneSimulation.Sample> value) {
    }

    record ReferenceLine(String label, double y, String color, String dashArray) {
    }

    record Event(String label, int second) {
    }

    record Annotation(String label, int second, double y, String color) {
    }

    /// yFloor / yCeiling are AUTO to fit the data; a fixed ceiling clips the series that exceed it.
    record Panel(String title, String note, List<Series> series, List<ReferenceLine> references,
                 List<Annotation> annotations, double yFloor, double yCeiling) {
    }

    record YScale(double min, double max, double step, int top, int bottom) {

        double y(double value) {
            return bottom - (value - min) / (max - min) * (bottom - top);
        }

        DoubleStream ticks() {
            var count = (int) Math.round((max - min) / step);
            return IntStream.rangeClosed(0, count).mapToDouble(i -> min + i * step);
        }
    }

    List<Panel> panels;
    List<Event> events;
    int horizonS;

    SvgPlot(List<Panel> panels, List<Event> events, int horizonS) {
        this.panels = panels;
        this.events = events;
        this.horizonS = horizonS;
    }

    void write(Path path, String title, String subtitle) throws IOException {
        var height = TOP + panels.size() * PANEL_HEIGHT + (panels.size() - 1) * GAP + BOTTOM;
        var body = IntStream.range(0, panels.size())
                .mapToObj(this::renderPanel)
                .collect(Collectors.joining("\n"));
        var svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" %s font-size="12">
                <rect width="100%%" height="100%%" fill="%s"/>
                <text x="%d" y="28" font-size="16" font-weight="600" fill="%s">%s</text>
                <text x="%d" y="48" fill="%s">%s</text>
                %s
                </svg>
                """.formatted(WIDTH, height, WIDTH, height, FONT, SURFACE, LEFT, TEXT, title, LEFT, MUTED, subtitle, body);
        Files.writeString(path, svg);
    }

    String renderPanel(int index) {
        var panel = panels.get(index);
        var top = TOP + index * (PANEL_HEIGHT + GAP);
        var bottom = top + PANEL_HEIGHT;
        var scale = yScale(panel, top, bottom);
        var clipId = "plot-" + index;
        var out = new StringBuilder();
        out.append(renderClipPath(clipId, top));
        out.append(renderTitle(panel, top));
        out.append(renderLegend(panel, top));
        out.append(renderYAxis(scale));
        out.append(renderXAxis(bottom, index == panels.size() - 1));
        out.append(renderEvents(top, bottom, index == 0));
        panel.references().forEach(reference -> out.append(renderReference(reference, scale)));
        out.append("<g clip-path=\"url(#%s)\">\n".formatted(clipId));
        panel.series().forEach(series -> out.append(renderSeries(series, scale)));
        out.append("</g>\n");
        panel.annotations().forEach(annotation -> out.append(renderAnnotation(annotation, scale)));
        out.append(renderNote(panel, top));
        return out.toString();
    }

    YScale yScale(Panel panel, int top, int bottom) {
        var seriesValues = panel.series().stream()
                .flatMapToDouble(series -> series.samples().stream().mapToDouble(series.value()));
        var referenceValues = panel.references().stream().mapToDouble(ReferenceLine::y);
        var values = DoubleStream.concat(seriesValues, referenceValues).toArray();
        var min = Double.isNaN(panel.yFloor()) ? DoubleStream.of(values).min().orElse(0.0) : panel.yFloor();
        var max = Double.isNaN(panel.yCeiling()) ? DoubleStream.of(values).max().orElse(1.0) : panel.yCeiling();
        var step = niceStep(max - min);
        var floor = Math.floor(min / step) * step;
        var ceiling = Math.ceil(max / step) * step;
        return new YScale(floor, ceiling == floor ? floor + step : ceiling, step, top, bottom);
    }

    static double niceStep(double range) {
        var raw = range / Y_TICK_TARGET;
        var magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        var normalized = raw / magnitude;
        var nice = normalized < 1.5 ? 1 : normalized < 3.5 ? 2 : normalized < 7.5 ? 5 : 10;
        return nice * magnitude;
    }

    double x(int second) {
        return LEFT + (double) second / horizonS * (WIDTH - LEFT - RIGHT);
    }

    String renderClipPath(String clipId, int top) {
        return """
                <clipPath id="%s"><rect x="%d" y="%d" width="%d" height="%d"/></clipPath>
                """.formatted(clipId, LEFT, top, WIDTH - LEFT - RIGHT, PANEL_HEIGHT);
    }

    String renderTitle(Panel panel, int top) {
        return """
                <text x="%d" y="%d" font-weight="600" fill="%s">%s</text>
                """.formatted(LEFT, top - 26, TEXT, panel.title());
    }

    String renderLegend(Panel panel, int top) {
        var legend = new StringBuilder();
        var cursor = WIDTH - RIGHT;
        for (var series : panel.series().reversed()) {
            cursor -= (int) (series.label().length() * APPROX_CHAR_WIDTH_PX) + 40;
            legend.append("""
                    <line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-width="2" stroke-dasharray="%s"/>
                    <text x="%d" y="%d" fill="%s">%s</text>
                    """.formatted(cursor, top - 10, cursor + 22, top - 10, series.color(), series.dashArray(),
                    cursor + 28, top - 6, TEXT, series.label()));
        }
        return legend.toString();
    }

    String renderNote(Panel panel, int top) {
        if (panel.note().isEmpty()) {
            return "";
        }
        return """
                <text x="%d" y="%d" text-anchor="end" fill="%s" font-size="11">%s</text>
                """.formatted(WIDTH - RIGHT - 6, top + 16, MUTED, panel.note());
    }

    String renderYAxis(YScale scale) {
        return scale.ticks()
                .mapToObj(tick -> renderYTick(tick, scale))
                .collect(Collectors.joining());
    }

    String renderYTick(double tick, YScale scale) {
        return """
                <line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="%s"/>
                <text x="%d" y="%.1f" text-anchor="end" dominant-baseline="middle" fill="%s">%s</text>
                """.formatted(LEFT, scale.y(tick), WIDTH - RIGHT, scale.y(tick), GRID, LEFT - 8, scale.y(tick), MUTED, tickLabel(tick));
    }

    static String tickLabel(double tick) {
        return tick == Math.rint(tick) ? "%.0f".formatted(tick) : "%.1f".formatted(tick);
    }

    String renderXAxis(int bottom, boolean withLabels) {
        var axis = """
                <line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s"/>
                """.formatted(LEFT, bottom, WIDTH - RIGHT, bottom, AXIS);
        var ticks = IntStream.iterate(0, minute -> minute <= horizonS / 60, minute -> minute + X_TICK_MINUTES)
                .mapToObj(minute -> renderXTick(minute, bottom, withLabels))
                .collect(Collectors.joining());
        var caption = withLabels
                ? """
                <text x="%.1f" y="%d" text-anchor="middle" fill="%s">time (min)</text>
                """.formatted(x(horizonS / 2), bottom + 36, MUTED)
                : "";
        return axis + ticks + caption;
    }

    String renderXTick(int minute, int bottom, boolean withLabel) {
        var label = withLabel
                ? """
                <text x="%.1f" y="%d" text-anchor="middle" fill="%s">%d</text>
                """.formatted(x(minute * 60), bottom + 18, MUTED, minute)
                : "";
        return """
                <line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="%s"/>
                """.formatted(x(minute * 60), bottom, x(minute * 60), bottom + 5, AXIS) + label;
    }

    String renderEvents(int top, int bottom, boolean withLabels) {
        return events.stream()
                .map(event -> renderEvent(event, top, bottom, withLabels))
                .collect(Collectors.joining());
    }

    String renderEvent(Event event, int top, int bottom, boolean withLabel) {
        var label = withLabel
                ? """
                <text x="%.1f" y="%d" fill="%s" font-size="11">%s</text>
                """.formatted(x(event.second()) + 4, top + 14, MUTED, event.label())
                : "";
        return """
                <line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="%s" stroke-dasharray="%s"/>
                """.formatted(x(event.second()), top, x(event.second()), bottom, AXIS, DASHED) + label;
    }

    String renderReference(ReferenceLine reference, YScale scale) {
        return """
                <line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="%s" stroke-width="1.5" stroke-dasharray="%s"/>
                <text x="%d" y="%.1f" text-anchor="end" fill="%s" font-size="11">%s</text>
                """.formatted(LEFT, scale.y(reference.y()), WIDTH - RIGHT, scale.y(reference.y()), reference.color(),
                reference.dashArray(), WIDTH - RIGHT - 6, scale.y(reference.y()) - 4, reference.color(), reference.label());
    }

    String renderSeries(Series series, YScale scale) {
        var points = series.samples().stream()
                .map(sample -> "%.1f,%.1f".formatted(x(sample.t()), scale.y(series.value().applyAsDouble(sample))))
                .collect(Collectors.joining(" "));
        return """
                <polyline points="%s" fill="none" stroke="%s" stroke-width="1.8" stroke-dasharray="%s" stroke-linejoin="round"/>
                """.formatted(points, series.color(), series.dashArray());
    }

    String renderAnnotation(Annotation annotation, YScale scale) {
        return """
                <circle cx="%.1f" cy="%.1f" r="5" fill="%s" stroke="%s" stroke-width="2"/>
                <text x="%.1f" y="%.1f" fill="%s" font-size="11">%s</text>
                """.formatted(x(annotation.second()), scale.y(annotation.y()), SURFACE, annotation.color(),
                x(annotation.second()) + 10, scale.y(annotation.y()) - 10, TEXT, annotation.label());
    }
}
