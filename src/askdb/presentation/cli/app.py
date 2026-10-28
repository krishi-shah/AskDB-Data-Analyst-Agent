"""CliApp: the askdb command line. Every command goes through AskDBFacade."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Optional

import typer
from rich.console import Console
from rich.prompt import Prompt
from rich.table import Table

from askdb.agent.memory import ConversationMemory
from askdb.agent.models import AgentAnswer, AnswerStatus
from askdb.app.facade import AskDBFacade
from askdb.charts.render import save_png
from askdb.data.errors import DatasetError
from askdb.data.models import QueryResult, SchemaInfo
from askdb.llm.provider import LLMError
from askdb.presentation.cli.progress import ConsoleProgressPrinter

console = Console()


class CliApp:
    def __init__(self, facade: AskDBFacade | None = None, trace: bool = False) -> None:
        self.facade = facade or AskDBFacade()
        self.printer = ConsoleProgressPrinter(console, trace)
        self.facade.subscribe(self.printer)
        state = self._load_state()
        if "policy" in state:
            self.facade.set_routing_policy(state["policy"])
        if "share_samples" in state:
            self.facade.set_share_samples(state["share_samples"])

    # State kept between separate askdb commands
    def _load_state(self) -> dict:
        path = self.facade.settings.state_file
        if path.is_file():
            return json.loads(path.read_text(encoding="utf-8"))
        return {}

    def _save_state(self, **values) -> None:
        state = self._load_state()
        state.update(values)
        self.facade.settings.state_file.write_text(json.dumps(state, indent=2), encoding="utf-8")

    def restore(self, keep_memory: bool = False) -> None:
        state = self._load_state()
        paths = state.get("dataset")
        if not paths:
            raise DatasetError("No dataset is loaded. Run: askdb import <file>")
        self.facade.import_dataset(paths, describe=False)
        if keep_memory and state.get("memory"):
            session = self.facade.require_session()
            session.memory = ConversationMemory.from_list(state["memory"], self.facade.settings.memory_turns)

    def _save_memory(self) -> None:
        session = self.facade.session
        if session is not None and session.memory is not None:
            self._save_state(memory=session.memory.to_list())

    # Commands
    def import_cmd(self, paths: list[str], describe: bool = True) -> None:
        if describe:
            console.print("[dim]Reading the schema and asking the model for descriptions...[/dim]")
        schema = self.facade.import_dataset(paths, describe=describe)
        self._save_state(dataset=[str(Path(p).resolve()) for p in paths], memory=[])
        console.print(f"[green]Loaded[/green] {schema.source_label}: {schema.summary()}")
        for problem in self.facade.describe_errors:
            console.print(f"[yellow]Description skipped for {problem}[/yellow]")
        if self.facade.index_warning:
            console.print(f"[yellow]{self.facade.index_warning}[/yellow]")
        self.print_schema(schema)

    def schema_cmd(self, table: str | None) -> None:
        self.restore()
        schema = self.facade.require_session().schema
        if table and schema.table(table) is None:
            raise DatasetError(f"No table named '{table}'. Tables: {', '.join(t.name for t in schema.tables)}")
        self.print_schema(schema, table)

    def describe_cmd(self, entry_id: str, text: str) -> None:
        self.restore()
        self.facade.update_description(entry_id, text)
        console.print(f"[green]Saved description for {entry_id}.[/green]")

    def ask_cmd(self, question: str, continue_conversation: bool = False, chart: str | None = None,
                interactive: bool = True) -> None:
        self.restore(keep_memory=continue_conversation)
        interactive = interactive and sys.stdin.isatty()
        answer = self.facade.ask(question, interactive=interactive)
        answer = self._clarify_loop(answer)
        self.print_answer(answer, chart)
        self._save_memory()

    def _clarify_loop(self, answer: AgentAnswer) -> AgentAnswer:
        while answer.status == AnswerStatus.NEEDS_CLARIFICATION:
            request = answer.clarification
            console.print(f"[magenta]{request.question}[/magenta]")
            for i, option in enumerate(request.options, start=1):
                console.print(f"  {i}. {option}")
            raw = Prompt.ask("Choose a number or type your meaning")
            choice = request.options[int(raw) - 1] if raw.isdigit() and 1 <= int(raw) <= len(request.options) else raw
            answer = self.facade.answer_clarification(choice)
        return answer

    def shell_cmd(self) -> None:
        self.restore()
        console.print("[bold]AskDB shell[/bold]. Type a question, ':sql <query>', ':new' for a new "
                      "conversation, ':chart <type>', or ':quit'.")
        last: AgentAnswer | None = None
        while True:
            try:
                line = Prompt.ask("[cyan]askdb[/cyan]").strip()
            except (EOFError, KeyboardInterrupt):
                break
            if not line:
                continue
            if line in (":quit", ":q", "exit"):
                break
            try:
                if line == ":new":
                    self.facade.reset_conversation()
                    console.print("[green]Started a new conversation.[/green]")
                elif line.startswith(":sql "):
                    last = self.facade.run_manual_sql(line[5:])
                    self.print_answer(last)
                elif line.startswith(":chart"):
                    self._rechart(last, line[6:].strip())
                else:
                    last = self._clarify_loop(self.facade.ask(line))
                    self.print_answer(last)
            except (DatasetError, LLMError) as exc:
                console.print(f"[red]Error:[/red] {exc}")

    def _rechart(self, answer: AgentAnswer | None, chart_type: str) -> None:
        if answer is None or answer.result is None or answer.result.is_empty():
            console.print("[yellow]Nothing to chart yet.[/yellow]")
            return
        chart = self.facade.build_chart(answer.result, chart_type)
        spec = chart.render(answer.result)
        if chart.name != chart_type:
            console.print(f"[yellow]A {chart_type} chart does not fit this result; using {chart.name}.[/yellow]")
        path = save_png(spec, Path("out") / f"chart_{answer.history_id or 'latest'}_{spec.chart_type}.png")
        console.print(f"[green]Chart saved:[/green] {path}")

    def sql_cmd(self, sql: str, chart: str | None = None) -> None:
        self.restore()
        self.print_answer(self.facade.run_manual_sql(sql), chart)

    def history_cmd(self, search: str | None) -> None:
        entries = self.facade.get_history(search or "")
        grid = Table(title="History" + (f" matching '{search}'" if search else ""), title_justify="left")
        for col in ("#", "when", "status", "question", "rows"):
            grid.add_column(col)
        for e in entries:
            label = ("[manual] " if e.manual else "") + e.question
            grid.add_row(str(e.id), e.created_at, e.status, label[:70], str(e.row_count))
        console.print(grid)
        saved = self.facade.get_saved_questions()
        if saved and not search:
            console.print("[bold]Saved questions:[/bold] " + ", ".join(s.name for s in saved))

    def save_cmd(self, entry_id: int, name: str) -> None:
        item = self.facade.save_question(entry_id, name)
        console.print(f"[green]Saved[/green] '{item.name}': {item.question}")

    def rerun_cmd(self, target: str, chart: str | None = None) -> None:
        self.restore()
        if target.isdigit():
            answer = self.facade.rerun_history(int(target))
        else:
            answer = self.facade.rerun_saved(target)
        self.print_answer(answer, chart)

    def config_cmd(self, policy: str | None, share_samples: bool | None) -> None:
        if policy:
            for warning in self.facade.set_routing_policy(policy):
                console.print(f"[yellow]{warning}[/yellow]")
            self._save_state(policy=self.facade.router.policy.name)
        if share_samples is not None:
            self.facade.set_share_samples(share_samples)
            self._save_state(share_samples=share_samples)
        console.print(f"Routing policy: [bold]{self.facade.router.policy.name}[/bold]")
        console.print(f"Share sample rows with the model: {self.facade.settings.share_samples}")
        for tier, name in self.facade.provider_status().items():
            console.print(f"  {tier}: {name}")

    def usage_cmd(self, reset: bool = False) -> None:
        if reset:
            self.facade.usage.reset()
        summary = self.facade.get_usage_summary()
        grid = Table(title=f"Model usage (policy {summary.policy})", title_justify="left")
        grid.add_column("model", no_wrap=True)
        for col in ("calls", "failed", "fallbacks", "tokens in", "tokens out", "avg ms", "cost $"):
            grid.add_column(col)
        for m in summary.models:
            grid.add_row(f"{m.provider}:{m.model}" if m.provider else m.model, str(m.calls), str(m.failures),
                         str(m.fallbacks), str(m.input_tokens), str(m.output_tokens), f"{m.avg_latency_ms:.0f}",
                         f"{m.cost_usd:.4f}")
        console.print(grid)
        console.print(f"Total: {summary.total_calls} calls, {summary.total_tokens} tokens, ${summary.total_cost:.4f}")

    # Output
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

    def print_answer(self, answer: AgentAnswer, chart: str | None = None) -> None:
        colour = {AnswerStatus.ANSWERED: "green", AnswerStatus.NO_DATA: "yellow"}.get(answer.status, "red")
        console.print(f"[{colour}]{answer.status.value}[/{colour}]" + (f" {answer.message}" if answer.message
                                                                      and not answer.insight else ""))
        if answer.insight:
            badge = "[green]verified[/green]" if answer.verification and answer.verification.verified else (
                f"[yellow]unverified: {', '.join(answer.verification.unverified)}[/yellow]" if answer.verification else "")
            console.print(f"[bold]{answer.insight.text}[/bold] {badge}")
        if answer.sql:
            console.print(f"[cyan]SQL:[/cyan] {answer.sql}")
        if answer.result is not None and not answer.result.is_empty():
            self.print_result(answer.result)
        if answer.chart is not None:
            console.print(f"[dim]Chart: {answer.chart.chart_type} - {answer.chart.title}[/dim]")
            if chart:
                spec = answer.chart
                if chart not in ("", "auto") and answer.result is not None:
                    product = self.facade.build_chart(answer.result, chart)
                    spec = product.render(answer.result)
                path = save_png(spec, Path("out") / f"chart_{answer.history_id or 'latest'}_{spec.chart_type}.png")
                console.print(f"[green]Chart saved:[/green] {path}")
        if answer.history_id:
            console.print(f"[dim]History #{answer.history_id}[/dim]")


app = typer.Typer(add_completion=False, no_args_is_help=True,
                  help="AskDB: ask questions about a SQLite or CSV dataset in plain English.")


def _run(action, trace: bool = False) -> None:
    try:
        action(CliApp(trace=trace))
    except (DatasetError, LLMError, ValueError) as exc:
        console.print(f"[red]Error:[/red] {exc}")
        raise typer.Exit(code=1)


@app.command("import")
def import_command(paths: list[str] = typer.Argument(..., help="A .db/.sqlite file or one or more .csv files"),
                   describe: bool = typer.Option(True, "--describe/--no-describe",
                                                 help="Ask the model for column descriptions")) -> None:
    """Load a dataset read-only, describe it, and show its schema."""
    _run(lambda cli: cli.import_cmd(paths, describe))


@app.command("schema")
def schema_command(table: Optional[str] = typer.Argument(None, help="Show one table only")) -> None:
    """Show tables, columns, keys and descriptions."""
    _run(lambda cli: cli.schema_cmd(table))


@app.command("describe")
def describe_command(entry: str = typer.Argument(..., help="table or table.column"),
                     text: str = typer.Argument(..., help="new description")) -> None:
    """Edit the description of a table or column."""
    _run(lambda cli: cli.describe_cmd(entry, text))


@app.command("ask")
def ask_command(question: str = typer.Argument(...),
                continue_: bool = typer.Option(False, "--continue", "-c", help="Follow up on the previous question"),
                trace: bool = typer.Option(False, "--trace", help="Show every model call and tool result"),
                chart: Optional[str] = typer.Option(None, "--chart",
                                                    help="Save the chart as PNG in out/ (auto, bar, line, pie, scatter, table)"),
                interactive: bool = typer.Option(True, "--interactive/--no-interactive",
                                                 help="Ask me when the question is ambiguous")) -> None:
    """Ask a question in plain English."""
    _run(lambda cli: cli.ask_cmd(question, continue_, chart, interactive), trace)


@app.command("shell")
def shell_command(trace: bool = typer.Option(False, "--trace")) -> None:
    """Interactive session with follow-up questions."""
    _run(lambda cli: cli.shell_cmd(), trace)


@app.command("sql")
def sql_command(sql: str = typer.Argument(..., help="A single SELECT statement"),
                chart: Optional[str] = typer.Option(None, "--chart", help="Save the chart as PNG in out/")) -> None:
    """Validate and run your own SQL without calling a model."""
    _run(lambda cli: cli.sql_cmd(sql, chart))


@app.command("history")
def history_command(search: Optional[str] = typer.Argument(None, help="Text to search for")) -> None:
    """List or search past questions."""
    _run(lambda cli: cli.history_cmd(search))


@app.command("save")
def save_command(entry_id: int = typer.Argument(..., help="History number"),
                 name: str = typer.Argument(..., help="Name for the saved question")) -> None:
    """Save a history entry under a name."""
    _run(lambda cli: cli.save_cmd(entry_id, name))


@app.command("rerun")
def rerun_command(target: str = typer.Argument(..., help="History number or saved question name"),
                  chart: Optional[str] = typer.Option(None, "--chart")) -> None:
    """Run the stored SQL again without calling a model."""
    _run(lambda cli: cli.rerun_cmd(target, chart))


@app.command("config")
def config_command(policy: Optional[str] = typer.Option(None, "--policy",
                                                        help="local_only, cheap_first or strong_only"),
                   share_samples: Optional[bool] = typer.Option(None, "--share-samples/--no-share-samples")) -> None:
    """Show or change the routing policy and sample sharing."""
    _run(lambda cli: cli.config_cmd(policy, share_samples))


@app.command("usage")
def usage_command(reset: bool = typer.Option(False, "--reset", help="Clear recorded usage")) -> None:
    """Show model calls, tokens, latency and cost."""
    _run(lambda cli: cli.usage_cmd(reset))


def main() -> None:
    app()


if __name__ == "__main__":
    main()
