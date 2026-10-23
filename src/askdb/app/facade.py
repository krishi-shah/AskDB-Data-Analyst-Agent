"""AskDBFacade: the single entry point used by the GUI and the CLI (Facade pattern)."""

from __future__ import annotations

from pathlib import Path

from askdb.agent.models import AgentAnswer, AnswerStatus
from askdb.app.session import Session
from askdb.config import Settings, load_settings
from askdb.data.errors import DatasetError, QueryError
from askdb.data.executor import QueryExecutor
from askdb.data.factory import DataSourceFactory
from askdb.data.models import SchemaInfo
from askdb.data.schema_reader import SchemaReader
from askdb.safety.validator import SqlValidator


class NoDatasetError(DatasetError):
    pass


class AskDBFacade:
    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or load_settings()
        self.settings.ensure_home()
        self.session: Session | None = None
        self._sources = DataSourceFactory(self.settings.max_file_mb)
        self._reader = SchemaReader()
        self._validator = SqlValidator(self.settings.row_limit)
        self._executor = QueryExecutor(self.settings.query_timeout_s, self.settings.row_limit)

    def import_dataset(self, paths: list[str | Path]) -> SchemaInfo:
        source = self._sources.create(paths)
        source.connect()
        schema = self._reader.read(source)
        if self.session is not None:
            self.session.close()
        self.session = Session(data_source=source, schema=schema, paths=[str(Path(p).resolve()) for p in paths])
        return schema

    def require_session(self) -> Session:
        if self.session is None:
            raise NoDatasetError("No dataset is loaded. Import a SQLite or CSV file first.")
        return self.session

    def run_manual_sql(self, sql: str) -> AgentAnswer:
        session = self.require_session()
        check = self._validator.validate(sql)
        if not check.ok:
            return AgentAnswer(status=AnswerStatus.REFUSED, question=sql, sql=sql, manual=True,
                               message=f"Query blocked by {check.rule}: {check.reason}")
        try:
            result = self._executor.run(check.sql, session.data_source)
        except QueryError as exc:
            return AgentAnswer(status=AnswerStatus.FAILED, question=sql, sql=sql, manual=True, message=str(exc))
        status = AnswerStatus.NO_DATA if result.is_empty() else AnswerStatus.ANSWERED
        result.sql = sql
        return AgentAnswer(status=status, question=sql, sql=sql, result=result, manual=True,
                           message="The query returned no rows." if result.is_empty() else "")
