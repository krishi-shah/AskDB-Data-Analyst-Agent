package ca.yorku.askdb.safety;

/** Decorator: applies its own check, then delegates to the wrapped rule. The first failure stops the chain. */
public abstract class SqlRuleDecorator implements SqlRule {
    private final SqlRule wrapped;

    protected SqlRuleDecorator(SqlRule wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public final ValidationResult check(String sql) {
        ValidationResult own = apply(sql);
        if (!own.ok()) {
            return own;
        }
        return wrapped.check(own.sql());
    }

    protected abstract ValidationResult apply(String sql);
}
