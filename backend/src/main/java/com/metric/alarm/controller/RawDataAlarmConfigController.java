package com.metric.alarm.controller;

import com.metric.alarm.entity.RawDataAlarmConfig;
import com.metric.alarm.service.RawDataAlarmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/raw-data-alarm")
public class RawDataAlarmConfigController {

    @Autowired
    private RawDataAlarmService rawDataAlarmService;

    @Autowired
    private com.metric.alarm.service.RawStrategyService rawStrategies;

    @PostMapping("/match")
    public com.metric.shared.dto.ApiResponse<?> tryMatch(@RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return com.metric.shared.dto.ApiResponse.success(rawStrategies.tryMatch(body.path("definition"),body.path("sample").asText()));
    }

    @GetMapping("/sources")
    public com.metric.shared.dto.ApiResponse<?> sources() {
        return com.metric.shared.dto.ApiResponse.success(rawStrategies.catalog());
    }

    @GetMapping("/strategy/{id}")
    public com.metric.shared.dto.ApiResponse<?> definition(@PathVariable Long id) {
        return com.metric.shared.dto.ApiResponse.success(rawStrategies.definition(id));
    }

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @PostMapping("/strategy")
    public com.metric.shared.dto.ApiResponse<?> saveStrategy(@RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return com.metric.shared.dto.ApiResponse.success(rawStrategies.save(
            mapper.convertValue(body.path("strategy"), com.metric.alarm.entity.AlarmStrategy.class), body.path("definition")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidConfig(IllegalArgumentException error) {
        return ResponseEntity.ok(Map.of("code", 400, "message", error.getMessage()));
    }

    /**
     * 获取所有配置
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllConfigs() {
        List<RawDataAlarmConfig> data = rawDataAlarmService.getAllConfigs();
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
    }

    /**
     * 获取启用的配置
     */
    @GetMapping("/enabled")
    public ResponseEntity<Map<String, Object>> getEnabledConfigs() {
        List<RawDataAlarmConfig> data = rawDataAlarmService.getEnabledConfigs();
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
    }

    /**
     * 保存配置
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody RawDataAlarmConfig config) {
        RawDataAlarmConfig saved = rawDataAlarmService.saveConfig(config);
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", saved));
    }

    /**
     * 更新配置
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateConfig(@PathVariable Long id, @RequestBody RawDataAlarmConfig config) {
        config.setId(id);
        RawDataAlarmConfig saved = rawDataAlarmService.saveConfig(config);
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", saved));
    }

    /**
     * 删除配置
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteConfig(@PathVariable Long id) {
        rawDataAlarmService.deleteConfig(id);
        return ResponseEntity.ok(Map.of("code", 200, "message", "success"));
    }

    /**
     * 切换配置状态
     */
    @PostMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggleStatus(@PathVariable Long id) {
        boolean newStatus = rawDataAlarmService.toggleConfigStatus(id);
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", newStatus));
    }

    /**
     * 手动触发检查
     */
    @PostMapping("/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerCheck(@PathVariable Long id) {
        rawDataAlarmService.triggerCheck(id);
        return ResponseEntity.ok(Map.of("code", 200, "message", "success"));
    }
}
