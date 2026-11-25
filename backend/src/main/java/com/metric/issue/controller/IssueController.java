package com.metric.issue.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.issue.entity.Issue;
import com.metric.issue.service.IssueAlertScheduler;
import com.metric.issue.service.IssueImportService;
import com.metric.issue.service.IssueService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/issue")
public class IssueController {

    @Autowired
    private com.metric.shared.util.BasePathProvider basePathProvider;

    private static final String UPLOAD_SUBDIR = "issue_attachments";

    @Autowired
    private IssueService issueService;

    @Autowired
    private IssueImportService issueImportService;

    @Autowired
    private IssueAlertScheduler issueAlertScheduler;

    @GetMapping
    public ApiResponse<Page<Issue>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String resolveStatus,
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long metricId,
            @RequestParam(required = false) String issueType,
            @RequestParam(required = false) String issueSource,
            @RequestParam(required = false) String issueAttribute,
            @RequestParam(required = false) String discoverer,
            @RequestParam(required = false) String responsiblePerson,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String registerTimeStart,
            @RequestParam(required = false) String registerTimeEnd,
            @RequestParam(required = false) Boolean hasAttachment,
            @RequestParam(required = false) Boolean hasCheckReason,
            @RequestParam(required = false) Integer tagStatus,
            @RequestParam(defaultValue = "registerTime") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        Sort sort = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        return ApiResponse.success(issueService.search(
                resolveStatus, systemId, categoryId, metricId, issueType, issueSource, issueAttribute, discoverer, responsiblePerson, keyword, registerTimeStart, registerTimeEnd, hasAttachment, hasCheckReason, tagStatus, pageable));
    }

    @GetMapping("/{id:\\d+}")
    public ApiResponse<Issue> findById(@PathVariable Long id) {
        return issueService.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Issue not found"));
    }

    /**
     * 系统名输入建议：返回历史上登记过的 distinct system_name 列表（按字母升序）
     * 用于问题登记弹窗的 el-autocomplete 输入建议，与指标系统表解耦
     */
    @GetMapping("/system-suggestions")
    public ApiResponse<List<String>> systemSuggestions(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(issueService.findDistinctSystemNames(keyword));
    }

    /**
     * 指标大类输入建议：返回历史上登记过的 distinct category_name 列表（按字母升序）
     * 用于问题登记弹窗的 el-autocomplete 输入建议，与指标大类表解耦
     */
    @GetMapping("/category-suggestions")
    public ApiResponse<List<String>> categorySuggestions(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(issueService.findDistinctCategoryNames(keyword));
    }

    /**
     * 指标小类输入建议：返回历史上登记过的 distinct sub_category_name 列表（按字母升序）
     * 用于问题登记弹窗的 el-autocomplete 输入建议，与指标小类表解耦
     */
    @GetMapping("/sub-category-suggestions")
    public ApiResponse<List<String>> subCategorySuggestions(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(issueService.findDistinctSubCategoryNames(keyword));
    }

    @PostMapping
    public ApiResponse<Issue> create(@RequestBody Issue issue) {
        return ApiResponse.success(issueService.save(issue));
    }

    @PutMapping("/{id:\\d+}")
    public ApiResponse<Issue> update(@PathVariable Long id, @RequestBody Issue issue) {
        return issueService.findById(id)
                .map(existing -> {
                    issue.setId(id);
                    return ApiResponse.success(issueService.save(issue));
                })
                .orElse(ApiResponse.error("Issue not found"));
    }

    @DeleteMapping("/{id:\\d+}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        issueService.deleteById(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/resolve")
    public ApiResponse<Issue> resolve(@PathVariable Long id, @RequestParam String resolveStatus) {
        Issue issue = issueService.updateResolveStatus(id, resolveStatus);
        return issue != null ? ApiResponse.success(issue) : ApiResponse.error("Issue not found");
    }

    @GetMapping("/statistics")
    public ApiResponse<Map<String, Object>> getStatistics() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.with(LocalTime.MIN);
        LocalDateTime weekStart = now.minusDays(7).with(LocalTime.MIN);

        return ApiResponse.success(Map.of(
                "totalIssueCount", issueService.countByResolveStatus("未解决") + issueService.countByResolveStatus("已解决"),
                "todayCount", issueService.countByRegisterTimeAfter(todayStart),
                "weekCount", issueService.countByRegisterTimeAfter(weekStart),
                "unresolvedCount", issueService.countByResolveStatus("未解决"),
                "resolvedCount", issueService.countByResolveStatus("已解决")
        ));
    }

    @GetMapping("/trend")
    public ApiResponse<List<Map<String, Object>>> getTrend(
            @RequestParam(defaultValue = "today") String dimension,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ApiResponse.success(issueService.getTrend(dimension, type, startDate, endDate));
    }

    /**
     * 按发现者聚合的统计看板：返回每个发现者的问题数、核查原因非空数、附件数，按问题数降序，返回 Top N
     */
    @GetMapping("/discoverer-stats")
    public ApiResponse<List<Map<String, Object>>> getDiscovererStats(
            @RequestParam(required = false) String registerTimeStart,
            @RequestParam(required = false) String registerTimeEnd,
            @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.success(issueService.getDiscovererStats(registerTimeStart, registerTimeEnd, limit));
    }

    @PostMapping("/generate-overdue-alerts")
    public ApiResponse<Integer> generateOverdueAlerts() {
        int count = issueAlertScheduler.generateOverdueAlerts();
        return ApiResponse.success(count);
    }

    // 附件上传目录（用 basePathProvider 而不是 user.dir，避免 CWD 依赖）

    @PostMapping("/{id}/attachment")
    public ApiResponse<Issue> uploadAttachment(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        Issue issue = issueService.findById(id).orElse(null);
        if (issue == null) {
            return ApiResponse.error("Issue not found");
        }
        if (file.isEmpty()) {
            return ApiResponse.error("文件不能为空");
        }
        try {
            // 用配置的 basePathProvider 而不是 System.getProperty("user.dir")
            File dir = new File(basePathProvider.getBasePath(), UPLOAD_SUBDIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 生成唯一文件名（保留原文件名 + UUID防止冲突）
            String originalFilename = file.getOriginalFilename();
            String safeFilename = originalFilename;
            if (originalFilename != null && originalFilename.contains(".")) {
                String nameWithoutExt = originalFilename.substring(0, originalFilename.lastIndexOf("."));
                String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
                // 清理文件名中的特殊字符，保留中文、英文、数字、下划线、短横线
                safeFilename = nameWithoutExt.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9_-]", "_") + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;
            }

            // 保存文件
            Path filePath = dir.toPath().resolve(safeFilename);
            Files.write(filePath, file.getBytes());

            // 更新问题记录（DB 存相对 basePathProvider 的路径）
            issue.setAttachmentPath(UPLOAD_SUBDIR + "/" + safeFilename);
            issue.setAttachmentName(originalFilename);
            Issue saved = issueService.save(issue);

            return ApiResponse.success(saved);
        } catch (IOException e) {
            e.printStackTrace();
            return ApiResponse.error("文件上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/attachment")
    public void downloadAttachment(@PathVariable Long id,
                                   @RequestParam(value = "inline", required = false, defaultValue = "false") boolean inline,
                                   HttpServletResponse response) {
        issueService.findById(id).ifPresent(issue -> {
            if (issue.getAttachmentPath() != null && !issue.getAttachmentPath().isEmpty()) {
                try {
                    // DB 存的是相对 basePathProvider 的路径（如 "issue_attachments/xxx.pdf"）
                    // 兼容历史数据：早期版本 attachmentPath 可能含 "files/" 前缀，这里去掉避免路径重复
                    String relPath = issue.getAttachmentPath();
                    if (relPath.startsWith("files/")) {
                        relPath = relPath.substring("files/".length());
                    }
                    Path filePath = new File(basePathProvider.getBasePath(), relPath).toPath();
                    if (Files.exists(filePath)) {
                        String contentType = Files.probeContentType(filePath);
                        if (contentType == null) {
                            // probeContentType 对部分类型返回 null，按扩展名兜底
                            contentType = guessContentTypeByName(issue.getAttachmentName());
                        }
                        if (contentType != null) {
                            response.setContentType(contentType);
                        }
                        response.setContentLengthLong(Files.size(filePath));

                        // 图片/PDF/文本类附件用 inline，浏览器可内联预览；
                        // Office（docx/xlsx/pptx）等浏览器原生不预览的格式保持 attachment 直接下载
                        boolean canPreview = contentType != null && (
                                contentType.startsWith("image/")
                                || contentType.equals("application/pdf")
                                || contentType.startsWith("text/"));
                        // URL encode 文件名，避免中文/空格导致 Header 解析失败
                        String encodedName = encodeFilename(issue.getAttachmentName());
                        String disposition = (inline && canPreview) ? "inline" : "attachment";
                        response.setHeader("Content-Disposition", disposition + "; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);
                        Files.copy(filePath, response.getOutputStream());
                        response.flushBuffer();
                    } else {
                        // 文件不存在
                        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
            } else {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
        });
    }

    /**
     * 按文件名扩展名推断 Content-Type（probeContentType 在某些系统上可能为 null 时兜底）
     */
    private static String guessContentTypeByName(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        return null;
    }

    /**
     * 用 RFC 5987 方式编码文件名（解决中文/特殊字符在 Content-Disposition 头里乱码问题）
     */
    private static String encodeFilename(String name) {
        if (name == null) return "file";
        try {
            return java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return name;
        }
    }

    // 导出问题列表为Excel
    @GetMapping("/export")
    public void exportIssues(
            @RequestParam(required = false) String resolveStatus,
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long metricId,
            @RequestParam(required = false) String issueType,
            @RequestParam(required = false) String issueSource,
            @RequestParam(required = false) String issueAttribute,
            @RequestParam(required = false) String discoverer,
            @RequestParam(required = false) String responsiblePerson,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String registerTimeStart,
            @RequestParam(required = false) String registerTimeEnd,
            @RequestParam(required = false) Boolean hasAttachment,
            @RequestParam(required = false) Integer tagStatus,
            HttpServletResponse response) throws IOException {
        // 使用现有筛选条件查询数据
        Pageable pageable = PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "registerTime"));
        Page<Issue> page = issueService.search(resolveStatus, systemId, categoryId, metricId, issueType, issueSource, issueAttribute, discoverer, responsiblePerson, keyword, registerTimeStart, registerTimeEnd, hasAttachment, null, tagStatus, pageable);
        List<Issue> issues = page.getContent();

        byte[] data = issueImportService.exportIssues(issues);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=issues.xlsx");
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }

    // 下载问题导入模板
    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        byte[] data = issueImportService.generateTemplate();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=issue_import_template.xlsx");
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }

    // 导入问题Excel
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importIssues(@RequestParam("file") MultipartFile file,
                                                          @RequestParam(defaultValue = "true") boolean overwrite) {
        try {
            if (file.isEmpty()) {
                return ApiResponse.error("请选择要导入的文件");
            }
            String filename = file.getOriginalFilename();
            if (filename == null || !filename.endsWith(".xlsx")) {
                return ApiResponse.error("请上传xlsx格式的Excel文件");
            }
            Map<String, Object> result = issueImportService.importIssues(file, overwrite);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}/attachment")
    public ApiResponse<Issue> deleteAttachment(@PathVariable Long id) {
        return issueService.findById(id)
                .map(issue -> {
                    if (issue.getAttachmentPath() != null && !issue.getAttachmentPath().isEmpty()) {
                        try {
                            Path filePath = new File(basePathProvider.getBasePath(), issue.getAttachmentPath()).toPath();
                            Files.deleteIfExists(filePath);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        issue.setAttachmentPath(null);
                        issue.setAttachmentName(null);
                        Issue saved = issueService.save(issue);
                        return ApiResponse.success(saved);
                    }
                    return ApiResponse.success(issue);
                })
                .orElse(ApiResponse.error("Issue not found"));
    }
}
