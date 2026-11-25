package com.metric.collect.controller;

import com.metric.collect.entity.Prompt;
import com.metric.collect.service.PromptService;
import com.metric.shared.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 提示词管理：保存常用提示词的纯文本，方便复制复用。
 *
 * <p>接口只暴露名称、描述、正文三项，不再有「关联文件 / 状态 / 类型」，
 * 对应页面也已去掉这三项。</p>
 */
@RestController
@RequestMapping("/api/prompt")
@RequiredArgsConstructor
public class PromptController {

    private final PromptService promptService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Prompt> result = (name != null && !name.isBlank())
                ? promptService.searchByName(name.trim(), pageable)
                : promptService.findAll(pageable);

        Map<String, Object> response = new HashMap<>();
        response.put("content", result.getContent());
        response.put("totalElements", result.getTotalElements());
        response.put("totalPages", result.getTotalPages());
        response.put("number", result.getNumber());
        response.put("size", result.getSize());
        return ApiResponse.success(response);
    }

    @GetMapping("/{id}")
    public ApiResponse<Prompt> getById(@PathVariable Long id) {
        return promptService.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("提示词不存在"));
    }

    @PostMapping
    public ApiResponse<Prompt> create(@RequestBody Prompt prompt) {
        try {
            return ApiResponse.success(promptService.create(prompt));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<Prompt> update(@PathVariable Long id, @RequestBody Prompt prompt) {
        try {
            return ApiResponse.success(promptService.update(id, prompt));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            promptService.delete(id);
            return ApiResponse.success();
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
