"""AgentOrchestrator: the bounded plan, act, observe loop (subject of the Observer pattern)."""

from __future__ import annotations

from askdb.agent.events import AgentEvent, AgentEventListener
from askdb.agent.insight import InsightGenerator, InsightVerifier
from askdb.agent.memory import Turn
from askdb.agent.models import (
    AgentAction,
    AgentAnswer,
    AgentState,
    AnswerStatus,
    Observation,
)
from askdb.agent.planner import Planner
from askdb.agent.prompt_builder import PromptBuilder
from askdb.agent.response_parser import ParseError, ResponseParser
from askdb.agent.tools.base import ToolResult
from askdb.agent.tools.registry import ToolRegistry
from askdb.llm.provider import LLMError, LLMResponse
from askdb.llm.router import ModelRouter


def _normalize_sql(sql: str) -> str:
    return " ".join(sql.strip().rstrip(";").split()).lower()


class AgentOrchestrator:
    def __init__(self, router: ModelRouter, planner: Planner, tools: ToolRegistry, builder: PromptBuilder,
                 parser: ResponseParser, insights: InsightGenerator, verifier: InsightVerifier,
                 max_steps: int = 8, max_repairs: int = 3, max_clarifications: int = 2) -> None:
        self.router = router
        self.planner = planner
        self.tools = tools
        self.builder = builder
        self.parser = parser
        self.insights = insights
        self.verifier = verifier
        self.max_steps = max_steps
        self.max_repairs = max_repairs
        self.max_clarifications = max_clarifications
        self._listeners: list[AgentEventListener] = []
        self._step = 0
        self.router.listener = self._on_model_call

    # Observer subject
    def subscribe(self, listener: AgentEventListener) -> None:
        if listener not in self._listeners:
            self._listeners.append(listener)

    def unsubscribe(self, listener: AgentEventListener) -> None:
        if listener in self._listeners:
            self._listeners.remove(listener)

    def _publish(self, kind: str, message: str, **data) -> None:
        event = AgentEvent(kind=kind, message=message, step=self._step, data=data)
        for listener in list(self._listeners):
            listener.on_event(event)

    def _on_model_call(self, task: str, response: LLMResponse | None, note: str) -> None:
        if response is None:
            self._publish("llm_error", note, task=task)
            return
        self._publish("llm_call", f"{task} via {response.provider}:{response.model}", task=task,
                      provider=response.provider, model=response.model, input_tokens=response.input_tokens,
                      output_tokens=response.output_tokens, latency_ms=response.latency_ms,
                      cost_usd=response.cost_usd, fallback=bool(note), note=note)

    # Entry points
    def run(self, question: str, session, interactive: bool = True) -> AgentAnswer:
        self._step = 0
        state = AgentState(question=question, context=session.memory.context_text() if session.memory else "",
                           interactive=interactive)
        session.pending_state = None
        self._publish("start", f"Question: {question}")
        try:
            state.plan = self.planner.create_plan(question, state.context, session.index, session.schema)
        except LLMError as exc:
            return self._failed(state, f"No model could plan the question: {exc}")
        plan = state.plan
        state.schema_text = self._schema_context(state, session)
        self._publish("plan", f"Plan ({plan.category}): {plan.approach}", category=plan.category,
                      tables=plan.tables, chart_hint=plan.chart_hint, ambiguous=plan.ambiguous)
        if plan.category == "destructive":
            return self.refuse(plan.reason or "AskDB only reads data; it cannot change or delete it.", state)
        if plan.category == "unanswerable":
            message = plan.reason or "The loaded dataset does not contain data that answers this question."
            self._publish("final", message, status=AnswerStatus.NO_DATA.value)
            return AgentAnswer(status=AnswerStatus.NO_DATA, question=question, message=message)
        if plan.needs_clarification:
            if interactive and state.clarification_rounds < self.max_clarifications:
                return self._ask(state, session, plan.clarification)
            state.clarified.append("The question is ambiguous; choose the most common meaning and state it.")
        return self._loop(state, session)

    @staticmethod
    def _schema_context(state: AgentState, session) -> str:
        """Small schemas are sent whole; large ones only the planned and retrieved tables."""
        schema = session.schema
        if len(schema.tables) <= 8:
            return schema.to_text()
        names = {t.name for t in schema.tables if state.plan and t.name.lower() in
                 {p.lower() for p in state.plan.tables}}
        if session.index is not None:
            names |= {hit.table for hit in session.index.search(state.question, 10)}
        return schema.to_text(sorted(names)) if names else schema.to_text()

    def resume(self, choice: str, session) -> AgentAnswer:
        state: AgentState | None = session.pending_state
        if state is None or state.pending_clarification is None:
            return AgentAnswer(status=AnswerStatus.FAILED, message="There is no question waiting for clarification.")
        request = state.pending_clarification
        state.clarified.append(f"{request.question} -> {choice}")
        state.pending_clarification = None
        session.pending_state = None
        self._step = state.step_count
        self._publish("clarified", f"Analyst chose: {choice}", choice=choice)
        return self._loop(state, session)

    def refuse(self, reason: str, state: AgentState | None = None) -> AgentAnswer:
        self._publish("refused", reason, status=AnswerStatus.REFUSED.value)
        return AgentAnswer(status=AnswerStatus.REFUSED, question=state.question if state else "", message=reason)

    # Loop
    def _loop(self, state: AgentState, session) -> AgentAnswer:
        while state.step_count < self.max_steps:
            state.step_count += 1
            self._step = state.step_count
            action = self._next_action(state)
            if isinstance(action, AgentAnswer):
                return action
            if action is None:
                continue
            if action.is_final:
                if state.successful_query is None:
                    self._observe(state, action, False, "You must run a successful run_query before answering.")
                    continue
                return self.finalize(session, state, action)
            answer = self._act(state, session, action)
            if answer is not None:
                return answer
        if state.successful_query is not None:
            return self.finalize(session, state, AgentAction(kind="final"))
        return self._failed(state, f"The agent stopped after {self.max_steps} steps without a working query.")

    def _next_action(self, state: AgentState) -> AgentAction | AgentAnswer | None:
        repairing = bool(state.last_error)
        specs = self.tools.specs(exclude=self._excluded_tools(state))
        if repairing:
            messages = self.builder.build_repair_prompt(state, state.last_error, specs)
        else:
            messages = self.builder.build_step_prompt(state, specs)
        try:
            response = self.router.complete(messages, task="repair" if repairing else "step",
                                            attempt=max(state.repair_count, 1))
        except LLMError as exc:
            return self._failed(state, f"No model could answer: {exc}")
        try:
            action = self.parser.parse_action(response.text)
        except ParseError as exc:
            self._publish("parse_error", str(exc))
            self._observe(state, AgentAction(kind="invalid"), False, f"Your reply was not usable: {exc}")
            return None
        self._publish("action", action.thought or f"Action: {action.kind} {action.tool}",
                      tool=action.tool, args=action.args, final=action.is_final)
        return action

    def _excluded_tools(self, state: AgentState) -> tuple[str, ...]:
        if not state.interactive or state.clarification_rounds >= self.max_clarifications:
            return ("ask_user", "make_chart")
        return ("make_chart",)

    def _act(self, state: AgentState, session, action: AgentAction) -> AgentAnswer | None:
        if action.tool == "ask_user" and "ask_user" in self._excluded_tools(state):
            self._observe(state, action, False, "Do not ask again. Choose the most common meaning and state it.")
            return None
        if action.tool == "run_query":
            sql = _normalize_sql(str(action.args.get("sql", "")))
            if sql in state.seen_sql:
                return self._failed(state, "The agent repeated SQL that had already failed, so it stopped. "
                                           f"Last error: {state.last_error}")
            if state.successful_query is not None and sql == _normalize_sql(state.successful_sql):
                return self.finalize(session, state, AgentAction(kind="final"))
        previous = state.observations[-1].action if state.observations else None
        if previous is not None and previous.tool == action.tool and previous.args == action.args:
            self._observe(state, action, False, f"You already called {action.tool} with these arguments; "
                                                "its result is above. Take a different step.")
            return None
        self._publish("tool_call", f"{action.tool}({action.args})", tool=action.tool, args=action.args)
        result = self.tools.execute(action.tool, action.args, session)
        self._publish("tool_result", result.content[:500], tool=action.tool, ok=result.ok)
        self._observe(state, action, result.ok, result.content)
        if action.tool == "ask_user" and result.ok:
            return self._ask(state, session, result.data)
        if action.tool == "run_query":
            return self._after_query(state, action, result)
        return None

    def _after_query(self, state: AgentState, action: AgentAction, result: ToolResult) -> AgentAnswer | None:
        state.last_sql = str(action.args.get("sql", ""))
        if result.ok and not result.data.is_empty():
            state.successful_query = result.data
            state.successful_sql = result.data.sql
            state.last_error = ""
            return None
        if result.ok:
            if state.empty_results >= 1:
                state.successful_query = result.data
                state.successful_sql = result.data.sql
                state.last_error = ""
                return None
            state.empty_results += 1
            error = ("The query returned 0 rows. If the question expects rows, check the filter values "
                     "with column_values and fix the query; otherwise answer that there are none.")
            return self.handle_failure(state, error, remember=False)
        return self.handle_failure(state, result.error)

    def handle_failure(self, state: AgentState, error: str, remember: bool = True) -> AgentAnswer | None:
        if remember:
            state.seen_sql.add(_normalize_sql(state.last_sql))
        state.repair_count += 1
        state.last_error = error
        if state.repair_count > self.max_repairs:
            return self._failed(state, f"The query still failed after {self.max_repairs} repair attempts. "
                                       f"Last error: {error}")
        self._publish("repair", f"Repair attempt {state.repair_count}: {error}", attempt=state.repair_count)
        return None

    def _ask(self, state: AgentState, session, request) -> AgentAnswer:
        state.clarification_rounds += 1
        state.pending_clarification = request
        session.pending_state = state
        self._publish("clarification", request.question, options=request.options)
        return AgentAnswer(status=AnswerStatus.NEEDS_CLARIFICATION, question=state.question,
                           message=request.question, clarification=request, steps=list(state.observations))

    def _observe(self, state: AgentState, action: AgentAction, ok: bool, content: str) -> None:
        state.observations.append(Observation(action=action, ok=ok, content=content))

    def _failed(self, state: AgentState, message: str) -> AgentAnswer:
        self._publish("final", message, status=AnswerStatus.FAILED.value)
        return AgentAnswer(status=AnswerStatus.FAILED, question=state.question, message=message,
                           sql=state.last_sql, steps=list(state.observations))

    # Finish
    def finalize(self, session, state: AgentState, action: AgentAction) -> AgentAnswer:
        result = state.successful_query
        session.last_result = result
        if result.is_empty():
            self._publish("final", "The query returned no rows.", status=AnswerStatus.NO_DATA.value)
            answer = AgentAnswer(status=AnswerStatus.NO_DATA, question=state.question, sql=state.successful_sql,
                                 result=result, message="No rows match this question.",
                                 steps=list(state.observations))
            self._remember(session, state, answer)
            return answer
        hint = state.plan.chart_hint if state.plan else ""
        chart = self.tools.execute("make_chart", {"chart_type": hint}, session)
        self._publish("chart", chart.content)
        insight = self.insights.generate(state.question, result)
        report = self.verifier.verify(insight, result, state.question)
        if not report.verified:
            self._publish("verify", f"Unverified numbers {report.unverified}; regenerating once.")
            insight = self.insights.generate(state.question, result, report.feedback())
            report = self.verifier.verify(insight, result, state.question)
        self._publish("verify", "All numbers verified." if report.verified
                      else f"Still unverified: {', '.join(report.unverified)}", verified=report.verified)
        answer = AgentAnswer(status=AnswerStatus.ANSWERED, question=state.question, sql=state.successful_sql,
                             result=result, chart=chart.data if chart.ok else None, insight=insight,
                             verification=report, message=action.answer, steps=list(state.observations))
        self._remember(session, state, answer)
        self._publish("final", insight.text, status=AnswerStatus.ANSWERED.value)
        return answer

    @staticmethod
    def _remember(session, state: AgentState, answer: AgentAnswer) -> None:
        if session.memory is None:
            return
        summary = answer.result.preview_text(5) if answer.result is not None else ""
        session.memory.add_turn(Turn(question=state.question, sql=answer.sql, result_summary=summary,
                                     answer=answer.insight.text if answer.insight else answer.message))
