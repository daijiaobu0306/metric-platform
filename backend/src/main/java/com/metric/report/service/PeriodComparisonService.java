package com.metric.report.service;

import com.metric.metric.service.HealthScoreEngine;
import com.metric.metric.service.MetricPeriodQueryService;
import com.metric.report.dto.PeriodComparisonRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多维度环比分析：大类健康度 / 小类健康度 / 指标值对比。
 *
 * <p><b>健康度（分类维度）</b>：把时段内所有有效采样点按统计方法（avg/median）聚合成代表分，
 * 状态由代表分按全局阈值（{@code metric.health.alarm-threshold} / {@code warning-threshold}，
 * 默认 60/80）推导，与指标健康度同一口径。对比输出「差值 + 状态变化」，
 * <b>不输出变化率</b>——分数是相对刻度，70 分涨 10% 得 77 分和 95 分涨 10% 得 104.5 分不是一回事，
 * 变化率对分数没有业务含义，只有差值能落到阈值档位上说明问题。
 *
 * <p><b>指标值（指标维度）</b>：同样聚合代表值后比较，输出差值、变化率与方向。
 * 「改善 / 劣化」不按涨跌判断，而是按该指标告警策略的比较条件推导「哪个方向是好」：
 * 条件为 {@code >} 的指标（如超时数、失败率）下降才是改善；条件为 {@code <} 的
 * （如可用率、及时率）上升才是改善；没有可用告警条件的指标方向不明，标为中性，
 * 前端不用红绿误导，只显示中性色的涨跌箭头。
 *
 * <p>留存快照类指标无历史，指标值对比可能为空。
 */
@Service
public class PeriodComparisonService {

    private static final String METHOD_AVG = "avg";
    private static final String METHOD_MEDIAN = "median";

    private static final String STATUS_NORMAL = "normal";
    private static final String STATUS_WARNING = "warning";
    private static final String STATUS_ALARM = "alarm";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final MetricPeriodQueryService periodQuery;
    private final HealthScoreEngine healthScoreEngine;

    public PeriodComparisonService(JdbcTemplate jdbc, MetricPeriodQueryService periodQuery,
                                   HealthScoreEngine healthScoreEngine) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.periodQuery = periodQuery;
        this.healthScoreEngine = healthScoreEngine;
    }

    public Map<String, Object> compare(PeriodComparisonRequest req) {
        String method = normalizeMethod(req.getMethod());
        LocalDateTime p1s = parse(req.getPeriod1Start(), "上周期开始时间");
        LocalDateTime p1e = parse(req.getPeriod1End(), "上周期结束时间");
        LocalDateTime p2s = parse(req.getPeriod2Start(), "本周期开始时间");
        LocalDateTime p2e = parse(req.getPeriod2End(), "本周期结束时间");
        if (!p1s.isBefore(p1e)) throw new IllegalArgumentException("上周期开始时间不能晚于结束时间");
        if (!p2s.isBefore(p2e)) throw new IllegalArgumentException("本周期开始时间不能晚于结束时间");

        boolean anyCategory = req.getCategoryNames() != null && !req.getCategoryNames().isEmpty();
        boolean anySub = req.getSubCategories() != null && !req.getSubCategories().isEmpty();
        boolean anyMetric = req.getMetricIds() != null && !req.getMetricIds().isEmpty();
        if (!anyCategory && !anySub && !anyMetric) {
            throw new IllegalArgumentException("请至少选择一个指标大类、小类或指标");
        }

        HealthScoreEngine.Settings settings = healthScoreEngine.settings();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", method);
        result.put("period1", Map.of("start", p1s.toString().replace('T', ' '), "end", p1e.toString().replace('T', ' ')));
        result.put("period2", Map.of("start", p2s.toString().replace('T', ' '), "end", p2e.toString().replace('T', ' ')));
        result.put("periodLength", periodLength(periodDays(p1s, p1e), periodDays(p2s, p2e)));
        result.put("healthThresholds", Map.of("alarm", settings.alarmThreshold(), "warning", settings.warningThreshold()));
        result.put("categoryHealth", anyCategory
                ? compareCategoryHealth(req.getCategoryNames(), method, settings, p1s, p1e, p2s, p2e) : List.of());
        result.put("subCategoryHealth", anySub
                ? compareSubCategoryHealth(req.getSubCategories(), method, settings, p1s, p1e, p2s, p2e) : List.of());
        result.put("metricValues", anyMetric
                ? compareMetricValues(req.getMetricIds(), method, p1s, p1e, p2s, p2e) : List.of());
        return result;
    }

    // ==================================================================
    // 周期自检
    // ==================================================================

    /** 周期天数按自然日计：结束时间归一为当日 23:59:59，所以用日期差 + 1，不按毫秒折算。 */
    private static long periodDays(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) + 1;
    }

    /**
     * 两周期长度自检。
     *
     * <p>长度不等时，累计型指标（工单数、告警数、业务价值等）的差值会随天数放大：本月只过了
     * 23 天对上月 31 天，差值天然为负，用户会把「天数少」读成「劣化」。所以只做提示不拦截——
     * 运营场景确实存在有意对比不等长周期的情况（节假日、口径调整），硬拦会挡住正当分析。
     */
    private static Map<String, Object> periodLength(long period1Days, long period2Days) {
        long gap = Math.abs(period1Days - period2Days);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("period1Days", period1Days);
        info.put("period2Days", period2Days);
        info.put("aligned", gap == 0);
        info.put("warning", gap <= 1 ? null
                : "两周期长度不同（上周期 " + period1Days + " 天，本周期 " + period2Days + " 天）："
                + "累计型指标（工单数、告警数、业务价值等）的差值会随天数放大，建议改用等长的同期区间再比对。");
        return info;
    }

    // ==================================================================
    // 大类健康度
    // ==================================================================

    private List<Map<String, Object>> compareCategoryHealth(List<String> names, String method,
                                                            HealthScoreEngine.Settings settings,
                                                            LocalDateTime p1s, LocalDateTime p1e,
                                                            LocalDateTime p2s, LocalDateTime p2e) {
        Map<String, List<BigDecimal>> period1 = categoryHealthSeries(names, p1s, p1e);
        Map<String, List<BigDecimal>> period2 = categoryHealthSeries(names, p2s, p2e);
        Map<String, String> fallback1 = categoryStatusSeries(names, p1s, p1e);
        Map<String, String> fallback2 = categoryStatusSeries(names, p2s, p2e);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : names) {
            BigDecimal v1 = aggregate(period1.get(name), method);
            BigDecimal v2 = aggregate(period2.get(name), method);
            rows.add(buildHealthRow(name, null, null, v1, v2,
                    statusOf(v1, fallback1.get(name), settings),
                    statusOf(v2, fallback2.get(name), settings),
                    period1.get(name), period2.get(name)));
        }
        return rows;
    }

    private Map<String, List<BigDecimal>> categoryHealthSeries(List<String> names, LocalDateTime start, LocalDateTime end) {
        Map<String, List<BigDecimal>> series = new LinkedHashMap<>();
        if (names.isEmpty()) return series;
        var params = new HashMap<String, Object>();
        params.put("names", names);
        params.put("start", start);
        params.put("end", end);
        for (var row : named.queryForList("""
                SELECT category_name AS categoryName, health_score AS score
                FROM category_health_history
                WHERE category_name IN (:names) AND record_time >= :start AND record_time <= :end
                """, params)) {
            BigDecimal score = decimal(row.get("score"));
            if (score == null) continue;
            series.computeIfAbsent(String.valueOf(row.get("categoryName")), k -> new ArrayList<>()).add(score);
        }
        return series;
    }

    private Map<String, String> categoryStatusSeries(List<String> names, LocalDateTime start, LocalDateTime end) {
        Map<String, java.util.LinkedHashSet<String>> byName = new LinkedHashMap<>();
        if (names.isEmpty()) return Map.of();
        var params = new HashMap<String, Object>();
        params.put("names", names);
        params.put("start", start);
        params.put("end", end);
        for (var row : named.queryForList("""
                SELECT category_name AS categoryName, status
                FROM category_health_history
                WHERE category_name IN (:names) AND record_time >= :start AND record_time <= :end
                """, params)) {
            String status = row.get("status") == null ? null : String.valueOf(row.get("status"));
            if (status == null || status.isBlank()) continue;
            byName.computeIfAbsent(String.valueOf(row.get("categoryName")), k -> new java.util.LinkedHashSet<>()).add(status);
        }
        Map<String, String> result = new LinkedHashMap<>();
        byName.forEach((name, statuses) -> result.put(name, worstStatus(statuses)));
        return result;
    }

    // ==================================================================
    // 小类健康度
    // ==================================================================

    private List<Map<String, Object>> compareSubCategoryHealth(List<PeriodComparisonRequest.SubCategoryRef> subs, String method,
                                                               HealthScoreEngine.Settings settings,
                                                               LocalDateTime p1s, LocalDateTime p1e,
                                                               LocalDateTime p2s, LocalDateTime p2e) {
        Map<String, List<BigDecimal>> period1 = subCategoryHealthSeries(subs, p1s, p1e);
        Map<String, List<BigDecimal>> period2 = subCategoryHealthSeries(subs, p2s, p2e);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var sub : subs) {
            String key = keyOf(sub);
            BigDecimal v1 = aggregate(period1.get(key), method);
            BigDecimal v2 = aggregate(period2.get(key), method);
            rows.add(buildHealthRow(sub.getSubCategoryName(), sub.getCategoryName(), null,
                    v1, v2, statusOf(v1, null, settings), statusOf(v2, null, settings),
                    period1.get(key), period2.get(key)));
        }
        return rows;
    }

    private Map<String, List<BigDecimal>> subCategoryHealthSeries(List<PeriodComparisonRequest.SubCategoryRef> subs,
                                                                  LocalDateTime start, LocalDateTime end) {
        Map<String, List<BigDecimal>> series = new LinkedHashMap<>();
        if (subs.isEmpty()) return series;
        StringBuilder sql = new StringBuilder(
            "SELECT category_name AS categoryName, sub_category_name AS subCategoryName, health_score AS score " +
            "FROM sub_category_health_history WHERE record_time >= ? AND record_time <= ? AND (");
        List<Object> args = new ArrayList<>(List.of(start, end));
        for (int i = 0; i < subs.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("(category_name = ? AND sub_category_name = ?)");
            args.add(subs.get(i).getCategoryName());
            args.add(subs.get(i).getSubCategoryName());
        }
        sql.append(")");
        for (var row : jdbc.queryForList(sql.toString(), args.toArray())) {
            BigDecimal score = decimal(row.get("score"));
            if (score == null) continue;
            String key = String.valueOf(row.get("categoryName")) + "|" + row.get("subCategoryName");
            series.computeIfAbsent(key, k -> new ArrayList<>()).add(score);
        }
        return series;
    }

    // ==================================================================
    // 指标值对比
    // ==================================================================

    private List<Map<String, Object>> compareMetricValues(List<Long> metricIds, String method,
                                                          LocalDateTime p1s, LocalDateTime p1e,
                                                          LocalDateTime p2s, LocalDateTime p2e) {
        Map<Long, Map<String, Object>> catalog = new HashMap<>();
        for (var metric : periodQuery.catalog(null)) {
            catalog.put(metricIdOf(metric), metric);
        }

        Map<Long, List<BigDecimal>> period1 = valueSeries(metricIds, p1s, p1e);
        Map<Long, List<BigDecimal>> period2 = valueSeries(metricIds, p2s, p2e);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long id : metricIds) {
            var metric = catalog.get(id);
            String name = metric == null ? null : String.valueOf(metric.get("metricName"));
            String category = metric == null ? null : String.valueOf(metric.get("categoryName"));
            String sub = metric == null ? null : String.valueOf(metric.get("subCategoryName"));
            String unit = metric == null ? null : String.valueOf(metric.get("unit"));
            String betterWhen = metric == null ? "neutral" : betterWhenOf(metric.get("alarmCondition"));
            String condition = metric == null ? null : text(metric.get("alarmCondition"));
            BigDecimal threshold = metric == null ? null : decimal(metric.get("alarmThreshold"));
            rows.add(buildMetricRow(name, category, sub, unit, betterWhen, condition, threshold,
                    method, period1.get(id), period2.get(id)));
        }
        return rows;
    }

    private Map<Long, List<BigDecimal>> valueSeries(List<Long> metricIds, LocalDateTime start, LocalDateTime end) {
        Map<Long, List<BigDecimal>> series = new HashMap<>();
        for (var row : periodQuery.metricValueSeries(metricIds, start, end)) {
            long id = metricIdOf(row);
            BigDecimal value = decimal(String.valueOf(row.get("metricValue")));
            if (value == null) continue;
            series.computeIfAbsent(id, k -> new ArrayList<>()).add(value);
        }
        return series;
    }

    // ==================================================================
    // 默认预选：最近有采集数据的指标
    // ==================================================================

    /**
     * 进页默认预选的指标：最近 {@code days} 天有采集记录，按大类各取前 {@code perCategory} 个，
     * 总数不超过 {@code limit}。
     *
     * <p>为什么需要它：环比分析要求先选大类/小类/指标，进页时一片空白，用户不知道该选谁。
     * 默认不能落在健康度维度——{@code category_health_history} 只在健康度算分跑过之后才有行，
     * 采集入库走 Worker 直接落 collection_row 的环境里它是空的，按大类做默认会得到一屏「—」。
     * 默认只能落在指标值维度，而指标值维度必须有「哪些指标真的有数」这个事实。
     *
     * <p>取数用原始采集记录，不用 metric_realtime / metric_value_history：后两者只留最新值与算分
     * 结果，采集刚落地或算分没跑过的指标在那里查不到，会让默认列表空得没有道理。
     *
     * <p>时间条件用 {@code collection_file.collected_at}（文件落库时间，可走索引），不用 data_time：
     * data_time 是 JSON 里的计算值，按它过滤要扫全部行（本地 20 万行约 1.5 秒）。这里只是挑候选
     * 指标，用文件时间足够；真正的对比区间仍由 data_time 决定。
     *
     * <p>排序按采集量降序 + 每类封顶，避免某一类的几十个高频指标把 8 个位置全占满。
     *
     * @return [{categoryName, metricId, metricCode, metricName, subCategoryName, observations}]
     */
    public List<Map<String, Object>> activeMetrics(int days, int perCategory, int limit) {
        int windowDays = Math.max(1, Math.min(90, days));
        int perCat = Math.max(1, Math.min(5, perCategory));
        int cap = Math.max(1, Math.min(20, limit));

        String sql = "SELECT cat.category_name AS categoryName, m.id AS metricId, "
                + "m.metric_code AS metricCode, m.metric_name AS metricName, "
                + "m.sub_category_name AS subCategoryName, t.cnt AS observations "
                + "FROM (SELECT JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.metric_code')) AS code, "
                + "             COUNT(*) AS cnt "
                + "      FROM collection_row r JOIN collection_file f ON f.id = r.batch_id "
                + "      WHERE f.collected_at >= ? "
                + "      GROUP BY code) t "
                + "JOIN metric_item m ON BINARY m.metric_code = BINARY t.code AND m.status = 1 "
                + "JOIN metric_category cat ON cat.id = m.category_id "
                + "ORDER BY t.cnt DESC, cat.id, m.id";

        Map<String, Integer> picked = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var row : jdbc.queryForList(sql, LocalDateTime.now().minusDays(windowDays))) {
            String category = String.valueOf(row.get("categoryName"));
            int used = picked.getOrDefault(category, 0);
            if (used >= perCat) continue;
            picked.put(category, used + 1);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("categoryName", category);
            item.put("metricId", ((Number) row.get("metricId")).longValue());
            item.put("metricCode", row.get("metricCode"));
            item.put("metricName", row.get("metricName"));
            item.put("subCategoryName", row.get("subCategoryName"));
            item.put("observations", ((Number) row.get("observations")).longValue());
            rows.add(item);
            if (rows.size() >= cap) break;
        }
        return rows;
    }

    // ==================================================================
    // 行构造
    // ==================================================================

    /**
     * 健康度行：只给差值 + 状态 + 状态变化，不给变化率。
     *
     * <p>分数是相对刻度且有阈值档位，看的是「从哪一档掉到哪一档、掉了多少分」，
     * 百分比涨跌对分数没有解释力，容易把 62→68 这种跨档变化读成"变化不大"。
     */
    private Map<String, Object> buildHealthRow(String name, String categoryName, String subCategoryName,
                                               BigDecimal v1, BigDecimal v2, String status1, String status2,
                                               List<BigDecimal> period1, List<BigDecimal> period2) {
        BigDecimal diff = (v1 == null || v2 == null) ? null : v2.subtract(v1);
        String direction = diff == null ? null
                : diff.signum() > 0 ? "up" : diff.signum() < 0 ? "down" : "flat";
        String change = statusChange(status1, status2);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        if (categoryName != null) row.put("categoryName", categoryName);
        if (subCategoryName != null) row.put("subCategoryName", subCategoryName);
        row.put("unit", "分");
        row.put("period1Value", round(v1));
        row.put("period2Value", round(v2));
        row.put("diff", round(diff));
        row.put("direction", direction);
        row.put("period1Status", status1);
        row.put("period2Status", status2);
        row.put("statusChange", change);
        // 健康度的「改善/劣化」由是否跨档决定，不看分数涨跌：72→76 还在同一档，
        // 只算同档微调；只有跨过关注线/异常线才是实质改善或劣化。
        row.put("improvement", healthImprovement(change, direction));
        row.put("period1Count", period1 == null ? 0 : period1.size());
        row.put("period2Count", period2 == null ? 0 : period2.size());
        return row;
    }

    /**
     * 健康度语义：跨档才是改善/劣化，同档内只记录方向。
     *
     * @return improved / deteriorated / same_level（同档但分数有变）/ flat（完全持平）/ null（缺数）
     */
    private String healthImprovement(String statusChange, String direction) {
        if (direction == null) return null;
        if ("improved".equals(statusChange)) return "improved";
        if ("deteriorated".equals(statusChange)) return "deteriorated";
        if ("flat".equals(direction)) return "flat";
        return "same_level";
    }

    /**
     * 指标值行：差值 + 变化率 + 方向 + 改善/劣化。
     *
     * <p>{@code betterWhen} 决定"好"的方向：{@code lower} 表示越低越好（告警条件是 {@code >}，
     * 超阈值才告警）、{@code higher} 表示越高越好（条件是 {@code <}）、{@code neutral} 表示
     * 没有可用告警条件，判不出好坏。
     */
    private Map<String, Object> buildMetricRow(String name, String categoryName, String subCategoryName, String unit,
                                               String betterWhen, String alarmCondition, BigDecimal alarmThreshold,
                                               String method, List<BigDecimal> period1, List<BigDecimal> period2) {
        BigDecimal v1 = aggregate(period1, method);
        BigDecimal v2 = aggregate(period2, method);
        BigDecimal diff = (v1 == null || v2 == null) ? null : v2.subtract(v1);
        BigDecimal rate = (v1 == null || v2 == null || v1.signum() == 0)
                ? null : diff.multiply(BigDecimal.valueOf(100)).divide(v1, 2, RoundingMode.HALF_UP);
        String direction = diff == null ? null
                : diff.signum() > 0 ? "up" : diff.signum() < 0 ? "down" : "flat";

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        if (categoryName != null) row.put("categoryName", categoryName);
        if (subCategoryName != null) row.put("subCategoryName", subCategoryName);
        row.put("unit", unit == null || unit.isBlank() ? null : unit);
        row.put("period1Value", round(v1));
        row.put("period2Value", round(v2));
        row.put("diff", round(diff));
        row.put("changeRate", rate);
        row.put("direction", direction);
        row.put("betterWhen", betterWhen);
        row.put("alarmCondition", alarmCondition);
        row.put("alarmThreshold", alarmThreshold);
        row.put("improvement", improvement(direction, betterWhen));
        row.put("period1Count", period1 == null ? 0 : period1.size());
        row.put("period2Count", period2 == null ? 0 : period2.size());
        return row;
    }

    /**
     * 涨跌 → 改善/劣化语义。
     *
     * @param betterWhen higher=越高越好 / lower=越低越好 / neutral=方向不明（判不出好坏）
     * @return improved / deteriorated / flat（无变化）/ neutral（有变化但方向不明）
     */
    private String improvement(String direction, String betterWhen) {
        if (direction == null) return null;
        if ("flat".equals(direction)) return "flat";
        if ("higher".equals(betterWhen)) return "up".equals(direction) ? "improved" : "deteriorated";
        if ("lower".equals(betterWhen)) return "down".equals(direction) ? "improved" : "deteriorated";
        return "neutral";
    }

    /** 由告警比较条件推导「值往哪个方向是好的」：> 告警 → 越低越好；< 告警 → 越高越好；无 → 中性。 */
    private String betterWhenOf(Object alarmCondition) {
        String cond = text(alarmCondition);
        if (">".equals(cond) || ">=".equals(cond)) return "lower";
        if ("<".equals(cond) || "<=".equals(cond)) return "higher";
        return "neutral";
    }

    /** 健康度状态：优先按聚合分数 + 全局阈值推导，保证与表格里的分数自洽；无分数时回退历史记录的状态。 */
    private String statusOf(BigDecimal score, String fallbackStatus, HealthScoreEngine.Settings settings) {
        String byScore = healthScoreEngine.statusOf(score, settings);
        return byScore != null ? byScore : normalizeStatus(fallbackStatus);
    }

    /** 状态变化：等级 正常(3) > 关注(2) > 异常(1)，升档为改善、降档为劣化。 */
    private String statusChange(String status1, String status2) {
        int r1 = statusRank(status1);
        int r2 = statusRank(status2);
        if (r1 == 0 || r2 == 0) return null;
        if (r2 > r1) return "improved";
        if (r2 < r1) return "deteriorated";
        return "unchanged";
    }

    private static int statusRank(String status) {
        if (STATUS_NORMAL.equals(status)) return 3;
        if (STATUS_WARNING.equals(status)) return 2;
        if (STATUS_ALARM.equals(status)) return 1;
        return 0;
    }

    private static String normalizeStatus(String status) {
        if (status == null) return null;
        String s = status.trim().toLowerCase();
        if (STATUS_NORMAL.equals(s) || STATUS_WARNING.equals(s) || STATUS_ALARM.equals(s)) return s;
        return null;
    }

    /** 时段内最差状态：alarm > warning > normal。 */
    private String worstStatus(Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return null;
        if (statuses.contains(STATUS_ALARM)) return STATUS_ALARM;
        if (statuses.contains(STATUS_WARNING)) return STATUS_WARNING;
        if (statuses.contains(STATUS_NORMAL)) return STATUS_NORMAL;
        return null;
    }

    private BigDecimal aggregate(List<BigDecimal> values, String method) {
        if (values == null || values.isEmpty()) return null;
        if (METHOD_MEDIAN.equals(method)) return median(values);
        return average(values);
    }

    private BigDecimal average(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) sum = sum.add(v);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal b) return b;
        String s = String.valueOf(value);
        if (s.isBlank() || "null".equals(s)) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static long metricIdOf(Map<String, Object> row) {
        Object v = row.get("metricId");
        return v instanceof Number n ? n.longValue() : 0;
    }

    private static String keyOf(PeriodComparisonRequest.SubCategoryRef sub) {
        return sub.getCategoryName() + "|" + sub.getSubCategoryName();
    }

    private static String normalizeMethod(String method) {
        return METHOD_MEDIAN.equalsIgnoreCase(method) ? METHOD_MEDIAN : METHOD_AVG;
    }

    private static LocalDateTime parse(String s, String label) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        try {
            return LocalDateTime.parse(s.trim().replace(' ', 'T'));
        } catch (Exception e) {
            throw new IllegalArgumentException(label + "格式应为 yyyy-MM-dd HH:mm:ss");
        }
    }
}