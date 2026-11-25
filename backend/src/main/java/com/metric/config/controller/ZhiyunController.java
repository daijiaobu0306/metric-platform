package com.metric.config.controller;

import com.metric.config.service.ZhiyunService;
import com.metric.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/zhiyun")
public class ZhiyunController {
    private final ZhiyunService service;
    public ZhiyunController(ZhiyunService service) { this.service = service; }

    @GetMapping("/admin")
    public ApiResponse<?> management() { return ApiResponse.success(service.management()); }

    @PutMapping("/admin")
    public ApiResponse<?> save(@RequestBody ZhiyunService.Update update) { return ApiResponse.success(service.save(update)); }

    @PostMapping("/launch")
    public ApiResponse<?> launch(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(service.launch());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<?> invalid(IllegalArgumentException e) { return ApiResponse.error(e.getMessage()); }
}
