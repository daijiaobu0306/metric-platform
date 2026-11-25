package com.metric.observe.service;

import com.metric.observe.entity.*;
import com.metric.observe.repository.*;
import com.metric.metric.entity.CategoryHealthHistory;
import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricHealthHistory;
import com.metric.metric.entity.SubCategoryHealthHistory;
import com.metric.metric.entity.SystemInfo;
import com.metric.metric.repository.CategoryHealthHistoryRepository;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.MetricHealthHistoryRepository;
import com.metric.metric.repository.SubCategoryHealthHistoryRepository;
import com.metric.metric.repository.SystemInfoRepository;
import com.metric.metric.service.WeightResolver;
import com.metric.metric.service.WeightConfigService;
import com.metric.config.service.ConfigService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * 健康度快照聚合服务
 *
 * 负责把明细表数据聚合并写入 5 张预聚合快照表：
 * - health_snapshot_1h ← metric_health_history / sub_category_health_history / category_health_history
 * - health_snapshot_6h ← health_snapshot_1h
 * - health_snapshot_1d ← health_snapshot_1h
 * - health_snapshot_7d ← health_snapshot_1d
 * - health_snapshot_30d ← health_snapshot_1d
 *
 * 系统层（level_type=system）由 CategoryHealthHistory 通过权重配置加权聚合
 *
 * 幂等性：每次聚合前先按 snapshot_time + level_type + 层级字段 查找并删除旧记录
 */
@Service
public class HealthSnapshotAggregationService {

    @Autowired private HealthSnapshot1hRepository snapshot1hRepo;
    @Autowired private HealthSnapshot6hRepository snapshot6hRepo;
    @Autowired private HealthSnapshot1dRepository snapshot1dRepo;
    @Autowired private HealthSnapshot7dRepository snapshot7dRepo;
    @Autowired private HealthSnapshot30dRepository snapshot30dRepo;

    @Autowired private MetricHealthHistoryRepository metricHealthRepo;
    @Autowired private SubCategoryHealthHistoryRepository subCategoryHealthRepo;
    @Autowired private ConfigService configService;

    /** system_config key: 健康度/权重默认 systemId（V20260914110001 新增，修复前硬编码 8L）*/
    private static final String CONFIG_KEY_DEFAULT_SYSTEM_ID = "metric.health.default-system-id";
    @Autowired private CategoryHealthHistoryRepository categoryHealthRepo;

    @Autowired private WeightConfigService weightConfigService;

    // 2026-11-17 P3：系统层聚合改为按系统 + 统一权重，与实时口径共用 WeightResolver
    @Autowired private WeightResolver weightResolver;
    @Autowired private MetricCategoryRepository categoryRepository;
    @Autowired private SystemInfoRepository systemInfoRepository;
    @Autowired private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    /** 4 大类名称（与 ObservationController 中保持一致） */
    private static final List<String> SYSTEM_CATEGORIES = List.of("对内部人员", "对外部客户", "运行效率", "计划执行");

    // ============================================================
    // 1 小时粒度 - 聚合明细表
    // ============================================================

    /**
     * 聚合上一小时明细数据 → health_snapshot_1h
     * @param hourStart 上一个小时的整点（例如 14:00:00 表示聚合 14:00-15:00 的数据）
     */
    @Transactional
    public int aggregateHourly(LocalDateTime hourStart) {
        LocalDateTime hourEnd = hourStart.plusHours(1);

        // 先删除该时段的旧快照（幂等性）
        deleteExistingSnapshots(snapshot1hRepo, "metric", hourStart);
        deleteExistingSnapshots(snapshot1hRepo, "subcategory", hourStart);
        deleteExistingSnapshots(snapshot1hRepo, "category", hourStart);
        deleteExistingSnapshots(snapshot1hRepo, "system", hourStart);

        int count = 0;

        // 1. metric 层聚合
        count += aggregateMetricLevelToHourly(hourStart, hourEnd);

        // 2. subcategory 层聚合
        count += aggregateSubCategoryLevelToHourly(hourStart, hourEnd);

        // 3. category 层聚合
        count += aggregateCategoryLevelToHourly(hourStart, hourEnd);

        // 4. system 层聚合（按 4 大类加权）
        count += aggregateSystemLevelToHourly(hourStart);

        return count;
    }

    private int aggregateMetricLevelToHourly(LocalDateTime start, LocalDateTime end) {
        // 从明细查该时段所有 metric 行
        List<MetricHealthHistory> rows = metricHealthRepo.findAll().stream()
                .filter(r -> r.getRecordTime() != null
                        && !r.getRecordTime().isBefore(start)
                        && r.getRecordTime().isBefore(end))
                .toList();

        // 按 metricId 分组聚合
        Map<Long, List<MetricHealthHistory>> grouped = new HashMap<>();
        for (MetricHealthHistory r : rows) {
            grouped.computeIfAbsent(r.getMetricId(), k -> new ArrayList<>()).add(r);
        }

        int saved = 0;
        for (Map.Entry<Long, List<MetricHealthHistory>> e : grouped.entrySet()) {
            HealthSnapshot1h snap = aggregateMetric(e.getValue(), start);
            snapshot1hRepo.save(snap);
            saved++;
        }
        return saved;
    }

    private int aggregateSubCategoryLevelToHourly(LocalDateTime start, LocalDateTime end) {
        List<SubCategoryHealthHistory> rows = subCategoryHealthRepo.findAll().stream()
                .filter(r -> r.getRecordTime() != null
                        && !r.getRecordTime().isBefore(start)
                        && r.getRecordTime().isBefore(end))
                .toList();

        Map<String, List<SubCategoryHealthHistory>> grouped = new HashMap<>();
        for (SubCategoryHealthHistory r : rows) {
            String key = r.getCategoryName() + "|" + r.getSubCategoryName();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        int saved = 0;
        for (List<SubCategoryHealthHistory> group : grouped.values()) {
            HealthSnapshot1h snap = aggregateSubCategory(group, start);
            if (snap != null) {
                snapshot1hRepo.save(snap);
                saved++;
            }
        }
        return saved;
    }

    private int aggregateCategoryLevelToHourly(LocalDateTime start, LocalDateTime end) {
        List<CategoryHealthHistory> rows = categoryHealthRepo.findAll().stream()
                .filter(r -> r.getRecordTime() != null
                        && !r.getRecordTime().isBefore(start)
                        && r.getRecordTime().isBefore(end))
                .toList();

        Map<String, List<CategoryHealthHistory>> grouped = new HashMap<>();
        for (CategoryHealthHistory r : rows) {
            grouped.computeIfAbsent(r.getCategoryName(), k -> new ArrayList<>()).add(r);
        }

        int saved = 0;
        for (List<CategoryHealthHistory> group : grouped.values()) {
            HealthSnapshot1h snap = aggregateCategory(group, start);
            if (snap != null) {
                snapshot1hRepo.save(snap);
                saved++;
            }
        }
        return saved;
    }

    private int aggregateSystemLevelToHourly(LocalDateTime start) {
        // 从刚刚写好的 health_snapshot_1h (level_type=category) 聚合
        List<HealthSnapshot1h> categorySnaps = snapshot1hRepo.findAll().stream()
                .filter(s -> "category".equals(s.getLevelType())
                        && s.getSnapshotTime().equals(start))
                .toList();

        if (categorySnaps.isEmpty()) return 0;

        // 2026-11-17 P3：系统层不再写死 0.25 权重、也不再只写 systemId=1，
        // 改为按系统分别计算：权重走统一 WeightResolver，缺分大类不参与分母。
        int saved = 0;
        for (var entry : systemSnapshotsFromCategories(categorySnaps).entrySet()) {
            HealthSnapshot1h snap = new HealthSnapshot1h();
            snap.setLevelType("system");
            snap.setSystemId(entry.getKey());
            snap.setSnapshotTime(start);
            applySystemSnapshot(snap, entry.getValue());
            snapshot1hRepo.save(snap);
            saved++;
        }
        return saved;
    }

    // ============================================================
    // 6 小时粒度 - 聚合 1h 快照
    // ============================================================

    @Transactional
    public int aggregateSixHourly(LocalDateTime sixHourStart) {
        LocalDateTime sixHourEnd = sixHourStart.plusHours(6);

        deleteExistingSnapshots(snapshot6hRepo, "metric", sixHourStart);
        deleteExistingSnapshots(snapshot6hRepo, "subcategory", sixHourStart);
        deleteExistingSnapshots(snapshot6hRepo, "category", sixHourStart);
        deleteExistingSnapshots(snapshot6hRepo, "system", sixHourStart);

        int count = 0;
        count += aggregateFromHourlyToBigger(snapshot1hRepo, snapshot6hRepo, "metric", sixHourStart, sixHourEnd);
        count += aggregateFromHourlyToBigger(snapshot1hRepo, snapshot6hRepo, "subcategory", sixHourStart, sixHourEnd);
        count += aggregateFromHourlyToBigger(snapshot1hRepo, snapshot6hRepo, "category", sixHourStart, sixHourEnd);
        // system 层：直接根据 6h 内 1h 的 system 快照加权
        count += aggregateSystemFromHourly(snapshot1hRepo, snapshot6hRepo, sixHourStart, sixHourEnd);
        return count;
    }

    // ============================================================
    // 1 天粒度 - 聚合 1h 快照
    // ============================================================

    @Transactional
    public int aggregateDaily(LocalDateTime dateStart) {
        LocalDateTime dateEnd = dateStart.plusDays(1);

        deleteExistingSnapshots(snapshot1dRepo, "metric", dateStart);
        deleteExistingSnapshots(snapshot1dRepo, "subcategory", dateStart);
        deleteExistingSnapshots(snapshot1dRepo, "category", dateStart);
        deleteExistingSnapshots(snapshot1dRepo, "system", dateStart);

        int count = 0;
        count += aggregateFromHourlyToBigger(snapshot1hRepo, snapshot1dRepo, "metric", dateStart, dateEnd);
        count += aggregateFromHourlyToBigger(snapshot1hRepo, snapshot1dRepo, "subcategory", dateStart, dateEnd);
        count += aggregateFromHourlyToBigger(snapshot1hRepo, snapshot1dRepo, "category", dateStart, dateEnd);
        count += aggregateSystemFromHourly(snapshot1hRepo, snapshot1dRepo, dateStart, dateEnd);
        return count;
    }

    // ============================================================
    // 7 天粒度 - 聚合 1d 快照
    // ============================================================

    @Transactional
    public int aggregateWeekly(LocalDateTime weekStart) {
        LocalDateTime weekEnd = weekStart.plusDays(7);

        deleteExistingSnapshots(snapshot7dRepo, "metric", weekStart);
        deleteExistingSnapshots(snapshot7dRepo, "subcategory", weekStart);
        deleteExistingSnapshots(snapshot7dRepo, "category", weekStart);
        deleteExistingSnapshots(snapshot7dRepo, "system", weekStart);

        int count = 0;
        count += aggregateFromDailyToBigger(snapshot1dRepo, snapshot7dRepo, "metric", weekStart, weekEnd);
        count += aggregateFromDailyToBigger(snapshot1dRepo, snapshot7dRepo, "subcategory", weekStart, weekEnd);
        count += aggregateFromDailyToBigger(snapshot1dRepo, snapshot7dRepo, "category", weekStart, weekEnd);
        count += aggregateSystemFromDaily(snapshot1dRepo, snapshot7dRepo, weekStart, weekEnd);
        return count;
    }

    // ============================================================
    // 30 天粒度 - 聚合 1d 快照
    // ============================================================

    @Transactional
    public int aggregateMonthly(LocalDateTime monthStart) {
        LocalDateTime monthEnd = monthStart.plusMonths(1);

        deleteExistingSnapshots(snapshot30dRepo, "metric", monthStart);
        deleteExistingSnapshots(snapshot30dRepo, "subcategory", monthStart);
        deleteExistingSnapshots(snapshot30dRepo, "category", monthStart);
        deleteExistingSnapshots(snapshot30dRepo, "system", monthStart);

        int count = 0;
        count += aggregateFromDailyToBigger(snapshot1dRepo, snapshot30dRepo, "metric", monthStart, monthEnd);
        count += aggregateFromDailyToBigger(snapshot1dRepo, snapshot30dRepo, "subcategory", monthStart, monthEnd);
        count += aggregateFromDailyToBigger(snapshot1dRepo, snapshot30dRepo, "category", monthStart, monthEnd);
        count += aggregateSystemFromDaily(snapshot1dRepo, snapshot30dRepo, monthStart, monthEnd);
        return count;
    }

    // ============================================================
    // 通用聚合：从小时快照聚合到更大粒度
    // ============================================================

    private <T extends HealthSnapshotBase> int aggregateFromHourlyToBigger(
            HealthSnapshot1hRepository sourceRepo,
            JpaRepository<T, Long> targetRepo,
            String levelType,
            LocalDateTime rangeStart,
            LocalDateTime rangeEnd) {

        List<HealthSnapshot1h> source = sourceRepo.findAll().stream()
                .filter(s -> levelType.equals(s.getLevelType())
                        && !s.getSnapshotTime().isBefore(rangeStart)
                        && s.getSnapshotTime().isBefore(rangeEnd))
                .toList();

        return aggregateAndSaveHourlyGroup(source, levelType, rangeStart, targetRepo);
    }

    private <T extends HealthSnapshotBase> int aggregateFromDailyToBigger(
            HealthSnapshot1dRepository sourceRepo,
            JpaRepository<T, Long> targetRepo,
            String levelType,
            LocalDateTime rangeStart,
            LocalDateTime rangeEnd) {

        List<HealthSnapshot1d> source = sourceRepo.findAll().stream()
                .filter(s -> levelType.equals(s.getLevelType())
                        && !s.getSnapshotTime().isBefore(rangeStart)
                        && s.getSnapshotTime().isBefore(rangeEnd))
                .toList();

        return aggregateAndSaveDailyGroup(source, levelType, rangeStart, targetRepo);
    }

    private <T extends HealthSnapshotBase> int aggregateAndSaveHourlyGroup(
            List<HealthSnapshot1h> source, String levelType, LocalDateTime snapshotTime,
            JpaRepository<T, Long> targetRepo) {

        // 按层级字段分组
        Map<String, List<HealthSnapshot1h>> grouped = groupByLevelFields(source, levelType);

        int saved = 0;
        for (List<HealthSnapshot1h> group : grouped.values()) {
            T snap = createTargetSnapshot(targetRepo, levelType, snapshotTime);
            applyAggregationToTarget(snap, group);
            copyLevelFieldsFromHourly(snap, group.get(0));
            targetRepo.save(snap);
            saved++;
        }
        return saved;
    }

    private <T extends HealthSnapshotBase> int aggregateAndSaveDailyGroup(
            List<HealthSnapshot1d> source, String levelType, LocalDateTime snapshotTime,
            JpaRepository<T, Long> targetRepo) {

        Map<String, List<HealthSnapshot1d>> grouped = groupByLevelFields(source, levelType);

        int saved = 0;
        for (List<HealthSnapshot1d> group : grouped.values()) {
            T snap = createTargetSnapshot(targetRepo, levelType, snapshotTime);
            applyAggregationToTarget(snap, group);
            copyLevelFieldsFromDaily(snap, group.get(0));
            targetRepo.save(snap);
            saved++;
        }
        return saved;
    }

    private <T extends HealthSnapshotBase> int aggregateSystemFromHourly(
            HealthSnapshot1hRepository sourceRepo,
            JpaRepository<T, Long> targetRepo,
            LocalDateTime rangeStart,
            LocalDateTime rangeEnd) {

        List<HealthSnapshot1h> source = sourceRepo.findAll().stream()
                .filter(s -> "category".equals(s.getLevelType())
                        && !s.getSnapshotTime().isBefore(rangeStart)
                        && s.getSnapshotTime().isBefore(rangeEnd))
                .toList();

        if (source.isEmpty()) return 0;

        int saved = 0;
        for (var entry : systemSnapshotsFromCategories(source).entrySet()) {
            T snap = createTargetSnapshot(targetRepo, "system", rangeStart);
            snap.setSystemId(entry.getKey());
            applySystemSnapshot(snap, entry.getValue());
            targetRepo.save(snap);
            saved++;
        }
        return saved;
    }

    private <T extends HealthSnapshotBase> int aggregateSystemFromDaily(
            HealthSnapshot1dRepository sourceRepo,
            JpaRepository<T, Long> targetRepo,
            LocalDateTime rangeStart,
            LocalDateTime rangeEnd) {

        List<HealthSnapshot1d> source = sourceRepo.findAll().stream()
                .filter(s -> "category".equals(s.getLevelType())
                        && !s.getSnapshotTime().isBefore(rangeStart)
                        && s.getSnapshotTime().isBefore(rangeEnd))
                .toList();

        if (source.isEmpty()) return 0;

        int saved = 0;
        for (var entry : systemSnapshotsFromCategories(source).entrySet()) {
            T snap = createTargetSnapshot(targetRepo, "system", rangeStart);
            snap.setSystemId(entry.getKey());
            applySystemSnapshot(snap, entry.getValue());
            targetRepo.save(snap);
            saved++;
        }
        return saved;
    }

    /** 系统层聚合结果：systemId → 分数/状态/计数 */
    private record SystemAgg(Integer score, String status, int normalCount, int warningCount,
                             int alarmCount, int aggregateCount) {}

    /**
     * 把 category 层快照按“系统名下大类 + 统一权重”换算成系统分。
     *
     * <p>改造前这里用简单平均 + 硬编码 0.25，导致系统分与实时口径不一致（验收要求差值 ≤ 1 分）。
     * 现在与实时层共用 WeightResolver：只对参与计算的大类归一，缺分大类不占权重。
     */
    private Map<Long, SystemAgg> systemSnapshotsFromCategories(
            List<? extends HealthSnapshotBase> categoryRows) {
        Map<Long, SystemAgg> result = new LinkedHashMap<>();
        if (categoryRows.isEmpty()) return result;

        WeightResolver.WeightSnapshot weights = weightResolver.snapshot();
        Map<String, Long> categoryIdByName = new HashMap<>();
        for (MetricCategory category : categoryRepository.findAll()) {
            categoryIdByName.put(category.getCategoryName(), category.getId());
        }

        for (SystemInfo system : systemInfoRepository.findAll()) {
            if (system.getStatus() != null && system.getStatus() != 1) continue;
            Set<String> names = new HashSet<>();
            for (MetricCategory category : categoryRepository.findBySystemId(system.getId())) {
                names.add(category.getCategoryName());
            }
            double weightedSum = 0;
            double totalWeight = 0;
            int normal = 0, warning = 0, alarm = 0, counted = 0;
            for (HealthSnapshotBase row : categoryRows) {
                if (row.getCategoryName() == null || !names.contains(row.getCategoryName())) continue;
                if (row.getHealthScore() == null) continue;
                Long categoryId = categoryIdByName.get(row.getCategoryName());
                BigDecimal weight = categoryId == null ? BigDecimal.ONE
                        : weights.categoryWeight(system.getId(), categoryId);
                weightedSum += row.getHealthScore() * weight.doubleValue();
                totalWeight += weight.doubleValue();
                if ("alarm".equals(row.getStatus())) alarm++;
                else if ("warning".equals(row.getStatus())) warning++;
                else normal++;
                counted++;
            }
            if (counted == 0) continue;
            int score = totalWeight > 0 ? (int) Math.round(weightedSum / totalWeight) : 100;
            // 状态沿用聚合层比例规则：有 alarm 大类不必然整体 alarm，但快照只拿到大类状态，
            // 这里按“任一大类 alarm 且占比≥20%，或存在 < 40 分”近似，保持与实时口径接近。
            boolean critical = categoryRows.stream()
                    .filter(row -> row.getCategoryName() != null && names.contains(row.getCategoryName()))
                    .anyMatch(row -> row.getHealthScore() != null && row.getHealthScore() < 40);
            String status = (critical || alarm / (double) counted >= 0.2) ? "alarm"
                    : (warning / (double) counted >= 0.3 ? "warning" : "normal");
            result.put(system.getId(), new SystemAgg(score, status, normal, warning, alarm, counted));
        }
        return result;
    }

    private static LocalDateTime toLocalDateTime(Object raw) {
        if (raw == null) return null;
        if (raw instanceof LocalDateTime time) return time;
        if (raw instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if (raw instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay();
        return LocalDateTime.parse(raw.toString().replace(' ', 'T'));
    }

    private void applySystemSnapshot(HealthSnapshotBase target, SystemAgg agg) {
        target.setHealthScore(agg.score());
        target.setStatus(agg.status());
        target.setNormalCount(agg.normalCount());
        target.setWarningCount(agg.warningCount());
        target.setAlarmCount(agg.alarmCount());
        target.setAggregateCount(agg.aggregateCount());
    }

    // ============================================================
    // 通用工具方法
    // ============================================================

    private <T> Map<String, List<T>> groupByLevelFields(List<T> source, String levelType) {
        Map<String, List<T>> grouped = new HashMap<>();
        for (T item : source) {
            String key;
            if (item instanceof HealthSnapshotBase base) {
                key = switch (levelType) {
                    case "metric" -> String.valueOf(base.getMetricId());
                    case "subcategory" -> base.getCategoryName() + "|" + base.getSubcategoryName();
                    case "category" -> base.getCategoryName();
                    default -> "all";
                };
            } else {
                key = "all";
            }
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private void applyAggregationToTarget(HealthSnapshotBase target, List<? extends HealthSnapshotBase> group) {
        // 简单平均
        double avgScore = group.stream()
                .filter(s -> s.getHealthScore() != null)
                .mapToInt(HealthSnapshotBase::getHealthScore)
                .average()
                .orElse(100);

        int totalNormal = group.stream().mapToInt(s -> s.getNormalCount() != null ? s.getNormalCount() : 0).sum();
        int totalWarning = group.stream().mapToInt(s -> s.getWarningCount() != null ? s.getWarningCount() : 0).sum();
        int totalAlarm = group.stream().mapToInt(s -> s.getAlarmCount() != null ? s.getAlarmCount() : 0).sum();

        target.setHealthScore((int) Math.round(avgScore));
        target.setNormalCount(totalNormal);
        target.setWarningCount(totalWarning);
        target.setAlarmCount(totalAlarm);
        target.setAggregateCount(group.size());
        target.setStatus(majorityStatus(group));
    }

    private void copyLevelFieldsFromHourly(HealthSnapshotBase target, HealthSnapshot1h source) {
        target.setMetricId(source.getMetricId());
        target.setSubcategoryName(source.getSubcategoryName());
        target.setCategoryName(source.getCategoryName());
    }

    private void copyLevelFieldsFromDaily(HealthSnapshotBase target, HealthSnapshot1d source) {
        target.setMetricId(source.getMetricId());
        target.setSubcategoryName(source.getSubcategoryName());
        target.setCategoryName(source.getCategoryName());
    }

    /**
     * 反射创建对应 Repository 的实体实例
     */
    @SuppressWarnings("unchecked")
    private <T extends HealthSnapshotBase> T createTargetSnapshot(
            JpaRepository<T, Long> targetRepo, String levelType, LocalDateTime snapshotTime) {
        HealthSnapshotBase snap;
        if (targetRepo instanceof HealthSnapshot6hRepository) {
            snap = new HealthSnapshot6h();
        } else if (targetRepo instanceof HealthSnapshot1dRepository) {
            snap = new HealthSnapshot1d();
        } else if (targetRepo instanceof HealthSnapshot7dRepository) {
            snap = new HealthSnapshot7d();
        } else if (targetRepo instanceof HealthSnapshot30dRepository) {
            snap = new HealthSnapshot30d();
        } else {
            throw new IllegalArgumentException("Unknown target repository: " + targetRepo.getClass());
        }
        snap.setLevelType(levelType);
        snap.setSnapshotTime(snapshotTime);
        return (T) snap;
    }

    /**
     * 众数（最多出现次数的状态）
     */
    private String majorityStatus(List<? extends HealthSnapshotBase> group) {
        Map<String, Integer> count = new HashMap<>();
        for (HealthSnapshotBase s : group) {
            if (s.getStatus() != null) {
                count.merge(s.getStatus(), 1, Integer::sum);
            }
        }
        if (count.isEmpty()) return "normal";
        // 优先级：alarm > warning > normal
        if (count.getOrDefault("alarm", 0) > 0) return "alarm";
        if (count.getOrDefault("warning", 0) > 0) return "warning";
        return "normal";
    }

    // ============================================================
    // 从明细表聚合到 1h 表（仅 metric / subcategory / category 三层）
    // ============================================================

    private HealthSnapshot1h aggregateMetric(List<MetricHealthHistory> rows, LocalDateTime snapshotTime) {
        HealthSnapshot1h snap = new HealthSnapshot1h();
        snap.setLevelType("metric");
        snap.setMetricId(rows.get(0).getMetricId());
        // 注意：metric 层不写 category_name / subcategory_name / system_id，避免与同层其他行的 UNIQUE 约束冲突
        snap.setSnapshotTime(snapshotTime);
        applySimpleAggregation(snap, rows);
        return snap;
    }

    private HealthSnapshot1h aggregateSubCategory(List<SubCategoryHealthHistory> rows, LocalDateTime snapshotTime) {
        if (rows.isEmpty()) return null;
        HealthSnapshot1h snap = new HealthSnapshot1h();
        snap.setLevelType("subcategory");
        snap.setCategoryName(rows.get(0).getCategoryName());
        snap.setSubcategoryName(rows.get(0).getSubCategoryName());
        // subcategory 层不写 metric_id / system_id
        snap.setSnapshotTime(snapshotTime);

        double avgScore = rows.stream()
                .filter(r -> r.getHealthScore() != null)
                .mapToInt(SubCategoryHealthHistory::getHealthScore)
                .average().orElse(100);
        int totalNormal = rows.stream().mapToInt(r -> r.getNormalCount() != null ? r.getNormalCount() : 0).sum();
        int totalWarning = rows.stream().mapToInt(r -> r.getWarningCount() != null ? r.getWarningCount() : 0).sum();
        int totalAlarm = rows.stream().mapToInt(r -> r.getAlarmCount() != null ? r.getAlarmCount() : 0).sum();

        snap.setHealthScore((int) Math.round(avgScore));
        snap.setNormalCount(totalNormal);
        snap.setWarningCount(totalWarning);
        snap.setAlarmCount(totalAlarm);
        snap.setAggregateCount(rows.size());
        snap.setStatus(majorityStatusFromDetail(rows));
        return snap;
    }

    private HealthSnapshot1h aggregateCategory(List<CategoryHealthHistory> rows, LocalDateTime snapshotTime) {
        if (rows.isEmpty()) return null;
        HealthSnapshot1h snap = new HealthSnapshot1h();
        snap.setLevelType("category");
        snap.setCategoryName(rows.get(0).getCategoryName());
        snap.setSnapshotTime(snapshotTime);

        double avgScore = rows.stream()
                .filter(r -> r.getHealthScore() != null)
                .mapToInt(CategoryHealthHistory::getHealthScore)
                .average().orElse(100);
        int totalNormal = rows.stream().mapToInt(r -> r.getNormalCount() != null ? r.getNormalCount() : 0).sum();
        int totalWarning = rows.stream().mapToInt(r -> r.getWarningCount() != null ? r.getWarningCount() : 0).sum();
        int totalAlarm = rows.stream().mapToInt(r -> r.getAlarmCount() != null ? r.getAlarmCount() : 0).sum();

        snap.setHealthScore((int) Math.round(avgScore));
        snap.setNormalCount(totalNormal);
        snap.setWarningCount(totalWarning);
        snap.setAlarmCount(totalAlarm);
        snap.setAggregateCount(rows.size());
        snap.setStatus(majorityStatusFromCategory(rows));
        return snap;
    }

    private void applySimpleAggregation(HealthSnapshotBase target, List<MetricHealthHistory> rows) {
        double avgScore = rows.stream()
                .filter(r -> r.getHealthScore() != null)
                .mapToInt(MetricHealthHistory::getHealthScore)
                .average().orElse(100);
        target.setHealthScore((int) Math.round(avgScore));
        target.setNormalCount((int) rows.stream().filter(r -> "normal".equals(r.getStatus())).count());
        target.setWarningCount((int) rows.stream().filter(r -> "warning".equals(r.getStatus())).count());
        target.setAlarmCount((int) rows.stream().filter(r -> "alarm".equals(r.getStatus())).count());
        target.setAggregateCount(rows.size());

        Map<String, Long> statusCount = rows.stream()
                .filter(r -> r.getStatus() != null)
                .collect(java.util.stream.Collectors.groupingBy(MetricHealthHistory::getStatus, java.util.stream.Collectors.counting()));
        target.setStatus(majorityByCount(statusCount));
    }

    private String majorityStatusFromDetail(List<SubCategoryHealthHistory> rows) {
        Map<String, Long> count = rows.stream()
                .filter(r -> r.getStatus() != null)
                .collect(java.util.stream.Collectors.groupingBy(SubCategoryHealthHistory::getStatus, java.util.stream.Collectors.counting()));
        return majorityByCount(count);
    }

    private String majorityStatusFromCategory(List<CategoryHealthHistory> rows) {
        Map<String, Long> count = rows.stream()
                .filter(r -> r.getStatus() != null)
                .collect(java.util.stream.Collectors.groupingBy(CategoryHealthHistory::getStatus, java.util.stream.Collectors.counting()));
        return majorityByCount(count);
    }

    private String majorityByCount(Map<String, Long> statusCount) {
        if (statusCount.isEmpty()) return "normal";
        if (statusCount.getOrDefault("alarm", 0L) > 0) return "alarm";
        if (statusCount.getOrDefault("warning", 0L) > 0) return "warning";
        return "normal";
    }

    // ============================================================
    // 幂等性：先删除旧快照
    // ============================================================

    private void deleteExistingSnapshots(HealthSnapshot1hRepository repo, String levelType, LocalDateTime snapshotTime) {
        // 通过原生 SQL 删除（保留幂等性，重复执行不会出错）
        entityManager.createNativeQuery(
                "DELETE FROM health_snapshot_1h WHERE level_type = :levelType AND snapshot_time = :snapshotTime")
                .setParameter("levelType", levelType)
                .setParameter("snapshotTime", snapshotTime)
                .executeUpdate();
    }

    private void deleteExistingSnapshots(HealthSnapshot6hRepository repo, String levelType, LocalDateTime snapshotTime) {
        entityManager.createNativeQuery(
                "DELETE FROM health_snapshot_6h WHERE level_type = :levelType AND snapshot_time = :snapshotTime")
                .setParameter("levelType", levelType)
                .setParameter("snapshotTime", snapshotTime)
                .executeUpdate();
    }

    private void deleteExistingSnapshots(HealthSnapshot1dRepository repo, String levelType, LocalDateTime snapshotTime) {
        entityManager.createNativeQuery(
                "DELETE FROM health_snapshot_1d WHERE level_type = :levelType AND snapshot_time = :snapshotTime")
                .setParameter("levelType", levelType)
                .setParameter("snapshotTime", snapshotTime)
                .executeUpdate();
    }

    private void deleteExistingSnapshots(HealthSnapshot7dRepository repo, String levelType, LocalDateTime snapshotTime) {
        entityManager.createNativeQuery(
                "DELETE FROM health_snapshot_7d WHERE level_type = :levelType AND snapshot_time = :snapshotTime")
                .setParameter("levelType", levelType)
                .setParameter("snapshotTime", snapshotTime)
                .executeUpdate();
    }

    private void deleteExistingSnapshots(HealthSnapshot30dRepository repo, String levelType, LocalDateTime snapshotTime) {
        entityManager.createNativeQuery(
                "DELETE FROM health_snapshot_30d WHERE level_type = :levelType AND snapshot_time = :snapshotTime")
                .setParameter("levelType", levelType)
                .setParameter("snapshotTime", snapshotTime)
                .executeUpdate();
    }

    // ============================================================
    // 高效回填（用 SQL 一次聚合，不再 720 次 Java 循环）
    // ============================================================

    /**
     * 从明细表聚合写入 health_snapshot_1h（覆盖过去 N 天的所有小时）
     */
    @Transactional
    public int backfillHourlyFromDetail(LocalDateTime start, LocalDateTime end) {
        // 先清空回填时间范围内的 1h 数据（避免旧数据干扰）
        entityManager.createNativeQuery(
                "DELETE FROM health_snapshot_1h WHERE snapshot_time >= :start AND snapshot_time < :end")
                .setParameter("start", start)
                .setParameter("end", end)
                .executeUpdate();

        int total = 0;
        // metric 层：metric_id 有值，其他层级字段为 NULL
        total += entityManager.createNativeQuery(
                "INSERT INTO health_snapshot_1h " +
                "(level_type, metric_id, snapshot_time, health_score, status, normal_count, warning_count, alarm_count, aggregate_count) " +
                "SELECT 'metric', metric_id, " +
                "       DATE_FORMAT(record_time, '%Y-%m-%d %H:00:00'), " +
                "       ROUND(AVG(health_score)), " +
                "       CASE WHEN SUM(CASE WHEN status='alarm' THEN 1 ELSE 0 END) > 0 THEN 'alarm' " +
                "            WHEN SUM(CASE WHEN status='warning' THEN 1 ELSE 0 END) > 0 THEN 'warning' " +
                "            ELSE 'normal' END, " +
                "       SUM(CASE WHEN status='normal' THEN 1 ELSE 0 END), " +
                "       SUM(CASE WHEN status='warning' THEN 1 ELSE 0 END), " +
                "       SUM(CASE WHEN status='alarm' THEN 1 ELSE 0 END), " +
                "       COUNT(*) " +
                "FROM metric_health_history " +
                "WHERE record_time >= :start AND record_time < :end " +
                "GROUP BY metric_id, DATE_FORMAT(record_time, '%Y-%m-%d %H:00:00')")
                .setParameter("start", start)
                .setParameter("end", end)
                .executeUpdate();

        // subcategory 层：category_name + subcategory_name 有值
        total += entityManager.createNativeQuery(
                "INSERT INTO health_snapshot_1h " +
                "(level_type, category_name, subcategory_name, snapshot_time, health_score, status, normal_count, warning_count, alarm_count, aggregate_count) " +
                "SELECT 'subcategory', category_name, sub_category_name, " +
                "       DATE_FORMAT(record_time, '%Y-%m-%d %H:00:00'), " +
                "       ROUND(AVG(health_score)), " +
                "       CASE WHEN SUM(CASE WHEN status='alarm' THEN 1 ELSE 0 END) > 0 THEN 'alarm' " +
                "            WHEN SUM(CASE WHEN status='warning' THEN 1 ELSE 0 END) > 0 THEN 'warning' " +
                "            ELSE 'normal' END, " +
                "       SUM(normal_count), SUM(warning_count), SUM(alarm_count), " +
                "       COUNT(*) " +
                "FROM sub_category_health_history " +
                "WHERE record_time >= :start AND record_time < :end " +
                "GROUP BY category_name, sub_category_name, DATE_FORMAT(record_time, '%Y-%m-%d %H:00:00')")
                .setParameter("start", start)
                .setParameter("end", end)
                .executeUpdate();

        // category 层：category_name 有值
        total += entityManager.createNativeQuery(
                "INSERT INTO health_snapshot_1h " +
                "(level_type, category_name, snapshot_time, health_score, status, normal_count, warning_count, alarm_count, aggregate_count) " +
                "SELECT 'category', category_name, " +
                "       DATE_FORMAT(record_time, '%Y-%m-%d %H:00:00'), " +
                "       ROUND(AVG(health_score)), " +
                "       CASE WHEN SUM(CASE WHEN status='alarm' THEN 1 ELSE 0 END) > 0 THEN 'alarm' " +
                "            WHEN SUM(CASE WHEN status='warning' THEN 1 ELSE 0 END) > 0 THEN 'warning' " +
                "            ELSE 'normal' END, " +
                "       SUM(normal_count), SUM(warning_count), SUM(alarm_count), " +
                "       COUNT(*) " +
                "FROM category_health_history " +
                "WHERE record_time >= :start AND record_time < :end " +
                "GROUP BY category_name, DATE_FORMAT(record_time, '%Y-%m-%d %H:00:00')")
                .setParameter("start", start)
                .setParameter("end", end)
                .executeUpdate();

        // system 层：按系统分别聚合（统一权重，不再简单平均 / 硬编码 0.25）
        total += backfillSystemRows("health_snapshot_1h", "health_snapshot_1h", start, end);

        return total;
    }

    /**
     * 从 health_snapshot_1h 聚合写入 health_snapshot_1d（按天）
     */
    @Transactional
    public int backfillDailyFromHourly(LocalDateTime start, LocalDateTime end) {
        entityManager.createNativeQuery(
                "DELETE FROM health_snapshot_1d WHERE snapshot_time >= :start AND snapshot_time < :end")
                .setParameter("start", start)
                .setParameter("end", end)
                .executeUpdate();

        // metric / subcategory / category 层
        String[] levelTypes = {"metric", "subcategory", "category"};
        int total = 0;
        for (String levelType : levelTypes) {
            String selectFields;
            String groupFields;
            if ("metric".equals(levelType)) {
                selectFields = "'metric', metric_id, DATE_FORMAT(snapshot_time, '%Y-%m-%d 00:00:00') AS snapshot_time, ROUND(AVG(health_score)), " +
                        "CASE WHEN SUM(CASE WHEN status='alarm' THEN 1 ELSE 0 END) > 0 THEN 'alarm' " +
                        "     WHEN SUM(CASE WHEN status='warning' THEN 1 ELSE 0 END) > 0 THEN 'warning' " +
                        "     ELSE 'normal' END, " +
                        "SUM(normal_count), SUM(warning_count), SUM(alarm_count), COUNT(*)";
                groupFields = "metric_id, DATE_FORMAT(snapshot_time, '%Y-%m-%d 00:00:00')";
            } else if ("subcategory".equals(levelType)) {
                selectFields = "'subcategory', category_name, subcategory_name, DATE_FORMAT(snapshot_time, '%Y-%m-%d 00:00:00') AS snapshot_time, ROUND(AVG(health_score)), " +
                        "CASE WHEN SUM(CASE WHEN status='alarm' THEN 1 ELSE 0 END) > 0 THEN 'alarm' " +
                        "     WHEN SUM(CASE WHEN status='warning' THEN 1 ELSE 0 END) > 0 THEN 'warning' " +
                        "     ELSE 'normal' END, " +
                        "SUM(normal_count), SUM(warning_count), SUM(alarm_count), COUNT(*)";
                groupFields = "category_name, subcategory_name, DATE_FORMAT(snapshot_time, '%Y-%m-%d 00:00:00')";
            } else {
                selectFields = "'category', category_name, DATE_FORMAT(snapshot_time, '%Y-%m-%d 00:00:00') AS snapshot_time, ROUND(AVG(health_score)), " +
                        "CASE WHEN SUM(CASE WHEN status='alarm' THEN 1 ELSE 0 END) > 0 THEN 'alarm' " +
                        "     WHEN SUM(CASE WHEN status='warning' THEN 1 ELSE 0 END) > 0 THEN 'warning' " +
                        "     ELSE 'normal' END, " +
                        "SUM(normal_count), SUM(warning_count), SUM(alarm_count), COUNT(*)";
                groupFields = "category_name, DATE_FORMAT(snapshot_time, '%Y-%m-%d 00:00:00')";
            }

            String sql = "INSERT INTO health_snapshot_1d " +
                    "(level_type, " + ("metric".equals(levelType) ? "metric_id, " : "") +
                    ("subcategory".equals(levelType) ? "category_name, subcategory_name, " : "") +
                    ("category".equals(levelType) ? "category_name, " : "") +
                    "snapshot_time, health_score, status, normal_count, warning_count, alarm_count, aggregate_count) " +
                    "SELECT " + selectFields + " " +
                    "FROM health_snapshot_1h " +
                    "WHERE level_type = :levelType AND snapshot_time >= :start AND snapshot_time < :end " +
                    "GROUP BY " + groupFields;
            total += entityManager.createNativeQuery(sql)
                    .setParameter("levelType", levelType)
                    .setParameter("start", start)
                    .setParameter("end", end)
                    .executeUpdate();
        }

        // system 层：按系统分别聚合（统一权重）
        total += backfillSystemRows("health_snapshot_1d", "health_snapshot_1h", start, end);

        return total;
    }

    /**
     * 从 health_snapshot_1d 聚合写入 health_snapshot_30d（按月）
     */
    @Transactional
    public int backfillMonthlyFromDaily(LocalDateTime start, LocalDateTime end) {
        entityManager.createNativeQuery(
                "DELETE FROM health_snapshot_30d WHERE snapshot_time >= :start AND snapshot_time < :end")
                .setParameter("start", start)
                .setParameter("end", end)
                .executeUpdate();

        String[] levelTypes = {"metric", "subcategory", "category"};
        int total = 0;
        for (String levelType : levelTypes) {
            String groupFields;
            if ("metric".equals(levelType)) {
                groupFields = "metric_id, DATE_FORMAT(snapshot_time, '%Y-%m-01 00:00:00')";
            } else if ("subcategory".equals(levelType)) {
                groupFields = "category_name, subcategory_name, DATE_FORMAT(snapshot_time, '%Y-%m-01 00:00:00')";
            } else {
                groupFields = "category_name, DATE_FORMAT(snapshot_time, '%Y-%m-01 00:00:00')";
            }

            String sql = "INSERT INTO health_snapshot_30d " +
                    "(level_type, " + ("metric".equals(levelType) ? "metric_id, " : "") +
                    ("subcategory".equals(levelType) ? "category_name, subcategory_name, " : "") +
                    ("category".equals(levelType) ? "category_name, " : "") +
                    "snapshot_time, health_score, status, normal_count, warning_count, alarm_count, aggregate_count) " +
                    "SELECT '" + levelType + "', " +
                    ("metric".equals(levelType) ? "metric_id, " : "") +
                    ("subcategory".equals(levelType) ? "category_name, subcategory_name, " : "") +
                    ("category".equals(levelType) ? "category_name, " : "") +
                    "DATE_FORMAT(snapshot_time, '%Y-%m-01 00:00:00'), " +
                    "ROUND(AVG(health_score)), " +
                    "CASE WHEN SUM(CASE WHEN status='alarm' THEN 1 ELSE 0 END) > 0 THEN 'alarm' " +
                    "     WHEN SUM(CASE WHEN status='warning' THEN 1 ELSE 0 END) > 0 THEN 'warning' " +
                    "     ELSE 'normal' END, " +
                    "SUM(normal_count), SUM(warning_count), SUM(alarm_count), COUNT(*) " +
                    "FROM health_snapshot_1d " +
                    "WHERE level_type = :levelType AND snapshot_time >= :start AND snapshot_time < :end " +
                    "GROUP BY " + groupFields;
            total += entityManager.createNativeQuery(sql)
                    .setParameter("levelType", levelType)
                    .setParameter("start", start)
                    .setParameter("end", end)
                    .executeUpdate();
        }

        // system 层：按系统分别聚合（统一权重）
        total += backfillSystemRows("health_snapshot_30d", "health_snapshot_1d", start, end);

        return total;
    }

    /**
     * 回填系统层快照：从 category 层快照按系统 + 统一权重算出系统分后逐条写入。
     *
     * <p>改造前这里是纯 SQL 的“4 大类简单平均 + systemId=1”，与实时口径不一致；
     * 改为 Java 计算后，实时分与 1h 快照的差值可以控制在 1 分以内（验收用例 5）。
     */
    private int backfillSystemRows(String targetTable, String sourceTable,
                                   LocalDateTime start, LocalDateTime end) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT snapshot_time, category_name, health_score, status FROM " + sourceTable + " " +
                "WHERE level_type = 'category' AND snapshot_time >= ? AND snapshot_time < ?",
                start, end);
        if (rows.isEmpty()) return 0;

        Map<LocalDateTime, List<HealthSnapshot1h>> byTime = new HashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDateTime time = toLocalDateTime(row.get("snapshot_time"));
            if (time == null) continue;
            HealthSnapshot1h holder = new HealthSnapshot1h();
            holder.setLevelType("category");
            holder.setCategoryName((String) row.get("category_name"));
            holder.setHealthScore(row.get("health_score") == null ? null
                    : ((Number) row.get("health_score")).intValue());
            holder.setStatus((String) row.get("status"));
            byTime.computeIfAbsent(time, key -> new ArrayList<>()).add(holder);
        }

        int saved = 0;
        for (var entry : byTime.entrySet()) {
            for (var systemEntry : systemSnapshotsFromCategories(entry.getValue()).entrySet()) {
                SystemAgg agg = systemEntry.getValue();
                saved += jdbc.update(
                        "INSERT INTO " + targetTable + " " +
                        "(level_type, system_id, snapshot_time, health_score, status, " +
                        " normal_count, warning_count, alarm_count, aggregate_count) " +
                        "VALUES ('system', ?, ?, ?, ?, ?, ?, ?, ?)",
                        systemEntry.getKey(), entry.getKey(), agg.score(), agg.status(),
                        agg.normalCount(), agg.warningCount(), agg.alarmCount(), agg.aggregateCount());
            }
        }
        return saved;
    }

    // ============================================================
    // 时间对齐工具
    // ============================================================

    /** 把 LocalDateTime 对齐到上一个整点 */
    public static LocalDateTime alignToHour(LocalDateTime dt) {
        return dt.withMinute(0).withSecond(0).withNano(0);
    }

    /** 把 LocalDateTime 对齐到上一个 6 小时段起点（0/6/12/18 点） */
    public static LocalDateTime alignToSixHour(LocalDateTime dt) {
        int h = (dt.getHour() / 6) * 6;
        return dt.withHour(h).withMinute(0).withSecond(0).withNano(0);
    }

    /** 把 LocalDateTime 对齐到当日 00:00 */
    public static LocalDateTime alignToDay(LocalDateTime dt) {
        return dt.withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    /** 把 LocalDateTime 对齐到本周一 00:00 */
    public static LocalDateTime alignToWeek(LocalDateTime dt) {
        return dt.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    /** 把 LocalDateTime 对齐到本月 1 号 00:00 */
    public static LocalDateTime alignToMonth(LocalDateTime dt) {
        return dt.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
    }
}
