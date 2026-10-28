"""Draws a ChartSpec with matplotlib. Used by the CLI (to save images) and by the GUI canvas."""

from __future__ import annotations

from pathlib import Path

from askdb.charts.product import ChartSpec


def draw(spec: ChartSpec, figure) -> None:
    figure.clear()
    ax = figure.add_subplot(111)
    if spec.chart_type == "table":
        ax.axis("off")
        shown = spec.rows[:20]
        if shown:
            cells = [["NULL" if v is None else str(v) for v in row] for row in shown]
            table = ax.table(cellText=cells, colLabels=spec.columns, loc="center")
            table.auto_set_font_size(False)
            table.set_fontsize(8)
        ax.set_title(spec.title + (" (first 20)" if len(spec.rows) > 20 else ""))
        return
    if spec.chart_type == "pie":
        values = next(iter(spec.series.values()))
        ax.pie(values, labels=spec.labels, autopct="%1.1f%%", startangle=90)
        ax.axis("equal")
    elif spec.chart_type == "scatter":
        name, values = next(iter(spec.series.items()))
        ax.scatter(spec.labels, values, s=18)
    elif spec.chart_type == "line":
        for name, values in spec.series.items():
            ax.plot(spec.labels, values, marker="o", markersize=3, label=name)
        _thin_ticks(ax, spec.labels)
    else:
        count = len(spec.series)
        width = 0.8 / max(count, 1)
        positions = range(len(spec.labels))
        for i, (name, values) in enumerate(spec.series.items()):
            ax.bar([p + i * width - 0.4 + width / 2 for p in positions], values, width, label=name)
        ax.set_xticks(list(positions))
        ax.set_xticklabels(spec.labels, rotation=45 if len(spec.labels) > 6 else 0, ha="right" if len(spec.labels) > 6 else "center")
    if spec.chart_type != "pie":
        ax.set_xlabel(spec.x_label)
        ax.set_ylabel(spec.y_label)
        if len(spec.series) > 1:
            ax.legend()
    ax.set_title(spec.title)
    figure.tight_layout()


def _thin_ticks(ax, labels: list) -> None:
    step = max(1, len(labels) // 12)
    ax.set_xticks(range(0, len(labels), step))
    ax.set_xticklabels([labels[i] for i in range(0, len(labels), step)], rotation=45, ha="right")


def save_png(spec: ChartSpec, path: str | Path) -> Path:
    import matplotlib

    matplotlib.use("Agg")
    from matplotlib.figure import Figure

    figure = Figure(figsize=(8, 5), dpi=110)
    draw(spec, figure)
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(out)
    return out
