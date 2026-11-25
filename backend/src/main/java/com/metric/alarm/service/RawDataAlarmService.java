package com.metric.alarm.service;

import com.metric.alarm.entity.Alarm;
import com.metric.alarm.entity.RawDataAlarmConfig;
import com.metric.alarm.repository.AlarmRepository;
import com.metric.alarm.repository.RawDataAlarmConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * @deprecated 废弃服务，请使用 AlertGenerateService 代替。
 * 该服务的功能已迁移到 AlertGenerateService 的 scanRawDataAlarms() 方法。
 * RawDataAlarmConfig 配置现在由 AlertGenerateService 统一读取。
 */
@Deprecated
@Service
public class RawDataAlarmService {

    @Autowired
    private RawDataAlarmConfigRepository configRepository;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.metric.collect.service.AlertGenerateService alertGenerateService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 定时扫描原始数据告警配置并检测
     * 每分钟执行一次
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void scanAndCheck() {
        List<RawDataAlarmConfig> configs = configRepository.findByAlarmStatus(1);
        for (RawDataAlarmConfig config : configs) {
            // NONE是逐条判定，统一扫描器已负责；旧聚合扫描器不得再次生成NONE(...)。
            if ("NONE".equalsIgnoreCase(config.getAggMethod())) continue;
            try {
                checkConfig(config);
            } catch (Exception e) {
                System.err.println("检查配置 " + config.getId() + " 时出错: " + e.getMessage());
            }
        }
    }

    /**
     * 检查单个配置
     */
    public void checkConfig(RawDataAlarmConfig config) {
        if ("NONE".equalsIgnoreCase(config.getAggMethod())) {
            alertGenerateService.checkRawDataConfig(config.getId());
            return;
        }
        // 计算统计值
        BigDecimal statValue = calculateStatValue(config);
        if (statValue == null) {
            return;
        }

        // 判断是否超过阈值
        boolean exceeded = false;
        String alarmContent = null;

        if (config.getAlarmThreshold() != null) {
            // 使用固定阈值
            exceeded = statValue.compareTo(config.getAlarmThreshold()) > 0;
            alarmContent = String.format("[%s] %s 超过固定阈值：当前值=%s，阈值=%s",
                    config.getTableName(),
                    config.getMetricField(),
                    formatNumber(statValue),
                    formatNumber(config.getAlarmThreshold()));
        } else if (config.getThresholdMultiplier() != null) {
            // 使用动态阈值：平均值 * 倍数
            BigDecimal dynamicThreshold = statValue.multiply(config.getThresholdMultiplier());
            exceeded = statValue.compareTo(dynamicThreshold) > 0;
            alarmContent = String.format("[%s] %s 超过动态阈值：当前值=%s，阈值=%s (平均值×%.1f)",
                    config.getTableName(),
                    config.getMetricField(),
                    formatNumber(statValue),
                    formatNumber(dynamicThreshold),
                    config.getThresholdMultiplier());
        }

        if (exceeded) {
            // 检查是否已存在未处理的告警（避免重复）
            if (!hasRecentAlarm(config)) {
                generateAlarm(config, statValue, alarmContent);
            }
        }
    }

    /**
     * 计算统计值
     */
    private BigDecimal calculateStatValue(RawDataAlarmConfig config) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");

        // 聚合方法
        String calcMethod = config.getCalcMethod() != null ? config.getCalcMethod().toUpperCase() : "AVG";
        if (!java.util.Set.of("AVG", "SUM", "COUNT", "MAX", "MIN").contains(calcMethod)) {
            throw new IllegalArgumentException("不支持的聚合方式：" + calcMethod);
        }
        sql.append(calcMethod).append("(").append(config.getMetricField()).append(")");

        sql.append(" FROM ").append(config.getTableName());

        // 时间窗口条件
        sql.append(" WHERE insert_time >= '")
                .append(LocalDateTime.now().minusMinutes(config.getTimeWindowMinutes()).format(FORMATTER))
                .append("'");

        // 过滤条件
        if (config.getFilterField() != null && !config.getFilterField().isBlank() && config.getFilterValue() != null && !config.getFilterValue().isBlank()) {
            sql.append(" AND ").append(config.getFilterField()).append(" = '").append(config.getFilterValue()).append("'");
        }

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql.toString());
            if (results != null && !results.isEmpty()) {
                Object value = results.get(0).values().iterator().next();
                if (value instanceof Number) {
                    return new BigDecimal(value.toString());
                }
            }
        } catch (Exception e) {
            System.err.println("执行统计SQL失败: " + sql + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * 检查是否已存在最近未处理的告警
     */
    private boolean hasRecentAlarm(RawDataAlarmConfig config) {
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(config.getTimeWindowMinutes());
        List<Alarm> existingAlarms = alarmRepository.findByAlarmTimeBetween(windowStart, LocalDateTime.now());

        return existingAlarms.stream()
                .anyMatch(a -> a.getAlarmContent() != null
                        && a.getAlarmContent().contains(config.getTableName())
                        && a.getAlarmContent().contains(config.getMetricField())
                        && a.getProcessStatus() != null
                        && a.getProcessStatus() == 0);
    }

    /**
     * 生成告警
     */
    private void generateAlarm(RawDataAlarmConfig config, BigDecimal statValue, String alarmContent) {
        Alarm alarm = new Alarm();
        alarm.setAlarmType("原始数据告警");
        alarm.setAlarmContent(alarmContent);
        alarm.setMetricValue(statValue);
        alarm.setThresholdValue(config.getAlarmThreshold());
        alarm.setAlarmTime(LocalDateTime.now());
        alarm.setProcessStatus(0);
        alarm.setShieldStatus(0);
        alarm.setTagStatus(0);
        alarm.setCategoryName("运行效率");
        alarm.setSubCategoryName(config.getTableName());
        alarm.setMetricName(config.getMetricField());

        alarmService.save(alarm);
        System.out.println("生成原始数据告警: " + alarmContent);
    }

    /**
     * 手动触发检查
     */
    public void triggerCheck(Long configId) {
        configRepository.findById(configId).ifPresent(this::checkConfig);
    }

    /**
     * 获取所有配置
     */
    public List<RawDataAlarmConfig> getAllConfigs() {
        return configRepository.findAll();
    }

    /**
     * 获取启用的配置
     */
    public List<RawDataAlarmConfig> getEnabledConfigs() {
        return configRepository.findByAlarmStatus(1);
    }

    /**
     * 保存配置
     */
    @Transactional
    public RawDataAlarmConfig saveConfig(RawDataAlarmConfig config) {
        if (config.getTableName() == null || config.getTableName().isBlank()
                || config.getMetricField() == null || config.getMetricField().isBlank())
            throw new IllegalArgumentException("请选择数据表和指标字段");
        for (String field : new String[]{config.getTableName(), config.getMetricField(), config.getFilterField(), config.getSourceIdField()}) {
            if (field != null && !field.isBlank() && !field.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("数据表或字段名称无效：" + field);
        }
        if (config.getId() != null) {
            RawDataAlarmConfig existing = configRepository.findById(config.getId()).orElseThrow(() -> new IllegalArgumentException("配置不存在"));
            config.setCreateTime(existing.getCreateTime());
        }
        return configRepository.save(config);
    }

    /**
     * 删除配置
     */
    @Transactional
    public void deleteConfig(Long id) {
        configRepository.deleteById(id);
    }

    /**
     * 切换配置状态
     */
    @Transactional
    public boolean toggleConfigStatus(Long id) {
        return configRepository.findById(id).map(config -> {
            config.setAlarmStatus(config.getAlarmStatus() == 1 ? 0 : 1);
            configRepository.save(config);
            return config.getAlarmStatus() == 1;
        }).orElse(false);
    }

    private String formatNumber(BigDecimal value) {
        if (value == null) return "null";
        return value.setScale(2, RoundingMode.HALF_UP).toString();
    }
}
