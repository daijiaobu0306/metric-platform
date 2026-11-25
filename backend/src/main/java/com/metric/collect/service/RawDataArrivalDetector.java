package com.metric.collect.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「数据汇聚」通知的原始数据到达检测器。
 *
 * <p>口径（用户明确）：这个通知只看<b>原始数据有没有新进来</b>，以及<b>进来多少条</b>；
 * 不看指标值、指标数据时间有没有变化 —— 指标是原始数据加工出来的结果，不是汇聚本身。
 *
 * <p>平台里原始数据落在两张表：
 * <ul>
 *   <li>{@code collection_file}：5 个文件采集源（业务价值 / 外部客户指标值 / 资源投入·需求开发 /
 *       运行效率 / 满意度及投诉服务体验），每导入一个文件一行，{@code row_count} 是解析出的原始数据行数；</li>
 *   <li>{@code original_data_import_log}：扫描类入库（集核、性能、错误、内部数据等），
 *       每导入一个文件一行，{@code record_count} 是写入记录数。</li>
 * </ul>
 *
 * <p>检测方式：各自维护一个水位（{@code collection_file.collected_at} 最大值、
 * {@code original_data_import_log.id} 最大值），只统计水位之后新增的文件及其数据条数，
 * 因此同一批数据不会被重复播报。启动后首轮只建立基线，不通知。
 *
 * <p>只读组件，不写任何业务表，也不改指标监控的代码。
 */
@Component
public class RawDataArrivalDetector {

    private static final Logger log = LoggerFactory.getLogger(RawDataArrivalDetector.class);

    /** 单条通知最多列几个来源，超出折叠成"等 N 个来源"，避免通知过长 */
    public static final int MAX_SOURCE_ITEMS = 3;

    /** 水位为空（表里还没有数据）时的起点，避免 NULL 比较导致漏统计 */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    @PersistenceContext
    private EntityManager entityManager;

    /** 文件采集水位：collection_file.collected_at 的已播报最大值 */
    private LocalDateTime fileWatermark;

    /** 扫描入库水位：original_data_import_log.id 的已播报最大值 */
    private Long importWatermark;

    private boolean baselineReady;

    /**
     * 检查是否有新到达的原始数据。
     *
     * @return 到达汇总；没有新数据（或首轮建立基线）时返回空汇总
     */
    public synchronized ArrivalSummary check() {
        LocalDateTime maxCollectedAt = queryMaxCollectedAt();
        Long maxImportId = queryMaxImportId();

        // 启动后第一轮只建立基线，避免把历史存量当成"刚进来的数据"播报
        if (!baselineReady) {
            fileWatermark = maxCollectedAt;
            importWatermark = maxImportId;
            baselineReady = true;
            return ArrivalSummary.empty();
        }

        Map<String, Long> bySource = new LinkedHashMap<>();

        // 1) 文件采集新增
        LocalDateTime fileFrom = fileWatermark == null ? EPOCH : fileWatermark;
        for (Object[] row : query(new String[]{
                "SELECT f.source_code, COALESCE(SUM(f.row_count), 0)",
                "FROM collection_file f WHERE f.collected_at > ? GROUP BY f.source_code"},
                fileFrom)) {
            String code = row[0] == null ? null : String.valueOf(row[0]);
            long count = toLong(row[1]);
            if (count > 0) {
                bySource.merge(resolveFileName(code), count, Long::sum);
            }
        }

        // 2) 扫描类入库新增
        long importFrom = importWatermark == null ? 0L : importWatermark;
        for (Object[] row : query(new String[]{
                "SELECT l.sub_category_code, COALESCE(SUM(l.record_count), 0)",
                "FROM original_data_import_log l WHERE l.id > ? GROUP BY l.sub_category_code"},
                importFrom)) {
            String code = row[0] == null ? null : String.valueOf(row[0]);
            long count = toLong(row[1]);
            if (count > 0) {
                bySource.merge(resolveImportName(code), count, Long::sum);
            }
        }

        // 无论是否统计到，水位都要推进，否则同一批会被反复检查
        if (maxCollectedAt != null) fileWatermark = maxCollectedAt;
        if (maxImportId != null) importWatermark = maxImportId;

        if (bySource.isEmpty()) {
            return ArrivalSummary.empty();
        }
        return new ArrivalSummary(sortByCountDesc(bySource));
    }

    // ---------------- 名称解析：用页面上真实存在的名称，不臆造 ----------------

    /** 文件采集源编码 → 采集源名称（与「数据汇聚」页一致） */
    private String resolveFileName(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) return "未标注采集源";
        Map<String, String> names = queryCodeNameMap("SELECT code, name FROM collection_source");
        return names.getOrDefault(sourceCode, sourceCode);
    }

    /** 扫描入库通道编码 → 小类名称；字典里没有该通道时如实显示通道编码 */
    private String resolveImportName(String subCategoryCode) {
        if (subCategoryCode == null || subCategoryCode.isBlank()) return "未标注通道";
        Map<String, String> names = queryCodeNameMap(
                "SELECT sub_category_code, sub_category_name FROM metric_sub_category");
        return names.getOrDefault(subCategoryCode, subCategoryCode);
    }

    // ---------------- 查询辅助 ----------------

    private Map<String, String> queryCodeNameMap(String sql) {
        Map<String, String> map = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();
            for (Object[] row : rows) {
                if (row[0] == null) continue;
                map.put(String.valueOf(row[0]), row[1] == null ? String.valueOf(row[0]) : String.valueOf(row[1]));
            }
        } catch (Exception e) {
            log.warn("读取来源名称失败: {}", e.getMessage());
        }
        return map;
    }

    private List<Object[]> query(String[] sqlParts, Object parameter) {
        String sql = String.join(" ", sqlParts);
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = entityManager.createNativeQuery(sql)
                    .setParameter(1, parameter)
                    .getResultList();
            return rows;
        } catch (Exception e) {
            log.warn("查询原始数据到达情况失败: {}", e.getMessage());
            return List.of();
        }
    }

    private LocalDateTime queryMaxCollectedAt() {
        try {
            Object value = entityManager
                    .createNativeQuery("SELECT MAX(collected_at) FROM collection_file")
                    .getSingleResult();
            if (value == null) return null;
            if (value instanceof LocalDateTime dt) return dt;
            if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
            return null;
        } catch (Exception e) {
            log.warn("读取文件采集水位失败: {}", e.getMessage());
            return null;
        }
    }

    private Long queryMaxImportId() {
        try {
            Object value = entityManager
                    .createNativeQuery("SELECT MAX(id) FROM original_data_import_log")
                    .getSingleResult();
            return value == null ? null : toLong(value);
        } catch (Exception e) {
            log.warn("读取原始数据入库水位失败: {}", e.getMessage());
            return null;
        }
    }

    private static long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number number) return number.longValue();
        if (value instanceof BigDecimal decimal) return decimal.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static LinkedHashMap<String, Long> sortByCountDesc(Map<String, Long> source) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(source.entrySet());
        list.sort((a, b) -> {
            int diff = Long.compare(b.getValue(), a.getValue());
            return diff != 0 ? diff : a.getKey().compareTo(b.getKey());
        });
        LinkedHashMap<String, Long> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : list) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }

    /** 一次检测到的原始数据到达汇总 */
    public static final class ArrivalSummary {

        private static final ArrivalSummary EMPTY = new ArrivalSummary(new LinkedHashMap<>());

        /** 来源名称 → 原始数据条数，按条数降序 */
        private final LinkedHashMap<String, Long> bySource;

        private ArrivalSummary(LinkedHashMap<String, Long> bySource) {
            this.bySource = bySource;
        }

        public static ArrivalSummary empty() {
            return EMPTY;
        }

        public boolean isEmpty() {
            return bySource.isEmpty();
        }

        public long total() {
            long total = 0;
            for (Long count : bySource.values()) total += count;
            return total;
        }

        public Map<String, Long> bySource() {
            return bySource;
        }

        /**
         * 简短文案：最多列 maxItems 个来源，形如 "业务价值 12 条 · 系统稽核 104 条"，
         * 超出时追加 "等 N 个来源"，避免一条通知占太多屏幕空间。
         */
        public String brief(int maxItems) {
            if (bySource.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Map.Entry<String, Long> entry : bySource.entrySet()) {
                if (shown >= maxItems) break;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(entry.getKey()).append(" ").append(entry.getValue()).append(" 条");
                shown++;
            }
            if (bySource.size() > shown) {
                sb.append(" 等 ").append(bySource.size()).append(" 个来源");
            }
            return sb.toString();
        }
    }
}
