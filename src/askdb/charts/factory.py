from __future__ import annotations

from askdb.charts.product import BarChart, ChartProduct, LineChart, PieChart, ScatterChart, TableChart
from askdb.data.models import QueryResult

CHART_TYPES = ("line", "bar", "scatter", "pie", "table")


class ChartFactory:
    """Factory: returns the hinted chart if it fits the result, otherwise the first chart that fits."""

    def __init__(self) -> None:
        self._order: list[ChartProduct] = [LineChart(), BarChart(), ScatterChart(), PieChart(), TableChart()]

    def create(self, result: QueryResult, hint: str = "") -> ChartProduct:
        hint = (hint or "").lower()
        for chart in self._order:
            if chart.name == hint and chart.suits(result):
                return chart
        return next(chart for chart in self._order if chart.suits(result))

    def fits(self, result: QueryResult) -> list[str]:
        return [chart.name for chart in self._order if chart.suits(result)]
