from __future__ import annotations

import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field

from askdb.data.models import QueryResult

_DATE = re.compile(r"^\d{4}(-\d{2}){0,2}([ T].*)?$|^\d{4}-?Q[1-4]$|^\d{4}-W\d{2}$")
_TIME_WORDS = ("date", "month", "year", "week", "day", "quarter", "period", "time")


@dataclass
class ChartSpec:
    chart_type: str
    title: str
    x_label: str = ""
    y_label: str = ""
    labels: list = field(default_factory=list)
    series: dict[str, list] = field(default_factory=dict)
    columns: list[str] = field(default_factory=list)
    rows: list[tuple] = field(default_factory=list)


def label_columns(result: QueryResult) -> list[int]:
    numeric = set(result.numeric_columns())
    return [i for i in range(len(result.columns)) if i not in numeric]


def is_time_column(result: QueryResult, index: int) -> bool:
    values = [v for v in result.column_values(index) if v is not None]
    if not values:
        return False
    if all(isinstance(v, str) and _DATE.match(v) for v in values):
        return True
    name = result.columns[index].lower()
    return any(w in name for w in _TIME_WORDS) and len(set(values)) == len(values)


class ChartProduct(ABC):
    """Product interface of the chart Factory."""

    name = "chart"

    @abstractmethod
    def suits(self, result: QueryResult) -> bool:
        ...

    @abstractmethod
    def render(self, result: QueryResult) -> ChartSpec:
        ...

    def _title(self, result: QueryResult, y: list[str], x: str) -> str:
        return f"{', '.join(y)} by {x}" if x else ", ".join(y)


class LineChart(ChartProduct):
    name = "line"

    @staticmethod
    def _time_axis(result: QueryResult) -> int | None:
        labels = label_columns(result)
        if labels and is_time_column(result, labels[0]):
            return labels[0]
        if not labels and is_time_column(result, 0):
            return 0
        return None

    def suits(self, result: QueryResult) -> bool:
        x = self._time_axis(result)
        return result.row_count >= 2 and x is not None and any(i != x for i in result.numeric_columns())

    def render(self, result: QueryResult) -> ChartSpec:
        x = self._time_axis(result)
        y_cols = [i for i in result.numeric_columns() if i != x]
        rows = sorted(result.rows, key=lambda r: str(r[x]))
        series = {result.columns[i]: [r[i] for r in rows] for i in y_cols}
        y_names = list(series)
        return ChartSpec("line", self._title(result, y_names, result.columns[x]), result.columns[x],
                         y_names[0] if len(y_names) == 1 else "value", [str(r[x]) for r in rows], series,
                         list(result.columns), result.rows)


class BarChart(ChartProduct):
    name = "bar"

    def suits(self, result: QueryResult) -> bool:
        return 1 <= result.row_count <= 30 and bool(label_columns(result)) and bool(result.numeric_columns())

    def render(self, result: QueryResult) -> ChartSpec:
        x = label_columns(result)[0]
        y_cols = result.numeric_columns()[:3]
        series = {result.columns[i]: [r[i] for r in result.rows] for i in y_cols}
        y_names = list(series)
        return ChartSpec("bar", self._title(result, y_names, result.columns[x]), result.columns[x],
                         y_names[0] if len(y_names) == 1 else "value",
                         ["NULL" if r[x] is None else str(r[x]) for r in result.rows], series,
                         list(result.columns), result.rows)


class ScatterChart(ChartProduct):
    name = "scatter"

    def suits(self, result: QueryResult) -> bool:
        return result.row_count >= 3 and len(result.numeric_columns()) >= 2

    def render(self, result: QueryResult) -> ChartSpec:
        xi, yi = result.numeric_columns()[:2]
        return ChartSpec("scatter", f"{result.columns[yi]} vs {result.columns[xi]}", result.columns[xi],
                         result.columns[yi], [r[xi] for r in result.rows], {result.columns[yi]: [r[yi] for r in result.rows]},
                         list(result.columns), result.rows)


class PieChart(ChartProduct):
    name = "pie"

    def suits(self, result: QueryResult) -> bool:
        numeric = result.numeric_columns()
        if not (2 <= result.row_count <= 8 and label_columns(result) and numeric):
            return False
        return all(v is not None and v >= 0 for v in result.column_values(numeric[0]))

    def render(self, result: QueryResult) -> ChartSpec:
        x = label_columns(result)[0]
        y = result.numeric_columns()[0]
        return ChartSpec("pie", f"Share of {result.columns[y]} by {result.columns[x]}", result.columns[x],
                         result.columns[y], [str(r[x]) for r in result.rows], {result.columns[y]: [r[y] for r in result.rows]},
                         list(result.columns), result.rows)


class TableChart(ChartProduct):
    name = "table"

    def suits(self, result: QueryResult) -> bool:
        return True

    def render(self, result: QueryResult) -> ChartSpec:
        return ChartSpec("table", f"{result.row_count} rows", columns=list(result.columns), rows=result.rows)
