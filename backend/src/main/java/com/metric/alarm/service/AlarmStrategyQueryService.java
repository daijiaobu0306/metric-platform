package com.metric.alarm.service;

import com.metric.alarm.entity.AlarmStrategy;
import com.metric.alarm.entity.StrategyRule;
import com.metric.alarm.repository.AlarmStrategyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 告警策略只读查询服务。
 *
 * <p>定位：指标、采集、报告等其它模块需要「某个指标的告警阈值」时，统一从这里读，
 * 避免各自拼 SQL、各自决定「多条策略取哪条」，造成同一指标在不同页面显示不同阈值。
 *
 * <p>口径（唯一约定）：
 * <ul>
 *   <li>只看 {@code strategy_type = 'metric'} 且 {@code status = 1}（已启用）的策略</li>
 *   <li>同一指标有多条启用策略时，取 {@code id} 最小、且该字段有值的那条</li>
 *   <li>没有启用策略 / 该字段为空 → 返回 {@code null}，调用方显示「-」，
 *       <b>不得兜底猜值或沿用旧字段</b></li>
 * </ul>
 *
 * <p>本类只读，不写数据，不改变告警生成逻辑。
 */
@Service
public class AlarmStrategyQueryService {

    @Autowired
    private AlarmStrategyRepository strategyRepository;

    /**
     * 指标级阈值快照：一次查询装载四张表（告警阈值/工单阈值/告警条件/工单条件）。
     * 列表页请用本方法取一次，再按 metricId 查 Map，避免逐条查库造成 N+1。
     */
    public MetricThresholdSnapshot loadSnapshot() {
        List<AlarmStrategy> list = strategyRepository.findByStrategyType("metric");
        list.sort(Comparator.comparing(AlarmStrategy::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Map<Long, BigDecimal> alarmThreshold = new HashMap<>();
        Map<Long, BigDecimal> gongdanThreshold = new HashMap<>();
        Map<Long, String> alarmCondition = new HashMap<>();
        Map<Long, String> gongdanCondition = new HashMap<>();

        Map<Long, List<StrategyRule>> alarmRules = new HashMap<>();
        Map<Long, List<StrategyRule>> gongdanRules = new HashMap<>();
        for (AlarmStrategy s : list) {
            if (!"metric".equals(s.getTargetType()) || s.getTargetId() == null) {
                continue;
            }
            if (s.getStatus() != null && s.getStatus() == 0) {
                continue;
            }
            Long id = s.getTargetId();
            if (Integer.valueOf(1).equals(s.getAlarmEnabled())) {
                var rules = StrategyRuleService.effectiveRules(s, false);
                if (!rules.isEmpty()) alarmRules.putIfAbsent(id, rules);
            }
            if (Integer.valueOf(1).equals(s.getGongdanEnabled())) {
                var rules = StrategyRuleService.effectiveRules(s, true);
                if (!rules.isEmpty()) gongdanRules.putIfAbsent(id, rules);
            }
            if (Integer.valueOf(1).equals(s.getAlarmEnabled()) && s.getAlarmThreshold() != null && !alarmThreshold.containsKey(id)) {
                alarmThreshold.put(id, s.getAlarmThreshold());
                alarmCondition.put(id, s.getAlarmCondition());
            }
            if (Integer.valueOf(1).equals(s.getGongdanEnabled()) && s.getGongdanThreshold() != null && !gongdanThreshold.containsKey(id)) {
                gongdanThreshold.put(id, s.getGongdanThreshold());
                gongdanCondition.put(id, s.getGongdanCondition());
            }
        }
        return new MetricThresholdSnapshot(alarmThreshold, gongdanThreshold, alarmCondition, gongdanCondition, alarmRules, gongdanRules);
    }

    /** 取该指标"主策略"（启用中 id 最小的一条） */
    public Optional<AlarmStrategy> findPrimaryMetricStrategy(Long metricId) {
        if (metricId == null) {
            return Optional.empty();
        }
        return strategyRepository.findByTargetTypeAndTargetIdAndStatus("metric", metricId, 1).stream()
                .filter(s -> s.getStrategyType() == null || "metric".equals(s.getStrategyType()))
                .min(Comparator.comparing(AlarmStrategy::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    /** 该指标的告警阈值；未配置返回 null */
    public BigDecimal findAlarmThreshold(Long metricId) {
        return findPrimaryMetricStrategy(metricId).map(AlarmStrategy::getAlarmThreshold).orElse(null);
    }

    /** 该指标的工单阈值；未配置返回 null */
    public BigDecimal findGongdanThreshold(Long metricId) {
        return findPrimaryMetricStrategy(metricId).map(AlarmStrategy::getGongdanThreshold).orElse(null);
    }

    /** 指标级阈值快照 */
    public static class MetricThresholdSnapshot {
        private final Map<Long, List<StrategyRule>> alarmRules;
        private final Map<Long, List<StrategyRule>> gongdanRules;
        private final Map<Long, BigDecimal> alarmThreshold;
        private final Map<Long, BigDecimal> gongdanThreshold;
        private final Map<Long, String> alarmCondition;
        private final Map<Long, String> gongdanCondition;

        public MetricThresholdSnapshot(Map<Long, BigDecimal> alarmThreshold,
                                       Map<Long, BigDecimal> gongdanThreshold,
                                       Map<Long, String> alarmCondition,
                                       Map<Long, String> gongdanCondition, Map<Long, List<StrategyRule>> alarmRules, Map<Long, List<StrategyRule>> gongdanRules) {
            this.alarmRules = alarmRules;
            this.gongdanRules = gongdanRules;
            this.alarmThreshold = alarmThreshold;
            this.gongdanThreshold = gongdanThreshold;
            this.alarmCondition = alarmCondition;
            this.gongdanCondition = gongdanCondition;
        }

        public List<StrategyRule> alarmRules(Long metricId) { return alarmRules.getOrDefault(metricId, List.of()); }
        public List<StrategyRule> gongdanRules(Long metricId) { return gongdanRules.getOrDefault(metricId, List.of()); }

        public BigDecimal alarmThreshold(Long metricId) {
            return metricId == null ? null : alarmThreshold.get(metricId);
        }

        public BigDecimal gongdanThreshold(Long metricId) {
            return metricId == null ? null : gongdanThreshold.get(metricId);
        }

        public String alarmCondition(Long metricId) {
            return metricId == null ? null : alarmCondition.get(metricId);
        }

        public String gongdanCondition(Long metricId) {
            return metricId == null ? null : gongdanCondition.get(metricId);
        }

        public boolean isEmpty() {
            return alarmThreshold.isEmpty() && gongdanThreshold.isEmpty();
        }
    }
}
