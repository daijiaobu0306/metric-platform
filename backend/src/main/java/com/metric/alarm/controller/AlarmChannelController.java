package com.metric.alarm.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.alarm.entity.AlarmChannelConfig;
import com.metric.alarm.repository.AlarmRepository;
import com.metric.alarm.service.AlarmChannelService;
import com.metric.shared.util.PathResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarm-channel")
@CrossOrigin
public class AlarmChannelController {

    @Autowired
    private AlarmChannelService alarmChannelService;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private PathResolver pathResolver;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> getAllChannels() {
        List<AlarmChannelConfig> configs = alarmChannelService.findAll();
        List<Map<String, Object>> result = configs.stream().map(this::toMap).toList();
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getChannel(@PathVariable Long id) {
        return alarmChannelService.findById(id)
                .map(config -> ApiResponse.success(toMap(config)))
                .orElse(ApiResponse.error("Channel not found"));
    }

    private Map<String, Object> toMap(AlarmChannelConfig config) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", config.getId());
        map.put("channelType", config.getChannelType());
        map.put("channelName", config.getChannelName());
        map.put("configKey", config.getConfigKey());
        map.put("configValue", config.getConfigValue());
        map.put("fullPath", "FILE".equals(config.getChannelType()) ? pathResolver.getDisplayPath(config.getConfigValue()) : null);
        map.put("template", config.getTemplate());
        map.put("scene", config.getScene());
        map.put("description", config.getDescription());
        map.put("enabled", config.getEnabled());
        map.put("createTime", config.getCreateTime());
        map.put("updateTime", config.getUpdateTime());
        return map;
    }

    @PostMapping
    public ApiResponse<AlarmChannelConfig> createChannel(@RequestBody AlarmChannelConfig config) {
        return ApiResponse.success(alarmChannelService.save(config));
    }

    @PutMapping("/{id}")
    public ApiResponse<AlarmChannelConfig> updateChannel(@PathVariable Long id, @RequestBody AlarmChannelConfig config) {
        AlarmChannelConfig existing = alarmChannelService.findById(id)
                .orElseThrow(() -> new RuntimeException("Channel not found"));
        existing.setChannelType(config.getChannelType());
        existing.setChannelName(config.getChannelName());
        existing.setConfigKey(config.getConfigKey());
        existing.setConfigValue(config.getConfigValue());
        existing.setTemplate(config.getTemplate());
        existing.setScene(config.getScene());
        existing.setDescription(config.getDescription());
        existing.setEnabled(config.getEnabled());
        return ApiResponse.success(alarmChannelService.save(existing));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteChannel(@PathVariable Long id) {
        alarmChannelService.delete(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/toggle")
    public ApiResponse<AlarmChannelConfig> toggleChannel(@PathVariable Long id, @RequestParam Integer enabled) {
        return ApiResponse.success(alarmChannelService.toggle(id, enabled));
    }

    @GetMapping("/statistics")
    public ApiResponse<Map<String, Object>> getStatistics() {
        Map<String, Object> stats = new HashMap<>();

        long totalCount = alarmRepository.count();
        stats.put("totalCount", totalCount);

        LocalDateTime todayStart = LocalDateTime.now().with(LocalTime.MIN);
        long todayCount = alarmRepository.countByAlarmTimeAfter(todayStart);
        stats.put("todayCount", todayCount);

        LocalDateTime weekStart = LocalDateTime.now().minusDays(7).with(LocalTime.MIN);
        long weekCount = alarmRepository.countByAlarmTimeAfter(weekStart);
        stats.put("weekCount", weekCount);

        long unprocessedCount = alarmRepository.countByProcessStatus(0);
        stats.put("unprocessedCount", unprocessedCount);

        long processedCount = alarmRepository.countByProcessStatus(1);
        stats.put("processedCount", processedCount);

        return ApiResponse.success(stats);
    }
}
