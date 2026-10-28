from __future__ import annotations

from askdb.agent.models import QueryPlan
from askdb.agent.prompt_builder import PromptBuilder
from askdb.agent.response_parser import ParseError, ResponseParser
from askdb.llm.router import ModelRouter


class Planner:
    """Retrieves the relevant schema and asks the model for a QueryPlan."""

    def __init__(self, router: ModelRouter, builder: PromptBuilder, parser: ResponseParser, k: int = 8) -> None:
        self.router = router
        self.builder = builder
        self.parser = parser
        self.k = k

    def create_plan(self, question: str, context: str, index, schema=None) -> QueryPlan:
        if schema is not None and (index is None or len(schema.tables) <= 8):
            hits = [schema.to_text()]
        elif index is not None:
            hits = [h.text for h in index.search(question + " " + context[-300:], self.k)]
        else:
            hits = []
        messages = self.builder.build_plan_prompt(question, context, hits)
        for _ in range(2):
            response = self.router.complete(messages, task="plan")
            try:
                return self.parser.parse_plan(response.text)
            except ParseError:
                continue
        return QueryPlan(category="query", approach="Explore the schema and write a query.")
