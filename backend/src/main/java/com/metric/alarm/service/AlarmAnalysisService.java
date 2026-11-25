package com.metric.alarm.service;

import com.metric.alarm.entity.Alarm;
import com.metric.alarm.entity.AlarmAnalysisConfig;
import com.metric.alarm.entity.AlarmAnalysisReport;
import com.metric.alarm.repository.AlarmAnalysisConfigRepository;
import com.metric.alarm.repository.AlarmAnalysisReportRepository;
import com.metric.alarm.repository.AlarmRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AlarmAnalysisService {

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private AlarmAnalysisReportRepository reportRepository;

    @Autowired
    private AlarmAnalysisConfigRepository configRepository;

    private static final Map<String, String> PATTERN_DESCRIPTIONS = new HashMap<>();
    private static final Map<String, String> SUGGESTIONS = new HashMap<>();

    static {
        // 模式描述
        PATTERN_DESCRIPTIONS.put("high_frequency", "高频告警：该指标在过去7天内告警次数显著高于其他指标");
        PATTERN_DESCRIPTIONS.put("time_based", "时间规律：该指标告警存在明显的时间聚集性");
        PATTERN_DESCRIPTIONS.put("value_spike", "数值异常：该指标告警时数值存在较大波动");
        PATTERN_DESCRIPTIONS.put("consecutive", "连续告警：该指标存在连续多天告警的情况");
        PATTERN_DESCRIPTIONS.put("correlated", "关联告警：该指标与其他指标存在同时告警的规律");

        // 建议
        SUGGESTIONS.put("high_frequency", "建议检查该指标的业务阈值设置是否合理，或考虑优化数据源性能");
        SUGGESTIONS.put("time_based", "建议在该时间段增加监控关注度，或检查是否有定时任务影响");
        SUGGESTIONS.put("value_spike", "建议检查数据采集稳定性，或考虑增加数据平滑处理");
        SUGGESTIONS.put("consecutive", "建议检查该问题的根本原因是否已解决，避免重复告警");
        SUGGESTIONS.put("correlated", "建议检查这几个指标是否受同一因素影响，进行联合排查");
    }

    /**
     * 定时分析任务 - 每天凌晨2点执行
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void scheduledAnalysis() {
        AlarmAnalysisConfig config = getConfig();
        if (config == null || config.getAnalysisEnabled() != 1) {
            System.out.println("告警规律分析功能未启用");
            return;
        }

        LocalDate analysisDate = LocalDate.now().minusDays(1);
        analyzeAndGenerateReport(analysisDate, config.getLookbackDays());
    }

    /**
     * 执行分析和生成报告
     */
    public void analyzeAndGenerateReport(LocalDate reportDate, Integer lookbackDays) {
        LocalDate startDate = reportDate.minusDays(lookbackDays != null ? lookbackDays : 7);

        // 查询历史告警数据
        List<Alarm> historicalAlarms = alarmRepository.findByAlarmTimeBetween(
                startDate.atStartOfDay(),
                reportDate.atTime(LocalTime.MAX)
        );

        if (historicalAlarms.isEmpty()) {
            System.out.println("分析周期内无告警数据");
            return;
        }

        // 按指标分组分析
        Map<String, List<Alarm>> alarmsByMetric = historicalAlarms.stream()
                .filter(a -> a.getMetricName() != null)
                .collect(Collectors.groupingBy(Alarm::getMetricName));

        List<AlarmAnalysisReport> reports = new ArrayList<>();

        for (Map.Entry<String, List<Alarm>> entry : alarmsByMetric.entrySet()) {
            String metricName = entry.getKey();
            List<Alarm> alarms = entry.getValue();

            AlarmAnalysisReport report = analyzeMetric(metricName, alarms, reportDate);
            reports.add(report);
        }

        // 保存报告
        reportRepository.saveAll(reports);
        System.out.println("生成告警分析报告 " + reports.size() + " 条");
    }

    /**
     * 分析单个指标
     */
    private AlarmAnalysisReport analyzeMetric(String metricName, List<Alarm> alarms, LocalDate reportDate) {
        AlarmAnalysisReport report = new AlarmAnalysisReport();
        report.setReportDate(reportDate);
        report.setMetricName(metricName);
        report.setAlarmCount(alarms.size());

        // 计算统计数据
        List<BigDecimal> values = alarms.stream()
                .map(Alarm::getMetricValue)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (!values.isEmpty()) {
            BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            report.setAvgValue(sum.divide(new BigDecimal(values.size()), 4, RoundingMode.HALF_UP));
            report.setMaxValue(values.stream().max(BigDecimal::compareTo).orElse(null));
        }

        // 检测告警模式
        List<String> detectedPatterns = detectPatterns(alarms);
        if (!detectedPatterns.isEmpty()) {
            String primaryPattern = detectedPatterns.get(0);
            report.setRuleType(primaryPattern);
            report.setRuleDescription(PATTERN_DESCRIPTIONS.getOrDefault(primaryPattern, ""));
            report.setSuggestion(SUGGESTIONS.getOrDefault(primaryPattern, "建议持续关注该指标"));
        } else {
            report.setRuleType("normal");
            report.setRuleDescription("未检测到明显规律");
            report.setSuggestion("该指标告警无明显规律，建议正常监控");
        }

        return report;
    }

    /**
     * 检测告警模式
     */
    private List<String> detectPatterns(List<Alarm> alarms) {
        List<String> patterns = new ArrayList<>();

        // 高频检测：告警次数超过阈值
        if (alarms.size() > 10) {
            patterns.add("high_frequency");
        }

        // 时间规律检测：检查告警时间分布
        Map<Integer, Long> hourDistribution = alarms.stream()
                .filter(a -> a.getAlarmTime() != null)
                .collect(Collectors.groupingBy(a -> a.getAlarmTime().getHour(), Collectors.counting()));

        long peakHourCount = hourDistribution.values().stream().max(Long::compareTo).orElse(0L);
        if (peakHourCount > alarms.size() * 0.4 && hourDistribution.size() < 6) {
            patterns.add("time_based");
        }

        // 数值波动检测
        List<BigDecimal> values = alarms.stream()
                .map(Alarm::getMetricValue)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (values.size() >= 3) {
            BigDecimal max = values.stream().max(BigDecimal::compareTo).orElse(null);
            BigDecimal min = values.stream().min(BigDecimal::compareTo).orElse(null);
            if (max != null && min != null && min.compareTo(BigDecimal.ZERO) > 0) {
                double varianceRatio = max.divide(min, 4, RoundingMode.HALF_UP).doubleValue();
                if (varianceRatio > 2.0) {
                    patterns.add("value_spike");
                }
            }
        }

        // 连续告警检测
        Set<LocalDate> alarmDates = alarms.stream()
                .filter(a -> a.getAlarmTime() != null)
                .map(a -> a.getAlarmTime().toLocalDate())
                .collect(Collectors.toSet());

        if (alarmDates.size() >= 3) {
            List<LocalDate> sortedDates = new ArrayList<>(alarmDates);
            Collections.sort(sortedDates);
            int consecutiveDays = 1;
            for (int i = 1; i < sortedDates.size(); i++) {
                if (sortedDates.get(i).minusDays(1).equals(sortedDates.get(i - 1))) {
                    consecutiveDays++;
                } else {
                    consecutiveDays = 1;
                }
                if (consecutiveDays >= 3) {
                    patterns.add("consecutive");
                    break;
                }
            }
        }

        // 关联告警检测：检查是否有多个指标在同一时间窗口内告警
        if (detectCorrelatedPattern(alarms)) {
            patterns.add("correlated");
        }

        return patterns;
    }

    /**
     * 检测关联告警模式
     * 如果多个不同指标在短时间窗口内（1小时）同时告警，说明它们可能存在关联
     */
    private boolean detectCorrelatedPattern(List<Alarm> alarms) {
        if (alarms.size() < 3) {
            return false;
        }

        // 按时间窗口分组，检查是否有多个不同指标在同一小时内告警
        Map<String, Set<String>> timeWindowMetrics = new HashMap<>();

        for (Alarm alarm : alarms) {
            if (alarm.getAlarmTime() == null || alarm.getMetricName() == null) {
                continue;
            }
            // 按小时分组
            LocalDateTime alarmTime = alarm.getAlarmTime();
            String timeKey = alarmTime.toLocalDate().toString() + "_" + (alarmTime.getHour() / 2); // 2小时窗口

            timeWindowMetrics.computeIfAbsent(timeKey, k -> new HashSet<>()).add(alarm.getMetricName());
        }

        // 检查是否有时间窗口内存在多个不同指标
        for (Set<String> metricsInWindow : timeWindowMetrics.values()) {
            if (metricsInWindow.size() >= 2) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取配置
     */
    public AlarmAnalysisConfig getConfig() {
        List<AlarmAnalysisConfig> configs = configRepository.findAll();
        if (configs.isEmpty()) {
            AlarmAnalysisConfig defaultConfig = new AlarmAnalysisConfig();
            return configRepository.save(defaultConfig);
        }
        return configs.get(0);
    }

    /**
     * 更新配置
     */
    @Transactional
    public AlarmAnalysisConfig updateConfig(AlarmAnalysisConfig config) {
        AlarmAnalysisConfig existing = getConfig();
        if (config.getCronExpression() != null) {
            existing.setCronExpression(config.getCronExpression());
        }
        if (config.getAnalysisEnabled() != null) {
            existing.setAnalysisEnabled(config.getAnalysisEnabled());
        }
        if (config.getLookbackDays() != null) {
            existing.setLookbackDays(config.getLookbackDays());
        }
        return configRepository.save(existing);
    }

    /**
     * 获取报告列表
     */
    public List<AlarmAnalysisReport> getReports(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            return reportRepository.findByReportDateBetweenOrderByReportDateDesc(startDate, endDate);
        }
        return reportRepository.findAll();
    }

    /**
     * 获取报告列表（分页）
     */
    public Page<AlarmAnalysisReport> getReportsPaged(LocalDate startDate, LocalDate endDate, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        if (startDate != null && endDate != null) {
            return reportRepository.findByReportDateBetweenOrderByReportDateDesc(startDate, endDate, pageable);
        }
        return reportRepository.findAllByOrderByReportDateDesc(pageable);
    }

    /**
     * 批量删除报告
     */
    @Transactional
    public int batchDeleteReports(List<Long> ids) {
        int count = 0;
        for (Long id : ids) {
            reportRepository.deleteById(id);
            count++;
        }
        return count;
    }

    /**
     * 获取指定日期的报告
     */
    public List<AlarmAnalysisReport> getReportsByDate(LocalDate reportDate) {
        return reportRepository.findByReportDateOrderByIdDesc(reportDate);
    }

    /**
     * 手动触发分析
     */
    @Transactional
    public void triggerAnalysis(LocalDate reportDate, Integer lookbackDays) {
        if (reportDate == null) {
            reportDate = LocalDate.now().minusDays(1);
        }
        if (lookbackDays == null) {
            lookbackDays = getConfig().getLookbackDays();
        }
        analyzeAndGenerateReport(reportDate, lookbackDays);
    }
}
