package ca.yorku.askdb.safety;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReadOnlyRule extends SqlRuleDecorator {
    private static final List<String> WRITE_KEYWORDS = List.of(
            "INSERT", "UPDATE", "DELETE", "REPLACE INTO", "UPSERT", "DROP", "CREATE", "ALTER",
            "TRUNCATE", "VACUUM", "REINDEX", "ANALYZE", "GRANT", "REVOKE", "MERGE");
    private static final Pattern FIRST_WORD = Pattern.compile("^[A-Za-z]+");

    public ReadOnlyRule(SqlRule wrapped) {
        super(wrapped);
    }

    @Override
    public String name() {
        return "read_only";
    }

    @Override
    protected ValidationResult apply(String sql) {
        String bare = SqlRule.stripLiteralsAndComments(sql).strip();
        Matcher first = FIRST_WORD.matcher(bare);
        if (!first.find() || !List.of("SELECT", "WITH").contains(first.group().toUpperCase(Locale.ROOT))) {
            return ValidationResult.failed(sql, name(), "Only SELECT queries are allowed; the data is read only.");
        }
        for (String word : WRITE_KEYWORDS) {
            Pattern p = Pattern.compile("\\b" + word.replace(" ", "\\s+") + "\\b", Pattern.CASE_INSENSITIVE);
            if (p.matcher(bare).find()) {
                return ValidationResult.failed(sql, name(), word + " is not allowed; the data is read only.");
            }
        }
        return ValidationResult.passed(sql);
    }
}
