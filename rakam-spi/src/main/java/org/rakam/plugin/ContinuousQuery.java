package org.rakam.plugin;

import com.facebook.presto.sql.parser.ParsingException;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.QueryBody;
import com.facebook.presto.sql.tree.QuerySpecification;
import com.facebook.presto.sql.tree.Table;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import org.rakam.server.http.annotations.ApiParam;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Created by buremba <Burak Emre Kabakcı> on 08/03/15 00:17.
 */
public class ContinuousQuery {
    private final static SqlParser SQL_PARSER = new SqlParser();
    @ApiParam(name = "project", required = true)
    public final String project;
    @ApiParam(name = "name", value="The name of the continuous query", required = true)
    public final String name;
    @ApiParam(name = "query", value="The sql query that will be executed and materialized", required = true)
    public final String query;
    @ApiParam(name = "table_name", value="The table name of the continuous query that can be used when querying", required = true)
    public final String tableName;
    @ApiParam(name = "collections", value="The source collections that will be streamed", required = true)
    public final List<String> collections;
    @ApiParam(name = "options", value="Additional information about the continuous query", required = false)
    public final Map<String, Object> options;

    private transient Instant last_update;

    @JsonCreator
    public ContinuousQuery(@JsonProperty("project") String project,
                           @JsonProperty("name") String name,
                           @JsonProperty("table_name") String tableName,
                           @JsonProperty("query") String query,
                           @JsonProperty("collections") List<String> collections,
                           @JsonProperty("options") Map<String, Object> options)
            throws ParsingException, IllegalArgumentException {
        this.project = checkNotNull(project, "project is required");
        this.name = checkNotNull(name, "name is required");
        this.query = checkNotNull(query, "query is required");
        this.tableName = checkNotNull(tableName, "table_name is required");
        this.collections = collections;
        this.options = options;
        validateQuery(query);
    }

    public void validateQuery(String query) throws ParsingException, IllegalArgumentException {
        Query statement = (Query) SQL_PARSER.createStatement(query);

        QueryBody queryBody = statement.getQueryBody();
        // it's ugly and seems complex but actually can be expressed simply by naming variables and
        // checking the conditions and throwing the exception it they're not satisfied.
        // but since i can use the throw statement only once (otherwise, i would have to make the sting final static etc.),
        // i couldn't find a better way.
        if(!(queryBody instanceof QuerySpecification) ||
                !((QuerySpecification) queryBody).getFrom().isPresent() ||
                !(((QuerySpecification) queryBody).getFrom().get() instanceof Table) ||
                !((Table) ((QuerySpecification) queryBody).getFrom().get()).getName().getParts().equals(ImmutableList.of("stream"))) {
            throw new IllegalArgumentException("The FROM part of the query must be 'stream' for continuous queries. " +
                    "Example: 'SELECT count(1) FROM stream GROUP BY country''");
        }

        if (statement.getLimit().isPresent()) {
            throw new IllegalArgumentException("Continuous queries must not have LIMIT statement");
        }
    }

    public String getTableName() {
        return "_continuous_"+tableName;
    }

    public synchronized void setLastUpdate(Instant last_update) {
        this.last_update = last_update;
    }

    public Instant lastUpdate() {
        return last_update;
    }
}