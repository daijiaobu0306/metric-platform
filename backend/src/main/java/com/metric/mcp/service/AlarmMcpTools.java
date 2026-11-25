package com.metric.mcp.service;

import com.metric.mcp.support.McpReadOnlyQuery;
import com.metric.mcp.support.McpRowMapper;
import com.metric.mcp.support.McpToolBuilder;
import com.metric.mcp.support.McpToolSupport;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警 MCP 的四个只读工具。
 *
 * 为什么第一个工具是「系统/大类字典」：用户会问「BOMC 系统最近有哪些告警」，
 * 模型并不知道 BOMC 对应哪个 system_id，也不知道「性能问题」落在哪个大类。
 * 没有字典工具，模型第一步就卡住或瞎猜 ID。
 */
@Component
public class AlarmMcpTools {

    private static final String SERVER = McpServers.ALARM;

    /** 告警等级：2=一般 3=重要 4=严重（与前端 strategyRules.js 一致，1 未使用）。 */
    private static final Map<Integer, String> LEVEL_NAMES = Map.of(2, "一般", 3, "重要", 4, "严重");
    /** 处理状态：0=未处理 1=已处理 2=已关闭 3=工单记录。 */
    private static final Map<Integer, String> PROCESS_NAMES =
            Map.of(0, "未处理", 1, "已处理", 2, "已关闭", 3, "工单记录");

    /**
     * 与 search() 里 SELECT 的列严格一一对应（21 列）。
     * 注意：等级名与处理状态名不在这里，映射后单独补充，避免列错位。
     */
    private static final String[] SEARCH_ALIASES = {
            "id", "alarmTime", "alarmLevel", "alarmType",
            "systemId", "systemName", "categoryId", "categoryName", "subCategoryName",
            "metricId", "metricName", "metricValue", "thresholdValue", "unit",
            "processStatus", "shieldStatus", "tagStatus",
            "responsiblePerson", "relatedGongdanId", "relatedIssueId", "alarmContent"
    };

    private final McpToolBuilder builder;
    private final McpReadOnlyQuery query;

    public AlarmMcpTools(McpToolBuilder builder, McpReadOnlyQuery query) {
        this.builder = builder;
        this.query = query;
    }

    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        return List.of(listSystems(), listCategories(), search(), summary());
    }

    // ------------------------------------------------------------------ 1. 系统字典

    private McpServerFeatures.SyncToolSpecification listSystems() {
        return builder.tool(SERVER, "alarm_list_systems", "系统清单",
                "列出平台纳管的系统（id + 名称 + 编码）。当用户用系统简称提问（如「BOMC 系统」）时，"
                        + "先用本工具把名称解析成 systemId，再调用 alarm_search。",
                McpToolBuilder.props(
                        "keyword", McpToolBuilder.prop("string", "按系统名称/编码模糊匹配，可省略")),
                List.of(),
                (exchange, args) -> {
                    String keyword = McpToolSupport.str(args, "keyword");
                    StringBuilder sql = new StringBuilder(
                            "SELECT id, system_name, system_code, status FROM system_info WHERE 1=1");
                    Map<String, Object> params = new LinkedHashMap<>();
                    if (keyword != null) {
                        sql.append(" AND (system_name LIKE :kw OR system_code LIKE :kw)");
                        params.put("kw", "%" + keyword + "%");
                    }
                    sql.append(" ORDER BY id");

                    List<Map<String, Object>> items = new ArrayList<>();
                    for (Object[] row : query.rows(sql.toString(), params)) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("systemId", row[0]);
                        item.put("systemName", row[1]);
                        item.put("systemCode", row[2]);
                        item.put("enabled", McpRowMapper.yesNo(toInt(row[3])));
                        items.add(item);
                    }
                    return new McpToolSupport.ToolOutcome(
                            Map.of("total", items.size(), "systems", items), items.size());
                });
    }

    // ------------------------------------------------------------------ 2. 大类/小类字典

    private McpServerFeatures.SyncToolSpecification listCategories() {
        return builder.tool(SERVER, "alarm_list_categories", "指标大类与小类清单",
                "列出指标大类及其下属小类，并给出各小类的指标数量。用于把「性能问题」「满意度」等口语"
                        + "映射成 categoryId 或小类名称，再调用 alarm_search / alarm_summary。",
                McpToolBuilder.props(
                        "systemId", McpToolBuilder.prop("integer", "只看某个系统下的大类，可省略")),
                List.of(),
                (exchange, args) -> {
                    Long systemId = McpToolSupport.longVal(args, "systemId");
                    StringBuilder sql = new StringBuilder("""
                            SELECT c.id, c.category_name, c.system_id, i.sub_category_name, COUNT(i.id)
                            FROM metric_category c
                            LEFT JOIN metric_item i ON i.category_id = c.id
                            WHERE 1=1
                            """);
                    Map<String, Object> params = new LinkedHashMap<>();
                    if (systemId != null) {
                        sql.append(" AND c.system_id = :systemId");
                        params.put("systemId", systemId);
                    }
                    sql.append(" GROUP BY c.id, c.category_name, c.system_id, i.sub_category_name")
                            .append(" ORDER BY c.id, i.sub_category_name");

                    // 按大类聚合，避免模型在一堆重复行里自己对
                    Map<Long, Map<String, Object>> grouped = new LinkedHashMap<>();
                    for (Object[] row : query.rows(sql.toString(), params)) {
                        Long categoryId = ((Number) row[0]).longValue();
                        Map<String, Object> category = grouped.computeIfAbsent(categoryId, key -> {
                            Map<String, Object> created = new LinkedHashMap<>();
                            created.put("categoryId", key);
                            created.put("categoryName", row[1]);
                            created.put("systemId", row[2]);
                            created.put("subCategories", new ArrayList<Map<String, Object>>());
                            return created;
                        });
                        if (row[3] != null) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> subs = (List<Map<String, Object>>) category.get("subCategories");
                            Map<String, Object> sub = new LinkedHashMap<>();
                            sub.put("name", row[3]);
                            sub.put("metricCount", row[4]);
                            subs.add(sub);
                        }
                    }
                    List<Map<String, Object>> items = new ArrayList<>(grouped.values());
                    return new McpToolSupport.ToolOutcome(
                            Map.of("total", items.size(), "categories", items), items.size());
                });
    }

    // ------------------------------------------------------------------ 3. 告警查询

    private McpServerFeatures.SyncToolSpecification search() {
        return builder.tool(SERVER, "alarm_search", "查询告警记录",
                "按系统、大类、级别、处理状态、关键词和时间窗口查询告警，按告警时间倒序返回。"
                        + "关键词会在系统名、大类、小类、指标名和告警内容中模糊匹配。"
                        + "默认查近 7 天，单次最多 50 条；返回 hasMore=true 时请缩小时间范围或翻页。",
                McpToolBuilder.props(
                        "systemId", McpToolBuilder.prop("integer", "系统 ID，来自 alarm_list_systems"),
                        "categoryId", McpToolBuilder.prop("integer", "指标大类 ID，来自 alarm_list_categories"),
                        "keyword", McpToolBuilder.prop("string", "关键词：系统名/大类/小类/指标名/告警内容"),
                        "alarmLevel", McpToolBuilder.prop("integer", "告警等级：2=一般 3=重要 4=严重"),
                        "alarmType", McpToolBuilder.prop("string", "告警类型，如 指标值告警 / 健康度告警"),
                        "processStatus", McpToolBuilder.prop("integer", "处理状态：0=未处理 1=已处理 2=已关闭 3=工单记录"),
                        "days", McpToolBuilder.prop("integer", "查询近 N 天，默认 7，上限 180"),
                        "limit", McpToolBuilder.prop("integer", "返回条数，默认 20，上限 50"),
                        "offset", McpToolBuilder.prop("integer", "偏移量，用于翻页，默认 0")),
                List.of(),
                (exchange, args) -> {
                    McpToolSupport.TimeWindow window = McpToolSupport.timeWindow(args);
                    McpToolSupport.Page page = McpToolSupport.window(args);

                    StringBuilder sql = new StringBuilder("""
                            SELECT a.id, a.alarm_time, a.alarm_level, a.alarm_type,
                                   a.system_id, COALESCE(NULLIF(a.system_name, ''), s.system_name) AS system_name,
                                   a.category_id, a.category_name, a.sub_category_name,
                                   a.metric_id, a.metric_name, a.metric_value, a.threshold_value, a.unit,
                                   a.process_status, a.shield_status, a.tag_status,
                                   a.responsible_person, a.related_gongdan_id, a.related_issue_id,
                                   LEFT(a.alarm_content, 200)
                            FROM alarm a
                            LEFT JOIN system_info s ON s.id = a.system_id
                            WHERE a.alarm_time >= :start AND a.alarm_time <= :end
                            """);
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("start", window.startParam());
                    params.put("end", window.endParam());

                    Long systemId = McpToolSupport.longVal(args, "systemId");
                    Long categoryId = McpToolSupport.longVal(args, "categoryId");
                    Integer level = McpToolSupport.intVal(args, "alarmLevel");
                    Integer processStatus = McpToolSupport.intVal(args, "processStatus");
                    String alarmType = McpToolSupport.str(args, "alarmType");
                    String keyword = McpToolSupport.str(args, "keyword");

                    if (systemId != null) {
                        sql.append(" AND a.system_id = :systemId");
                        params.put("systemId", systemId);
                    }
                    if (categoryId != null) {
                        sql.append(" AND a.category_id = :categoryId");
                        params.put("categoryId", categoryId);
                    }
                    if (level != null) {
                        sql.append(" AND a.alarm_level = :level");
                        params.put("level", level);
                    }
                    if (processStatus != null) {
                        sql.append(" AND a.process_status = :processStatus");
                        params.put("processStatus", processStatus);
                    }
                    if (alarmType != null) {
                        sql.append(" AND a.alarm_type = :alarmType");
                        params.put("alarmType", alarmType);
                    }
                    if (keyword != null) {
                        sql.append(" AND (a.system_name LIKE :kw OR s.system_name LIKE :kw OR a.category_name LIKE :kw")
                                .append(" OR a.sub_category_name LIKE :kw OR a.metric_name LIKE :kw")
                                .append(" OR a.alarm_content LIKE :kw)");
                        params.put("kw", "%" + keyword + "%");
                    }
                    sql.append(" ORDER BY a.alarm_time DESC")
                            .append(" LIMIT ").append(page.limit() + 1)
                            .append(" OFFSET ").append(page.offset());

                    List<Object[]> rows = query.rows(sql.toString(), params);
                    boolean hasMore = rows.size() > page.limit();
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (int i = 0; i < rows.size() && i < page.limit(); i++) {
                        Map<String, Object> item = McpRowMapper.map(SEARCH_ALIASES, rows.get(i));
                        // 部分列在别名数组之外做了可读性补充
                        item.put("alarmLevelName", McpRowMapper.nameOf(LEVEL_NAMES, toInt(rows.get(i)[2]), "未定级"));
                        item.put("processStatusName", McpRowMapper.nameOf(PROCESS_NAMES, toInt(rows.get(i)[14]), "未知"));
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

    // ------------------------------------------------------------------ 4. 告警统计

    private McpServerFeatures.SyncToolSpecification summary() {
        return builder.tool(SERVER, "alarm_summary", "告警维度统计",
                "按维度聚合告警数量，并同时给出总量、等级分布和处理状态分布。"
                        + "适合回答「哪个系统告警最多」「最近严重告警有多少」这类问题，"
                        + "比拉明细再自己数更省 token。",
                McpToolBuilder.props(
                        "systemId", McpToolBuilder.prop("integer", "只看某个系统"),
                        "categoryId", McpToolBuilder.prop("integer", "只看某个指标大类"),
                        "days", McpToolBuilder.prop("integer", "统计近 N 天，默认 7，上限 180"),
                        "groupBy", McpToolBuilder.enumProp("聚合维度，默认 system",
                                List.of("system", "category", "subCategory", "level", "type", "processStatus"))),
                List.of(),
                (exchange, args) -> {
                    McpToolSupport.TimeWindow window = McpToolSupport.timeWindow(args);
                    String groupBy = McpToolSupport.str(args, "groupBy");
                    if (groupBy == null) {
                        groupBy = "system";
                    }
                    String groupColumn = switch (groupBy) {
                        case "system" -> "COALESCE(NULLIF(a.system_name, ''), s.system_name)";
                        case "category" -> "category_name";
                        case "subCategory" -> "sub_category_name";
                        case "level" -> "alarm_level";
                        case "type" -> "alarm_type";
                        case "processStatus" -> "process_status";
                        default -> throw new IllegalArgumentException(
                                "groupBy 仅支持 system / category / subCategory / level / type / processStatus");
                    };

                    StringBuilder base = new StringBuilder(
                            " FROM alarm a LEFT JOIN system_info s ON s.id = a.system_id"
                                    + " WHERE a.alarm_time >= :start AND a.alarm_time <= :end");
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("start", window.startParam());
                    params.put("end", window.endParam());
                    Long systemId = McpToolSupport.longVal(args, "systemId");
                    Long categoryId = McpToolSupport.longVal(args, "categoryId");
                    if (systemId != null) {
                        base.append(" AND a.system_id = :systemId");
                        params.put("systemId", systemId);
                    }
                    if (categoryId != null) {
                        base.append(" AND a.category_id = :categoryId");
                        params.put("categoryId", categoryId);
                    }

                    long total = query.scalar("SELECT COUNT(*)" + base, params);

                    List<Map<String, Object>> groups = new ArrayList<>();
                    List<Object[]> groupRows = query.rows(
                            "SELECT " + groupColumn + ", COUNT(*)" + base
                                    + " GROUP BY " + groupColumn + " ORDER BY COUNT(*) DESC LIMIT 20", params);
                    for (Object[] row : groupRows) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", labelOf(groupBy, row[0]));
                        item.put("count", row[1]);
                        groups.add(item);
                    }

                    List<Map<String, Object>> byLevel = new ArrayList<>();
                    for (Object[] row : query.rows(
                            "SELECT alarm_level, COUNT(*)" + base + " GROUP BY alarm_level ORDER BY alarm_level", params)) {
                        Integer level = toInt(row[0]);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("level", level);
                        item.put("name", McpRowMapper.nameOf(LEVEL_NAMES, level, "未定级"));
                        item.put("count", row[1]);
                        byLevel.add(item);
                    }

                    List<Map<String, Object>> byProcess = new ArrayList<>();
                    for (Object[] row : query.rows(
                            "SELECT process_status, COUNT(*)" + base
                                    + " GROUP BY process_status ORDER BY process_status", params)) {
                        Integer status = toInt(row[0]);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("processStatus", status);
                        item.put("name", McpRowMapper.nameOf(PROCESS_NAMES, status, "未知"));
                        item.put("count", row[1]);
                        byProcess.add(item);
                    }

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("timeWindow", Map.of(
                            "days", window.days(),
                            "start", window.start().toString(),
                            "end", window.end().toString()));
                    payload.put("total", total);
                    payload.put("groupBy", groupBy);
                    payload.put("groups", groups);
                    payload.put("byLevel", byLevel);
                    payload.put("byProcessStatus", byProcess);
                    return new McpToolSupport.ToolOutcome(payload, groups.size());
                });
    }

    private Object labelOf(String groupBy, Object raw) {
        return switch (groupBy) {
            case "level" -> McpRowMapper.nameOf(LEVEL_NAMES, toInt(raw), "未定级");
            case "processStatus" -> McpRowMapper.nameOf(PROCESS_NAMES, toInt(raw), "未知");
            // 历史告警里大量记录没有 system_id（原始数据告警），如实标注而不是塞进某个系统
            case "system" -> raw == null ? "未归属系统" : raw;
            default -> raw == null ? "未填写" : raw;
        };
    }

    private static Integer toInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
