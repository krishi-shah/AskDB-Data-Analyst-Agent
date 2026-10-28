package ca.yorku.askdb.safety;

public class RowLimitRule extends SqlRuleDecorator {
    private final int maxRows;

    public RowLimitRule(SqlRule wrapped, int maxRows) {
        super(wrapped);
        this.maxRows = maxRows;
    }

    @Override
    public String name() {
        return "row_limit";
    }

    @Override
    protected ValidationResult apply(String sql) {
        return ValidationResult.passed("SELECT * FROM (" + sql + ") LIMIT " + maxRows);
    }
}
