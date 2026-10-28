from __future__ import annotations

from askdb.charts.factory import ChartFactory
from askdb.charts.product import ChartProduct
from askdb.data.models import QueryResult


class ChartRecommender:
    """Asks ChartFactory for a chart; it does not choose the class itself."""

    def __init__(self, factory: ChartFactory | None = None) -> None:
        self.factory = factory or ChartFactory()

    def recommend(self, result: QueryResult, hint: str = "") -> ChartProduct:
        return self.factory.create(result, hint)
