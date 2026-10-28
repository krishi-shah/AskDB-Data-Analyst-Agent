package ca.yorku.askdb.safety;

public class SingleStatementRule extends SqlRuleDecorator {
    public SingleStatementRule(SqlRule wrapped) {
        super(wrapped);
    }

    @Override
    public String name() {
        return "single_statement";
    }

    @Override
    protected ValidationResult apply(String sql) {
        String text = sql.strip().replaceAll(";+\\s*$", "").strip();
        if (text.isEmpty()) {
            return ValidationResult.failed(sql, name(), "The SQL statement is empty.");
        }
        if (SqlRule.stripLiteralsAndComments(text).contains(";")) {
            return ValidationResult.failed(sql, name(), "Only one SQL statement can run at a time.");
        }
        return ValidationResult.passed(text);
    }
}
