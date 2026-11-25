package com.metric.config.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.config.entity.PlatformConfig;
import com.metric.config.service.PlatformConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/platform")
@CrossOrigin
public class PlatformConfigController {

    @Autowired
    private PlatformConfigService platformConfigService;

    @GetMapping("/config")
    public ApiResponse<Map<String, String>> getAllConfigs() {
        return ApiResponse.success(platformConfigService.getAllConfigs());
    }

    @GetMapping("/config/{key}")
    public ApiResponse<PlatformConfig> getConfig(@PathVariable String key) {
        return platformConfigService.getConfig(key)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Config not found"));
    }

    @PostMapping("/config")
    public ApiResponse<PlatformConfig> saveConfig(@RequestBody PlatformConfig config) {
        return ApiResponse.success(platformConfigService.saveConfig(config));
    }

    @PostMapping("/logo/upload")
    public ApiResponse<String> uploadLogo(@RequestParam("file") MultipartFile file) {
        try {
            // 验证文件
            if (file.isEmpty()) {
                return ApiResponse.error("请选择文件");
            }

            String contentType = file.getContentType();
            if (contentType == null || (!contentType.startsWith("image/png") && !contentType.startsWith("image/jpeg"))) {
                return ApiResponse.error("只支持 PNG、JPG 格式");
            }

            long maxSize = 2 * 1024 * 1024; // 2MB
            if (file.getSize() > maxSize) {
                return ApiResponse.error("文件大小不能超过 2MB");
            }

            String logoPath = platformConfigService.uploadLogo(file);
            return ApiResponse.success(logoPath);
        } catch (IOException e) {
            return ApiResponse.error("上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/logo/history")
    public ApiResponse<List<String>> getLogoHistory() {
        return ApiResponse.success(platformConfigService.getLogoHistory());
    }

    @DeleteMapping("/logo")
    public ApiResponse<Void> deleteLogo(@RequestParam String logoPath) {
        platformConfigService.deleteLogo(logoPath);
        return ApiResponse.success();
    }
}
