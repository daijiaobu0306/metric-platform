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
 * 工单 MCP 的三个只读工具。
 *
 * 字段收敛：剔除 pushed_title / pushed_content（内部推送快照）与 external_sync_msg 全文，
 * 正文类字段截断，避免把整篇工单正文塞进模型上下文。
 */
@Component
public class GongdanMcpTools {

    private static final String SERVER = McpServers.GONGDAN;
    private static final Map<Integer, String> LEVEL_NAMES = Map.of(2, "一般", 3, "重要", 4, "严重");

    private static final String[] SEARCH_ALIASES = {
            "id", "gongdanId", "gongdanTitle", "gongdanType",
            "systemId", "categoryId", "metricId", "alarmObject",
            "alarmLevel", "alarmPriority", "alarmImpact", "alarmCount",
            "alarmFirstTime", "alarmLastTime",
            "currentStep", "currentHandler", "responsiblePerson", "secondResponsible", "executeUser",
            "closedStatus", "closedTime", "gongdanTime",
            "externalId", "externalStatus", "synced", "bomcAction", "bomcActionReason"
    };

    private final McpToolBuilder builder;
    private final McpReadOnlyQuery query;

    public GongdanMcpTools(McpToolBuilder builder, McpReadOnlyQuery query) {
        this.builder = builder;
        this.query = query;
    }

    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        return List.of(search(), detail(), summary());
    }

    // ------------------------------------------------------------------ 1. 工单查询

    private McpServerFeatures.SyncToolSpecification search() {
        return builder.tool(SERVER, "gongdan_search", "查询工单记录",
                "按关键词、处理状态、等级、时间窗口查询工单，按工单时间倒序返回。"
                        + "关键词会匹配工单号、标题、内容、告警对象和当前处理人。"
                        + "回答「BOMC 推送失败的单」「本周未关闭的工单」这类问题用本工具。",
                McpToolBuilder.props(
                        "keyword", McpToolBuilder.prop("string", "关键词：工单号/标题/内容/告警对象/处理人"),
                        "closedStatus", McpToolBuilder.prop("integer", "关闭状态：0=未关闭 1=已关闭"),
                        "alarmLevel", McpToolBuilder.prop("integer", "告警等级：2=一般 3=重要 4=严重"),
                        "externalStatus", McpToolBuilder.prop("string", "BOMC 状态，如 已推送 / 推送失败"),
                        "days", McpToolBuilder.prop("integer", "查询近 N 天（按工单时间），默认 7，上限 180"),
                        "limit", McpToolBuilder.prop("integer", "返回条数，默认 20，上限 50"),
                        "offset", McpToolBuilder.prop("integer", "偏移量，默认 0")),
                List.of(),
                (exchange, args) -> {
                    McpToolSupport.TimeWindow window = McpToolSupport.timeWindow(args);
                    McpToolSupport.Page page = McpToolSupport.window(args);

                    StringBuilder sql = new StringBuilder("""
                            SELECT id, gongdan_id, gongdan_title, gongdan_type,
                                   system_id, category_id, metric_id, alarm_object,
                                   alarm_level, alarm_priority, alarm_impact, alarm_count,
                                   alarm_first_time, alarm_last_time,
                                   current_step, current_handler, responsible_person, second_responsible, execute_user,
                                   closed_status, closed_time, gongdan_time,
                                   external_id, external_status, synced, bomc_action, bomc_action_reason
                            FROM gongdan
                            WHERE gongdan_time >= :start AND gongdan_time <= :end
                            """);
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("start", window.startParam());
                    params.put("end", window.endParam());

                    String keyword = McpToolSupport.str(args, "keyword");
                    Integer closedStatus = McpToolSupport.intVal(args, "closedStatus");
                    Integer level = McpToolSupport.intVal(args, "alarmLevel");
                    String externalStatus = McpToolSupport.str(args, "externalStatus");

                    if (keyword != null) {
                        sql.append(" AND (gongdan_id LIKE :kw OR gongdan_title LIKE :kw")
                                .append(" OR gongdan_content LIKE :kw OR alarm_object LIKE :kw")
                                .append(" OR current_handler LIKE :kw)");
                        params.put("kw", "%" + keyword + "%");
                    }
                    if (closedStatus != null) {
                        sql.append(" AND closed_status = :closedStatus");
                        params.put("closedStatus", closedStatus);
                    }
                    if (level != null) {
                        sql.append(" AND alarm_level = :level");
                        params.put("level", level);
                    }
                    if (externalStatus != null) {
                        sql.append(" AND external_status LIKE :externalStatus");
                        params.put("externalStatus", "%" + externalStatus + "%");
                    }
                    sql.append(" ORDER BY gongdan_time DESC")
                            .append(" LIMIT ").append(page.limit() + 1)
                            .append(" OFFSET ").append(page.offset());

                    List<Object[]> rows = query.rows(sql.toString(), params);
                    boolean hasMore = rows.size() > page.limit();
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (int i = 0; i < rows.size() && i < page.limit(); i++) {
                        Object[] row = rows.get(i);
                        Map<String, Object> item = McpRowMapper.map(SEARCH_ALIASES, row);
                        item.put("alarmLevelName", McpRowMapper.nameOf(LEVEL_NAMES, toInt(row[8]), "未定级"));
                        item.put("closedStatusName", toInt(row[19]) != null && toInt(row[19]) == 1 ? "已关闭" : "未关闭");
                        items.add(item);
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

    // ------------------------------------------------------------------ 2. 工单详情

    private McpServerFeatures.SyncToolSpecification detail() {
        return builder.tool(SERVER, "gongdan_get", "工单详情",
                "按内部 ID 或工单号查询单条工单的完整信息，含工单正文（截断）、BOMC 推送状态与处理环节。",
                McpToolBuilder.props(
                        "id", McpToolBuilder.prop("integer", "工单内部 ID，与 gongdan_search 返回的 id 一致"),
                        "gongdanId", McpToolBuilder.prop("string", "工单号，形如 GD20261119xxxx")),
                List.of(),
                (exchange, args) -> {
                    Long id = McpToolSupport.longVal(args, "id");
                    String gongdanId = McpToolSupport.str(args, "gongdanId");
                    if (id == null && gongdanId == null) {
                        throw new IllegalArgumentException("id 与 gongdanId 至少提供一个");
                    }

                    StringBuilder sql = new StringBuilder("""
                            SELECT id, gongdan_id, gongdan_title, gongdan_type,
                                   system_id, category_id, metric_id, alarm_object,
                                   alarm_level, alarm_priority, alarm_impact, alarm_count,
                                   alarm_first_time, alarm_last_time,
                                   current_step, current_handler, responsible_person, second_responsible, execute_user,
                                   closed_status, closed_time, gongdan_time,
                                   external_id, external_status, synced, bomc_action, bomc_action_reason,
                                   LEFT(gongdan_content, 600), LEFT(external_sync_msg, 200)
                            FROM gongdan WHERE
                            """);
                    Map<String, Object> params = new LinkedHashMap<>();
                    if (id != null) {
                        sql.append(" id = :id");
                        params.put("id", id);
                    } else {
                        sql.append(" gongdan_id = :gongdanId");
                        params.put("gongdanId", gongdanId);
                    }

                    List<Object[]> rows = query.rows(sql.toString(), params);
                    if (rows.isEmpty()) {
                        throw new IllegalArgumentException("未找到对应工单");
                    }
                    Object[] row = rows.get(0);
                    Map<String, Object> item = McpRowMapper.map(SEARCH_ALIASES, row);
                    item.put("alarmLevelName", McpRowMapper.nameOf(LEVEL_NAMES, toInt(row[8]), "未定级"));
                    item.put("closedStatusName", toInt(row[19]) != null && toInt(row[19]) == 1 ? "已关闭" : "未关闭");
                    item.put("gongdanContent", McpRowMapper.normalize(row[27]));
                    item.put("externalSyncMsg", McpRowMapper.normalize(row[28]));
                    return new McpToolSupport.ToolOutcome(item, 1);
                });
    }

    // ------------------------------------------------------------------ 3. 工单统计

    private McpServerFeatures.SyncToolSpecification summary() {
        return builder.tool(SERVER, "gongdan_summary", "工单维度统计",
                "按维度聚合工单数量，并给出总量、关闭情况、等级分布和已关闭工单的平均闭环时长。"
                        + "适合回答「哪个环节积压最多」「平均多久关单」。",
                McpToolBuilder.props(
                        "days", McpToolBuilder.prop("integer", "统计近 N 天，默认 7，上限 180"),
                        "groupBy", McpToolBuilder.enumProp("聚合维度，默认 closedStatus",
                                List.of("closedStatus", "type", "level", "step", "handler", "externalStatus"))),
                List.of(),
                (exchange, args) -> {
                    McpToolSupport.TimeWindow window = McpToolSupport.timeWindow(args);
                    String groupBy = McpToolSupport.str(args, "groupBy");
                    if (groupBy == null) {
                        groupBy = "closedStatus";
                    }
                    String groupColumn = switch (groupBy) {
                        case "closedStatus" -> "closed_status";
                        case "type" -> "gongdan_type";
                        case "level" -> "alarm_level";
                        case "step" -> "current_step";
                        case "handler" -> "current_handler";
                        case "externalStatus" -> "external_status";
                        default -> throw new IllegalArgumentException(
                                "groupBy 仅支持 closedStatus / type / level / step / handler / externalStatus");
                    };

                    StringBuilder base = new StringBuilder(
                            " FROM gongdan WHERE gongdan_time >= :start AND gongdan_time <= :end");
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("start", window.startParam());
                    params.put("end", window.endParam());

                    long total = query.scalar("SELECT COUNT(*)" + base, params);

                    List<Map<String, Object>> groups = new ArrayList<>();
                    for (Object[] row : query.rows(
                            "SELECT " + groupColumn + ", COUNT(*)" + base
                                    + " GROUP BY " + groupColumn + " ORDER BY COUNT(*) DESC LIMIT 20", params)) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", labelOf(groupBy, row[0]));
                        item.put("count", row[1]);
                        groups.add(item);
                    }

                    long closed = query.scalar(
                            "SELECT COUNT(*)" + base + " AND closed_status = 1", params);
                    long open = total - closed;

                    // 平均闭环时长（分钟）：只统计两端时间都齐全的已关闭工单
                    Long avgMinutes = null;
                    Double avg = query.nullableScalar(
                            "SELECT AVG(TIMESTAMPDIFF(MINUTE, gongdan_time, closed_time))" + base
                                    + " AND closed_status = 1 AND closed_time IS NOT NULL", params);
                    if (avg != null) {
                        avgMinutes = Math.round(avg);
                    }

                    List<Map<String, Object>> byLevel = new ArrayList<>();
                    for (Object[] row : query.rows(
                            "SELECT alarm_level, COUNT(*)" + base
                                    + " GROUP BY alarm_level ORDER BY alarm_level", params)) {
                        Integer level = toInt(row[0]);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("level", level);
                        item.put("name", McpRowMapper.nameOf(LEVEL_NAMES, level, "未定级"));
                        item.put("count", row[1]);
                        byLevel.add(item);
                    }

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("timeWindow", Map.of(
                            "days", window.days(),
                            "start", window.start().toString(),
                            "end", window.end().toString()));
                    payload.put("total", total);
                    payload.put("closed", closed);
                    payload.put("open", open);
                    payload.put("avgCloseMinutes", avgMinutes);
                    payload.put("avgCloseHours", avgMinutes == null ? null
                            : Math.round(avgMinutes / 6.0) / 10.0);
                    payload.put("groupBy", groupBy);
                    payload.put("groups", groups);
                    payload.put("byLevel", byLevel);
                    return new McpToolSupport.ToolOutcome(payload, groups.size());
                });
    }

    private Object labelOf(String groupBy, Object raw) {
        if (raw == null) {
            return "未填写";
        }
        return switch (groupBy) {
            case "closedStatus" -> toInt(raw) != null && toInt(raw) == 1 ? "已关闭" : "未关闭";
            case "level" -> McpRowMapper.nameOf(LEVEL_NAMES, toInt(raw), "未定级");
            default -> raw;
        };
    }

    private static Integer toInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
