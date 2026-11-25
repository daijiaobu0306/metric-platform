package com.metric.skill.controller;

import com.metric.security.entity.SysUser;
import com.metric.security.service.CurrentUserProvider;
import com.metric.shared.dto.ApiResponse;
import com.metric.skill.dto.SkillSummary;
import com.metric.skill.service.SkillRepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 技能仓库：技能管理页面的「技能仓库」页签。
 *
 * <p>只负责技能包的展示、下载与上传，不执行技能——技能由内网的 StarAgent 等智能体
 * 读取压缩包后自行运行，平台这边只保证「包是自包含的、谁都能下载到同一份」。</p>
 */
@RestController
@RequestMapping("/api/skill-repository")
@RequiredArgsConstructor
public class SkillRepositoryController {

    private final SkillRepositoryService service;
    private final CurrentUserProvider users;

    @GetMapping
    public ApiResponse<List<SkillSummary>> list() {
        boolean admin = isAdmin();
        return ApiResponse.success(service.list(users.operatorName(), admin));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        try {
            var download = service.download(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(download.fileName(), StandardCharsets.UTF_8)
                            .build().toString())
                    .body(download.content());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取技能包失败：" + e.getMessage());
        }
    }

    @PostMapping("/upload")
    public ApiResponse<SkillSummary> upload(@RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.success(service.upload(file, users.operatorName()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        } catch (IOException e) {
            return ApiResponse.error("保存技能包失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        try {
            service.delete(id, users.operatorName(), isAdmin());
            return ApiResponse.success();
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        } catch (IOException e) {
            return ApiResponse.error("删除技能包失败：" + e.getMessage());
        }
    }

    private boolean isAdmin() {
        return users.currentUser()
                .map(SysUser::getIsAdmin)
                .map(Integer.valueOf(1)::equals)
                .orElse(false);
    }
}
