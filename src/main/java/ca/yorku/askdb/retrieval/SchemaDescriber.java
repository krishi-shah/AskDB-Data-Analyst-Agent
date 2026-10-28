package ca.yorku.askdb.retrieval;

import ca.yorku.askdb.agent.ParseException;
import ca.yorku.askdb.agent.PromptBuilder;
import ca.yorku.askdb.agent.ResponseParser;
import ca.yorku.askdb.data.ColumnInfo;
import ca.yorku.askdb.data.SchemaInfo;
import ca.yorku.askdb.data.TableInfo;
import ca.yorku.askdb.llm.LLMException;
import ca.yorku.askdb.llm.LLMResponse;
import ca.yorku.askdb.llm.ModelRouter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Asks the model for plain-English table and column descriptions (one call per table). */
public class SchemaDescriber {
    private final ModelRouter router;
    private final PromptBuilder builder;
    private final ResponseParser parser;
    private final List<String> errors = new ArrayList<>();

    public SchemaDescriber(ModelRouter router, PromptBuilder builder, ResponseParser parser) {
        this.router = router;
        this.builder = builder;
        this.parser = parser;
    }

    public List<String> getErrors() {
        return List.copyOf(errors);
    }

    /** Describes every table, or only the named ones (those without a cached description). */
    public SchemaInfo describe(SchemaInfo schema, Collection<String> tables) {
        errors.clear();
        for (TableInfo table : schema.getTables()) {
            if (tables != null && !tables.contains(table.getName())) {
                continue;
            }
            List<ResponseParser.TableDescription> parsed;
            try {
                LLMResponse response = router.complete(builder.buildDescriptionPrompt(schema, table.getName()),
                        "describe", 1, 600);
                parsed = parser.parseDescriptions(response.text());
            } catch (LLMException | ParseException e) {
                errors.add(table.getName() + ": " + e.getMessage());
                continue;
            }
            for (ResponseParser.TableDescription item : parsed) {
                if (!item.name().equalsIgnoreCase(table.getName())) {
                    continue;
                }
                table.setDescription(item.description());
                for (Map.Entry<String, String> e : item.columns().entrySet()) {
                    ColumnInfo column = table.column(e.getKey());
                    if (column != null) {
                        column.setDescription(e.getValue());
                    }
                }
            }
        }
        return schema;
    }
}
