package com.metric.collect.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.shared.dto.PluginExecutionResult;
import com.metric.collect.entity.PluginConfig;
import com.metric.collect.entity.PluginExecutionLog;
import com.metric.collect.service.PluginConfigImportService;
import com.metric.collect.service.PluginConfigService;
import com.metric.collect.service.PluginExecutorService;
import com.metric.collect.repository.PluginExecutionLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/plugin-config")
public class PluginConfigController {

    @Autowired
    private PluginConfigService pluginConfigService;

    @Autowired
    private PluginExecutorService pluginExecutorService;

    @Autowired
    private PluginExecutionLogRepository executionLogRepository;

    @Autowired
    private PluginConfigImportService pluginConfigImportService;

    @GetMapping
    public ApiResponse<Page<PluginConfig>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String pluginType,
            @RequestParam(required = false) Integer enabled) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return ApiResponse.success(pluginConfigService.search(name, pluginType, enabled, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<PluginConfig> findById(@PathVariable Long id) {
        return pluginConfigService.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Plugin not found"));
    }

    @PostMapping
    public ApiResponse<PluginConfig> create(@RequestBody PluginConfig pluginConfig) {
        return ApiResponse.success(pluginConfigService.save(pluginConfig));
    }

    @PutMapping("/{id}")
    public ApiResponse<PluginConfig> update(@PathVariable Long id, @RequestBody PluginConfig pluginConfig) {
        return pluginConfigService.findById(id)
                .map(existing -> {
                    pluginConfig.setId(id);
                    return ApiResponse.success(pluginConfigService.save(pluginConfig));
                })
                .orElse(ApiResponse.error("Plugin not found"));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        pluginConfigService.deleteById(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/toggle")
    public ApiResponse<PluginConfig> toggleEnabled(@PathVariable Long id) {
        return pluginConfigService.toggleEnabled(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Plugin not found"));
    }

    @PostMapping("/{id}/debug")
    public ApiResponse<PluginExecutionResult> debugExecute(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> testParams) {
        return ApiResponse.success(pluginExecutorService.debugExecute(id, testParams));
    }

    @GetMapping("/{id}/logs")
    public ApiResponse<Page<PluginExecutionLog>> getLogs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("execTime").descending());
        return ApiResponse.success(executionLogRepository.findByPluginId(id, pageable));
    }

    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        byte[] template = pluginConfigImportService.generateTemplate();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=plugin_config_template.xlsx");
        response.getOutputStream().write(template);
        response.getOutputStream().flush();
    }

    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ApiResponse.error("请选择要导入的文件");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.endsWith(".xlsx")) {
            return ApiResponse.error("请上传xlsx格式的Excel文件");
        }
        try {
            Map<String, Object> result = pluginConfigImportService.importExcel(file);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }
}
