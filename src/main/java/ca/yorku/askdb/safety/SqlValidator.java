package ca.yorku.askdb.safety;

/** Builds the decorator chain SingleStatement -> ReadOnly -> ForbiddenObject -> RowLimit -> PassThrough. */
public class SqlValidator {
    private final SqlRule rule;

    public SqlValidator(int maxRows) {
        rule = new SingleStatementRule(
                new ReadOnlyRule(new ForbiddenObjectRule(new RowLimitRule(new PassThroughRule(), maxRows))));
    }

    public ValidationResult validate(String sql) {
        return rule.check(sql == null ? "" : sql);
    }
}
