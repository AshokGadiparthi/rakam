package org.rakam.plugin;

import com.facebook.presto.sql.parser.ParsingException;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.Query;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.rakam.server.http.annotations.ApiParam;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;


public class ContinuousQuery {
    @JsonIgnore
    private final static SqlParser SQL_PARSER = new SqlParser();

    public final String name;
    public final String query;
    @JsonIgnore
    public Query queryStatement;
    public final String tableName;
    public final List<String> partitionKeys;
    public final Map<String, Object> options;

    @JsonCreator
    public ContinuousQuery(@ApiParam(value = "name", description="The name of the continuous query") String name,
                           @ApiParam(value = "table_name", description="The table name of the continuous query that can be used when querying") String tableName,
                           @ApiParam(value = "query", description="The sql query that will be executed and materialized") String query,
                           @ApiParam(value = "partition_keys", description="Partition columns of the table", required = false) List<String> partitionKeys,
                           @ApiParam(value = "options", description="Additional information about the continuous query", required = false) Map<String, Object> options)
            throws ParsingException, IllegalArgumentException {
        this.name = checkNotNull(name, "name is required");
        this.tableName = checkNotNull(tableName, "table_name is required");
        this.options = options == null ? ImmutableMap.of() : options;
        this.partitionKeys = partitionKeys == null ? ImmutableList.of() : partitionKeys;
        this.query = query;
    }

    @JsonIgnore
    public synchronized Query getQuery() {
        if(queryStatement == null) {
            queryStatement = (Query) SQL_PARSER.createStatement(checkNotNull(query, "query is required"));
        }
        return queryStatement;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContinuousQuery)) return false;

        ContinuousQuery that = (ContinuousQuery) o;

        if (name != null ? !name.equals(that.name) : that.name != null) return false;
        if (!query.equals(that.query)) return false;
        if (!tableName.equals(that.tableName)) return false;
        if (partitionKeys != null ? !partitionKeys.equals(that.partitionKeys) : that.partitionKeys != null)
            return false;
        return !(options != null ? !options.equals(that.options) : that.options != null);

    }

    @Override
    public int hashCode() {
        int result = query.hashCode();
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + tableName.hashCode();
        result = 31 * result + (partitionKeys != null ? partitionKeys.hashCode() : 0);
        result = 31 * result + (options != null ? options.hashCode() : 0);
        return result;
    }
}