package com.metric.alarm.service;

import com.metric.alarm.dto.AlarmSummaryRequest;
import com.metric.alarm.entity.Alarm;
import com.metric.alarm.entity.AlarmStrategy;
import com.metric.alarm.entity.StrategyRule;
import com.metric.alarm.repository.AlarmStrategyRepository;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.service.MetricService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/** 只在查询时聚合，保留每次告警的原始值、阈值、时间及操作记录。 */
@Service
public class AlarmSummaryService {
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private AlarmStrategyRepository strategies;
    @Autowired private MetricService metrics;
    @Autowired private com.metric.metric.service.SystemService systems;
    @Autowired private AlarmService alarms;

    // 先按可验证ID定位对象；旧记录无ID时用完整历史身份快照隔离，不据名称关联正式策略。
    private static final String IDENTITY = """
        CASE
          WHEN a.alarm_type LIKE '%健康度%' AND s.target_id IS NOT NULL
            AND ((s.target_type='metric' AND s.target_id=a.metric_id)
              OR (s.target_type='category' AND s.target_id=a.category_id)
              OR (s.target_type='system' AND s.target_id=a.system_id)
              OR (s.target_type='subCategory' AND s.target_name=a.sub_category_name)) THEN JSON_ARRAY(s.target_type,s.target_id)
          WHEN a.metric_id IS NOT NULL THEN JSON_ARRAY('metric',a.metric_id)
          WHEN a.source_table IS NOT NULL THEN JSON_ARRAY('raw',a.source_table,a.strategy_id,a.metric_name,a.category_name,a.sub_category_name,a.business_name,a.step_name)
          WHEN s.target_id IS NOT NULL AND a.alarm_type NOT LIKE '%健康度%' THEN JSON_ARRAY(s.target_type,s.target_id)
          ELSE JSON_ARRAY('historical',a.system_id,a.category_id,a.system_name,a.category_name,a.sub_category_name,a.metric_name,a.strategy_id)
        END
        """;
    private static final String KEY = "SHA2(CAST(JSON_ARRAY(a.alarm_type," + IDENTITY + ") AS CHAR),256)";
    private static final String FROM = " FROM alarm a LEFT JOIN alarm_strategy s ON s.id=a.strategy_id ";
    private static final BeanPropertyRowMapper<Alarm> ALARM_MAPPER = new BeanPropertyRowMapper<>(Alarm.class);

    public record Threshold(Long strategyId, String strategyName, Integer level, String condition,
                            BigDecimal threshold, Integer consecutiveTimes, boolean enabled) {}
    public record StrategyChoice(Long id, String name, Integer status) {}
    public record Group(Alarm latest, String groupKey, long alarmCount, long unprocessedCount, long processedCount,
                        long ticketCount, long shieldedCount, LocalDateTime firstAlarmTime, LocalDateTime lastAlarmTime,
                        String targetType, Long targetId, String strategyType, String targetName, String thresholdUnit,
                        String currentValueUnit, String systemName, String categoryName,
                        List<Threshold> alarmRules, List<Threshold> gongdanRules, List<StrategyChoice> strategyChoices) {}
    private record Scope(String where, MapSqlParameterSource params, long snapshotId) {}
    private record GroupRow(Alarm latest, String key, long count, long pending, long processed, long tickets,
                            long shielded, LocalDateTime first, LocalDateTime last, String targetType, Long targetId, String targetName) {}

    /** 先对完整时间线分段，再应用筛选，避免隐藏正常记录后改变组边界。 */
    private String episodeCte(Scope scope) {
        return "WITH identities AS (SELECT a.*,s.target_type AS strategy_target_type,s.target_id AS strategy_target_id,"
                + "s.target_name AS strategy_target_name," + KEY + " AS object_key" + FROM + " WHERE a.id<=:snapshot),"
                + " ordered_events AS (SELECT identities.*,LAG(alarm_time) OVER(PARTITION BY object_key ORDER BY alarm_time,id) AS previous_time,"
                + "LAG(id) OVER(PARTITION BY object_key ORDER BY alarm_time,id) AS previous_id FROM identities),"
                + " boundaries AS (SELECT ordered_events.*,CASE WHEN previous_id IS NULL OR alarm_time IS NULL OR previous_time IS NULL"
                + " OR alarm_time>DATE_ADD(previous_time,INTERVAL 30 MINUTE) THEN 1 ELSE 0 END AS starts_episode FROM ordered_events),"
                + " sessions AS (SELECT boundaries.*,SUM(starts_episode) OVER(PARTITION BY object_key ORDER BY alarm_time,id ROWS UNBOUNDED PRECEDING) AS episode_number FROM boundaries),"
                + " anchored AS (SELECT sessions.*,FIRST_VALUE(id) OVER(PARTITION BY object_key,episode_number ORDER BY alarm_time,id) AS episode_anchor FROM sessions),"
                + " grouped AS (SELECT anchored.*,SHA2(CONCAT(object_key,':',episode_anchor),256) AS group_key FROM anchored),"
                + " filtered AS (SELECT a.* FROM grouped a" + scope.where() + ") ";
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(AlarmSummaryRequest request) {
        Scope scope = scope(request);
        String cte = episodeCte(scope) + ", ranked AS (SELECT filtered.*,ROW_NUMBER() OVER(PARTITION BY group_key ORDER BY alarm_time DESC,id DESC) AS rn FROM filtered),"
                + " totals AS (SELECT group_key,COUNT(*) AS alarm_count,SUM(process_status=0) AS pending_count,SUM(process_status=1) AS processed_count,"
                + "SUM(process_status=3) AS ticket_count,SUM(shield_status=1) AS shielded_count,MIN(alarm_time) AS first_alarm_time,MAX(alarm_time) AS last_alarm_time FROM filtered GROUP BY group_key) ";
        String sort = switch (Objects.toString(request.getSortBy(), "")) {
            case "alarmCount" -> "t.alarm_count"; case "firstAlarmTime" -> "t.first_alarm_time";
            default -> "r.alarm_time";
        };
        int size = Math.max(1, Math.min(500, request.getSize()));
        int page = Math.max(0, request.getPage());
        scope.params().addValue("limit", size).addValue("offset", (long) page * size);
        String sql = cte + "SELECT r.*,t.alarm_count,t.pending_count,t.processed_count,t.ticket_count,t.shielded_count,t.first_alarm_time,t.last_alarm_time "
                + "FROM ranked r JOIN totals t ON t.group_key=r.group_key WHERE r.rn=1 ORDER BY " + sort
                + ("asc".equalsIgnoreCase(request.getSortOrder()) ? " ASC" : " DESC") + ",r.id DESC LIMIT :limit OFFSET :offset";
        List<GroupRow> rows = jdbc.query(sql, scope.params(), (rs, index) -> new GroupRow(ALARM_MAPPER.mapRow(rs, index), rs.getString("group_key"),
                rs.getLong("alarm_count"), rs.getLong("pending_count"), rs.getLong("processed_count"), rs.getLong("ticket_count"), rs.getLong("shielded_count"),
                time(rs, "first_alarm_time"), time(rs, "last_alarm_time"), rs.getString("strategy_target_type"), rs.getObject("strategy_target_id", Long.class), rs.getString("strategy_target_name")));
        Map<String, Object> count = jdbc.queryForMap(episodeCte(scope) + "SELECT COUNT(DISTINCT group_key) AS groups_count,COUNT(*) AS events_count FROM filtered", scope.params());
        Map<Long, MetricItem> metricMap = new HashMap<>();
        metrics.findAllItems(Pageable.unpaged()).forEach(metric -> metricMap.put(metric.getId(), metric));
        Map<Long, com.metric.metric.entity.MetricCategory> categoryMap = new HashMap<>();
        metrics.findAllCategories().forEach(category -> categoryMap.put(category.getId(), category));
        Map<Long, String> systemNames = new HashMap<>();
        systems.findAll().forEach(system -> systemNames.put(system.getId(), system.getSystemName()));
        Map<String, List<AlarmStrategy>> strategyMap = new HashMap<>();
        for (AlarmStrategy strategy : strategies.findAll()) {
            strategyMap.computeIfAbsent(strategyKey(strategy.getStrategyType(), strategy.getTargetType(), strategy.getTargetId()), ignored -> new ArrayList<>()).add(strategy);
        }
        List<Group> groups = new ArrayList<>();
        for (GroupRow row : rows) {
            Alarm latest = row.latest();
            boolean health = latest.getAlarmType() != null && latest.getAlarmType().contains("健康度");
            boolean overdue = "问题逾期告警".equals(latest.getAlarmType());
            boolean raw = latest.getSourceTable() != null || (latest.getAlarmType() != null && latest.getAlarmType().contains("原始数据"));
            String type = overdue ? "issue" : health ? "health" : raw ? "raw" : "metric";
            boolean strategyMatches = row.targetId() != null && ("metric".equals(row.targetType()) && Objects.equals(row.targetId(), latest.getMetricId())
                    || "category".equals(row.targetType()) && Objects.equals(row.targetId(), latest.getCategoryId())
                    || "system".equals(row.targetType()) && Objects.equals(row.targetId(), latest.getSystemId())
                    || "subCategory".equals(row.targetType()) && latest.getSubCategoryName() != null && Objects.equals(row.targetName(), latest.getSubCategoryName()));
            String targetType = raw && "raw".equals(row.targetType()) ? "raw" : overdue ? null : health && strategyMatches ? row.targetType() : latest.getMetricId() != null ? "metric" : null;
            Long targetId = raw && "raw".equals(row.targetType()) ? row.targetId() : overdue ? null : health && strategyMatches ? row.targetId() : latest.getMetricId();
            MetricItem metric = metricMap.get(latest.getMetricId());
            String unit = overdue ? "天" : health ? "分" : metric == null || blank(metric.getUnit()) == null ? blank(latest.getUnit()) : blank(metric.getUnit());
            String valueUnit = overdue ? "天" : health ? "分" : blank(latest.getUnit()) != null ? blank(latest.getUnit()) : unit;
            Long categoryId = latest.getCategoryId() != null ? latest.getCategoryId() : metric == null ? null : metric.getCategoryId();
            var category = categoryMap.get(categoryId);
            Long systemId = latest.getSystemId() != null ? latest.getSystemId() : category == null ? null : category.getSystemId();
            String systemName = systemNames.getOrDefault(systemId, latest.getSystemName());
            String categoryName = category == null ? latest.getCategoryName() : category.getCategoryName();
            List<AlarmStrategy> choices = strategyMap.getOrDefault(strategyKey(type, targetType, targetId), List.of()).stream().sorted(Comparator.comparing(AlarmStrategy::getId)).toList();
            String targetName = metric != null && "metric".equals(targetType) ? metric.getMetricName()
                    : !choices.isEmpty() ? choices.get(0).getTargetName() : "system".equals(targetType) ? latest.getSystemName()
                    : "subCategory".equals(targetType) ? latest.getSubCategoryName() : "category".equals(targetType) ? latest.getCategoryName() : latest.getMetricName();
            groups.add(new Group(latest, row.key(), row.count(), row.pending(), row.processed(), row.tickets(), row.shielded(), row.first(), row.last(),
                    targetType, targetId, type, targetName, unit, valueUnit, systemName, categoryName, thresholds(choices, false), thresholds(choices, true),
                    choices.stream().map(s -> new StrategyChoice(s.getId(), s.getStrategyName(), s.getStatus())).toList()));
        }
        List<String> alarmTypes = jdbc.getJdbcTemplate().queryForList("SELECT DISTINCT alarm_type FROM alarm WHERE alarm_type IS NOT NULL AND TRIM(alarm_type)<>'' ORDER BY alarm_type", String.class);
        return Map.of("content", groups, "totalElements", count.get("groups_count"), "totalEvents", count.get("events_count"), "snapshotId", scope.snapshotId(), "alarmTypes", alarmTypes);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> history(AlarmSummaryRequest request) {
        requireKey(request.getGroupKey());
        if (request.getSnapshotId() == null) throw new IllegalArgumentException("缺少查询快照，请刷新列表");
        Scope scope = scope(request);
        scope.params().addValue("key", request.getGroupKey());
        String where = " FROM filtered a WHERE a.group_key=:key";
        int size = Math.max(1, Math.min(100, request.getSize()));
        scope.params().addValue("limit", size).addValue("offset", (long) Math.max(0, request.getPage()) * size);
        List<Alarm> content = jdbc.query(episodeCte(scope) + "SELECT a.*" + where + " ORDER BY a.alarm_time DESC,a.id DESC LIMIT :limit OFFSET :offset", scope.params(), ALARM_MAPPER);
        Map<String, Object> stats = jdbc.queryForMap(episodeCte(scope) + "SELECT COUNT(*) AS alarmCount,SUM(a.process_status=0) AS unprocessedCount,"
                + "SUM(a.process_status=1) AS processedCount,SUM(a.process_status=3) AS ticketCount,SUM(a.shield_status=1) AS shieldedCount,"
                + "MIN(a.alarm_time) AS firstAlarmTime,MAX(a.alarm_time) AS lastAlarmTime" + where, scope.params());
        for (String field : List.of("firstAlarmTime", "lastAlarmTime")) {
            if (stats.get(field) instanceof java.sql.Timestamp timestamp) stats.put(field, timestamp.toLocalDateTime());
        }
        return Map.of("content", content, "totalElements", stats.get("alarmCount"), "summary", stats);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> act(AlarmSummaryRequest request, String actor) {
        if (request.getSnapshotId() == null || request.getGroups() == null || request.getGroups().isEmpty() || request.getGroups().size() > 500)
            throw new IllegalArgumentException("请选择告警组，刷新列表后重试");
        if (!Set.of("process", "delete", "shield").contains(Objects.toString(request.getAction(), ""))) throw new IllegalArgumentException("不支持的操作");
        if ("process".equals(request.getAction()) && (request.getNewProcessStatus() == null || !Set.of(0, 1).contains(request.getNewProcessStatus()))) throw new IllegalArgumentException("处理状态无效");
        if ("shield".equals(request.getAction()) && (request.getNewShieldStatus() == null || !Set.of(0, 1).contains(request.getNewShieldStatus()))) throw new IllegalArgumentException("屏蔽状态无效");
        if (Integer.valueOf(1).equals(request.getNewShieldStatus()) && request.getShieldEndTime() != null && !request.getShieldEndTime().isAfter(LocalDateTime.now()))
            throw new IllegalArgumentException("屏蔽结束时间必须晚于当前时间");
        Scope scope = scope(request);
        List<String> keys = new ArrayList<>(); Map<String, Long> expected = new HashMap<>();
        for (var selection : request.getGroups()) {
            requireKey(selection.getGroupKey());
            if (selection.getExpectedCount() < 1 || expected.put(selection.getGroupKey(), selection.getExpectedCount()) != null) throw new IllegalArgumentException("组选择重复或次数无效");
            keys.add(selection.getGroupKey());
        }
        scope.params().addValue("keys", keys);
        // 锁定本次筛选快照内的记录；同组新到达的事件不会被误操作。
        List<Map<String, Object>> members = jdbc.queryForList(episodeCte(scope)
                + "SELECT a.id,a.process_status,f.group_key FROM alarm a JOIN filtered f ON f.id=a.id" + scope.where()
                + " AND f.group_key IN (:keys) ORDER BY a.id FOR UPDATE", scope.params());
        Map<String, Long> actual = new HashMap<>();
        members.forEach(member -> actual.merge(member.get("group_key").toString(), 1L, Long::sum));
        if (!expected.equals(actual)) throw new IllegalArgumentException("告警记录状态已变化，请刷新列表后重新操作");
        int affected = 0;
        for (Map<String, Object> member : members) {
            Long id = ((Number) member.get("id")).longValue();
            switch (request.getAction()) {
                case "delete" -> { alarms.deleteById(id); affected++; }
                case "shield" -> { alarms.updateShieldStatus(id, request.getNewShieldStatus(), request.getShieldEndTime()); affected++; }
                case "process" -> {
                    int status = member.get("process_status") == null ? -1 : ((Number) member.get("process_status")).intValue();
                    // 工单与其它特殊状态不改成普通告警处理状态。
                    if ((status == 0 || status == 1) && status != request.getNewProcessStatus()) {
                        alarms.updateProcessStatus(id, request.getNewProcessStatus(), actor); affected++;
                    }
                }
                default -> throw new IllegalArgumentException("操作无效");
            }
        }
        return Map.of("affectedCount", affected);
    }

    private Scope scope(AlarmSummaryRequest request) {
        long snapshot = request.getSnapshotId() == null ? Objects.requireNonNullElse(jdbc.getJdbcTemplate().queryForObject("SELECT COALESCE(MAX(id),0) FROM alarm", Long.class), 0L) : request.getSnapshotId();
        if (snapshot < 0) throw new IllegalArgumentException("查询快照无效");
        MapSqlParameterSource params = new MapSqlParameterSource("snapshot", snapshot);
        StringBuilder where = new StringBuilder(" WHERE a.id<=:snapshot");
        add(where, params, "a.system_id", "system", request.getSystemId());
        add(where, params, "a.category_id", "category", request.getCategoryId());
        add(where, params, "a.metric_id", "metric", request.getMetricId());
        add(where, params, "a.alarm_type", "type", blank(request.getAlarmType()));
        add(where, params, "a.process_status", "process", request.getProcessStatus());
        add(where, params, "a.shield_status", "shield", request.getShieldStatus());
        if (blank(request.getAlarmContent()) != null) { where.append(" AND a.alarm_content LIKE :content"); params.addValue("content", "%" + request.getAlarmContent().trim() + "%"); }
        if (blank(request.getAlarmTimeStart()) != null) { where.append(" AND a.alarm_time>=:start"); params.addValue("start", date(request.getAlarmTimeStart(), false)); }
        if (blank(request.getAlarmTimeEnd()) != null) { where.append(" AND a.alarm_time<=:end"); params.addValue("end", date(request.getAlarmTimeEnd(), true)); }
        return new Scope(where.toString(), params, snapshot);
    }

    /** 转换也使用同一组身份与筛选条件，不以最新一条记录代替整组。 */
    public List<Alarm> conversionMembers(com.metric.alarm.dto.AlarmConversionRequest request, boolean lock) {
        if (request.getAlarmId() != null) {
            List<Alarm> members = jdbc.query("SELECT a.* FROM alarm a WHERE a.id=:id" + (lock ? " FOR UPDATE" : ""),
                    new MapSqlParameterSource("id", request.getAlarmId()), ALARM_MAPPER);
            if (members.isEmpty()) throw new IllegalArgumentException("告警已不存在，请刷新列表");
            return members;
        }
        if (request.getSnapshotId() == null || request.getGroups() == null || request.getGroups().size() != 1)
            throw new IllegalArgumentException("每次请选择一个告警组转换");
        var selected = request.getGroups().get(0);
        requireKey(selected.getGroupKey());
        Scope scope = scope(request);
        scope.params().addValue("key", selected.getGroupKey());
        List<Alarm> members = jdbc.query(episodeCte(scope)
                + "SELECT a.* FROM alarm a JOIN filtered f ON f.id=a.id" + scope.where()
                + " AND f.group_key=:key ORDER BY a.id" + (lock ? " FOR UPDATE" : ""), scope.params(), ALARM_MAPPER);
        if (members.isEmpty() || members.size() != selected.getExpectedCount())
            throw new IllegalArgumentException("告警记录已变化，请刷新列表后重新操作");
        return members;
    }
    private void add(StringBuilder where, MapSqlParameterSource params, String column, String name, Object value) { if (value != null) { where.append(" AND ").append(column).append("=:").append(name); params.addValue(name, value); } }
    private String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private LocalDateTime date(String value, boolean end) { String text = value.trim(); return text.length() == 10 ? LocalDate.parse(text).atTime(end ? LocalTime.MAX : LocalTime.MIN) : LocalDateTime.parse(text.replace(' ', 'T')); }
    private static LocalDateTime time(ResultSet rs, String column) throws SQLException { var value = rs.getTimestamp(column); return value == null ? null : value.toLocalDateTime(); }
    private void requireKey(String key) { if (key == null || !key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("告警组标识无效，请刷新列表"); }
    private String strategyKey(String type, String targetType, Long targetId) { return type + ":" + targetType + ":" + targetId; }
    private List<Threshold> thresholds(List<AlarmStrategy> choices, boolean ticket) {
        List<Threshold> result = new ArrayList<>();
        for (AlarmStrategy strategy : choices) {
            List<StrategyRule> rules = ticket ? strategy.getGongdanRules() : strategy.getAlarmRules();
            if (rules == null) {
                BigDecimal value = ticket ? strategy.getGongdanThreshold() : strategy.getAlarmThreshold();
                if (value == null) continue;
                StrategyRule legacy = new StrategyRule(); legacy.setLevel(2); legacy.setThreshold(value);
                legacy.setCondition(ticket ? strategy.getGongdanCondition() : strategy.getAlarmCondition());
                legacy.setConsecutiveTimes(ticket ? strategy.getGongdanConsecutiveTimes() : strategy.getAlarmConsecutiveTimes()); rules = List.of(legacy);
            }
            for (StrategyRule rule : rules) result.add(new Threshold(strategy.getId(), strategy.getStrategyName(), rule.getLevel(), rule.getCondition(), rule.getThreshold(), rule.getConsecutiveTimes(),
                    Integer.valueOf(1).equals(strategy.getStatus()) && Integer.valueOf(1).equals(ticket ? strategy.getGongdanEnabled() : strategy.getAlarmEnabled())));
        }
        result.sort(Comparator.comparing(Threshold::enabled).reversed().thenComparing(Threshold::strategyId).thenComparing(Threshold::level, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }
}
