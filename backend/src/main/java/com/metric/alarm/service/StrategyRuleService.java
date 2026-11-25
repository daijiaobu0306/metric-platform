package com.metric.alarm.service;

import com.metric.alarm.entity.AlarmStrategy;
import com.metric.alarm.entity.StrategyRule;
import org.springframework.stereotype.Service;
import java.util.*;

/** 多档规则校验及旧单阈值兼容。空数组明确表示没有规则，null表示旧客户端未提交。 */
@Service
public class StrategyRuleService {
    public void prepare(AlarmStrategy strategy, AlarmStrategy existing) {
        if (existing != null) {
            if (strategy.getAlarmRules() == null && existing.getAlarmRules() != null
                    && strategy.getAlarmThreshold() != null && !Objects.equals(strategy.getAlarmThreshold(), existing.getAlarmThreshold()))
                throw new IllegalArgumentException("此策略已使用分级告警规则，请使用分级策略编辑器修改");
            if (strategy.getGongdanRules() == null && existing.getGongdanRules() != null
                    && strategy.getGongdanThreshold() != null && !Objects.equals(strategy.getGongdanThreshold(), existing.getGongdanThreshold()))
                throw new IllegalArgumentException("此策略已使用分级工单规则，请使用分级策略编辑器修改");
            if (strategy.getAlarmRules() == null) strategy.setAlarmRules(existing.getAlarmRules());
            if (strategy.getGongdanRules() == null) strategy.setGongdanRules(existing.getGongdanRules());
        }
        validate(strategy.getAlarmRules(), "告警", strategy.getAlarmEnabled());
        validate(strategy.getGongdanRules(), "工单", strategy.getGongdanEnabled());
        // 单值字段仅作为旧列表的摘要；规则列表是新策略的唯一执行来源。
        if (strategy.getAlarmRules() != null) {
            StrategyRule first = strategy.getAlarmRules().stream().min(Comparator.comparing(StrategyRule::getLevel)).orElse(null);
            strategy.setAlarmThreshold(first == null ? null : first.getThreshold());
            strategy.setAlarmCondition(first == null ? null : first.getCondition());
            strategy.setAlarmConsecutiveTimes(first == null ? null : first.getConsecutiveTimes());
        }
        if (strategy.getGongdanRules() != null) {
            StrategyRule first = strategy.getGongdanRules().stream().min(Comparator.comparing(StrategyRule::getLevel)).orElse(null);
            strategy.setGongdanThreshold(first == null ? null : first.getThreshold());
            strategy.setGongdanCondition(first == null ? null : first.getCondition());
            strategy.setGongdanConsecutiveTimes(first == null ? null : first.getConsecutiveTimes());
        }
    }

    private void validate(List<StrategyRule> rules, String label, Integer enabled) {
        if (rules == null) return;
        if (rules.size() > 3) throw new IllegalArgumentException(label + "规则最多三个档次");
        if (Integer.valueOf(1).equals(enabled) && rules.isEmpty()) throw new IllegalArgumentException(label + "已开启，请至少添加一条规则");
        Set<Integer> levels = new HashSet<>();
        for (StrategyRule rule : rules) {
            if (rule == null || rule.getLevel() == null || !Set.of(2, 3, 4).contains(rule.getLevel()) || !levels.add(rule.getLevel()))
                throw new IllegalArgumentException(label + "级别必须为一般、重要、严重且不得重复");
            // 关闭的规则允许保留未配置阈值；非空条件仍需合法。
            if (rule.getCondition() != null && !Set.of(">", "<", ">=", "<=", "=").contains(rule.getCondition()))
                throw new IllegalArgumentException(label + "比较条件不合法");
            if (Integer.valueOf(1).equals(enabled) && (rule.getThreshold() == null || rule.getCondition() == null))
                throw new IllegalArgumentException(label + "阈值或比较条件未配置");
            if (rule.getConsecutiveTimes() == null || rule.getConsecutiveTimes() < 1)
                throw new IllegalArgumentException(label + "连续有效更新次数必须为正整数");
            if (rule.getThreshold() != null && (rule.getThreshold().scale() > 4 || rule.getThreshold().abs().compareTo(new java.math.BigDecimal("100000000000")) >= 0))
                throw new IllegalArgumentException(label + "阈值最多11位整数、4位小数");
        }
    }

    public static List<StrategyRule> effectiveRules(AlarmStrategy strategy, boolean gongdan) {
        List<StrategyRule> rules = gongdan ? strategy.getGongdanRules() : strategy.getAlarmRules();
        if (rules != null) return rules;
        java.math.BigDecimal threshold = gongdan ? strategy.getGongdanThreshold() : strategy.getAlarmThreshold();
        String condition = gongdan ? strategy.getGongdanCondition() : strategy.getAlarmCondition();
        if (threshold == null || condition == null) return List.of();
        StrategyRule legacy = new StrategyRule();
        legacy.setLevel(2);
        legacy.setThreshold(threshold);
        legacy.setCondition(condition);
        Integer times = gongdan ? strategy.getGongdanConsecutiveTimes() : strategy.getAlarmConsecutiveTimes();
        legacy.setConsecutiveTimes(times == null ? 1 : times);
        return List.of(legacy);
    }
}
