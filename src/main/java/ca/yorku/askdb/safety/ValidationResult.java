package ca.yorku.askdb.safety;

public record ValidationResult(boolean ok, String sql, String reason, String rule) {

    public static ValidationResult passed(String sql) {
        return new ValidationResult(true, sql, "", "");
    }

    public static ValidationResult failed(String sql, String rule, String reason) {
        return new ValidationResult(false, sql, reason, rule);
    }
}
