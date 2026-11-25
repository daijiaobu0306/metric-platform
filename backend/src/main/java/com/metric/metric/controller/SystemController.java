package com.metric.metric.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.metric.entity.SystemInfo;
import com.metric.metric.service.SystemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    @Autowired
    private SystemService systemService;

    @GetMapping
    public ApiResponse<Page<SystemInfo>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return ApiResponse.success(systemService.findAll(pageable));
    }

    @GetMapping("/list")
    public ApiResponse<List<SystemInfo>> findAllList() {
        return ApiResponse.success(systemService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<SystemInfo> findById(@PathVariable Long id) {
        return systemService.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("System not found"));
    }

    @PostMapping
    public ApiResponse<SystemInfo> create(@RequestBody SystemInfo systemInfo) {
        return ApiResponse.success(systemService.save(systemInfo));
    }

    @PutMapping("/{id}")
    public ApiResponse<SystemInfo> update(@PathVariable Long id, @RequestBody SystemInfo systemInfo) {
        return systemService.findById(id)
                .map(existing -> {
                    systemInfo.setId(id);
                    return ApiResponse.success(systemService.save(systemInfo));
                })
                .orElse(ApiResponse.error("System not found"));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        systemService.deleteById(id);
        return ApiResponse.success();
    }
}
