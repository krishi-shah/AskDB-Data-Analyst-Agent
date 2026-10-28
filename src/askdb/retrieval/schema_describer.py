from __future__ import annotations

from askdb.agent.prompt_builder import PromptBuilder
from askdb.agent.response_parser import ParseError, ResponseParser
from askdb.data.models import SchemaInfo
from askdb.llm.provider import LLMError
from askdb.llm.router import ModelRouter


class SchemaDescriber:
    """Asks the model for plain-English table and column descriptions (one call per table)."""

    def __init__(self, router: ModelRouter, builder: PromptBuilder, parser: ResponseParser) -> None:
        self.router = router
        self.builder = builder
        self.parser = parser
        self.errors: list[str] = []

    def describe(self, schema: SchemaInfo, tables: list[str] | None = None) -> SchemaInfo:
        """Describes every table, or only the named ones (those without a cached description)."""
        self.errors = []
        for table in schema.tables:
            if tables is not None and table.name not in tables:
                continue
            messages = self.builder.build_description_prompt(schema, table.name)
            try:
                response = self.router.complete(messages, task="describe", max_tokens=600)
                parsed = self.parser.parse_descriptions(response.text)
            except (LLMError, ParseError) as exc:
                self.errors.append(f"{table.name}: {exc}")
                continue
            for item in parsed:
                if item["name"].lower() != table.name.lower():
                    continue
                table.description = item["description"]
                for name, text in item["columns"].items():
                    column = table.column(name)
                    if column is not None:
                        column.description = text
        return schema
