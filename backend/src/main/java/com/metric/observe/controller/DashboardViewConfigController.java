package com.metric.observe.controller;

import com.metric.observe.entity.DashboardViewConfig;
import com.metric.observe.service.DashboardViewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard-view")
public class DashboardViewConfigController {

    @Autowired
    private DashboardViewService dashboardViewService;

    /**
     * 获取所有启用的视图列表
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllViews() {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", dashboardViewService.getEnabledViews()));
    }

    /**
     * 根据视图代码获取视图
     */
    @GetMapping("/{viewCode}")
    public ResponseEntity<Map<String, Object>> getViewByCode(@PathVariable String viewCode) {
        return dashboardViewService.getViewByCode(viewCode)
                .<ResponseEntity<Map<String, Object>>>map(v -> ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", v)))
                .orElseGet(() -> {
                    Map<String, Object> body = new java.util.HashMap<>();
                    body.put("code", 404);
                    body.put("message", "视图不存在");
                    body.put("data", null);
                    return ResponseEntity.ok(body);
                });
    }

    /**
     * 获取默认视图
     */
    @GetMapping("/default")
    public ResponseEntity<Map<String, Object>> getDefaultView() {
        return dashboardViewService.getDefaultView()
                .<ResponseEntity<Map<String, Object>>>map(v -> ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", v)))
                .orElseGet(() -> {
                    Map<String, Object> body = new java.util.HashMap<>();
                    body.put("code", 404);
                    body.put("message", "无默认视图");
                    body.put("data", null);
                    return ResponseEntity.ok(body);
                });
    }

    /**
     * 获取视图的布局配置
     */
    @GetMapping("/{viewCode}/layout")
    public ResponseEntity<Map<String, Object>> getViewLayout(@PathVariable String viewCode) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", dashboardViewService.getViewLayout(viewCode)));
    }

    /**
     * 获取视图的模块配置
     */
    @GetMapping("/{viewCode}/modules")
    public ResponseEntity<Map<String, Object>> getViewModules(@PathVariable String viewCode) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", dashboardViewService.getViewModules(viewCode)));
    }

    /**
     * 保存视图配置
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveView(@RequestBody DashboardViewConfig config) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", dashboardViewService.saveView(config)));
    }

    /**
     * 更新视图配置
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateView(@PathVariable Long id, @RequestBody DashboardViewConfig config) {
        config.setId(id);
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", dashboardViewService.saveView(config)));
    }

    /**
     * 删除视图配置
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteView(@PathVariable Long id) {
        dashboardViewService.deleteView(id);
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", true));
    }

    /**
     * 切换视图状态
     */
    @PostMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggleStatus(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", dashboardViewService.toggleViewStatus(id)));
    }

    /**
     * 设置默认视图
     */
    @PostMapping("/{id}/set-default")
    public ResponseEntity<Map<String, Object>> setDefaultView(@PathVariable Long id) {
        dashboardViewService.setDefaultView(id);
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", true));
    }

    /**
     * 更新布局配置
     */
    @PutMapping("/{id}/layout")
    public ResponseEntity<Map<String, Object>> updateLayout(
            @PathVariable Long id,
            @RequestBody String layoutConfig) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", dashboardViewService.updateLayoutConfig(id, layoutConfig)));
    }

    /**
     * 更新模块配置
     */
    @PutMapping("/{id}/modules")
    public ResponseEntity<Map<String, Object>> updateModules(
            @PathVariable Long id,
            @RequestBody String moduleConfig) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", dashboardViewService.updateModuleConfig(id, moduleConfig)));
    }
}
