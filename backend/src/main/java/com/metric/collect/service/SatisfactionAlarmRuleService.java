package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Updates the complete count policy atomically with its monitor and strategy. */
@Service
public class SatisfactionAlarmRuleService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SatisfactionAlarmRuleService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }

    @Transactional
    public void update(long metricId, Map<String, Object> input) {
        var rows = jdbc.queryForList("SELECT m.metric_code,p.config_value AS alarm_policy FROM metric_item m JOIN metric_category c ON c.id=m.category_id "
                + "LEFT JOIN system_config p ON p.config_key=CONCAT('satisfaction.alarm.policy.',m.metric_code) "
                + "WHERE m.id=? AND c.category_code='satisfaction' AND m.data_source='satisfaction_report' FOR UPDATE", metricId);
        if (rows.size() != 1) throw new IllegalArgumentException("满意度指标不存在");
        if (rows.get(0).get("alarm_policy") == null) throw new IllegalArgumentException("满意度分段或月累计告警规则未配置");
        String code = rows.get(0).get("metric_code").toString();
        if (!Set.of("SAT_FINAL_R003", "SAT_FINAL_R009", "SAT_FINAL_R015").contains(code))
            throw new IllegalArgumentException("该指标没有分段或月累计规则");
        if (!">".equals(input.get("alarmCondition"))) throw new IllegalArgumentException("工单量告警条件须为大于");
        BigDecimal daily = count(input.get("alarmThreshold"));
        BigDecimal monthly = count(input.get("monthlyThreshold"));
        BigDecimal edge = code.equals("SAT_FINAL_R003") ? count(input.get("edgeThreshold")) : null;
        if (!(input.get("workdayOnly") instanceof Boolean workday)) throw new IllegalArgumentException("请设置工作日规则");
        if (code.equals("SAT_FINAL_R015") && workday) throw new IllegalArgumentException("综调工单量按每日统计");
        try {
            ObjectNode policy = (ObjectNode) mapper.readTree(rows.get(0).get("alarm_policy").toString());
            policy.put("monthlyThreshold", monthly); policy.put("workdayOnly", workday);
            if (edge != null) policy.put("edgeThreshold", edge);
            String json = mapper.writeValueAsString(policy);
            if (json.length() > 500) throw new IllegalArgumentException("告警规则超过存储长度");
            // 2026-10-28「指标与告警解耦」后 metric_item 已删除 alarm_threshold /
            // alarm_threshold_type 两列（阈值唯一来源改为 alarm_strategy），
            // 原先的 UPDATE metric_item SET alarm_threshold=... 会抛 Unknown column，
            // 导致本接口整体回滚。阈值仍按下面三处落库：
            //   system_config      → 满意度分段/月累计规则（业务策略）
            //   metric_monitor_config → 采集侧监控配置（历史兼容）
            //   alarm_strategy     → 真正的告警阈值来源
            jdbc.update("UPDATE system_config SET config_value=? WHERE config_key=?", json, SatisfactionAlarmValueService.policyKey(code));
            jdbc.update("UPDATE metric_monitor_config SET alarm_threshold=?,alarm_condition='>' WHERE metric_id=?", daily, metricId);
            jdbc.update("UPDATE alarm_strategy SET alarm_threshold=?,alarm_condition='>' WHERE target_type='metric' AND strategy_type='metric' AND target_id=?", daily, metricId);
            jdbc.update("INSERT INTO metric_audit_log(operation_type,entity_type,entity_id,old_value,new_value,change_content) VALUES ('修改','metric_item',?,?,?,'修改满意度工单量完整告警规则')",
                    metricId, rows.get(0).get("alarm_policy"), json);
        } catch (java.io.IOException e) { throw new IllegalArgumentException("告警规则JSON无效", e); }
    }

    private BigDecimal count(Object value) {
        try {
            BigDecimal number = new BigDecimal(value.toString());
            if (number.signum() < 0 || number.stripTrailingZeros().scale() > 0 || number.compareTo(new BigDecimal("99999999")) > 0)
                throw new IllegalArgumentException("工单量阈值须为0至99999999的整数");
            return number;
        } catch (NullPointerException | NumberFormatException e) { throw new IllegalArgumentException("请填写有效的工单量阈值", e); }
    }
}
