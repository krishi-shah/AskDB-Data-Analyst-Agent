package ca.yorku.askdb.safety;

/** Concrete component: the innermost rule of the chain. Accepts whatever reached it. */
public class PassThroughRule implements SqlRule {
    @Override
    public String name() {
        return "pass";
    }

    @Override
    public ValidationResult check(String sql) {
        return ValidationResult.passed(sql);
    }
}
