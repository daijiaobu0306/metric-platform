package com.metric.mcp.service;

import com.metric.mcp.support.McpReadOnlyQuery;
import com.metric.mcp.support.McpRowMapper;
import com.metric.mcp.support.McpToolBuilder;
import com.metric.mcp.support.McpToolSupport;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问题 MCP 的三个只读工具。
 *
 * 字段收敛：剔除附件路径/附件名与后评估正文，问题描述与检查原因截断。
 */
@Component
public class IssueMcpTools {

    private static final String SERVER = McpServers.ISSUE;

    private static final String[] SEARCH_ALIASES = {
            "id", "issueCode", "issueType", "systemId", "systemName",
            "categoryId", "categoryName", "subCategoryName", "metricId", "metricName",
            "issueAttribute", "issueSource", "discoverer", "responsiblePerson",
            "resolveStatus", "registerTime", "planResolveTime", "resolveTime", "tagStatus"
    };

    private final McpToolBuilder builder;
    private final McpReadOnlyQuery query;

    public IssueMcpTools(McpToolBuilder builder, McpReadOnlyQuery query) {
        this.builder = builder;
        this.query = query;
    }

    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        return List.of(search(), detail(), summary());
    }

    // ------------------------------------------------------------------ 1. 问题查询

    private McpServerFeatures.SyncToolSpecification search() {
        return builder.tool(SERVER, "issue_search", "查询问题记录",
                "按关键词、解决状态、类型、来源、属性、系统和时间窗口查询问题，按登记时间倒序返回。"
                        + "关键词会匹配问题编码、问题描述、指标名、系统名与责任人。",
                McpToolBuilder.props(
                        "keyword", McpToolBuilder.prop("string", "关键词：问题编码/描述/指标名/系统名/责任人"),
                        "resolveStatus", McpToolBuilder.enumProp("解决状态", List.of("未解决", "已解决")),
                        "issueType", McpToolBuilder.prop("string", "问题类型"),
                        "issueSource", McpToolBuilder.prop("string", "问题来源，如 告警转问题 / 手工登记"),
                        "issueAttribute", McpToolBuilder.prop("string", "问题属性"),
                        "systemId", McpToolBuilder.prop("integer", "系统 ID"),
                        "days", McpToolBuilder.prop("integer", "按登记时间查近 N 天，默认 7，上限 180"),
                        "limit", McpToolBuilder.prop("integer", "返回条数，默认 20，上限 50"),
                        "offset", McpToolBuilder.prop("integer", "偏移量，默认 0")),
                List.of(),
                (exchange, args) -> {
                    McpToolSupport.TimeWindow window = McpToolSupport.timeWindow(args);
                    McpToolSupport.Page page = McpToolSupport.window(args);

                    StringBuilder sql = new StringBuilder("""
                            SELECT id, issue_code, issue_type, system_id, system_name,
                                   category_id, category_name, sub_category_name, metric_id, metric_name,
                                   issue_attribute, issue_source, discoverer, responsible_person,
                                   resolve_status, register_time, plan_resolve_time, resolve_time, tag_status
                            FROM issue
                            WHERE register_time >= :start AND register_time <= :end
                            """);
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("start", window.startParam());
                    params.put("end", window.endParam());

                    String keyword = McpToolSupport.str(args, "keyword");
                    String resolveStatus = McpToolSupport.str(args, "resolveStatus");
                    String issueType = McpToolSupport.str(args, "issueType");
                    String issueSource = McpToolSupport.str(args, "issueSource");
                    String issueAttribute = McpToolSupport.str(args, "issueAttribute");
                    Long systemId = McpToolSupport.longVal(args, "systemId");

                    if (keyword != null) {
                        sql.append(" AND (issue_code LIKE :kw OR issue_description LIKE :kw")
                                .append(" OR metric_name LIKE :kw OR system_name LIKE :kw")
                                .append(" OR responsible_person LIKE :kw)");
                        params.put("kw", "%" + keyword + "%");
                    }
                    if (resolveStatus != null) {
                        sql.append(" AND resolve_status = :resolveStatus");
                        params.put("resolveStatus", resolveStatus);
                    }
                    if (issueType != null) {
                        sql.append(" AND issue_type = :issueType");
                        params.put("issueType", issueType);
                    }
                    if (issueSource != null) {
                        sql.append(" AND issue_source = :issueSource");
                        params.put("issueSource", issueSource);
                    }
                    if (issueAttribute != null) {
                        sql.append(" AND issue_attribute = :issueAttribute");
                        params.put("issueAttribute", issueAttribute);
                    }
                    if (systemId != null) {
                        sql.append(" AND system_id = :systemId");
                        params.put("systemId", systemId);
                    }
                    sql.append(" ORDER BY register_time DESC")
                            .append(" LIMIT ").append(page.limit() + 1)
                            .append(" OFFSET ").append(page.offset());

                    List<Object[]> rows = query.rows(sql.toString(), params);
                    boolean hasMore = rows.size() > page.limit();
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (int i = 0; i < rows.size() && i < page.limit(); i++) {
                        items.add(McpRowMapper.map(SEARCH_ALIASES, rows.get(i)));
                    }

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("timeWindow", Map.of(
                            "days", window.days(),
                            "start", window.start().toString(),
                            "end", window.end().toString()));
                    payload.put("returned", items.size());
                    payload.put("offset", page.offset());
                    payload.put("hasMore", hasMore);
                    payload.put("items", items);
                    return new McpToolSupport.ToolOutcome(payload, items.size());
                });
    }

    // ------------------------------------------------------------------ 2. 问题详情

    private McpServerFeatures.SyncToolSpecification detail() {
        return builder.tool(SERVER, "issue_get", "问题详情",
                "按内部 ID 或问题编码查询单条问题，含问题描述（截断）、检查原因（截断）与解决情况。",
                McpToolBuilder.props(
                        "id", McpToolBuilder.prop("integer", "问题内部 ID"),
                        "issueCode", McpToolBuilder.prop("string", "问题编码")),
                List.of(),
                (exchange, args) -> {
                    Long id = McpToolSupport.longVal(args, "id");
                    String issueCode = McpToolSupport.str(args, "issueCode");
                    if (id == null && issueCode == null) {
                        throw new IllegalArgumentException("id 与 issueCode 至少提供一个");
                    }

                    StringBuilder sql = new StringBuilder("""
                            SELECT id, issue_code, issue_type, system_id, system_name,
                                   category_id, category_name, sub_category_name, metric_id, metric_name,
                                   issue_attribute, issue_source, discoverer, responsible_person,
                                   resolve_status, register_time, plan_resolve_time, resolve_time, tag_status,
                                   LEFT(issue_description, 600), LEFT(check_reason, 400)
                            FROM issue WHERE
                            """);
                    Map<String, Object> params = new LinkedHashMap<>();
                    if (id != null) {
                        sql.append(" id = :id");
                        params.put("id", id);
                    } else {
                        sql.append(" issue_code = :issueCode");
                        params.put("issueCode", issueCode);
                    }

                    List<Object[]> rows = query.rows(sql.toString(), params);
                    if (rows.isEmpty()) {
                        throw new IllegalArgumentException("未找到对应问题");
                    }
                    Object[] row = rows.get(0);
                    Map<String, Object> item = McpRowMapper.map(SEARCH_ALIASES, row);
                    item.put("issueDescription", McpRowMapper.normalize(row[19]));
                    item.put("checkReason", McpRowMapper.normalize(row[20]));
                    return new McpToolSupport.ToolOutcome(item, 1);
                });
    }

    // ------------------------------------------------------------------ 3. 问题统计

    private McpServerFeatures.SyncToolSpecification summary() {
        return builder.tool(SERVER, "issue_summary", "问题维度统计",
                "按维度聚合问题数量，并给出总量、已解决/未解决分布和超期未解决数量。"
                        + "超期口径：计划解决时间已过且仍未解决。",
                McpToolBuilder.props(
                        "days", McpToolBuilder.prop("integer", "按登记时间统计近 N 天，默认 30，上限 180"),
                        "groupBy", McpToolBuilder.enumProp("聚合维度，默认 resolveStatus",
                                List.of("resolveStatus", "type", "source", "attribute", "system", "category"))),
                List.of(),
                (exchange, args) -> {
                    Map<String, Object> normalized = new LinkedHashMap<>(args);
                    if (McpToolSupport.intVal(args, "days") == null) {
                        normalized.put("days", 30);
                    }
                    McpToolSupport.TimeWindow window = McpToolSupport.timeWindow(normalized);
                    String groupBy = McpToolSupport.str(args, "groupBy");
                    if (groupBy == null) {
                        groupBy = "resolveStatus";
                    }
                    String groupColumn = switch (groupBy) {
                        case "resolveStatus" -> "resolve_status";
                        case "type" -> "issue_type";
                        case "source" -> "issue_source";
                        case "attribute" -> "issue_attribute";
                        case "system" -> "system_name";
                        case "category" -> "category_name";
                        default -> throw new IllegalArgumentException(
                                "groupBy 仅支持 resolveStatus / type / source / attribute / system / category");
                    };

                    StringBuilder base = new StringBuilder(
                            " FROM issue WHERE register_time >= :start AND register_time <= :end");
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("start", window.startParam());
                    params.put("end", window.endParam());

                    long total = query.scalar("SELECT COUNT(*)" + base, params);
                    long resolved = query.scalar(
                            "SELECT COUNT(*)" + base + " AND resolve_status = '已解决'", params);
                    long overdue = query.scalar(
                            "SELECT COUNT(*)" + base
                                    + " AND resolve_status <> '已解决' AND plan_resolve_time IS NOT NULL"
                                    + " AND plan_resolve_time < NOW()", params);

                    List<Map<String, Object>> groups = new ArrayList<>();
                    for (Object[] row : query.rows(
                            "SELECT " + groupColumn + ", COUNT(*)" + base
                                    + " GROUP BY " + groupColumn + " ORDER BY COUNT(*) DESC LIMIT 20", params)) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", row[0] == null ? "未填写" : row[0]);
                        item.put("count", row[1]);
                        groups.add(item);
                    }

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("timeWindow", Map.of(
                            "days", window.days(),
                            "start", window.start().toString(),
                            "end", window.end().toString()));
                    payload.put("total", total);
                    payload.put("resolved", resolved);
                    // 注意：这里的 notResolved 含「处理中」，与 groups 按状态拆分的结果可交叉核对
                    payload.put("notResolved", total - resolved);
                    payload.put("overdueUnresolved", overdue);
                    payload.put("groupBy", groupBy);
                    payload.put("groups", groups);
                    return new McpToolSupport.ToolOutcome(payload, groups.size());
                });
    }
}
