from __future__ import annotations

from rich.console import Console

from askdb.agent.events import AgentEvent, AgentEventListener

_SHORT = {
    "plan": "Planning",
    "tool_call": "Running tool",
    "repair": "Repairing",
    "clarification": "Needs clarification",
    "chart": "Chart",
    "verify": "Checking numbers",
}


class ConsoleProgressPrinter(AgentEventListener):
    """Observer that prints agent progress. With trace on it also prints model calls and tool output."""

    def __init__(self, console: Console, trace: bool = False) -> None:
        self.console = console
        self.trace = trace

    def on_event(self, event: AgentEvent) -> None:
        if event.kind in _SHORT:
            self.console.print(f"[dim]  step {event.step}[/dim] [bold]{_SHORT[event.kind]}:[/bold] {event.message[:160]}")
        elif self.trace and event.kind == "llm_call":
            d = event.data
            fallback = " [yellow](fallback)[/yellow]" if d.get("fallback") else ""
            self.console.print(f"[dim]  model {d['provider']}:{d['model']} task={d['task']} "
                               f"tokens={d['input_tokens']}+{d['output_tokens']} {d['latency_ms']:.0f} ms[/dim]{fallback}")
        elif event.kind == "llm_error":
            self.console.print(f"[yellow]  model problem: {event.message[:160]}[/yellow]")
        elif self.trace and event.kind in ("action", "tool_result", "parse_error", "clarified"):
            self.console.print(f"[dim]  {event.kind}: {event.message[:400]}[/dim]")
