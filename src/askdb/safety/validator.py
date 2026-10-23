from __future__ import annotations

from askdb.safety.rules import (
    ForbiddenObjectRule,
    PassThroughRule,
    ReadOnlyRule,
    RowLimitRule,
    SingleStatementRule,
    SqlRule,
    ValidationResult,
)


class SqlValidator:
    """Builds the decorator chain SingleStatement -> ReadOnly -> ForbiddenObject -> RowLimit."""

    def __init__(self, max_rows: int = 1000) -> None:
        self._rule: SqlRule = SingleStatementRule(
            ReadOnlyRule(ForbiddenObjectRule(RowLimitRule(PassThroughRule(), max_rows)))
        )

    def validate(self, sql: str) -> ValidationResult:
        return self._rule.check(sql)
