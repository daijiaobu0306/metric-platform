package com.metric.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位（面向人员）维度服务。
 *
 * <p>设计边界（2026-11-17 与用户确认）：
 * <ul>
 *   <li><b>岗位 ≠ sys_role</b>。sys_role 决定能看哪些菜单/专屏；岗位是业务标签，
 *       只用于指标分组，不参与鉴权。</li>
 *   <li><b>一个指标只挂一个岗位</b>（业务确认），因此不存在一个指标被两个岗位重复计入。</li>
 * </ul>
 *
 * <p>2026-11-17 二次收敛：本类原来还负责「分岗位健康度加权聚合」，供运营观测「人员视角」
 * 和专屏「岗位」下拉取数。人员视角已下线、专屏岗位过滤已改为健康度观测页签里的角色卡片
 * （前端按 healthCards 的 audienceCode/audienceName 分组，算术平均），
 * 因此聚合逻辑整体删除，本类只保留字典、编码归一和名称查询，避免留下第二套健康度口径。
 */
@Service
@Transactional(readOnly = true)
public class MetricAudienceService {

    /** 岗位排序 + 编码（页面下拉共用）。 */
    public record Audience(String code, String name, int sortOrder) {}

    /** 旧自由文本别名 → 岗位编码。业务口径：业务维护人员/维护人员 统一为「维护人员」。 */
    private static final Map<String, String> NAME_ALIASES = Map.of(
            "业务维护人员", "maintainer",
            "维护人员", "maintainer",
            "网格经理", "grid_leader",
            "营业人员", "salesperson",
            "销售人员", "direct_sales"
    );

    private final JdbcTemplate jdbc;

    public MetricAudienceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 字典 ====================

    /** 启用中的岗位，按 sort_order 排序。 */
    public List<Audience> activeAudiences() {
        return jdbc.query("""
                SELECT audience_code,audience_name,sort_order FROM metric_audience
                WHERE status=1 ORDER BY sort_order,id
                """, (rs, i) -> new Audience(rs.getString(1), rs.getString(2), rs.getInt(3)));
    }

    /** 岗位字典 + 指标数量，供指标体系配置的「面向人员」下拉使用（含指标数为 0 的岗位）。 */
    public List<Map<String, Object>> catalog() {
        return jdbc.queryForList("""
                SELECT a.audience_code AS audienceCode,a.audience_name AS audienceName,
                       a.sort_order AS sortOrder,
                       COUNT(i.id) AS metricCount,
                       COALESCE(SUM(CASE WHEN i.status=1 THEN 1 ELSE 0 END),0) AS enabledMetricCount
                FROM metric_audience a LEFT JOIN metric_item i ON i.audience_code=a.audience_code
                WHERE a.status=1
                GROUP BY a.id,a.audience_code,a.audience_name,a.sort_order
                ORDER BY a.sort_order,a.id
                """);
    }

    /**
     * 自由文本「面向人员」→ 岗位编码；识别不了返回 null。
     *
     * <p>导入 Excel 时用：先把原文归一（去空白、全角括号等），
     * 再按字典名称 + 别名匹配，匹配不到就留空，交由页面手工指定，
     * 不猜岗位（猜错会污染岗位健康度）。
     */
    public String resolveCode(String rawText) {
        if (rawText == null) return null;
        String text = normalizeText(rawText);
        if (text.isEmpty()) return null;
        Map<String, String> index = nameIndex();
        String code = index.get(text);
        if (code != null) return code;
        for (var entry : index.entrySet()) {
            if (text.contains(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    /** 岗位编码 → 名称。 */
    public String nameOf(String code) {
        if (code == null || code.isBlank()) return null;
        List<String> names = jdbc.queryForList(
                "SELECT audience_name FROM metric_audience WHERE audience_code=?", String.class, code);
        return names.isEmpty() ? null : names.get(0);
    }

    // ==================== 字典维护（2026-11-18） ====================

    /**
     * 岗位字典表单。
     *
     * <p>编码只在新增时可填，之后不可改 —— 指标表存的是编码，编码一改等于把所有
     * 已有指标断链。名称/排序/状态可改。
     */
    public record AudienceForm(String audienceCode, String audienceName, Integer sortOrder, Integer status) {}

    /** 管理端视图：全部岗位（含停用）+ 引用指标数 + 指标里出现但未登记的编码。 */
    public Map<String, Object> adminCatalog() {
        List<Map<String, Object>> list = jdbc.queryForList("""
                SELECT a.id, a.audience_code AS audienceCode, a.audience_name AS audienceName,
                       a.sort_order AS sortOrder, a.status,
                       COUNT(i.id) AS metricCount,
                       COALESCE(SUM(CASE WHEN i.status = 1 THEN 1 ELSE 0 END), 0) AS enabledMetricCount
                FROM metric_audience a
                LEFT JOIN metric_item i ON i.audience_code = a.audience_code
                GROUP BY a.id, a.audience_code, a.audience_name, a.sort_order, a.status
                ORDER BY a.sort_order, a.id
                """);
        // 兜底发现：指标里挂了、字典里没有的编码（历史数据/直接改库留下的），
        // 不登记的话这些角色在页面下拉和角色卡片里都会消失。
        List<Map<String, Object>> unregistered = jdbc.queryForList("""
                SELECT i.audience_code AS audienceCode,
                       COUNT(*) AS metricCount,
                       MAX(i.responsible_person) AS sampleName
                FROM metric_item i
                LEFT JOIN metric_audience a ON a.audience_code = i.audience_code
                WHERE i.audience_code IS NOT NULL AND i.audience_code <> '' AND a.id IS NULL
                GROUP BY i.audience_code
                ORDER BY metricCount DESC, i.audience_code
                """);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("unregistered", unregistered);
        result.put("nextSortOrder", nextSortOrder());
        return result;
    }

    @Transactional
    public Map<String, Object> createAudience(AudienceForm form) {
        String code = requireCode(form == null ? null : form.audienceCode());
        String name = requireName(form == null ? null : form.audienceName());
        if (exists("SELECT COUNT(*) FROM metric_audience WHERE audience_code = ?", code)) {
            throw new IllegalArgumentException("岗位编码「" + code + "」已存在");
        }
        if (exists("SELECT COUNT(*) FROM metric_audience WHERE audience_name = ?", name)) {
            throw new IllegalArgumentException("岗位名称「" + name + "」已存在");
        }
        int sortOrder = form.sortOrder() == null ? nextSortOrder() : form.sortOrder();
        int status = form.status() == null ? 1 : form.status();
        jdbc.update("""
                INSERT INTO metric_audience (audience_code, audience_name, sort_order, status)
                VALUES (?, ?, ?, ?)
                """, code, name, sortOrder, status);
        return rowOf(code);
    }

    /**
     * 改名称/排序/状态。改名称时同步回填指标上的「面向人员」文本快照，
     * 否则列表（读字典）和详情/导出（读快照）会显示两个名字。
     */
    @Transactional
    public Map<String, Object> updateAudience(Long id, AudienceForm form) {
        Map<String, Object> existing = rowOfId(id);
        if (existing == null) throw new IllegalArgumentException("岗位不存在：" + id);
        String code = String.valueOf(existing.get("audienceCode"));
        String name = requireName(form == null ? null : form.audienceName());
        int sortOrder = form.sortOrder() == null ? ((Number) existing.get("sortOrder")).intValue() : form.sortOrder();
        int status = form.status() == null ? ((Number) existing.get("status")).intValue() : form.status();
        if (exists("SELECT COUNT(*) FROM metric_audience WHERE audience_name = ? AND id <> ?", name, id)) {
            throw new IllegalArgumentException("岗位名称「" + name + "」已存在");
        }
        long refs = countMetrics(code);
        // 停用允许保留指标引用：停用只表示「不再作为角色维度展示」（下拉、专屏角色卡片隐藏），
        // 指标本身的「面向人员」编码保留，重新启用后自动恢复；删除才要求先改挂。
        jdbc.update("UPDATE metric_audience SET audience_name = ?, sort_order = ?, status = ? WHERE id = ?",
                name, sortOrder, status, id);
        int synced = 0;
        if (!name.equals(String.valueOf(existing.get("audienceName")))) {
            synced = jdbc.update("UPDATE metric_item SET responsible_person = ? WHERE audience_code = ?", name, code);
        }
        Map<String, Object> result = rowOf(code);
        result.put("syncedMetrics", synced);
        result.put("retainedMetricRefs", status == 0 ? refs : 0);
        return result;
    }

    @Transactional
    public void deleteAudience(Long id) {
        Map<String, Object> existing = rowOfId(id);
        if (existing == null) throw new IllegalArgumentException("岗位不存在：" + id);
        long refs = countMetrics(String.valueOf(existing.get("audienceCode")));
        if (refs > 0) {
            throw new IllegalStateException("该岗位下还有 " + refs + " 个指标，不能删除；建议改为停用或先改挂到其他岗位");
        }
        jdbc.update("DELETE FROM metric_audience WHERE id = ?", id);
    }

    private Map<String, Object> rowOf(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.audience_code AS audienceCode, a.audience_name AS audienceName,
                       a.sort_order AS sortOrder, a.status,
                       (SELECT COUNT(*) FROM metric_item i WHERE i.audience_code = a.audience_code) AS metricCount
                FROM metric_audience a WHERE a.audience_code = ?
                """, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> rowOfId(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.audience_code AS audienceCode, a.audience_name AS audienceName,
                       a.sort_order AS sortOrder, a.status
                FROM metric_audience a WHERE a.id = ?
                """, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long countMetrics(String code) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM metric_item WHERE audience_code = ?", Long.class, code);
        return count == null ? 0 : count;
    }

    private int nextSortOrder() {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 0) FROM metric_audience", Integer.class);
        return (max == null ? 0 : max) + 10;
    }

    private boolean exists(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count != null && count > 0;
    }

    /** 编码是稳定标识：非空、不含空白、不超过 50 字符（历史数据里可能是中文编码，不强制英文）。 */
    private static String requireCode(String raw) {
        String code = raw == null ? "" : raw.trim();
        if (code.isEmpty()) throw new IllegalArgumentException("岗位编码不能为空");
        if (code.length() > 50) throw new IllegalArgumentException("岗位编码不能超过 50 个字符");
        if (code.matches(".*\\s+.*")) throw new IllegalArgumentException("岗位编码不能包含空格");
        return code;
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("岗位名称不能为空");
        if (name.length() > 100) throw new IllegalArgumentException("岗位名称不能超过 100 个字符");
        return name;
    }

    /** 归一化后的「名称/别名 → 编码」索引。 */
    private Map<String, String> nameIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (Audience a : activeAudiences()) index.put(normalizeText(a.name()), a.code());
        for (var entry : NAME_ALIASES.entrySet()) index.putIfAbsent(normalizeText(entry.getKey()), entry.getValue());
        return index;
    }

    private static String normalizeText(String raw) {
        return raw == null ? "" : raw.replace('（', '(').replace('）', ')')
                .replaceAll("\\s+", "").trim();
    }
}
