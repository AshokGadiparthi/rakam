package org.rakam.postgresql.analysis;

import com.google.common.base.Throwables;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.postgresql.util.PGobject;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.collection.Event;
import org.rakam.collection.FieldDependencyBuilder;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.plugin.EventStore;
import org.rakam.util.JsonHelper;

import javax.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.IntStream;

@Singleton
public class PostgresqlEventStore implements EventStore {
    private final Set<String> sourceFields;
    private final JDBCPoolDataSource connectionPool;
    public static final Calendar UTC_CALENDAR = Calendar.getInstance(TimeZone.getTimeZone(ZoneId.of("UTC")));

    @Inject
    public PostgresqlEventStore(@Named("store.adapter.postgresql") JDBCPoolDataSource connectionPool, FieldDependencyBuilder.FieldDependency fieldDependency) {
        this.connectionPool = connectionPool;
        this.sourceFields = fieldDependency.dependentFields.keySet();
    }

    @Override
    public void store(org.rakam.collection.Event event) {
        GenericRecord record = event.properties();
        try (Connection connection = connectionPool.getConnection()) {
            PreparedStatement ps = connection.prepareStatement(getQuery(event));
            bindParam(connection, ps, event.schema(), record);
            ps.executeUpdate();
        } catch (SQLException e) {
            Throwables.propagate(e);
        }
    }

    @Override
    public int[] storeBatch(List<Event> events) {
        try (Connection connection = connectionPool.getConnection()) {
            connection.setAutoCommit(false);
            // last event must have the last schema
            Event lastEvent = events.get(events.size() - 1);
            PreparedStatement ps = connection.prepareStatement(getQuery(lastEvent));

            for (int i = 0; i < events.size(); i++) {
                Event event = events.get(i);
                bindParam(connection, ps, event.schema(), event.properties());
                ps.addBatch();
                if(i > 0 && i % 1000 == 0) {
                    ps.executeBatch();
                }
            }

            ps.executeBatch();

            connection.commit();
            connection.setAutoCommit(true);
            return EventStore.SUCCESSFUL_BATCH;
        } catch (SQLException e) {
            Throwables.propagate(e);
            return IntStream.range(0, events.size()).toArray();
        }
    }

    private void bindParam(Connection connection, PreparedStatement ps, List<SchemaField> fields, GenericRecord record) throws SQLException {
        Object value;
        for (int i = 0; i < fields.size(); i++) {
            SchemaField field = fields.get(i);
            value = record.get(field.getName());

            if (value == null) {
                ps.setNull(i + 1, 0);
                continue;
            }

            FieldType type = field.getType();
            switch (type) {
                case STRING:
                    ps.setString(i + 1, (String) value);
                    break;
                case LONG:
                    ps.setLong(i + 1, ((Number) value).longValue());
                case INTEGER:
                    ps.setInt(i + 1, ((Number) value).intValue());
                case DECIMAL:
                    ps.setBigDecimal(i + 1, new BigDecimal(((Number) value).doubleValue()));
                    break;
                case DOUBLE:
                    ps.setDouble(i + 1, ((Number) value).doubleValue());
                    break;
                case TIMESTAMP:
                    ps.setTimestamp(i + 1, new Timestamp(((Number) value).longValue()), UTC_CALENDAR);
                    break;
                case TIME:
                    ps.setTime(i + 1, Time.valueOf(LocalTime.ofSecondOfDay(((Number) value).intValue())), UTC_CALENDAR);
                    break;
                case DATE:
                    ps.setDate(i + 1, Date.valueOf(LocalDate.ofEpochDay(((Number) value).intValue())), UTC_CALENDAR);
                    break;
                case BOOLEAN:
                    ps.setBoolean(i + 1, (Boolean) value);
                    break;
                default:
                    if (type.isArray()) {
                        String typeName = toPostgresqlPrimitiveTypeName(type.getArrayElementType());
                        ps.setArray(i + 1, connection.createArrayOf(typeName, ((List) value).toArray()));
                    } else if (type.isMap()) {
                        PGobject jsonObject = new PGobject();
                        jsonObject.setType("jsonb");
                        jsonObject.setValue(JsonHelper.encode(value));
                        ps.setObject(i + 1, jsonObject);
                    } else {
                        throw new UnsupportedOperationException();
                    }
            }
        }
    }

    private String getQuery(Event event) {
        // since we don't cache queries, we should care about performance so we just use StringBuilder instead of streams.
        // String columns = schema.getFields().stream().map(Schema.Field::name).collect(Collectors.joining(", "));
        // String parameters = schema.getFields().stream().map(f -> "?").collect(Collectors.joining(", "));
        StringBuilder query = new StringBuilder("INSERT INTO ")
                .append(event.project())
                .append(".")
                .append(event.collection());
        StringBuilder params = new StringBuilder();
        Schema schema = event.properties().getSchema();
        List<Schema.Field> fields = schema.getFields();

        Schema.Field f = fields.get(0);
        if (!sourceFields.contains(f.name())) {
            query.append(" (\"").append(f.name());
            params.append('?');
        }

        for (int i = 1; i < fields.size(); i++) {
            Schema.Field field = fields.get(i);

            if (!sourceFields.contains(field.name())) {
                query.append("\", \"").append(field.name());
                params.append(", ?");
            }
        }

        return query.append("\") VALUES (").append(params.toString()).append(")").toString();
    }

    public static String toPostgresqlPrimitiveTypeName(FieldType type) {
        switch (type) {
            case LONG:
                return "int8";
            case INTEGER:
                return "int4";
            case DECIMAL:
                return "decimal";
            case STRING:
                return "text";
            case BOOLEAN:
                return "bool";
            case DATE:
                return "date";
            case TIME:
                return "time";
            case TIMESTAMP:
                return "timestamp";
            case DOUBLE:
                return "float8";
            default:
                throw new IllegalStateException("sql type couldn't converted to fieldtype");
        }
    }
}
