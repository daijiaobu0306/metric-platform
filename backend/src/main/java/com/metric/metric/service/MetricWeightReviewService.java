package com.metric.metric.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.security.entity.SysUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
public class MetricWeightReviewService {
    private static final String RUN_QUERY = """
            SELECT r.*, c.category_name, c.system_id,
                   (SELECT COUNT(*) FROM metric_weight_recommendation d WHERE d.run_id = r.id AND d.review_status = 'pending') AS pending_count
            FROM metric_weight_recommendation_run r LEFT JOIN metric_category c ON c.id = r.category_id
            WHERE r.run_key NOT LIKE 'demo:%'
            """;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper json;

    public MetricWeightReviewService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
    }

    public record Selection(Long id, BigDecimal approvedPercent) {}
    public record ReviewRequest(List<Selection> selections, String previewToken, String note) {}
    public record Change(long metricId, String metricName, int beforePercent, int afterPercent, boolean selected) {}
    public record Preview(List<Change> changes, String token, Impact impact) {}
    public record Impact(Integer beforeScore, Integer afterScore, int samples, int totalMetrics) {}
    private record Member(long id, String name, BigDecimal weight, String token, String definitionToken, int configuredCount) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String type, Long category, String state, Long before) {
        var parameters = new MapSqlParameterSource().addValue("type", type).addValue("category", category)
                .addValue("state", state).addValue("before", before).addValue("now", now());
        var runs = namedJdbc.queryForList(RUN_QUERY + """
                  AND (:type IS NULL OR r.recommendation_type = :type)
                  AND (:category IS NULL OR r.category_id = :category)
                  AND (:before IS NULL OR r.id < :before)
                  AND (
                    :state IS NULL
                    OR (:state = 'failed' AND r.run_status = 'failed')
                    OR (
                      r.run_status = 'ready' AND EXISTS (
                        SELECT 1 FROM metric_weight_recommendation d
                        WHERE d.run_id = r.id
                          AND (
                            (:state = 'expired' AND d.review_status = 'pending' AND r.expires_at <= :now)
                            OR (:state = d.review_status AND (d.review_status <> 'pending' OR r.expires_at > :now))
                          )
                      )
                    )
                  )
                ORDER BY r.id DESC LIMIT 25
                """, parameters);
        runs.forEach(run -> {
            run.remove("baseline");
            run.remove("raw_analysis");
            normalizeDates(run, "generated_at", "expires_at");
        });
        return runs;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long id) {
        var run = new LinkedHashMap<>(run(id));
        run.put("expired", expired(run));
        var items = jdbc.queryForList("""
                SELECT * FROM metric_weight_recommendation WHERE run_id = ? ORDER BY id
                """, id);
        items.forEach(row -> normalizeDates(row, "reviewed_at"));
        run.put("items", items);
        run.remove("baseline");
        normalizeDates(run, "generated_at", "period_start", "period_end", "expires_at");
        return run;
    }

    @Transactional(readOnly = true)
    public Preview preview(long runId, ReviewRequest request) {
        return buildPreview(runId, run(runId), request, true);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public long approve(long runId, ReviewRequest request, SysUser reviewer) {
        var run = lockRunAndCategory(runId);
        Preview preview = buildPreview(runId, run, request, false);
        if (!preview.token().equals(request.previewToken())) {
            throw conflict("预览已变化，请重新查看本组权重后再批准");
        }
        long category = number(run, "category_id");
        String subCategory = (String) run.get("sub_category_name");
        var before = members(category, subCategory);
        var configured = new HashSet<Long>();
        before.stream().filter(m -> m.configuredCount() == 1).forEach(m -> configured.add(m.id()));
        for (Change change : preview.changes()) {
            BigDecimal weight = BigDecimal.valueOf(change.afterPercent(), 2);
            if (configured.contains(change.metricId())) {
                jdbc.update("""
                    UPDATE weight_config SET weight = ?, update_time = CURRENT_TIMESTAMP
                    WHERE category_id = ? AND metric_id = ? AND weight_type = 'metric'
                    """, weight, category, change.metricId());
            } else {
                jdbc.update("""
                        INSERT INTO weight_config(category_id, metric_id, weight_type, weight)
                        VALUES (?, ?, 'metric', ?)
                        """, category, change.metricId(), weight);
            }
        }
        var after = members(category, subCategory);
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO metric_weight_version(scope_type, scope_id, sub_category_name, source, run_id,
                        operator_id, operator_name, weights_before, weights_after, applied_at)
                    VALUES ('metric', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, category);
            statement.setString(2, subCategory);
            statement.setString(3, (String) run.get("recommendation_type"));
            statement.setLong(4, runId);
            statement.setLong(5, reviewer.getId());
            statement.setString(6, reviewer.getUsername());
            statement.setString(7, write(before));
            statement.setString(8, write(Map.of("tokens", tokens(after), "weights", after, "changes", preview.changes())));
            statement.setObject(9, now());
            return statement;
        }, keys);
        long versionId = Objects.requireNonNull(keys.getKey()).longValue();
        var selected = selections(request);
        for (var row : pendingRows(runId, selected.keySet())) {
            long id = number(row, "id");
            BigDecimal edited = selected.get(id).approvedPercent();
            BigDecimal share = edited == null ? (BigDecimal) row.get("suggested_share") : edited.movePointLeft(2);
            jdbc.update("""
                    UPDATE metric_weight_recommendation SET review_status = 'applied', approved_share = ?,
                        reviewed_by = ?, reviewer_name = ?, reviewed_at = ?, review_note = ?, weight_version_id = ?
                    WHERE id = ?
                    """, share, reviewer.getId(), reviewer.getUsername(), now(), note(request), versionId, id);
        }
        return versionId;
    }

    @Transactional
    public void reject(long runId, ReviewRequest request, SysUser reviewer) {
        lockRun(runId);
        String note = note(request);
        if (note == null || note.isBlank()) throw badRequest("请填写驳回原因");
        var selected = selections(request);
        for (var row : pendingRows(runId, selected.keySet())) {
            jdbc.update("""
                    UPDATE metric_weight_recommendation SET review_status = 'rejected', review_note = ?,
                        reviewed_by = ?, reviewer_name = ?, reviewed_at = ? WHERE id = ?
                    """, note, reviewer.getId(), reviewer.getUsername(), now(), number(row, "id"));
        }
    }

    private Preview buildPreview(long runId, Map<String, Object> run, ReviewRequest request, boolean simulate) {
        if (!"ready".equals(run.get("run_status")) || expired(run)) {
            throw conflict("建议已过期或生成失败");
        }
        var selected = selections(request);
        var members = members(number(run, "category_id"), (String) run.get("sub_category_name"));
        if (members.isEmpty() || members.stream().anyMatch(m -> m.configuredCount() > 1
                || m.weight().signum() < 0 || m.weight().compareTo(BigDecimal.ONE) > 0)) {
            throw conflict("本组没有启用指标，或现有权重存在重复或越界，请先核对配置");
        }
        var previousVersions = jdbc.queryForList("""
                SELECT weights_after FROM metric_weight_version WHERE run_id = ? ORDER BY id DESC LIMIT 1
                """, String.class, runId);
        var expected = previousVersions.isEmpty() ? read(run.get("baseline"))
                : read(previousVersions.get(0)).path("tokens");
        var actual = tokens(members);
        if (!expected.equals(json.valueToTree(actual))) {
            throw conflict("指标组或基础权重已变化，请生成新建议");
        }

        var rawWeights = new LinkedHashMap<Long, BigDecimal>();
        members.forEach(m -> rawWeights.put(m.id(), m.weight()));
        var before = MetricWeightShares.distribute(rawWeights, 100);
        var remaining = new LinkedHashMap<>(rawWeights);
        var definitions = new HashMap<Long, String>();
        members.forEach(m -> definitions.put(m.id(), m.definitionToken()));
        var approved = new LinkedHashMap<Long, Integer>();
        for (var row : pendingRows(runId, selected.keySet())) {
            long metricId = number(row, "metric_id");
            if (!"business_importance".equals(row.get("assessment"))) {
                throw badRequest("当前风险建议仅供参考，可驳回，不能应用为基础权重");
            }
            if (remaining.remove(metricId) == null) throw conflict("指标已停用、删除或不属于本组");
            if (!Objects.equals(definitions.get(metricId), row.get("definition_token"))) {
                throw conflict("指标定义已变化，请生成新建议");
            }
            if (previousVersions.isEmpty() && ((BigDecimal) row.get("current_share")).movePointRight(2).intValueExact() != before.get(metricId)) {
                throw conflict("建议中的原占比与权重快照不一致，请重新生成");
            }
            BigDecimal edited = selected.get(number(row, "id")).approvedPercent();
            approved.put(metricId, edited == null ? ((BigDecimal) row.get("suggested_share")).movePointRight(2).intValueExact() : edited.intValueExact());
        }

        int leftover = 100 - approved.values().stream().mapToInt(Integer::intValue).sum();
        if (leftover < 0 || (leftover == 0 && remaining.values().stream().anyMatch(w -> w.signum() > 0))) {
            throw badRequest("已选建议占用的比例过高，请给同组未选指标留出权重");
        }
        Map<Long, Integer> after;
        try {
            after = MetricWeightShares.distribute(remaining, leftover);
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
        after.putAll(approved);
        var changes = members.stream()
                .map(m -> new Change(m.id(), m.name(), before.get(m.id()), after.get(m.id()), approved.containsKey(m.id())))
                .toList();
        String token = digest(write(List.of(runId, actual, new TreeMap<>(selected), changes)));
        return new Preview(changes, token, simulate ? impact(rawWeights, after) : null);
    }

    private Impact impact(Map<Long, BigDecimal> before, Map<Long, Integer> after) {
        var results = namedJdbc.queryForList("""
                SELECT m.id, (SELECT h.health_score FROM metric_health_history h
                              WHERE h.metric_id = m.id AND h.health_score IS NOT NULL
                              ORDER BY h.record_time DESC, h.id DESC LIMIT 1) AS health_score
                FROM metric_item m WHERE m.id IN (:ids)
                """, Map.of("ids", before.keySet()));
        BigDecimal beforeScore = BigDecimal.ZERO;
        BigDecimal beforeTotal = BigDecimal.ZERO;
        long afterScore = 0;
        int afterTotal = 0;
        int count = 0;
        for (var result : results) {
            if (result.get("health_score") == null) continue;
            long id = number(result, "id");
            long score = number(result, "health_score");
            BigDecimal weight = before.get(id);
            beforeScore = beforeScore.add(weight.multiply(BigDecimal.valueOf(score)));
            beforeTotal = beforeTotal.add(weight);
            afterScore += score * after.get(id);
            afterTotal += after.get(id);
            count++;
        }
        return new Impact(beforeTotal.signum() > 0 ? beforeScore.divide(beforeTotal, 0, java.math.RoundingMode.HALF_UP).intValueExact() : null,
                afterTotal > 0 ? (int) Math.round((double) afterScore / afterTotal) : null, count, before.size());
    }

    private List<Member> members(long category, String subCategory) {
        return jdbc.query("""
                SELECT metric_id, metric_name, base_weight, baseline_token, definition_token, configured_count
                FROM metric_weight_review_baseline WHERE category_id = ? AND sub_category_name <=> ?
                ORDER BY metric_id
                """, (rs, n) -> new Member(rs.getLong("metric_id"), rs.getString("metric_name"),
                rs.getBigDecimal("base_weight"), rs.getString("baseline_token"), rs.getString("definition_token"), rs.getInt("configured_count")),
                category, subCategory);
    }

    private List<Map<String, Object>> pendingRows(long run, Set<Long> ids) {
        var rows = namedJdbc.queryForList("""
                SELECT * FROM metric_weight_recommendation
                WHERE run_id = :run AND id IN (:ids) AND review_status = 'pending' ORDER BY id
                """, new MapSqlParameterSource().addValue("run", run).addValue("ids", ids));
        if (rows.size() != ids.size()) throw conflict("部分建议已审核或不属于本批次，请刷新");
        return rows;
    }

    private Map<String, Object> lockRunAndCategory(long id) {
        long category = lockRun(id);
        if (jdbc.queryForList("SELECT id FROM metric_category WHERE id = ? FOR UPDATE", Long.class, category).isEmpty()) {
            throw conflict("所属指标大类已删除");
        }
        jdbc.queryForList("SELECT id FROM metric_item WHERE category_id = ? FOR UPDATE", Long.class, category);
        return run(id);
    }

    private long lockRun(long id) {
        var categories = jdbc.queryForList("""
                SELECT category_id FROM metric_weight_recommendation_run
                WHERE id = ? AND run_key NOT LIKE 'demo:%' FOR UPDATE
                """, Long.class, id);
        if (categories.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "建议批次不存在");
        return categories.get(0);
    }

    private Map<String, Object> run(long id) {
        var rows = jdbc.queryForList(RUN_QUERY + " AND r.id = ?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "建议批次不存在");
        return rows.get(0);
    }

    private Map<Long, Selection> selections(ReviewRequest request) {
        if (request == null || request.selections() == null || request.selections().isEmpty() || request.selections().size() > 1000) {
            throw badRequest("请选择 1 至 1000 条建议");
        }
        Map<Long, Selection> result = new LinkedHashMap<>();
        for (Selection selection : request.selections()) {
            if (selection == null || selection.id() == null || result.putIfAbsent(selection.id(), selection) != null) {
                throw badRequest("选择中存在缺失或重复的建议");
            }
            BigDecimal value = selection.approvedPercent();
            if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0
                    || value.stripTrailingZeros().scale() > 0)) throw badRequest("审核比例须为 0 至 100 的整数");
        }
        return result;
    }

    private static Map<String, String> tokens(List<Member> members) {
        Map<String, String> tokens = new LinkedHashMap<>();
        members.forEach(m -> tokens.put(Long.toString(m.id()), m.token()));
        return tokens;
    }

    private String note(ReviewRequest request) {
        if (request == null) throw badRequest("缺少审核内容");
        if (request.note() != null && request.note().length() > 500) throw badRequest("审核说明最多 500 字");
        return request.note();
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
    }

    private static boolean expired(Map<String, Object> run) {
        return !localTime(run.get("expires_at")).isAfter(now());
    }

    private static void normalizeDates(Map<String, Object> row, String... fields) {
        for (String field : fields) {
            if (row.get(field) != null) row.put(field, localTime(row.get(field)).toString());
        }
    }

    private static LocalDateTime localTime(Object value) {
        return value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }

    private JsonNode read(Object value) {
        try {
            return json.readTree(value.toString());
        } catch (JsonProcessingException e) {
            throw conflict("建议的权重快照格式无效");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存权重快照", e);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
