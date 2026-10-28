package ca.yorku.askdb.safety;

import java.util.List;
import java.util.regex.Pattern;

public class ForbiddenObjectRule extends SqlRuleDecorator {
    private static final List<String> FORBIDDEN = List.of(
            "PRAGMA", "ATTACH", "DETACH", "load_extension", "sqlite_master", "sqlite_schema",
            "sqlite_temp_master", "readfile", "writefile", "fts3_tokenizer");

    public ForbiddenObjectRule(SqlRule wrapped) {
        super(wrapped);
    }

    @Override
    public String name() {
        return "forbidden_object";
    }

    @Override
    protected ValidationResult apply(String sql) {
        String bare = SqlRule.stripLiteralsAndComments(sql);
        for (String word : FORBIDDEN) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE);
            if (p.matcher(bare).find()) {
                return ValidationResult.failed(sql, name(), word + " cannot be used in a query.");
            }
        }
        return ValidationResult.passed(sql);
    }
}
