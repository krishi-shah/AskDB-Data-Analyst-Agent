package ca.yorku.askdb.agent.tools;

import ca.yorku.askdb.app.Session;
import ca.yorku.askdb.retrieval.SchemaEntry;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class SearchSchemaTool implements Tool {
    private static final Map<String, Param> PARAMS = Map.of(
            "query", new Param("string", true, "words to look for, e.g. 'revenue by month'"),
            "k", new Param("integer", false, "how many matches (default 6)"));

    @Override
    public String name() {
        return "search_schema";
    }

    @Override
    public String description() {
        return "Find the tables and columns most related to some words. Returns names, types and descriptions.";
    }

    @Override
    public Map<String, Param> parameters() {
        return ordered(PARAMS, "query", "k");
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Session session) {
        int k = Tool.intArg(args, "k", 6, 1, 15);
        if (session.getIndex() == null) {
            return ToolResult.success(session.getSchema().toText(), null);
        }
        List<SchemaEntry> hits = session.getIndex().search((String) args.get("query"), k);
        TreeSet<String> tables = hits.stream().map(SchemaEntry::getTable).collect(Collectors.toCollection(TreeSet::new));
        String text = "Matches:\n" + hits.stream().map(h -> "- " + h.getText()).collect(Collectors.joining("\n"))
                + "\n\nFull columns of matched tables:\n" + session.getSchema().toText(tables);
        return ToolResult.success(text, hits);
    }

    static Map<String, Param> ordered(Map<String, Param> params, String... keys) {
        Map<String, Param> out = new java.util.LinkedHashMap<>();
        for (String k : keys) {
            out.put(k, params.get(k));
        }
        return out;
    }
}
