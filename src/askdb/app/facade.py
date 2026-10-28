"""AskDBFacade: the single entry point used by the GUI and the CLI (Facade pattern)."""

from __future__ import annotations

from pathlib import Path

from askdb.agent.events import AgentEventListener
from askdb.agent.insight import InsightGenerator, InsightVerifier
from askdb.agent.memory import ConversationMemory
from askdb.agent.models import AgentAnswer, AnswerStatus
from askdb.agent.orchestrator import AgentOrchestrator
from askdb.agent.planner import Planner
from askdb.agent.prompt_builder import PromptBuilder
from askdb.agent.response_parser import ResponseParser
from askdb.agent.tools.ask_user import AskUserTool
from askdb.agent.tools.column_values import ColumnValuesTool
from askdb.agent.tools.make_chart import MakeChartTool
from askdb.agent.tools.registry import ToolRegistry
from askdb.agent.tools.run_query import RunQueryTool
from askdb.agent.tools.sample_rows import SampleRowsTool
from askdb.agent.tools.search_schema import SearchSchemaTool
from askdb.app.session import Session
from askdb.app.usage_tracker import UsageSummary, UsageTracker
from askdb.charts.factory import ChartFactory
from askdb.charts.product import ChartProduct
from askdb.charts.recommender import ChartRecommender
from askdb.config import Settings, load_settings
from askdb.data.errors import DatasetError, QueryError
from askdb.data.executor import QueryExecutor
from askdb.data.factory import DataSourceFactory
from askdb.data.models import QueryResult, SchemaInfo
from askdb.data.schema_reader import SchemaReader
from askdb.llm.factory import LLMProviderFactory
from askdb.llm.policies import policy_by_name
from askdb.llm.provider import LLMProvider, ProviderUnavailableError
from askdb.llm.router import ModelRouter
from askdb.retrieval.embeddings import EmbeddingProvider, default_embedding_provider
from askdb.retrieval.schema_describer import SchemaDescriber
from askdb.retrieval.schema_index import SchemaIndex
from askdb.safety.validator import SqlValidator
from askdb.storage import app_db
from askdb.storage.descriptions import DescriptionStore
from askdb.storage.history import HistoryEntry, HistoryRepository
from askdb.storage.saved_questions import SavedQuestion, SavedQuestionRepository

TIER_PROVIDERS = {"fast": "gemini", "strong": "groq"}


class NoDatasetError(DatasetError):
    pass


class AskDBFacade:
    def __init__(self, settings: Settings | None = None, local_provider: LLMProvider | None = None,
                 embedder: EmbeddingProvider | None = None) -> None:
        self.settings = settings or load_settings()
        self.settings.ensure_home()
        self.session: Session | None = None
        self.dataset_key = ""
        s = self.settings

        self._sources = DataSourceFactory(s.max_file_mb)
        self._reader = SchemaReader()
        self._validator = SqlValidator(s.row_limit)
        self._executor = QueryExecutor(s.query_timeout_s, s.row_limit)
        self._charts = ChartFactory()
        self._recommender = ChartRecommender(self._charts)

        self._conn = app_db.connect(s.app_db)
        self._history = HistoryRepository(self._conn)
        self._saved = SavedQuestionRepository(self._conn)
        self._descriptions = DescriptionStore(self._conn)
        self.usage = UsageTracker(self._conn)

        self._providers = LLMProviderFactory(s)
        providers = {"local": local_provider or self._providers.create("ollama")}
        self.router = ModelRouter(providers)
        self._embedder = embedder
        self._builder = PromptBuilder(share_samples=s.share_samples)
        self._parser = ResponseParser()
        self._describer = SchemaDescriber(self.router, self._builder, self._parser)
        self._sample_tools: list = []
        self.orchestrator = AgentOrchestrator(
            self.router,
            Planner(self.router, self._builder, self._parser),
            self._build_tools(),
            self._builder,
            self._parser,
            InsightGenerator(self.router, self._builder, self._parser),
            InsightVerifier(),
            s.max_steps, s.max_repairs, s.max_clarifications,
        )
        self.orchestrator.subscribe(self.usage)
        self.set_routing_policy(s.policy)

    def _build_tools(self) -> ToolRegistry:
        registry = ToolRegistry()
        sample = SampleRowsTool(self._executor, self.settings.share_samples)
        values = ColumnValuesTool(self._executor, self.settings.share_samples)
        self._sample_tools = [sample, values]
        for tool in (SearchSchemaTool(), sample, values, RunQueryTool(self._validator, self._executor),
                     MakeChartTool(self._recommender), AskUserTool()):
            registry.register(tool)
        return registry

    # Observers
    def subscribe(self, listener: AgentEventListener) -> None:
        self.orchestrator.subscribe(listener)

    def unsubscribe(self, listener: AgentEventListener) -> None:
        self.orchestrator.unsubscribe(listener)

    # F01, F02
    def import_dataset(self, paths: list[str | Path], describe: bool = True) -> SchemaInfo:
        source = self._sources.create(paths)
        source.connect()
        schema = self._reader.read(source)
        key = "|".join(str(Path(p).resolve()) for p in paths)
        self._descriptions.apply(key, schema)
        self._describer.errors = []
        missing = [t.name for t in schema.tables if not t.description]
        if describe and missing:
            self._describer.describe(schema, missing)
            self._descriptions.save_all(key, schema)
        if self._embedder is None:
            self._embedder = default_embedding_provider(self.settings.ollama_url)
        index = SchemaIndex(self._embedder)
        index.build(schema)
        if self.session is not None:
            self.session.close()
        self.dataset_key = key
        self.session = Session(data_source=source, schema=schema, paths=key.split("|"), index=index,
                               memory=ConversationMemory(self.settings.memory_turns))
        return schema

    @property
    def describe_errors(self) -> list[str]:
        return list(self._describer.errors)

    @property
    def index_warning(self) -> str:
        return self.session.index.warning if self.session and self.session.index else ""

    def require_session(self) -> Session:
        if self.session is None:
            raise NoDatasetError("No dataset is loaded. Import a SQLite or CSV file first.")
        return self.session

    def update_description(self, entry_id: str, text: str) -> None:
        session = self.require_session()
        table_name, _, column_name = entry_id.partition(".")
        table = session.schema.table(table_name)
        if table is None:
            raise DatasetError(f"No table named '{table_name}'.")
        if column_name:
            column = table.column(column_name)
            if column is None:
                raise DatasetError(f"Table {table.name} has no column '{column_name}'.")
            column.description = text
            entry_id = f"{table.name}.{column.name}"
            types = f" {column.type}"
        else:
            table.description = text
            entry_id = table.name
            types = ""
        self._descriptions.save_edit(self.dataset_key, entry_id, text)
        if session.index is not None:
            if column_name:
                session.index.update_entry(entry_id, f"{entry_id}{types} - {text}")
            else:
                session.index.build(session.schema)

    # F03, F04, F06, F09, F10
    def ask(self, question: str, interactive: bool = True) -> AgentAnswer:
        session = self.require_session()
        answer = self.orchestrator.run(question, session, interactive)
        return self._record(answer)

    def answer_clarification(self, choice: str) -> AgentAnswer:
        session = self.require_session()
        answer = self.orchestrator.resume(choice, session)
        return self._record(answer)

    def has_pending_clarification(self) -> bool:
        return self.session is not None and self.session.has_pending_clarification()

    def reset_conversation(self) -> None:
        session = self.require_session()
        session.memory.clear()
        session.pending_state = None

    # F05, F07, F08
    def run_manual_sql(self, sql: str, question: str = "") -> AgentAnswer:
        session = self.require_session()
        label = question or sql
        check = self._validator.validate(sql)
        if not check.ok:
            answer = AgentAnswer(status=AnswerStatus.REFUSED, question=label, sql=sql, manual=True,
                                 message=f"Query blocked by {check.rule}: {check.reason}")
            return self._record(answer)
        try:
            result = self._executor.run(check.sql, session.data_source)
        except QueryError as exc:
            return self._record(AgentAnswer(status=AnswerStatus.FAILED, question=label, sql=sql, manual=True,
                                            message=str(exc)))
        result.sql = sql.strip().rstrip(";")
        session.last_result = result
        if result.is_empty():
            answer = AgentAnswer(status=AnswerStatus.NO_DATA, question=label, sql=result.sql, result=result,
                                 manual=True, message="The query returned no rows.")
        else:
            chart = self._recommender.recommend(result).render(result)
            answer = AgentAnswer(status=AnswerStatus.ANSWERED, question=label, sql=result.sql, result=result,
                                 chart=chart, manual=True)
        return self._record(answer)

    def build_chart(self, result: QueryResult, chart_type: str) -> ChartProduct:
        return self._charts.create(result, chart_type)

    def chart_types_for(self, result: QueryResult) -> list[str]:
        return self._charts.fits(result)

    # F11
    def get_history(self, search: str = "") -> list[HistoryEntry]:
        return self._history.search(search) if search else self._history.recent()

    def save_question(self, entry_id: int, name: str) -> SavedQuestion:
        entry = self._history.get(entry_id)
        if entry is None:
            raise DatasetError(f"No history entry #{entry_id}.")
        if not entry.sql:
            raise DatasetError(f"History entry #{entry_id} has no SQL to save.")
        item = SavedQuestion(name=name.strip(), question=entry.question, sql=entry.sql, dataset=entry.dataset)
        if not item.name:
            raise DatasetError("Give the saved question a name.")
        self._saved.save(item)
        return item

    def get_saved_questions(self) -> list[SavedQuestion]:
        return self._saved.list()

    def rerun_history(self, entry_id: int) -> AgentAnswer:
        entry = self._history.get(entry_id)
        if entry is None:
            raise DatasetError(f"No history entry #{entry_id}.")
        if not entry.sql:
            raise DatasetError(f"History entry #{entry_id} has no SQL to rerun. Ask the question again instead.")
        return self.run_manual_sql(entry.sql, question=entry.question)

    def rerun_saved(self, name: str) -> AgentAnswer:
        item = self._saved.get(name)
        if item is None:
            raise DatasetError(f"No saved question named '{name}'.")
        return self.run_manual_sql(item.sql, question=item.question)

    def _record(self, answer: AgentAnswer) -> AgentAnswer:
        if answer.status == AnswerStatus.NEEDS_CLARIFICATION:
            return answer
        summary = answer.insight.text if answer.insight else answer.message
        entry = HistoryEntry(question=answer.question, sql=answer.sql, status=answer.status.value,
                             dataset=self.dataset_key, row_count=answer.result.row_count if answer.result else 0,
                             summary=summary or "", manual=answer.manual)
        answer.history_id = self._history.add(entry)
        return answer

    # F12
    def set_routing_policy(self, name: str) -> list[str]:
        """Installs the policy. Returns warnings for cloud tiers that have no key."""
        policy = policy_by_name(name)
        warnings = []
        if policy.allows_cloud:
            for tier, provider_name in TIER_PROVIDERS.items():
                if tier in self.router.providers:
                    continue
                try:
                    self.router.add_provider(tier, self._providers.create(provider_name))
                except ProviderUnavailableError as exc:
                    warnings.append(f"{tier} tier ({provider_name}) unavailable: {exc} Using the local model.")
        self.router.set_policy(policy)
        self.settings.policy = policy.name
        return warnings

    def set_share_samples(self, share: bool) -> None:
        self.settings.share_samples = share
        self._builder.share_samples = share
        for tool in self._sample_tools:
            tool.share_samples = share

    def provider_status(self) -> dict[str, str]:
        return {tier: f"{p.model_name()} ({'cloud' if p.is_cloud else 'local'})"
                for tier, p in self.router.providers.items()}

    def get_usage_summary(self) -> UsageSummary:
        return self.usage.summary(self.router.policy.name)
