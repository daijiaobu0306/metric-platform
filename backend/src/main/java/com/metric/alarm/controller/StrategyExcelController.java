package com.metric.alarm.controller;

import com.metric.alarm.service.StrategyExcelService;
import com.metric.security.service.CurrentUserProvider;
import com.metric.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/alarm/strategy-excel")
public class StrategyExcelController {
    @Autowired private StrategyExcelService excel;
    @Autowired private CurrentUserProvider currentUser;

    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        byte[] bytes = excel.exportWorkbook();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=alarm_strategy_template.xlsx");
        response.getOutputStream().write(bytes);
    }

    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty() || file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx"))
            return ApiResponse.error("请选择xlsx格式的策略模板");
        if (file.getSize() > 20 * 1024 * 1024) return ApiResponse.error("文件不能超过20MB");
        try { return ApiResponse.success(excel.preview(file.getBytes(), owner())); }
        catch (IOException | IllegalArgumentException e) { return ApiResponse.error("文件校验失败：" + e.getMessage()); }
    }

    @PostMapping("/execute")
    public ApiResponse<Map<String, Object>> execute(@RequestBody Map<String, String> body) {
        try { return ApiResponse.success(excel.execute(body.get("token"), owner())); }
        catch (IllegalArgumentException e) { return ApiResponse.error(e.getMessage()); }
    }

    // 操作人必须取用户名/显示名，不能用 Authentication.getName()：
    // principal 是 SysUser 实体，getName() 会回落成整段 toString()，写入审计表 operator(50) 直接超长报 500。
    private String owner() { return currentUser.operatorName(); }
}
