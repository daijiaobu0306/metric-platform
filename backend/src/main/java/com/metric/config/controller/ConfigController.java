package com.metric.config.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.shared.dto.SyncResult;
import com.metric.alarm.entity.AlarmStrategy;
import com.metric.config.entity.SystemConfig;
import com.metric.alarm.repository.AlarmStrategyRepository;
import com.metric.config.service.ConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    @Autowired
    private ConfigService configService;

    @Autowired
    private AlarmStrategyRepository strategyRepository;


    private boolean isExternalMenuKey(String key) {
        if(key==null)return false;
        String normalized=java.text.Normalizer.normalize(key,java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}","").strip();
        return "navigation.external_menus.v1".equalsIgnoreCase(normalized)
                || "integration.zhiyun.v1".equalsIgnoreCase(normalized);
    }
    // === 系统配置 API ===

    @GetMapping("/system")
    public ApiResponse<List<SystemConfig>> findAllConfigs() {
        return ApiResponse.success(configService.findAllConfigs());
    }

    @GetMapping("/system/type/{configType}")
    public ApiResponse<List<SystemConfig>> findConfigsByType(@PathVariable String configType) {
        return ApiResponse.success(configService.findConfigsByType(configType));
    }

    @GetMapping("/system/key/{configKey}")
    public ApiResponse<SystemConfig> findConfigByKey(@PathVariable String configKey) {
        return configService.findConfigByKey(configKey)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Config not found"));
    }

    @GetMapping("/system/{id}")
    public ApiResponse<SystemConfig> findConfigById(@PathVariable Long id) {
        return configService.findConfigById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Config not found"));
    }

    @PostMapping("/system")
    public ApiResponse<SystemConfig> createConfig(@RequestBody SystemConfig config) {
        if (isExternalMenuKey(config.getConfigKey())) return ApiResponse.error("保留配置请使用菜单管理或智云嵌入配置入口");
        try {
            return ApiResponse.success(configService.saveConfig(config));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PutMapping("/system/{id}")
    public ApiResponse<SystemConfig> updateConfig(@PathVariable Long id, @RequestBody SystemConfig config) {
        return configService.findConfigById(id)
                .map(existing -> {
                    if (isExternalMenuKey(existing.getConfigKey()) || isExternalMenuKey(config.getConfigKey())) return ApiResponse.<SystemConfig>error("保留配置请使用菜单管理或智云嵌入配置入口");
                    config.setId(id);
                    try {
                        return ApiResponse.success(configService.saveConfig(config));
                    } catch (IllegalArgumentException e) {
                        return ApiResponse.<SystemConfig>error(e.getMessage());
                    }
                })
                .orElse(ApiResponse.error("Config not found"));
    }

    @DeleteMapping("/system/{id}")
    public ApiResponse<Void> deleteConfig(@PathVariable Long id) {
        if (configService.findConfigById(id).map(c -> isExternalMenuKey(c.getConfigKey())).orElse(false)) return ApiResponse.error("保留配置请使用菜单管理或智云嵌入配置入口");
        configService.deleteConfigById(id);
        return ApiResponse.success();
    }

    // === 告警策略 API ===

    @GetMapping("/strategy")
    public ApiResponse<List<AlarmStrategy>> findAllStrategies() {
        return ApiResponse.success(configService.findAllStrategies());
    }

    @GetMapping("/strategy/type/{strategyType}")
    public ApiResponse<List<AlarmStrategy>> findStrategiesByType(@PathVariable String strategyType) {
        return ApiResponse.success(configService.findStrategiesByType(strategyType));
    }

    @GetMapping("/strategy/target")
    public ApiResponse<List<AlarmStrategy>> findStrategiesByTarget(
            @RequestParam String targetType,
            @RequestParam Long targetId) {
        return ApiResponse.success(configService.findStrategiesByTarget(targetType, targetId));
    }

    @GetMapping("/strategy/filter")
    public ApiResponse<Page<AlarmStrategy>> filterStrategies(
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String strategyType,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer alarmEnabled,
            @RequestParam(required = false) Integer gongdanEnabled,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long subCategoryId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<AlarmStrategy> page = configService.findStrategiesByFilter(
                id, name, strategyType, targetType, status, alarmEnabled, gongdanEnabled,
                categoryId, subCategoryId, pageNum, pageSize);
        return ApiResponse.success(page);
    }

    /** 按当前筛选条件批量启停（大类/小类筛出多少条改多少条，不受分页限制）。 */
    public record StrategyBatchStatusRequest(Long id, String name, String strategyType, String targetType,
                                             Integer status, Integer alarmEnabled, Integer gongdanEnabled,
                                             Long categoryId, Long subCategoryId, Integer targetStatus) {}

    @PostMapping("/strategy/batch-status")
    public ApiResponse<Integer> batchStrategyStatus(@RequestBody StrategyBatchStatusRequest request) {
        try {
            int updated = configService.batchUpdateStrategyStatus(request.id(), request.name(),
                    request.strategyType(), request.targetType(), request.status(), request.alarmEnabled(),
                    request.gongdanEnabled(), request.categoryId(), request.subCategoryId(), request.targetStatus());
            return ApiResponse.success(updated);
        } catch (IllegalArgumentException error) {
            return ApiResponse.error(error.getMessage());
        }
    }

    @GetMapping("/strategy/{id}")
    public ApiResponse<AlarmStrategy> findStrategyById(@PathVariable Long id) {
        return configService.findStrategyById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Strategy not found"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.metric.alarm.service.StrategyRuleService strategyRuleService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @PostMapping("/strategy")
    public ApiResponse<AlarmStrategy> createStrategy(@RequestBody AlarmStrategy strategy) {
        // 策略即阈值唯一真值：不再反向写入 metric_monitor_config（2026-10-28 解耦）
        if ("raw".equals(strategy.getStrategyType())) throw new IllegalArgumentException("原始数据策略请使用原始数据策略编辑器保存");
        strategyRuleService.prepare(strategy, null);
        AlarmStrategy saved = strategyRepository.save(strategy);
        return ApiResponse.success(saved);
    }

    @PutMapping("/strategy/{id}")
    public ApiResponse<SyncResult> updateStrategy(@PathVariable Long id, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        AlarmStrategy strategy = objectMapper.convertValue(body, AlarmStrategy.class);
        return configService.findStrategyById(id)
                .map(existing -> {
                    if ("raw".equals(strategy.getStrategyType()) || ("raw".equals(existing.getStrategyType()) && (body.has("alarmRules") || body.has("gongdanRules") || body.has("strategyType"))))
                        throw new IllegalArgumentException("原始数据策略请使用原始数据策略编辑器保存");
                    strategy.setId(id);
                    if (!body.has("alarmEnabled")) strategy.setAlarmEnabled(existing.getAlarmEnabled());
                    if (!body.has("gongdanEnabled")) strategy.setGongdanEnabled(existing.getGongdanEnabled());
                    if (!body.has("alarmCondition")) strategy.setAlarmCondition(existing.getAlarmCondition());
                    if (!body.has("gongdanCondition")) strategy.setGongdanCondition(existing.getGongdanCondition());
                    if (!body.has("alarmConsecutiveTimes")) strategy.setAlarmConsecutiveTimes(existing.getAlarmConsecutiveTimes());
                    if (!body.has("gongdanConsecutiveTimes")) strategy.setGongdanConsecutiveTimes(existing.getGongdanConsecutiveTimes());
                    // 保留基础信息字段
                    if (strategy.getStrategyName() == null) {
                        strategy.setStrategyName(existing.getStrategyName());
                    }
                    if (strategy.getStrategyType() == null) {
                        strategy.setStrategyType(existing.getStrategyType());
                    }
                    if (strategy.getTargetType() == null) {
                        strategy.setTargetType(existing.getTargetType());
                    }
                    if (strategy.getTargetId() == null) {
                        strategy.setTargetId(existing.getTargetId());
                    }
                    if (strategy.getTargetName() == null) {
                        strategy.setTargetName(existing.getTargetName());
                    }
                    if (strategy.getConditionType() == null) {
                        strategy.setConditionType(existing.getConditionType());
                    }
                    if (strategy.getThresholdValue() == null) {
                        strategy.setThresholdValue(existing.getThresholdValue());
                    }
                    if (strategy.getConsecutiveTimes() == null) {
                        strategy.setConsecutiveTimes(existing.getConsecutiveTimes());
                    }
                    if (strategy.getGenerateType() == null) {
                        strategy.setGenerateType(existing.getGenerateType());
                    }
                    // 保留告警配置字段（只有传入null才保留，允许前端显式设置0）
                    if (strategy.getAlarmEnabled() == null) {
                        strategy.setAlarmEnabled(existing.getAlarmEnabled());
                    }
                    if (strategy.getAlarmThreshold() == null) {
                        strategy.setAlarmThreshold(existing.getAlarmThreshold());
                    }
                    if (strategy.getAlarmCondition() == null) {
                        strategy.setAlarmCondition(existing.getAlarmCondition());
                    }
                    if (strategy.getAlarmConsecutiveTimes() == null) {
                        strategy.setAlarmConsecutiveTimes(existing.getAlarmConsecutiveTimes());
                    }
                    // 保留工单配置字段
                    if (strategy.getGongdanEnabled() == null) {
                        strategy.setGongdanEnabled(existing.getGongdanEnabled());
                    }
                    if (strategy.getGongdanThreshold() == null) {
                        strategy.setGongdanThreshold(existing.getGongdanThreshold());
                    }
                    if (strategy.getGongdanCondition() == null) {
                        strategy.setGongdanCondition(existing.getGongdanCondition());
                    }
                    if (strategy.getGongdanConsecutiveTimes() == null) {
                        strategy.setGongdanConsecutiveTimes(existing.getGongdanConsecutiveTimes());
                    }
                    // 保留状态
                    if (strategy.getStatus() == null) {
                        strategy.setStatus(existing.getStatus());
                    }
                    strategy.setCreateTime(existing.getCreateTime());
                    strategyRuleService.prepare(strategy, existing);
                    SyncResult result = configService.saveStrategy(strategy);
                    return ApiResponse.success(result);
                })
                .orElseGet(() -> ApiResponse.<SyncResult>error("Strategy not found"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> invalidStrategy(IllegalArgumentException error) {
        return ApiResponse.error(error.getMessage());
    }

    @Autowired
    private com.metric.alarm.service.RawStrategyService rawStrategies;

    @DeleteMapping("/strategy/{id}")
    public ApiResponse<Void> deleteStrategy(@PathVariable Long id) {
        if (configService.findStrategyById(id).filter(s -> "raw".equals(s.getStrategyType())).isPresent()) rawStrategies.delete(id);
        else configService.deleteStrategyById(id);
        return ApiResponse.success();
    }
}
