package com.metric.alarm.controller;

import com.metric.alarm.entity.AlarmSwitchConfig;
import com.metric.alarm.service.AlarmSwitchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarm-switch")
public class AlarmSwitchConfigController {

    @Autowired
    private AlarmSwitchService alarmSwitchService;

    /**
     * 获取所有开关配置
     */
    @GetMapping
    public ResponseEntity<List<AlarmSwitchConfig>> getAllConfigs() {
        return ResponseEntity.ok(alarmSwitchService.getAllConfigs());
    }

    /**
     * 获取配置Map
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getConfigStatus() {
        Map<String, Object> data = alarmSwitchService.getConfigMap();
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
    }

    /**
     * 切换告警开关
     */
    @PostMapping("/toggle/alarm")
    public ResponseEntity<Map<String, Object>> toggleAlarm() {
        boolean newStatus = alarmSwitchService.toggleAlarm();
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", newStatus));
    }

    /**
     * 切换工单开关
     */
    @PostMapping("/toggle/gongdan")
    public ResponseEntity<Map<String, Object>> toggleGongdan() {
        boolean newStatus = alarmSwitchService.toggleGongdan();
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", newStatus));
    }

    /**
     * 设置告警开关
     */
    @PostMapping("/alarm")
    public ResponseEntity<Void> setAlarmEnabled(@RequestParam boolean enabled) {
        alarmSwitchService.setAlarmEnabled(enabled);
        return ResponseEntity.ok().build();
    }

    /**
     * 设置工单开关
     */
    @PostMapping("/gongdan")
    public ResponseEntity<Void> setGongdanEnabled(@RequestParam boolean enabled) {
        alarmSwitchService.setGongdanEnabled(enabled);
        return ResponseEntity.ok().build();
    }

    /**
     * 更新配置
     */
    @PutMapping("/{id}")
    public ResponseEntity<AlarmSwitchConfig> updateConfig(
            @PathVariable Long id,
            @RequestParam(required = false) Integer configValue,
            @RequestParam(required = false) String description) {
        AlarmSwitchConfig updated = alarmSwitchService.updateConfig(id, configValue, description);
        if (updated != null) {
            return ResponseEntity.ok(updated);
        }
        return ResponseEntity.notFound().build();
    }
}
