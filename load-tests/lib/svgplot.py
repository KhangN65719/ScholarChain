"""Tiny stdlib SVG writer for the Phase 15 experiments (no third-party library).

One function, `panels_svg`: several stacked line charts that share ONE time axis (UTC), so
the plots of different metrics are synchronized by construction. Values are drawn as they
are; nothing is smoothed or interpolated except the straight line between two points.
"""

from datetime import datetime, timezone
from xml.sax.saxutils import escape

COLORS = ["#1f77b4", "#d62728", "#2ca02c", "#ff7f0e", "#9467bd", "#8c564b", "#17becf", "#7f7f7f"]


def _hms(epoch):
    return datetime.fromtimestamp(epoch, tz=timezone.utc).strftime("%H:%M:%S")


def panels_svg(title, panels, t_min, t_max, markers=()):
    """Return an SVG document as text.

    panels  list of (y_label, series); series is a list of (label, [(epoch_seconds, value), ...])
    t_min, t_max  shared x range in epoch seconds
    markers list of (epoch_seconds, label): a dashed vertical line across every panel
    """
    width, left, right, top, panel_h, gap = 1000, 70, 20, 44, 170, 38
    height = top + len(panels) * (panel_h + gap) + 10
    span = max(float(t_max) - float(t_min), 1e-9)

    def x_of(t):
        return left + (float(t) - float(t_min)) / span * (width - left - right)

    out = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" font-family="monospace" font-size="11">'
        % (width, height),
        '<rect width="100%" height="100%" fill="white"/>',
        '<text x="%d" y="20" font-size="14">%s</text>' % (left, escape(title)),
        '<text x="%d" y="34" fill="#555">shared time axis, UTC; no smoothing</text>' % left,
    ]
    for k, (y_label, series) in enumerate(panels):
        y0 = top + k * (panel_h + gap)
        values = [v for _, pts in series for _, v in pts]
        y_max = max(values) if values else 1.0
        y_min = min(0.0, min(values)) if values else 0.0
        if y_max <= y_min:
            y_max = y_min + 1.0

        def y_of(v, y0=y0, y_min=y_min, y_max=y_max):
            return y0 + panel_h - (float(v) - y_min) / (y_max - y_min) * panel_h

        out.append(
            '<rect x="%d" y="%d" width="%d" height="%d" fill="none" stroke="#999"/>'
            % (left, y0, width - left - right, panel_h)
        )
        out.append('<text x="%d" y="%d" text-anchor="end">%s</text>' % (left - 4, y0 + 10, escape("%.4g" % y_max)))
        out.append('<text x="%d" y="%d" text-anchor="end">%s</text>' % (left - 4, y0 + panel_h, escape("%.4g" % y_min)))
        out.append('<text x="%d" y="%d" fill="#333">%s</text>' % (left + 4, y0 - 6, escape(y_label)))
        if not values:
            out.append('<text x="%d" y="%d" fill="#a00">no data</text>' % (left + 10, y0 + panel_h / 2))
        for s, (label, pts) in enumerate(series):
            color = COLORS[s % len(COLORS)]
            if pts:
                points = " ".join("%.1f,%.1f" % (x_of(t), y_of(v)) for t, v in pts)
                out.append('<polyline fill="none" stroke="%s" stroke-width="1.5" points="%s"/>' % (color, points))
                for t, v in pts:
                    out.append('<circle cx="%.1f" cy="%.1f" r="1.8" fill="%s"/>' % (x_of(t), y_of(v), color))
            out.append(
                '<text x="%d" y="%d" fill="%s">%s</text>'
                % (width - right - 230, y0 + 12 + 12 * s, color, escape(label[:34]))
            )
        for epoch, label in markers:
            if t_min <= epoch <= t_max:
                out.append(
                    '<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="#444" stroke-dasharray="4 3"/>'
                    % (x_of(epoch), y0, x_of(epoch), y0 + panel_h)
                )
                if k == 0:
                    out.append('<text x="%.1f" y="%d" fill="#444">%s</text>' % (x_of(epoch) + 2, y0 + panel_h - 4, escape(label[:24])))
    axis_y = top + len(panels) * (panel_h + gap) - gap + 14
    for i in range(6):
        t = float(t_min) + span * i / 5.0
        out.append('<text x="%.1f" y="%d" text-anchor="middle">%s</text>' % (x_of(t), axis_y, _hms(t)))
    out.append("</svg>")
    return "\n".join(out) + "\n"
