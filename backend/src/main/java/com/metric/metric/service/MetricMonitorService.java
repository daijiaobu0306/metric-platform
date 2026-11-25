package com.metric.metric.service;

import com.metric.shared.dto.SyncResult;
import com.metric.collect.service.SatisfactionAlarmValueService;
import com.metric.metric.entity.*;
import com.metric.metric.repository.*;
import com.metric.alarm.repository.AlarmRepository;
import com.metric.alarm.service.AlarmSwitchService;
import com.metric.gongdan.entity.Gongdan;
import com.metric.gongdan.repository.GongdanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class MetricMonitorService {

    // V46: 本次扫描各 sub_category 成功更新的指标计数（供 Toast 通知聚合使用）
    private volatile Map<String, Integer> lastScanSubCategoryCount = Map.of();
    private volatile LocalDateTime lastScanEndTime = null;

    /** 最近一次扫描的 sub_category 统计结果：key=sub_category_name, value=成功处理条数 */
    public Map<String, Integer> getLastScanSubCategoryCount() {
        return lastScanSubCategoryCount;
    }

    public LocalDateTime getLastScanEndTime() {
        return lastScanEndTime;
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SatisfactionAlarmValueService satisfactionAlarmValues;

    @Autowired
    private ResourceInputMetricService resourceInputMetrics;

    @Autowired
    private ExternalCustomerMetricService externalCustomerValues;
    @Autowired
    private BusinessValueMetricService businessValueMetrics;

    @Autowired
    private MetricItemRepository metricItemRepository;

    @Autowired
    private MetricRealtimeRepository metricRealtimeRepository;

    @Autowired
    private MetricMonitorConfigRepository metricMonitorConfigRepository;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private GongdanRepository gongdanRepository;

    @Autowired
    private MetricCategoryRepository metricCategoryRepository;

    @Autowired
    private MetricSubCategoryRepository metricSubCategoryRepository;

    @Autowired
    private AlarmSwitchService alarmSwitchService;

    @Autowired
    private SystemInfoRepository systemInfoRepository;

    /** 告警阈值唯一来源：告警策略（指标表已不再承载阈值，2026-10-28 解耦） */
    @Autowired
    private com.metric.alarm.service.AlarmStrategyQueryService alarmStrategyQueryService;

    @Autowired
    private MetricObservationReadService observationReads;
    @Autowired
    private CollectedMetricService collectedMetrics;
    @Autowired
    private TransactionTemplate transactions;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // === 告警阈值快照（来自 alarm_strategy，一次查询装载，避免 N+1）===
    private com.metric.alarm.service.AlarmStrategyQueryService.MetricThresholdSnapshot thresholdSnapshot;

    /**
     * 刷新阈值快照。策略由告警模块维护，本服务是单例，
     * 因此每个对外读取入口都要刷新一次，避免读到旧阈值。
     */
    private void refreshThresholdCaches() {
        thresholdSnapshot = alarmStrategyQueryService.loadSnapshot();
    }

    /** 指标告警阈值；该指标没有启用的告警策略时返回 null（页面显示「-」） */
    private BigDecimal alarmThresholdOf(Long metricId) {
        if (thresholdSnapshot == null) refreshThresholdCaches();
        return thresholdSnapshot.alarmThreshold(metricId);
    }

    private BigDecimal gongdanThresholdOf(Long metricId) {
        if (thresholdSnapshot == null) refreshThresholdCaches();
        return thresholdSnapshot.gongdanThreshold(metricId);
    }

    /** 指标告警比较符；未配置策略时返回 ">" 仅用于避免判空，不参与展示 */
    private String alarmConditionOf(Long metricId) {
        if (thresholdSnapshot == null) refreshThresholdCaches();
        String c = thresholdSnapshot.alarmCondition(metricId);
        return c != null ? c : ">";
    }

    private String gongdanConditionOf(Long metricId) {
        if (thresholdSnapshot == null) refreshThresholdCaches();
        String c = thresholdSnapshot.gongdanCondition(metricId);
        return c != null ? c : ">";
    }

    /**
     * 扫描并更新所有指标的状态
     * @return 处理的指标数量
     */
    public int scanAndUpdateMetrics() {
        refreshThresholdCaches();
        int processedCount = 0;
        Map<String, Integer> counts = new LinkedHashMap<>();

        // 获取所有启用的指标配置
        List<MetricItem> metricItems = metricItemRepository.findByStatus(1);
        if (metricItems == null || metricItems.isEmpty()) {
            lastScanSubCategoryCount = Map.of();
            System.out.println("未找到启用的指标配置");
            return 0;
        }

        for (MetricItem metricItem : metricItems) {
            try {
                // Commit each metric before reading the next one so the scan does not hold a growing set of locks.
                boolean hasData = Boolean.TRUE.equals(transactions.execute(status -> processMetricItem(metricItem)));
                if (hasData) {
                    processedCount++;
                    // V46: 只有真正拿到原始数据时才计入 sub_category 统计
                    String subCat = metricItem.getSubCategoryName();
                    if (subCat == null || subCat.isEmpty()) subCat = "未分类";
                    counts.merge(subCat, 1, Integer::sum);
                }
            } catch (Exception e) {
                System.err.println("处理指标 " + metricItem.getMetricName() + " 时出错: " + e.getMessage());
            }
        }

        lastScanSubCategoryCount = Collections.unmodifiableMap(counts);
        lastScanEndTime = LocalDateTime.now();
        System.out.println("=== 指标监控扫描完成，共处理 " + processedCount + " 个指标 ===");
        return processedCount;
    }

    /**
     * 处理单个指标。
     *
     * @return true 表示本次实际拿到了原始数据并写入了 MetricRealtime；
     *         false 表示原始数据为空或计算失败，不算"采集到数据"。
     */
    @Transactional
    public boolean processMetricItem(MetricItem metricItem) {
        if (!SatisfactionAlarmValueService.supports(metricItem)) {
            var published = collectedMetrics.publishIfSupported(metricItem.getId());
            if (published.isPresent()) return published.get();
        }
        if (hasCategory(metricItem, "resourceInput")) return resourceInputMetrics.refresh();
        // 获取或创建实时数据记录
        MetricRealtime realtime = metricRealtimeRepository.findByMetricId(metricItem.getId())
                .orElseGet(() -> createNewMetricRealtime(metricItem));

        // 获取监控配置
        MetricMonitorConfig config = metricMonitorConfigRepository.findByMetricId(metricItem.getId())
                .orElseGet(() -> createDefaultMonitorConfig(metricItem));

        if (SatisfactionAlarmValueService.supports(metricItem)) {
            var observations = satisfactionAlarmValues.observations(metricItem,
                    alarmThresholdOf(metricItem.getId()), alarmConditionOf(metricItem.getId()));
            if (observations.isEmpty()) return false;
            var daily = observations.get(0);
            boolean exceeded = observations.stream().anyMatch(SatisfactionAlarmValueService.Observation::breached);
            realtime.setMetricValue(daily.value());
            var sourceObservation=collectedMetrics.latest(metricItem.getId());
            realtime.setDataTime(sourceObservation!=null && sourceObservation.dataTime().toLocalDate().equals(daily.businessDate())
                ? sourceObservation.dataTime():daily.businessDate().atStartOfDay());
            realtime.setIsExceed(exceeded ? 1 : 0);
            realtime.setExceedCount(exceeded ? 1 : 0);
            realtime.setStatus(daily.value() == null ? "缺数" : exceeded ? "告警" : "正常");
            metricRealtimeRepository.save(realtime);
            // The common strategy scanner handles alarm switches, shielding and deduplication.
            return daily.value() != null;
        }

        // 计算当前指标值（从原始数据）
        boolean external = hasCategory(metricItem, "external_customer");
        boolean collected = BusinessValueMetricService.supports(metricItem);
        var collectedValue = collected ? businessValueMetrics.latest(metricItem.getId()) : null;
        var externalValue = external ? externalCustomerValues.latest(metricItem.getId()) : null;
        BigDecimal currentValue = collected ? (collectedValue == null ? null : collectedValue.value()) : external
                ? (externalValue == null ? null : externalValue.value()) : null;
        if (currentValue == null) {
            return false;  // 没拿到原始数据，不算采集成功
        }

        // 获取真实的数据时间（从原始数据表查询）
        LocalDateTime realDataTime = collectedValue != null ? collectedValue.dataTime() : externalValue == null ? null : externalValue.dataTime();
        LocalDateTime now = LocalDateTime.now();
        if (realDataTime == null) {
            realDataTime = now;  // 如果查询不到真实时间，使用当前时间
        }

        realtime.setMetricValue(currentValue);
        realtime.setDataTime(realDataTime);

        // 判断是否超过告警阈值
        boolean alarmExceeded = isExceeded(currentValue, alarmThresholdOf(metricItem.getId()), alarmConditionOf(metricItem.getId()));

        // 判断是否超过工单阈值
        boolean gongdanExceeded = isExceeded(currentValue, gongdanThresholdOf(metricItem.getId()), gongdanConditionOf(metricItem.getId()));

        // 更新连续超限次数
        if (alarmExceeded) {
            if (realtime.getIsExceed() == null || realtime.getIsExceed() == 0) {
                realtime.setExceedCount(1);
            } else {
                realtime.setExceedCount(realtime.getExceedCount() + 1);
            }
            realtime.setLastExceedTime(now);
            realtime.setIsExceed(1);
        } else {
            realtime.setIsExceed(0);
            // 如果是在窗口期内恢复了，不重置计数，让它自然消亡
        }

        // 判断状态
        if (collected && alarmThresholdOf(metricItem.getId()) == null && gongdanThresholdOf(metricItem.getId()) == null) {
            realtime.setStatus("待配置");
        } else if (gongdanExceeded && config.getConsecutiveCount() != null
                && realtime.getExceedCount() >= config.getConsecutiveCount()) {
            realtime.setStatus("工单");
        } else if (alarmExceeded) {
            realtime.setStatus("告警");
        } else {
            realtime.setStatus("正常");
        }

        metricRealtimeRepository.save(realtime);

        // 生成告警（如果需要）
        if (alarmExceeded && config.getAlarmStatus() == 1) {
            generateAlarmIfNeeded(metricItem, realtime, config);
        }

        // 生成工单（如果需要）
        if (gongdanExceeded && config.getGongdanStatus() == 1
                && config.getConsecutiveCount() != null
                && realtime.getExceedCount() >= config.getConsecutiveCount()) {
            generateGongdanIfNeeded(metricItem, realtime, config);
        }

        return true;  // 成功采集到数据
    }

    /**
     * 创建新的实时数据记录
     */
    private MetricRealtime createNewMetricRealtime(MetricItem metricItem) {
        MetricRealtime realtime = new MetricRealtime();
        realtime.setMetricId(metricItem.getId());
        realtime.setIsExceed(0);
        realtime.setExceedCount(0);
        realtime.setStatus("正常");
        return realtime;
    }

    /**
     * 创建默认监控配置
     */
    private MetricMonitorConfig createDefaultMonitorConfig(MetricItem metricItem) {
        MetricMonitorConfig config = new MetricMonitorConfig();
        config.setMetricId(metricItem.getId());
        config.setAlarmWindowMinutes(5);
        // 阈值不再从指标表继承：指标不承载告警阈值（2026-10-28 解耦），
        // 告警阈值请在「告警管理 → 告警策略」中配置。
        config.setAlarmStatus(1);
        config.setGongdanWindowMinutes(30);
        config.setConsecutiveCount(3);
        config.setGongdanStatus(1);
        return metricMonitorConfigRepository.save(config);
    }

    private boolean hasCategory(MetricItem metric, String categoryCode) {
        return metricCategoryRepository.findById(metric.getCategoryId())
                .map(category -> categoryCode.equals(category.getCategoryCode())).orElse(false);
    }

    /**
     * 判断是否超过阈值
     */
    private boolean isExceeded(BigDecimal value, BigDecimal threshold, String condition) {
        if (value == null || threshold == null) {
            return false;
        }
        if (">".equals(condition)) {
            return value.compareTo(threshold) > 0;
        } else if ("<".equals(condition)) {
            return value.compareTo(threshold) < 0;
        } else if (">=".equals(condition)) {
            return value.compareTo(threshold) >= 0;
        } else if ("<=".equals(condition)) {
            return value.compareTo(threshold) <= 0;
        } else if ("=".equals(condition) || "==".equals(condition)) {
            return value.compareTo(threshold) == 0;
        }
        return false;
    }

    /**
     * 生成告警（如果还没有未处理的告警）
     * @deprecated 已禁用，告警统一通过 AlertGenerateService + AlarmStrategy 生成
     */
    @Deprecated
    private void generateAlarmIfNeeded(MetricItem metricItem, MetricRealtime realtime, MetricMonitorConfig config) {
        // 已禁用：告警统一通过 AlertGenerateService + AlarmStrategy 生成
        return;
    }

    /**
     * 生成工单（如果还没有未处理的工单）
     * @deprecated 已禁用，工单统一通过 AlertGenerateService + AlarmStrategy 生成
     */
    @Deprecated
    private void generateGongdanIfNeeded(MetricItem metricItem, MetricRealtime realtime, MetricMonitorConfig config) {
        // 已禁用：工单统一通过 AlertGenerateService + AlarmStrategy 生成
        return;
    }

    /**
     * 生成工单号
     * @deprecated 已禁用
     */
    @Deprecated
    private String generateGongdanId() {
        return "GD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    /**
     * 获取所有实时指标数据（用于前端展示）
     */
    public List<Map<String, Object>> getRealtimeMetrics(String category, String subCategory) {
        return getRealtimeMetrics(category, subCategory, false);
    }

    /**
     * 按大类取实时指标行；小类在同一份结果里过滤。
     * 一次完整读取要解析十几万行采集 JSON（约 18s），切换小类不应重复付出该成本，
     * 所以按大类缓存。缓存过期后先返回旧结果并异步刷新，用户不等待；
     * 前端主动刷新（refresh=true）时绕过缓存重新读取。
     */
    public List<Map<String, Object>> getRealtimeMetrics(String category, String subCategory, boolean refresh) {
        String categoryScope = normalizeScopeName(category);
        String subCategoryScope = normalizeScopeName(subCategory);
        if (categoryScope == null && subCategoryScope == null) return getAllRealtimeMetrics();
        // 只给小类时范围跨大类，缓存键无法表达，按原路径直查。
        if (categoryScope == null) {
            return observationReads.latest(observationReads.scope(null, subCategoryScope), null, LocalDateTime.now());
        }
        List<Map<String, Object>> rows = realtimeRowsFor(categoryScope, refresh);
        if (subCategoryScope == null) return rows;
        return rows.stream()
                .filter(row -> subCategoryScope.equals(row.get("subCategoryName")))
                .toList();
    }

    private static final long REALTIME_CACHE_TTL_MS = 60_000L;
    private final Map<String, RealtimeCacheEntry> realtimeCache = new ConcurrentHashMap<>();
    private final ExecutorService realtimeCacheRefresher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "realtime-scope-refresh");
        thread.setDaemon(true);
        return thread;
    });

    private static final class RealtimeCacheEntry {
        private final List<Map<String, Object>> rows;
        private final long loadedAt;
        private final AtomicBoolean refreshing = new AtomicBoolean(false);

        private RealtimeCacheEntry(List<Map<String, Object>> rows, long loadedAt) {
            this.rows = List.copyOf(rows);
            this.loadedAt = loadedAt;
        }
    }

    private List<Map<String, Object>> realtimeRowsFor(String categoryScope, boolean refresh) {
        if (refresh) {
            RealtimeCacheEntry fresh = new RealtimeCacheEntry(loadRealtimeRows(categoryScope), System.currentTimeMillis());
            realtimeCache.put(categoryScope, fresh);
            return fresh.rows;
        }
        RealtimeCacheEntry entry = realtimeCache.get(categoryScope);
        if (entry == null) {
            entry = realtimeCache.computeIfAbsent(categoryScope,
                    key -> new RealtimeCacheEntry(loadRealtimeRows(key), System.currentTimeMillis()));
            return entry.rows;
        }
        if (System.currentTimeMillis() - entry.loadedAt > REALTIME_CACHE_TTL_MS
                && entry.refreshing.compareAndSet(false, true)) {
            RealtimeCacheEntry stale = entry;
            realtimeCacheRefresher.submit(() -> {
                try {
                    realtimeCache.put(categoryScope,
                            new RealtimeCacheEntry(loadRealtimeRows(categoryScope), System.currentTimeMillis()));
                } catch (RuntimeException error) {
                    System.out.println("刷新实时指标缓存失败(" + categoryScope + "): " + error.getMessage());
                } finally {
                    stale.refreshing.set(false);
                }
            });
        }
        return entry.rows;
    }

    private List<Map<String, Object>> loadRealtimeRows(String categoryScope) {
        return observationReads.latest(observationReads.scope(categoryScope, null), null, LocalDateTime.now());
    }

    /** 空白筛选值视为未筛选，避免空串触发一次无意义的指标 ID 查询。 */
    private static String normalizeScopeName(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public List<Map<String, Object>> getAllRealtimeMetrics() {
        return observationReads.latest(null,null,LocalDateTime.now());
    }

    /**
     * 构建有实时数据的指标行
     */
    private Map<String, Object> buildMetricRow(MetricItem metricItem, MetricMonitorConfig config, MetricRealtime realtime) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", realtime.getId());
        row.put("metricId", realtime.getMetricId());
        row.put("metricName", metricItem.getMetricName());
        row.put("metricCode", metricItem.getMetricCode());
        row.put("subCategoryName", metricItem.getSubCategoryName());
        row.put("metricValue", realtime.getMetricValue());
        row.put("dataTime", realtime.getDataTime() != null ? realtime.getDataTime().format(FORMATTER) : "");
        row.put("isExceed", realtime.getIsExceed());
        row.put("exceedCount", realtime.getExceedCount());
        row.put("status", realtime.getStatus());

        // 当状态为工单时，查询关联的工单ID
        if ("工单".equals(realtime.getStatus())) {
            List<Gongdan> gongdans = gongdanRepository.findByMetricIdOrderByGongdanTimeDesc(realtime.getMetricId());
            if (!gongdans.isEmpty()) {
                row.put("gongdanId", gongdans.get(0).getId());
            }
        }

        // 告警配置
        if (config != null) {
            row.put("alarmThreshold", alarmThresholdOf(metricItem.getId()));
            row.put("alarmCondition", alarmConditionOf(metricItem.getId()));
            row.put("alarmStatus", config.getAlarmStatus());
            row.put("gongdanThreshold", gongdanThresholdOf(metricItem.getId()));
            row.put("gongdanCondition", gongdanConditionOf(metricItem.getId()));
            row.put("consecutiveCount", config.getConsecutiveCount());
            row.put("gongdanStatus", config.getGongdanStatus());
        } else {
            row.put("alarmThreshold", alarmThresholdOf(metricItem.getId()));
            row.put("alarmCondition", alarmConditionOf(metricItem.getId()));
            row.put("alarmStatus", 1);
            row.put("gongdanThreshold", gongdanThresholdOf(metricItem.getId()));
            row.put("gongdanCondition", gongdanConditionOf(metricItem.getId()));
            row.put("consecutiveCount", 3);
            row.put("gongdanStatus", 1);
        }

        row.put("unit", metricItem.getUnit());
        row.put("metricDefinition", metricItem.getMetricDefinition());
        return row;
    }

    /**
     * 构建没有实时数据的指标行
     */
    private Map<String, Object> buildMetricRowWithoutRealtime(MetricItem metricItem, MetricMonitorConfig config) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", null);
        row.put("metricId", metricItem.getId());
        row.put("metricName", metricItem.getMetricName());
        row.put("metricCode", metricItem.getMetricCode());
        row.put("subCategoryName", metricItem.getSubCategoryName());
        row.put("metricValue", null);
        row.put("dataTime", "");
        row.put("isExceed", 0);
        row.put("exceedCount", 0);
        row.put("status", "正常");

        // 告警配置
        if (config != null) {
            row.put("alarmThreshold", alarmThresholdOf(metricItem.getId()));
            row.put("alarmCondition", alarmConditionOf(metricItem.getId()));
            row.put("alarmStatus", config.getAlarmStatus());
            row.put("gongdanThreshold", gongdanThresholdOf(metricItem.getId()));
            row.put("gongdanCondition", gongdanConditionOf(metricItem.getId()));
            row.put("consecutiveCount", config.getConsecutiveCount());
            row.put("gongdanStatus", config.getGongdanStatus());
        } else {
            row.put("alarmThreshold", alarmThresholdOf(metricItem.getId()));
            row.put("alarmCondition", alarmConditionOf(metricItem.getId()));
            row.put("alarmStatus", 1);
            row.put("gongdanThreshold", gongdanThresholdOf(metricItem.getId()));
            row.put("gongdanCondition", gongdanConditionOf(metricItem.getId()));
            row.put("consecutiveCount", 3);
            row.put("gongdanStatus", 1);
        }

        row.put("unit", metricItem.getUnit());
        row.put("metricDefinition", metricItem.getMetricDefinition());
        return row;
    }

    /**
     * 获取业务视角的实时指标数据（用于运营报表-实时指标页面）
     * 按指标大类（小类）过滤，返回 metric_realtime 表中的最新指标值
     *
     * @param category 指标大类名称：对外部客户、对内部人员、系统运行、计划执行
     * @param subCategory 指标小类名称：如查询类、系统稽核、系统性能等，可为空
     * @return 实时指标列表
     */
    public List<Map<String, Object>> getBusinessRealtimeMetrics(String category, String subCategory, String timeRange) {
        if(category==null || category.isBlank()) return List.of();
        LocalDateTime now=LocalDateTime.now();
        LocalDateTime start=timeRange==null || timeRange.isBlank()?null:"today".equals(timeRange)?now.toLocalDate().atStartOfDay():com.metric.shared.util.GranularityUtil.getStartTime(timeRange,now);
        return observationReads.latest(observationReads.scope(category,subCategory),start,now).stream().filter(row -> !Boolean.TRUE.equals(row.get("isStale"))).toList();
    }

    /**
     * 根据指标ID获取实时数据
     */
    public Map<String, Object> getRealtimeMetricById(Long metricId) {
        return observationReads.latest(List.of(metricId),null,LocalDateTime.now()).stream().findFirst().orElse(null);
    }

    /**
     * 获取监控配置
     */
    public MetricMonitorConfig getMonitorConfig(Long metricId) {
        refreshThresholdCaches();
        return metricMonitorConfigRepository.findByMetricId(metricId).orElse(null);
    }

    /**
     * 更新监控配置
     */
    @Transactional
    public SyncResult updateMonitorConfig(Long metricId, MetricMonitorConfig newConfig) {
        MetricMonitorConfig config = metricMonitorConfigRepository.findByMetricId(metricId)
                .orElseGet(() -> {
                    MetricMonitorConfig c = new MetricMonitorConfig();
                    c.setMetricId(metricId);
                    return c;
                });

        if (newConfig.getAlarmWindowMinutes() != null) {
            config.setAlarmWindowMinutes(newConfig.getAlarmWindowMinutes());
        }
        // 阈值/条件不再写入监控配置：阈值唯一来源为告警策略（2026-10-28 解耦）。
        // 若调用方仍传阈值，此处忽略，请改用「告警管理 → 告警策略」。
        if (newConfig.getAlarmStatus() != null) {
            config.setAlarmStatus(newConfig.getAlarmStatus());
        }
        if (newConfig.getGongdanWindowMinutes() != null) {
            config.setGongdanWindowMinutes(newConfig.getGongdanWindowMinutes());
        }
        if (newConfig.getConsecutiveCount() != null) {
            config.setConsecutiveCount(newConfig.getConsecutiveCount());
        }
        if (newConfig.getGongdanStatus() != null) {
            config.setGongdanStatus(newConfig.getGongdanStatus());
        }

        MetricMonitorConfig saved = metricMonitorConfigRepository.save(config);
        refreshThresholdCaches();

        // 阈值不再由监控配置同步到策略：策略是阈值唯一真值（2026-10-28 解耦）。
        return SyncResult.success("配置保存成功", 1);
    }

    /**
     * 阈值模拟 - 基于历史数据预测告警数量
     */
    public Map<String, Object> simulateThreshold(Long metricId, BigDecimal threshold, String condition,
                                                  LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> result = new HashMap<>();

        // 获取指标信息
        Optional<MetricItem> metricOpt = metricItemRepository.findById(metricId);
        if (metricOpt.isEmpty()) {
            result.put("error", "未找到该指标");
            return result;
        }

        MetricItem metric = metricOpt.get();
        String subCategoryName = metric.getSubCategoryName();

        // 确定数据表和字段
        String tableName = getTableNameBySubCategory(subCategoryName);
        if (tableName == null) {
            result.put("error", "该指标类型不支持模拟");
            return result;
        }

        String valueColumn = "inner_query".equals(tableName) ? "avg_duration_sec" : "avg_response_sec";
        String timeColumn = "data_time";

        // 如果没有指定时间范围，默认最近7天
        if (startTime == null) {
            startTime = LocalDateTime.now().minusDays(7);
        }
        if (endTime == null) {
            endTime = LocalDateTime.now();
        }

        try {
            // 查询原始数据
            List<Map<String, Object>> dataList = queryRawDataForSimulation(
                    tableName, subCategoryName, valueColumn, timeColumn, startTime, endTime);

            // 应用阈值条件筛选
            List<Map<String, Object>> simulatedAlarms = new ArrayList<>();
            int totalCount = 0;

            for (Map<String, Object> record : dataList) {
                Object valueObj = record.get("value");
                if (valueObj == null) continue;

                BigDecimal value = new BigDecimal(valueObj.toString());
                if (isExceeded(value, threshold, condition)) {
                    totalCount++;
                    simulatedAlarms.add(Map.of(
                            "time", record.get("time"),
                            "value", value,
                            "threshold", threshold,
                            "condition", condition
                    ));
                }
            }

            // 按小时/天聚合
            Map<String, Integer> aggregated = new LinkedHashMap<>();
            for (Map<String, Object> alarm : simulatedAlarms) {
                String time = alarm.get("time").toString();
                // 聚合到小时级别
                String hourKey = time.length() >= 13 ? time.substring(0, 13) : time;
                aggregated.put(hourKey, aggregated.getOrDefault(hourKey, 0) + 1);
            }

            // 转换为图表数据格式
            List<Map<String, Object>> chartData = new ArrayList<>();
            aggregated.forEach((time, count) -> {
                chartData.add(Map.of("time", time, "count", count));
            });

            result.put("data", chartData);
            result.put("total", totalCount);
            result.put("metricName", metric.getMetricName());
            result.put("subCategoryName", subCategoryName);
            result.put("threshold", threshold);
            result.put("condition", condition);

        } catch (Exception e) {
            result.put("error", "模拟计算失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 根据指标小类获取数据表名
     */
    private String getTableNameBySubCategory(String subCategoryName) {
        if ("查询类".equals(subCategoryName)) {
            return "inner_query";
        } else if ("办理类-单业务".equals(subCategoryName)) {
            return "inner_step_response";
        } else if ("办理类-融合业务".equals(subCategoryName)) {
            return "inner_step_response";
        } else if ("系统稽核".equals(subCategoryName)) {
            return "system_jhd";
        }
        return null;
    }

    /**
     * 查询原始数据用于模拟
     */
    private List<Map<String, Object>> queryRawDataForSimulation(
            String tableName, String subCategoryName, String valueColumn, String timeColumn,
            LocalDateTime startTime, LocalDateTime endTime) throws SQLException {

        List<Map<String, Object>> result = new ArrayList<>();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DATE_FORMAT(").append(timeColumn).append(", '%Y-%m-%d %H:00:00') as time_bucket, ");
        sql.append("AVG(").append(valueColumn).append(") as value ");
        sql.append("FROM ").append(tableName).append(" WHERE 1=1 ");
        sql.append("AND ").append(timeColumn).append(" >= ? ");
        sql.append("AND ").append(timeColumn).append(" <= ? ");

        // 添加子类别过滤
        if ("inner_step_response".equals(tableName)) {
            String subCategoryValue = "办理类-单业务".equals(subCategoryName) ? "crm" : "chbn";
            sql.append(" AND sub_category = '").append(subCategoryValue).append("' ");
        }

        sql.append(" GROUP BY time_bucket ORDER BY time_bucket");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            stmt.setObject(1, startTime);
            stmt.setObject(2, endTime);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("time", rs.getString("time_bucket"));
                    row.put("value", rs.getDouble("value"));
                    result.add(row);
                }
            }
        }

        return result;
    }

    /**
     * 初始化监控配置（根据 metric_item 的阈值）
     */
    @Transactional
    public void initializeMonitorConfigs() {
        List<MetricItem> metricItems = metricItemRepository.findByStatus(1);
        int count = 0;

        for (MetricItem metricItem : metricItems) {
            if (metricMonitorConfigRepository.findByMetricId(metricItem.getId()).isEmpty()) {
                MetricMonitorConfig config = createDefaultMonitorConfig(metricItem);
                metricMonitorConfigRepository.save(config);
                count++;
            }
        }

        System.out.println("=== 初始化监控配置完成，共创建 " + count + " 条配置 ===");
    }

    /**
     * 格式化数字，去除多余的小数位
     */
    private String formatNumber(Number value) {
        if (value == null) return "0";
        return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    }
}
