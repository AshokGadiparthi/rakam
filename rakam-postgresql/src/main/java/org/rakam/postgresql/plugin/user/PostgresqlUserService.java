package org.rakam.postgresql.plugin.user;

import com.facebook.presto.sql.tree.QualifiedName;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rakam.analysis.ContinuousQueryService;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.plugin.user.AbstractUserService;
import org.rakam.plugin.user.UserStorage;
import org.rakam.postgresql.report.PostgresqlQueryExecutor;
import org.rakam.report.QueryExecution;
import org.rakam.report.QueryResult;
import org.rakam.util.JsonHelper;
import org.rakam.util.RakamException;

import javax.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static org.rakam.util.ValidationUtil.checkProject;

public class PostgresqlUserService extends AbstractUserService {
    private final Metastore metastore;
    private final PostgresqlQueryExecutor executor;
    private final ContinuousQueryService continuousQueryService;

    @Inject
    public PostgresqlUserService(UserStorage storage, ContinuousQueryService continuousQueryService, Metastore metastore, PostgresqlQueryExecutor executor) {
        super(storage);
        this.continuousQueryService = continuousQueryService;
        this.metastore = metastore;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<List<CollectionEvent>> getEvents(String project, String user, int limit, Instant beforeThisTime) {
        checkProject(project);
        checkNotNull(user);
        checkArgument(limit <= 1000, "Maximum 1000 events can be fetched at once.");
        String sqlQuery = metastore.getCollections(project).entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(field -> field.getName().equals("_user")))
                .filter(entry -> entry.getValue().stream().anyMatch(field -> field.getName().equals("_time")))
                .map(entry ->
                        format("select '%s' as collection, row_to_json(coll) json, _time from \"%s\".\"%s\" coll where _user = '%s' %s",
                                entry.getKey(), project, entry.getKey(), user, beforeThisTime == null ? "" : String.format("and _time < timestamp '%s'", beforeThisTime.toString())))
                .collect(Collectors.joining(" union all "));

        if (sqlQuery.isEmpty()) {
            return CompletableFuture.completedFuture(ImmutableList.<CollectionEvent>of());
        }

        CompletableFuture<QueryResult> queryResult = executor.executeRawQuery(format("select collection, json from (%s) data order by _time desc limit %d", sqlQuery, limit)).getResult();
        return queryResult.thenApply(result -> {
            if (result.isFailed()) {
                throw new RakamException(result.getError().toString(), HttpResponseStatus.INTERNAL_SERVER_ERROR);
            }

            List<CollectionEvent> events = new ArrayList(result.getResult().size());
            for (List<Object> objects : result.getResult()) {
                events.add(new CollectionEvent((String) objects.get(0), JsonHelper.read(objects.get(1).toString(), Map.class)));
            }

            return events;
        });
    }

    @Override
    public void merge(String project, String user, String anonymousId, Instant createdAt, Instant mergedAt) {
        try (Connection connection = executor.getConnection()) {
            for (String collectionName : metastore.getCollectionNames(project)) {
                PreparedStatement ps = connection.prepareStatement(String.format("UPDATE %s SET _user = ? WHERE _user = ? WHERE _time BETWEEN ? and ?",
                        executor.formatTableReference(project, QualifiedName.of(collectionName))));
                ps.setString(1, user);
                ps.setString(2, anonymousId);
                ps.setTimestamp(3, Timestamp.from(createdAt));
                ps.setTimestamp(4, Timestamp.from(createdAt));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public QueryExecution precalculate(String project, PreCalculateQuery query) {
        // no-op
        return QueryExecution.completedQueryExecution(null, QueryResult.empty());
    }
}
