"""CliApp: the askdb command line. Every command goes through AskDBFacade."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Optional

import typer
from rich.console import Console
from rich.table import Table

from askdb.agent.models import AgentAnswer, AnswerStatus
from askdb.app.facade import AskDBFacade
from askdb.data.errors import DatasetError
from askdb.data.models import QueryResult, SchemaInfo

console = Console()


class CliApp:
    def __init__(self, facade: AskDBFacade | None = None) -> None:
        self.facade = facade or AskDBFacade()

    # Persistence of the current dataset between separate askdb commands.
    def _load_state(self) -> dict:
        path = self.facade.settings.state_file
        if path.is_file():
            return json.loads(path.read_text(encoding="utf-8"))
        return {}

    def _save_state(self, **values) -> None:
        state = self._load_state()
        state.update(values)
        self.facade.settings.state_file.write_text(json.dumps(state, indent=2), encoding="utf-8")

    def restore(self) -> None:
        paths = self._load_state().get("dataset")
        if not paths:
            raise DatasetError("No dataset is loaded. Run: askdb import <file>")
        self.facade.import_dataset(paths)

    def import_cmd(self, paths: list[str]) -> None:
        schema = self.facade.import_dataset(paths)
        self._save_state(dataset=[str(Path(p).resolve()) for p in paths])
        console.print(f"[green]Loaded[/green] {schema.source_label}: {schema.summary()}")
        self.print_schema(schema)

    def schema_cmd(self, table: str | None) -> None:
        self.restore()
        schema = self.facade.require_session().schema
        if table and schema.table(table) is None:
            raise DatasetError(f"No table named '{table}'. Tables: {', '.join(t.name for t in schema.tables)}")
        self.print_schema(schema, table)

    def sql_cmd(self, sql: str) -> None:
        self.restore()
        self.print_answer(self.facade.run_manual_sql(sql))

    @staticmethod
    def print_schema(schema: SchemaInfo, table: str | None = None) -> None:
        for t in schema.tables:
            if table and t.name.lower() != table.lower():
                continue
            grid = Table(title=f"{t.name} ({t.row_count} rows)" + (f" - {t.description}" if t.description else ""),
                         title_justify="left")
            grid.add_column("column")
            grid.add_column("type")
            grid.add_column("key")
            grid.add_column("description")
            for c in t.columns:
                key = "PK" if c.primary_key else (f"FK {c.references}" if c.references else "")
                grid.add_row(c.name, c.type, key, c.description)
            console.print(grid)

    @staticmethod
    def print_result(result: QueryResult, max_rows: int = 25) -> None:
        grid = Table(show_lines=False)
        for col in result.columns:
            grid.add_column(str(col))
        for row in result.rows[:max_rows]:
            grid.add_row(*["NULL" if v is None else str(v) for v in row])
        console.print(grid)
        more = f", showing {max_rows}" if result.row_count > max_rows else ""
        limit = " (row limit reached)" if result.truncated else ""
        console.print(f"[dim]{result.row_count} rows{more}{limit}, {result.elapsed_ms:.0f} ms[/dim]")

    def print_answer(self, answer: AgentAnswer) -> None:
        colour = {AnswerStatus.ANSWERED: "green", AnswerStatus.NO_DATA: "yellow"}.get(answer.status, "red")
        console.print(f"[{colour}]{answer.status.value}[/{colour}]" + (f" {answer.message}" if answer.message else ""))
        if answer.sql:
            console.print(f"[cyan]SQL:[/cyan] {answer.sql}")
        if answer.result is not None and not answer.result.is_empty():
            self.print_result(answer.result)


app = typer.Typer(add_completion=False, help="AskDB: ask questions about a SQLite or CSV dataset.")


def _run(action) -> None:
    try:
        action(CliApp())
    except DatasetError as exc:
        console.print(f"[red]Error:[/red] {exc}")
        raise typer.Exit(code=1)


@app.command("import")
def import_command(paths: list[str] = typer.Argument(..., help="A .db/.sqlite file or one or more .csv files")) -> None:
    """Load a dataset read-only and show its schema."""
    _run(lambda cli: cli.import_cmd(paths))


@app.command("schema")
def schema_command(table: Optional[str] = typer.Argument(None, help="Show one table only")) -> None:
    """Show tables, columns, keys and descriptions."""
    _run(lambda cli: cli.schema_cmd(table))


@app.command("sql")
def sql_command(sql: str = typer.Argument(..., help="A single SELECT statement")) -> None:
    """Validate and run your own SQL without calling a model."""
    _run(lambda cli: cli.sql_cmd(sql))


def main() -> None:
    app()


if __name__ == "__main__":
    main()
