package org.rakam.ui.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.plugin.MaterializedView;
import org.rakam.ui.DashboardService;
import org.rakam.ui.customreport.CustomReport;
import org.rakam.ui.page.CustomPageDatabase.Page;
import org.rakam.ui.report.Report;

import javax.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class UIRecipe {
    private final List<Report> reports;
    private final List<CustomReport> customReports;
    private final List<Page> customPages;
    private final List<DashboardBuilder> dashboards;

    @JsonCreator
    public UIRecipe(@JsonProperty("custom_reports") List<CustomReport> customReports,
                    @JsonProperty("custom_pages") List<Page> customPages,
                    @JsonProperty("dashboards") List<DashboardBuilder> dashboards,
                    @JsonProperty("reports") List<Report> reports) {
        this.customReports = customReports == null ? ImmutableList.of() : customReports;
        this.customPages = customPages == null ? ImmutableList.of() : customPages;
        this.reports = reports == null ? ImmutableList.of() : ImmutableList.copyOf(reports);
        this.dashboards = dashboards == null ? ImmutableList.of() : ImmutableList.copyOf(dashboards);
    }

    @JsonProperty("custom_pages")
    public List<Page> getCustomPages() {
        return customPages;
    }

    @JsonProperty("custom_reports")
    public List<CustomReport> getCustomReports() {
        return customReports;
    }

    @JsonProperty("dashboards")
    public List<DashboardBuilder> getDashboards() {
        return dashboards;
    }

    @JsonProperty("reports")
    public List<Report> getReports() {
        return reports;
    }

    public static class Collection {
        public final List<Map<String, SchemaFieldInfo>> columns;

        @JsonCreator
        public Collection(@JsonProperty("columns") List<Map<String, SchemaFieldInfo>> columns) {
            this.columns = columns;
        }

        @JsonIgnore
        public List<SchemaField> build() {
            return columns.stream()
                    .map(column -> {
                        Map.Entry<String, SchemaFieldInfo> next = column.entrySet().iterator().next();
                        return new SchemaField(next.getKey(), next.getValue().type);
                    }).collect(Collectors.toList());
        }
    }

    public static class MaterializedViewBuilder {
        public final String name;
        public final String table_name;
        public final String query;
        public final boolean incremental;
        public final Duration updateInterval;

        @Inject
        public MaterializedViewBuilder(@JsonProperty("name") String name, @JsonProperty("table_name") String table_name, @JsonProperty("query") String query, @JsonProperty("update_interval") Duration updateInterval, @JsonProperty("incremental") Boolean incremental) {
            this.name = name;
            this.table_name = table_name;
            this.query = query;
            this.incremental = incremental;
            this.updateInterval = updateInterval;
        }

        public MaterializedView createMaterializedView(String project) {
            return new MaterializedView(name, table_name, query, updateInterval, incremental, ImmutableMap.of());
        }
    }

    public static class DashboardBuilder {
        public final String name;
        public final List<DashboardService.DashboardItem> items;

        @JsonCreator
        public DashboardBuilder(
                @JsonProperty("name") String name,
                @JsonProperty("items") List<DashboardService.DashboardItem> items) {
            this.name = name;
            this.items = items;
        }
    }

    public static class SchemaFieldInfo {
        public final String category;
        public final FieldType type;

        @JsonCreator
        public SchemaFieldInfo(@JsonProperty("category") String category,
                               @JsonProperty("type") FieldType type) {
            this.category = category;
            this.type = type;
        }
    }
}
