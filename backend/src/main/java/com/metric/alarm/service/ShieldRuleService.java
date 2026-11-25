package com.metric.alarm.service;

import com.metric.alarm.entity.ShieldRule;
import com.metric.alarm.repository.ShieldRuleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ShieldRuleService {

    @Autowired
    private ShieldRuleRepository shieldRuleRepository;

    @Autowired
    private AlarmAuditService alarmAuditService;

    public List<ShieldRule> findAll() {
        return shieldRuleRepository.findAll();
    }

    public Optional<ShieldRule> findById(Long id) {
        return shieldRuleRepository.findById(id);
    }

    public boolean isAlarmShielded(Long systemId, Long categoryId, Long metricId) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 检查METRIC级别屏蔽
        if (metricId != null) {
            if (shieldRuleRepository.existsByShieldTypeAndTargetIdAndShieldEndTimeIsNullOrShieldEndTimeAfter(
                    "METRIC", metricId, now)) {
                return true;
            }
        }

        // 2. 检查CATEGORY级别屏蔽
        if (categoryId != null) {
            if (shieldRuleRepository.existsByShieldTypeAndTargetIdAndShieldEndTimeIsNullOrShieldEndTimeAfter(
                    "CATEGORY", categoryId, now)) {
                return true;
            }
        }

        // 3. 检查SYSTEM级别屏蔽
        if (systemId != null) {
            if (shieldRuleRepository.existsByShieldTypeAndTargetIdAndShieldEndTimeIsNullOrShieldEndTimeAfter(
                    "SYSTEM", systemId, now)) {
                return true;
            }
        }

        return false;
    }

    @Transactional
    public ShieldRule save(ShieldRule rule) {
        ShieldRule saved = shieldRuleRepository.save(rule);
        // 记录操作日志
        alarmAuditService.logShieldRuleOperation(
            saved.getId() == null ? AlarmAuditService.OP_SHIELD_RULE_CREATE : AlarmAuditService.OP_SHIELD_RULE_UPDATE,
            saved.getId(),
            "system",
            null,
            null,
            saved.getId() == null ? "新增屏蔽规则" : "修改屏蔽规则",
            "屏蔽类型: " + rule.getShieldType() + ", 对象: " + rule.getTargetName()
        );
        return saved;
    }

    @Transactional
    public void deleteById(Long id) {
        shieldRuleRepository.deleteById(id);
        // 记录操作日志
        alarmAuditService.logShieldRuleOperation(
            AlarmAuditService.OP_SHIELD_RULE_DELETE,
            id,
            "system",
            null,
            null,
            "删除屏蔽规则",
            null
        );
    }

    @Transactional
    public void processExpiredRules() {
        List<ShieldRule> expiredRules = shieldRuleRepository.findExpiredRules(LocalDateTime.now());
        shieldRuleRepository.deleteAll(expiredRules);
    }
}
