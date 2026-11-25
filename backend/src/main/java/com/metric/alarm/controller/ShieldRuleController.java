package com.metric.alarm.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.alarm.entity.ShieldRule;
import com.metric.alarm.service.ShieldRuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/shield-rule")
public class ShieldRuleController {

    @Autowired
    private ShieldRuleService shieldRuleService;

    @GetMapping
    public ApiResponse<List<ShieldRule>> list() {
        return ApiResponse.success(shieldRuleService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<ShieldRule> get(@PathVariable Long id) {
        return shieldRuleService.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Shield rule not found"));
    }

    @PostMapping
    public ApiResponse<ShieldRule> create(@RequestBody ShieldRule rule) {
        if (rule.getShieldType() == null || rule.getTargetId() == null) {
            return ApiResponse.error("shieldType and targetId are required");
        }
        rule.setShieldTime(LocalDateTime.now());
        return ApiResponse.success(shieldRuleService.save(rule));
    }

    @PutMapping("/{id}")
    public ApiResponse<ShieldRule> update(@PathVariable Long id, @RequestBody ShieldRule rule) {
        return shieldRuleService.findById(id)
                .map(existing -> {
                    existing.setShieldEndTime(rule.getShieldEndTime());
                    return ApiResponse.success(shieldRuleService.save(existing));
                })
                .orElse(ApiResponse.error("Shield rule not found"));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        shieldRuleService.deleteById(id);
        return ApiResponse.success(null);
    }
}
