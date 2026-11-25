package com.metric.metric.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.metric.entity.CategoryHealthHistory;
import com.metric.metric.entity.MetricHealthHistory;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.entity.MetricRealtime;
import com.metric.metric.entity.MetricValueHistory;
import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.SubCategoryHealthHistory;
import com.metric.metric.entity.WeightConfig;
import com.metric.metric.entity.MetricScoreBaseline;
import com.metric.metric.repository.CategoryHealthHistoryRepository;
import com.metric.metric.repository.MetricScoreBaselineRepository;
import com.metric.metric.repository.MetricHealthHistoryRepository;
import com.metric.metric.repository.MetricItemRepository;
import com.metric.metric.repository.MetricRealtimeRepository;
import com.metric.metric.repository.MetricValueHistoryRepository;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.SubCategoryHealthHistoryRepository;
import com.metric.metric.repository.WeightConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 指标值和健康度计算服务
 */
@Service
public class MetricValueCalcService {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MetricItemRepository metricItemRepository;

    @Autowired
    private MetricRealtimeRepository metricRealtimeRepository;

    @Autowired
    private MetricValueHistoryRepository metricValueHistoryRepository;

    @Autowired
    private MetricHealthHistoryRepository metricHealthHistoryRepository;

    @Autowired
    private CategoryHealthHistoryRepository categoryHealthHistoryRepository;

    @Autowired
    private SubCategoryHealthHistoryRepository subCategoryHealthHistoryRepository;

    @Autowired
    private MetricCategoryRepository categoryRepository;

    @Autowired
    private WeightConfigRepository weightConfigRepository;

    @Autowired
    private MetricScoreBaselineRepository scoreBaselineRepository;

    @Autowired private MetricWeightScenarioService weightScenarios;
    @Autowired private ObjectMapper objectMapper;

    /** 2026-11-17 P2：健康度算分与聚合统一委托给规则引擎 */
    @Autowired private HealthScoringService healthScoringService;

    @Autowired
    private com.metric.alarm.service.AlarmStrategyQueryService alarmStrategyQueryService;

    /** 运行效率大类 ID（原「系统运行」，历史数据沿用 10，见 AGENTS 分类口径）。 */
    private static final long SYSTEM_RUN_CATEGORY_ID = 10L;

    /**
     * 评分基准缓存：一次装载全部指标基准，避免逐条查询造成 N+1
     * （直连公网库时每次查询要一个网络往返，详见 2026-10-27 的性能事故）。
     */
    private Map<Long, MetricScoreBaseline> baselineCache;

    /** 告警策略阈值快照（仅用于给实时值打「工单/告警」状态标签，不参与算分） */
    private com.metric.alarm.service.AlarmStrategyQueryService.MetricThresholdSnapshot thresholdSnapshot;

    /** 每轮计算开始时清空缓存，下一轮重新装载 */
    private void invalidateScoreCaches() {
        baselineCache = null;
        thresholdSnapshot = null;
    }

    private void refreshBaselineCache() {
        baselineCache = scoreBaselineRepository.findAll().stream()
                .collect(Collectors.toMap(MetricScoreBaseline::getMetricId, b -> b, (a, b) -> a));
    }

    /**
     * 取该指标的评分基准值。
     *
     * <p>指标已不再承载告警阈值（2026-10-28 解耦），算分基准改由
     * {@code metric_score_baseline} 提供，与告警策略完全无关。
     * 未配置时返回 null，调用方按「无基准」处理，与改造前「阈值为空」等价。
     */
    private BigDecimal baselineValueOf(MetricItem metric) {
        if (metric == null || metric.getId() == null) {
            return null;
        }
        if (baselineCache == null) {
            refreshBaselineCache();
        }
        MetricScoreBaseline baseline = baselineCache.get(metric.getId());
        return baseline != null ? baseline.getBaselineValue() : null;
    }

    /**
     * 取该指标的工单阈值（来自告警策略）。
     * 仅用于给实时值打「工单」状态标签；不参与健康度算分。
     */
    private BigDecimal gongdanThresholdOf(MetricItem metric) {
        if (metric == null || metric.getId() == null) {
            return null;
        }
        if (thresholdSnapshot == null) {
            thresholdSnapshot = alarmStrategyQueryService.loadSnapshot();
        }
        return thresholdSnapshot.gongdanThreshold(metric.getId());
    }

    /**
     * 计算所有指标小类的指标值
     */
    public void calcAllMetricValues() {
        invalidateScoreCaches();
        // 对外部客户 - 系统稽核
        calcMetricValuesForSystemAudit();
        // Python collection publishes internal staff and production performance/error values.
    }

    /**
     * 计算所有指标的健康度。
     *
     * <p>2026-11-17 健康度算法改造（P2）：健康度不再由本类按小类硬编码计算，
     * 统一交给 {@link HealthScoringService}（规则驱动：人工基线 / 动态基线 / 不计分）。
     * 本类只保留“指标值怎么算”的采集口径，算分与聚合在 HealthScoringService /
     * HealthAggregationService 里，避免同一个分数存在两套算法。
     */
    public void calcAllHealthScores() {
        healthScoringService.runAll();
    }

    /**
     * 计算对外部客户-系统稽核的指标值
     */
    private void calcMetricValuesForSystemAudit() {
        List<MetricItem> metrics = metricItemRepository.findBySubCategoryNameAndStatus("系统稽核", 1);
        if (metrics == null || metrics.isEmpty()) {
            System.out.println("未找到系统稽核指标配置");
            return;
        }

        LocalDateTime latestDataTime = getLatestDataTimeForSystemJhd();
        if (latestDataTime == null) {
            System.out.println("未找到系统稽核数据");
            return;
        }

        for (MetricItem metric : metrics) {
            calcSystemAuditMetricValue(metric, latestDataTime);
        }
    }

    /**
     * 计算系统稽核指标值
     */
    private void calcSystemAuditMetricValue(MetricItem metric, LocalDateTime dataTime) {
        // system_jhd 表按 check_code 查询，异常量是 abnormal_count，同时获取一级/二级分类
        String sql = "SELECT SUM(abnormal_count) as abnormal_sum, MAX(first_classify) as first_classify, MAX(second_classify) as second_classify FROM system_jhd WHERE check_code = ?";

        int abnormalSum = 0;
        String firstClassify = null;
        String secondClassify = null;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, metric.getMetricCode());

            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                abnormalSum = rs.getInt("abnormal_sum");
                firstClassify = rs.getString("first_classify");
                secondClassify = rs.getString("second_classify");
            }
        } catch (SQLException e) {
            System.err.println("计算系统稽核指标值失败: " + metric.getMetricName() + " - " + e.getMessage());
            return;
        }

        // 异常量为0时表示正常，非0表示异常
        int metricValue = abnormalSum;
        int overLimitCount = abnormalSum > 0 ? 1 : 0;
        // 使用传入的 dataTime（从 system_jhd 表查询的真实时间）截断到小时
        LocalDateTime truncatedTime = dataTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS);

        // 先更新 metric_realtime（不管历史是否存在）
        updateMetricRealtime(metric, metricValue, truncatedTime, overLimitCount > 0);

        // 检查历史是否已存在
        if (metricValueHistoryRepository.findByMetricIdAndDataTime(metric.getId(), truncatedTime).isPresent()) {
            System.out.println("本小时指标值已存在，仅更新 realtime: " + metric.getMetricName());
            return;
        }

        saveMetricValueForExternal(metric, "系统稽核", metricValue, truncatedTime, 1, overLimitCount, firstClassify, secondClassify);
    }

    /**
     * 获取system_jhd表的最新数据时间
     */
    private LocalDateTime getLatestDataTimeForSystemJhd() {
        String sql = "SELECT MAX(insert_time) as latest_time FROM system_jhd";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Timestamp ts = rs.getTimestamp("latest_time");
                return ts != null ? ts.toLocalDateTime() : null;
            }
        } catch (SQLException e) {
            System.err.println("获取system_jhd最新数据时间失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 计算系统运行的健康度
     */
    private void calcHealthScoresForSystemRun() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);

        // 计算系统性能小类健康度
        calcSystemRunSubCategoryHealth("系统性能", now);
        // 计算系统异常小类健康度
        calcSystemRunSubCategoryHealth("系统异常", now);

        // 保存小类健康度到sub_category_health_history
        calcSubCategoryHealthScore("运行效率", "系统性能", now);
        calcSubCategoryHealthScore("运行效率", "系统异常", now);

        // 计算系统运行大类健康度
        calcCategoryHealthScore("运行效率", now);
    }

    /**
     * 计算系统运行某个小类的健康度
     */
    private void calcSystemRunSubCategoryHealth(String subCategoryName, LocalDateTime now) {
        // 2026-11-17 P0：同样改为「运行效率大类 + 小类名」双重定位，避免同名列串算。
        List<MetricItem> metrics = metricItemRepository.findByCategoryIdAndStatus(SYSTEM_RUN_CATEGORY_ID, 1).stream()
                .filter(item -> subCategoryName.equals(item.getSubCategoryName()))
                .toList();
        if (metrics.isEmpty()) {
            return;
        }

        for (MetricItem metric : metrics) {
            // 从metric_realtime获取最新值
            if (BusinessValueMetricService.supports(metric)) continue;
            Optional<MetricRealtime> realtimeOpt = metricRealtimeRepository.findByMetricId(metric.getId());
            if (!realtimeOpt.isPresent()) {
                continue;
            }
            MetricRealtime realtime = realtimeOpt.get();
            BigDecimal metricValue = realtime.getMetricValue();
            if (metricValue == null) {
                continue;
            }

            int currentValue = metricValue.intValue();
            int threshold = baselineValueOf(metric) != null ? baselineValueOf(metric).intValue() : 0;

            // 计算健康度和状态
            int healthScore;
            String status;
            if ("系统异常".equals(subCategoryName)) {
                // 系统异常指标：失败次数/异常次数越低越好
                if (currentValue == 0) {
                    healthScore = 100;
                    status = "normal";
                } else if (currentValue <= threshold) {
                    healthScore = 70 + (30 * (threshold - currentValue) / threshold);
                    status = "warning";
                } else {
                    healthScore = Math.max(0, 100 - (currentValue - threshold) * 10);
                    status = healthScore < 50 ? "alarm" : "warning";
                }
            } else {
                // 系统性能指标：超过阈值越低越好
                if (currentValue <= threshold) {
                    healthScore = 100;
                    status = "normal";
                } else {
                    healthScore = Math.max(0, 100 - (currentValue - threshold) * 5);
                    status = healthScore < 70 ? "alarm" : (healthScore < 90 ? "warning" : "normal");
                }
            }

            // 先查是否已存在该时间点的记录，存在则更新，避免唯一键冲突
            MetricHealthHistory healthHistory = metricHealthHistoryRepository
                    .findByMetricIdAndRecordTime(metric.getId(), now)
                    .orElse(new MetricHealthHistory());
            healthHistory.setMetricId(metric.getId());
            healthHistory.setMetricCode(metric.getMetricCode());
            healthHistory.setMetricName(metric.getMetricName());
            healthHistory.setCategoryName("运行效率");
            healthHistory.setSubCategoryName(subCategoryName);
            healthHistory.setHealthScore(healthScore);
            healthHistory.setStatus(status);
            healthHistory.setCurrentValue(currentValue);
            healthHistory.setThreshold(baselineValueOf(metric));
            healthHistory.setDataTime(realtime.getDataTime());
            healthHistory.setRecordTime(now);

            metricHealthHistoryRepository.save(healthHistory);
            System.out.println("保存系统运行健康度: " + metric.getMetricName() + " = " + healthScore + " (status=" + status + ")");
        }
    }

    /**
     * 保存指标值（对外部客户）
     */
    private void saveMetricValueForExternal(MetricItem metric, String subCategoryName, int metricValue,
                                  LocalDateTime dataTime, int businessCount, int overLimitCount,
                                  String firstClassify, String secondClassify) {
        MetricValueHistory history = new MetricValueHistory();
        history.setMetricId(metric.getId());
        history.setMetricCode(metric.getMetricCode());
        history.setMetricName(metric.getMetricName());
        history.setCategoryName("对外部客户");
        history.setSubCategoryName(subCategoryName);
        history.setMetricValue(metricValue);
        history.setThreshold(baselineValueOf(metric));
        history.setUnit(metric.getUnit());
        history.setRecordTime(LocalDateTime.now());
        history.setDataTime(dataTime);
        history.setBusinessCount(businessCount);
        history.setOverLimitCount(overLimitCount);
        history.setFirstClassify(firstClassify);
        history.setSecondClassify(secondClassify);

        metricValueHistoryRepository.save(history);

        // 同时更新 metric_realtime 表
        updateMetricRealtime(metric, metricValue, dataTime, overLimitCount > 0);

        System.out.println("保存指标值: " + metric.getMetricName() + " = " + metricValue + " (total=" + businessCount + ")");
    }

    /**
     * 更新 metric_realtime 表（对内部人员/对外部客户指标）
     */
    private void updateMetricRealtime(MetricItem metric, int metricValue, LocalDateTime dataTime, boolean isExceeded) {
        MetricRealtime realtime = metricRealtimeRepository.findByMetricId(metric.getId())
                .orElseGet(() -> {
                    MetricRealtime newRealtime = new MetricRealtime();
                    newRealtime.setMetricId(metric.getId());
                    newRealtime.setCreateTime(LocalDateTime.now());
                    return newRealtime;
                });

        realtime.setMetricValue(BigDecimal.valueOf(metricValue));
        realtime.setDataTime(dataTime);
        realtime.setUpdateTime(LocalDateTime.now());

        if (isExceeded) {
            if (realtime.getIsExceed() == null || realtime.getIsExceed() == 0) {
                realtime.setExceedCount(1);
            } else {
                realtime.setExceedCount(realtime.getExceedCount() + 1);
            }
            realtime.setLastExceedTime(LocalDateTime.now());
            realtime.setIsExceed(1);
        } else {
            realtime.setIsExceed(0);
        }

        // 判断状态：超过工单阈值则状态为工单，否则根据 isExceed 判断
        BigDecimal gongdanThreshold = gongdanThresholdOf(metric);
        if (gongdanThreshold != null && BigDecimal.valueOf(metricValue).compareTo(gongdanThreshold) > 0) {
            realtime.setStatus("工单");
        } else if (isExceeded) {
            realtime.setStatus("告警");
        } else {
            realtime.setStatus("正常");
        }

        metricRealtimeRepository.save(realtime);
    }

    /**
     * 计算健康度
     */
    public void calcHealthScores(String categoryName, String subCategoryName) {
        healthScoringService.runCategory(categoryName);
    }

    /**
     * 计算单个指标的健康度
     */
    private void calcSingleHealthScore(String categoryName, String subCategoryName,
                                      MetricItem metric, LocalDateTime now) {
        try {
            LocalDateTime sevenDaysAgo = now.minusDays(7);

            List<MetricValueHistory> historyList = metricValueHistoryRepository
                    .findByMetricIdAndRecordTimeBetweenOrderByRecordTimeAsc(
                            metric.getId(), sevenDaysAgo, now);

            if (historyList.size() < 3) {
                System.out.println("历史数据不足，跳过健康度计算: " + metric.getMetricName());
                return;
            }

            MetricValueHistory latestValue = historyList.get(historyList.size() - 1);
            int currentValue = latestValue.getMetricValue();

            BigDecimal threshold = baselineValueOf(metric);
            double thresholdValue = threshold != null ? threshold.doubleValue() : 0;

            // 系统稽核特殊处理：异常量越低越好，阈值为0
            int healthScore;
            String status;
            if ("系统稽核".equals(subCategoryName)) {
                // 异常量为0时最健康(100)，异常量越大越不健康
                if (currentValue == 0) {
                    healthScore = 100;
                    status = "normal";
                } else if (currentValue <= 10) {
                    healthScore = 50;
                    status = "warning";
                } else {
                    healthScore = 0;
                    status = "alarm";
                }
            } else {
                // 计算均值和标准差（仅用于非系统稽核指标）
                double sum = 0;
                for (MetricValueHistory h : historyList) {
                    sum += h.getMetricValue();
                }
                double avg = sum / historyList.size();

                double variance = 0;
                for (MetricValueHistory h : historyList) {
                    variance += Math.pow(h.getMetricValue() - avg, 2);
                }
                double stdDev = Math.sqrt(variance / historyList.size());

                double lowerBound = avg - 2 * stdDev;
                double upperBound = avg + 2 * stdDev;

                healthScore = calculateHealthScore(currentValue, thresholdValue, avg);

                if (currentValue > thresholdValue) {
                    status = "alarm";
                } else if (currentValue > upperBound) {
                    status = "warning";
                } else {
                    status = "normal";
                }
            }

            // 先查是否已存在该时间点的记录，存在则更新，避免唯一键冲突
            MetricHealthHistory healthHistory = metricHealthHistoryRepository
                    .findByMetricIdAndRecordTime(metric.getId(), now)
                    .orElse(new MetricHealthHistory());
            healthHistory.setMetricId(metric.getId());
            healthHistory.setMetricCode(metric.getMetricCode());
            healthHistory.setMetricName(metric.getMetricName());
            healthHistory.setCategoryName(categoryName);
            healthHistory.setSubCategoryName(subCategoryName);
            healthHistory.setHealthScore(healthScore);
            healthHistory.setStatus(status);
            healthHistory.setCurrentValue(currentValue);
            healthHistory.setThreshold(threshold);
            healthHistory.setDataTime(latestValue.getDataTime());
            healthHistory.setRecordTime(now);
            // 系统稽核需要保存一级/二级分类用于前端分组展示
            if (latestValue.getFirstClassify() != null) {
                healthHistory.setFirstClassify(latestValue.getFirstClassify());
            }
            if (latestValue.getSecondClassify() != null) {
                healthHistory.setSecondClassify(latestValue.getSecondClassify());
            }

            metricHealthHistoryRepository.save(healthHistory);
            System.out.println("保存健康度: " + metric.getMetricName() +
                    " = " + healthScore + " (status=" + status +
                    ", current=" + currentValue + ")");

        } catch (Exception e) {
            System.err.println("计算健康度失败: " + metric.getMetricName() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 计算健康度分数
     */
    private int calculateHealthScore(int currentValue, double threshold, double baseline) {
        if (currentValue >= threshold) {
            return 0;
        }

        if (threshold > baseline) {
            double score = 100.0 * (threshold - currentValue) / (threshold - baseline);
            return Math.min(100, Math.max(0, (int) Math.round(score)));
        }

        return currentValue >= threshold ? 0 : 100;
    }

    /**
     * 计算大类健康度（使用加权平均）
     */
    private void calcCategoryHealthScore(String categoryName, LocalDateTime now) {
        List<MetricHealthHistory> latestHealthList = metricHealthHistoryRepository
                .findByCategoryNameOrderByRecordTimeDesc(categoryName);

        if (latestHealthList.isEmpty()) {
            return;
        }

        MetricCategory category = categoryRepository.findByCategoryName(categoryName).orElse(null);
        if (category == null) return;
        var catalog = activeCatalog(category.getId());
        var latestByMetric = latestEligible(latestHealthList, catalog);
        if (latestByMetric.isEmpty()) return;

        var bySubCategory = latestByMetric.values().stream().collect(Collectors.groupingBy(
                h -> Optional.ofNullable(catalog.get(h.getMetricId()).getSubCategoryName())));
        Map<String, BigDecimal> subCategoryWeights = getSubCategoryWeights(category.getId());
        var metricWeights = getMetricWeights(category.getId());
        var adjustments = adjustmentsAt(now);
        var groups = new ArrayList<WeightedGroup>();
        BigDecimal totalWeightedScore = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (var entry : bySubCategory.entrySet()) {
            String groupName = entry.getKey().orElse("未分类");
            BigDecimal groupWeight = subCategoryWeights.getOrDefault(groupName, BigDecimal.ONE);
            var group = weightedGroup(groupName, groupWeight, entry.getValue(), metricWeights, adjustments);
            groups.add(group);
            totalWeightedScore = totalWeightedScore.add(group.score().multiply(groupWeight));
            totalWeight = totalWeight.add(groupWeight);
        }

        int avgScore = totalWeight.signum() > 0
                ? totalWeightedScore.divide(totalWeight, 0, RoundingMode.HALF_UP).intValueExact() : 0;
        int normalCount = countStatus(latestByMetric, "normal");
        int warningCount = countStatus(latestByMetric, "warning");
        int alarmCount = countStatus(latestByMetric, "alarm");

        String status = "normal";
        if (alarmCount > 0) {
            status = "alarm";
        } else if (warningCount > 0) {
            status = "warning";
        }

        // 检查是否已存在相同类别和时间的记录，如果存在则更新
        Optional<CategoryHealthHistory> existingOpt = categoryHealthHistoryRepository
                .findByCategoryNameAndRecordTime(categoryName, now);
        CategoryHealthHistory categoryHistory;
        if (existingOpt.isPresent()) {
            categoryHistory = existingOpt.get();
        } else {
            categoryHistory = new CategoryHealthHistory();
            categoryHistory.setCategoryName(categoryName);
            categoryHistory.setRecordTime(now);
        }
        categoryHistory.setHealthScore(avgScore);
        categoryHistory.setStatus(status);
        categoryHistory.setNormalCount(normalCount);
        categoryHistory.setWarningCount(warningCount);
        categoryHistory.setAlarmCount(alarmCount);

        categoryHistory.setWeightTrace(weightTrace(category.getId(), groups));
        categoryHealthHistoryRepository.save(categoryHistory);

        System.out.println("保存大类健康度: " + categoryName +
                " = " + avgScore + " (normal=" + normalCount +
                ", warning=" + warningCount + ", alarm=" + alarmCount + ")");
    }

    /**
     * 计算小类健康度（使用该小类下各指标健康度的加权平均）
     */
    public void calcSubCategoryHealthScore(String categoryName, String subCategoryName, LocalDateTime now) {
        // 获取该小类下所有指标的最新健康度数据
        List<MetricHealthHistory> latestHealthList = metricHealthHistoryRepository
                .findByCategoryNameAndSubCategoryNameOrderByRecordTimeDesc(categoryName, subCategoryName);

        if (latestHealthList.isEmpty()) {
            return;
        }

        MetricCategory category = categoryRepository.findByCategoryName(categoryName).orElse(null);
        if (category == null) return;
        var catalog = activeCatalog(category.getId());
        catalog.values().removeIf(item -> !subCategoryName.equals(item.getSubCategoryName()));
        var latestByMetric = latestEligible(latestHealthList, catalog);
        if (latestByMetric.isEmpty()) return;

        var group = weightedGroup(subCategoryName, BigDecimal.ONE, latestByMetric.values(),
                getMetricWeights(category.getId()), adjustmentsAt(now));
        int avgScore = group.score().setScale(0, RoundingMode.HALF_UP).intValueExact();
        int normalCount = countStatus(latestByMetric, "normal");
        int warningCount = countStatus(latestByMetric, "warning");
        int alarmCount = countStatus(latestByMetric, "alarm");

        String status = "normal";
        if (alarmCount > 0) {
            status = "alarm";
        } else if (warningCount > 0) {
            status = "warning";
        }

        // 检查是否已存在相同类别、小类和时间的记录，如果存在则更新
        Optional<SubCategoryHealthHistory> existingOpt = subCategoryHealthHistoryRepository
                .findByCategoryNameAndSubCategoryNameAndRecordTime(categoryName, subCategoryName, now);
        SubCategoryHealthHistory subCategoryHistory;
        if (existingOpt.isPresent()) {
            subCategoryHistory = existingOpt.get();
        } else {
            subCategoryHistory = new SubCategoryHealthHistory();
            subCategoryHistory.setCategoryName(categoryName);
            subCategoryHistory.setSubCategoryName(subCategoryName);
            subCategoryHistory.setRecordTime(now);
        }
        subCategoryHistory.setHealthScore(avgScore);
        subCategoryHistory.setStatus(status);
        subCategoryHistory.setNormalCount(normalCount);
        subCategoryHistory.setWarningCount(warningCount);
        subCategoryHistory.setAlarmCount(alarmCount);

        subCategoryHistory.setWeightTrace(weightTrace(category.getId(), List.of(group)));
        subCategoryHealthHistoryRepository.save(subCategoryHistory);

        System.out.println("保存小类健康度: " + categoryName + " - " + subCategoryName +
                " = " + avgScore + " (normal=" + normalCount +
                ", warning=" + warningCount + ", alarm=" + alarmCount + ")");
    }

    private Map<Long, MetricItem> activeCatalog(long categoryId) {
        return metricItemRepository.findByCategoryIdAndStatus(categoryId, 1).stream()
                .collect(Collectors.toMap(MetricItem::getId, item -> item));
    }

    private Map<Long, MetricHealthHistory> latestEligible(List<MetricHealthHistory> history, Map<Long, MetricItem> catalog) {
        Map<Long, MetricHealthHistory> latest = new HashMap<>();
        for (var record : history) {
            if (catalog.containsKey(record.getMetricId()) && record.getHealthScore() != null) {
                latest.putIfAbsent(record.getMetricId(), record);
            }
        }
        return latest;
    }

    private static int countStatus(Map<Long, MetricHealthHistory> history, String status) {
        return (int) history.values().stream().filter(record -> status.equals(record.getStatus())).count();
    }

    private Map<Long, MetricWeightScenarioService.Adjustment> adjustmentsAt(LocalDateTime now) {
        return weightScenarios.adjustments(now.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate());
    }

    record WeightedMetric(long metricId, int score, LocalDateTime sampleAt, BigDecimal baseWeight,
                          BigDecimal adjustedWeight, MetricWeightScenarioService.Adjustment scenario) {}
    record WeightedGroup(String name, BigDecimal groupWeight, BigDecimal score, BigDecimal adjustedTotal,
                         List<WeightedMetric> metrics) {}

    static WeightedGroup weightedGroup(String name, BigDecimal groupWeight,
                                       java.util.Collection<MetricHealthHistory> history,
                                       Map<Long, BigDecimal> weights,
                                       Map<Long, MetricWeightScenarioService.Adjustment> adjustments) {
        var metrics = new ArrayList<WeightedMetric>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal numerator = BigDecimal.ZERO;
        for (var record : history) {
            long id = record.getMetricId();
            var scenario = adjustments.get(id);
            BigDecimal base = weights.getOrDefault(id, BigDecimal.ONE);
            BigDecimal adjusted = scenario == null ? base : base.multiply(scenario.multiplier());
            numerator = numerator.add(adjusted.multiply(BigDecimal.valueOf(record.getHealthScore())));
            total = total.add(adjusted);
            metrics.add(new WeightedMetric(id, record.getHealthScore(), record.getRecordTime(), base, adjusted, scenario));
        }
        BigDecimal score = total.signum() > 0 ? numerator.divide(total, 16, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return new WeightedGroup(name, groupWeight, score, total, metrics);
    }

    private String weightTrace(long categoryId, List<WeightedGroup> groups) {
        try {
            return objectMapper.writeValueAsString(Map.of("algorithmVersion", 1, "categoryId", categoryId, "groups", groups));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存健康度权重快照", e);
        }
    }

    private Map<String, BigDecimal> getSubCategoryWeights(Long categoryId) {
        Map<String, BigDecimal> weights = new HashMap<>();
        var configs = weightConfigRepository.findByCategoryIdAndWeightType(categoryId, "subcategory");
        if (!configs.isEmpty()) {
            for (WeightConfig config : configs) weights.put(config.getSubCategoryName(), config.getWeight());
        } else {
            var subNames = metricItemRepository.findByCategoryIdAndStatus(categoryId, 1).stream()
                    .map(MetricItem::getSubCategoryName).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
            if (!subNames.isEmpty()) {
                BigDecimal equalWeight = BigDecimal.ONE.divide(BigDecimal.valueOf(subNames.size()), 2, RoundingMode.HALF_UP);
                subNames.forEach(name -> weights.put(name, equalWeight));
            }
        }
        return weights;
    }

    /**
     * 获取指标权重配置
     */
    private Map<Long, BigDecimal> getMetricWeights(Long categoryId) {
        Map<Long, BigDecimal> weights = new HashMap<>();
        // 从weight_config表获取指标权重
        List<WeightConfig> configs = weightConfigRepository
                .findByCategoryIdAndWeightType(categoryId, "metric");
        if (configs != null && !configs.isEmpty()) {
            for (WeightConfig config : configs) {
                weights.put(config.getMetricId(), config.getWeight());
            }
        } else {
            // 默认每个指标权重为1
            List<MetricItem> items = metricItemRepository.findByCategoryIdAndStatus(categoryId, 1);
            for (MetricItem item : items) {
                weights.put(item.getId(), BigDecimal.ONE);
            }
        }
        return weights;
    }
}
