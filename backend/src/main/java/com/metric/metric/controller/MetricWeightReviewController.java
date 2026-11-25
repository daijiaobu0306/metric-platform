package com.metric.metric.controller;

import com.metric.metric.service.MetricWeightReviewService;
import com.metric.metric.service.MetricWeightReviewService.ReviewRequest;
import com.metric.security.entity.SysUser;
import com.metric.security.service.CurrentUserProvider;
import com.metric.shared.dto.ApiResponse;
import com.metric.skill.service.SkillPackageWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/weight/recommendations")
@RequiredArgsConstructor
public class MetricWeightReviewController {
    private final MetricWeightReviewService service;
    private final CurrentUserProvider users;

    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) String type,
                               @RequestParam(required = false) Long category,
                               @RequestParam(required = false) String state,
                               @RequestParam(required = false) Long before) {
        requireReviewer();
        return ApiResponse.success(service.list(type, category, state, before));
    }

    @GetMapping("/skills")
    public ResponseEntity<byte[]> downloadSkills() throws IOException {
        requireReviewer();
        // 技能清单与打包规则统一在 com.metric.skill，技能管理的「技能仓库」用同一份来源，
        // 避免这里和仓库页各写一套清单后出现「两边技能不一样」。
        var bytes = SkillPackageWriter.zipAll();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("指标平台权重技能.zip", StandardCharsets.UTF_8).build().toString())
                .body(bytes);
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable long id) {
        requireReviewer();
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping("/{id}/preview")
    public ApiResponse<?> preview(@PathVariable long id, @RequestBody ReviewRequest request) {
        requireReviewer();
        return ApiResponse.success(service.preview(id, request));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<?> approve(@PathVariable long id, @RequestBody ReviewRequest request) {
        return ApiResponse.success(service.approve(id, request, requireReviewer()));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<?> reject(@PathVariable long id, @RequestBody ReviewRequest request) {
        service.reject(id, request, requireReviewer());
        return ApiResponse.success();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> reviewError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(ApiResponse.error(error.getStatusCode().value(), error.getReason()));
    }

    private SysUser requireReviewer() {
        return users.currentUser().filter(user -> Integer.valueOf(1).equals(user.getIsAdmin()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可审核 AI 权重建议"));
    }
}
