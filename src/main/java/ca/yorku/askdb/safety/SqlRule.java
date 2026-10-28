package ca.yorku.askdb.safety;

/** Component of the Decorator pattern: checks one SQL statement. */
public interface SqlRule {
    String name();

    ValidationResult check(String sql);

    /** Blanks out comments, string literals, and quoted identifiers so keywords inside them are ignored. */
    static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if (sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                i = end == -1 ? n : end;
                out.append(' ');
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end == -1 ? n : end + 2;
                out.append(' ');
            } else if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                char close = ch == '[' ? ']' : ch;
                int j = i + 1;
                while (j < n) {
                    if (sql.charAt(j) == close) {
                        if (close != ']' && j + 1 < n && sql.charAt(j + 1) == close) {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                out.append(ch == '\'' ? "''" : "\"\"");
                i = j + 1;
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }
}
