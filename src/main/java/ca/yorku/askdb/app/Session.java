package ca.yorku.askdb.app;

import ca.yorku.askdb.agent.AgentState;
import ca.yorku.askdb.agent.ConversationMemory;
import ca.yorku.askdb.data.DataSource;
import ca.yorku.askdb.data.QueryResult;
import ca.yorku.askdb.data.SchemaInfo;
import ca.yorku.askdb.retrieval.SchemaIndex;
import java.util.List;

/** State of the loaded dataset and the current conversation. */
public class Session {
    private final DataSource dataSource;
    private final SchemaInfo schema;
    private final List<String> paths;
    private final SchemaIndex index;
    private final ConversationMemory memory;
    private AgentState pendingState;
    private QueryResult lastResult;

    public Session(DataSource dataSource, SchemaInfo schema, List<String> paths, SchemaIndex index,
                   ConversationMemory memory) {
        this.dataSource = dataSource;
        this.schema = schema;
        this.paths = List.copyOf(paths);
        this.index = index;
        this.memory = memory;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public SchemaInfo getSchema() {
        return schema;
    }

    public List<String> getPaths() {
        return paths;
    }

    public SchemaIndex getIndex() {
        return index;
    }

    public ConversationMemory getMemory() {
        return memory;
    }

    public AgentState getPendingState() {
        return pendingState;
    }

    public void setPendingState(AgentState pendingState) {
        this.pendingState = pendingState;
    }

    public QueryResult getLastResult() {
        return lastResult;
    }

    public void setLastResult(QueryResult lastResult) {
        this.lastResult = lastResult;
    }

    public boolean hasPendingClarification() {
        return pendingState != null;
    }

    public void close() {
        dataSource.close();
    }
}
