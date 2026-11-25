package com.metric.collect.service;

import com.metric.shared.util.BasePathProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Reports active legacy collectors and collection tasks without creating directories. */
@Service
public class ScanHealthService {
    private final CollectionDirectoryPreparationService directories;
    private final BasePathProvider paths;

    public ScanHealthService(CollectionDirectoryPreparationService directories, BasePathProvider paths) {
        this.directories = directories;
        this.paths = paths;
    }

    // 小类编码 → 人类可读名称
    private static final Map<String, String> CODE_NAME_MAP = Map.of(
        "error", "系统异常",
        "error_frontend", "前端异常",
        "performance", "系统性能",
        "inner", "内部人员",
        "jihe", "系统稽核",
        "jihe_detail", "稽核工单明细",
        "ability_metric", "能力指标"
    );

    public Map<String, Object> checkHealth() {
        String root = paths.getBasePath();
        var targets = directories.enabledTargets();
        List<Map<String, Object>> issues = new ArrayList<>();
        for (var target : targets) {
            Map<String, Object> inbox = check(target, "扫描", target.importPath(), root);
            Map<String, Object> archive = check(target, "归档", target.archivePath(), root);
            if (inbox != null) issues.add(inbox);
            if (archive != null) issues.add(archive);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("healthy", issues.isEmpty());
        result.put("totalChecked", targets.size());
        result.put("issueCount", issues.stream().map(i -> i.get("channelKey")).distinct().count());
        result.put("issues", issues);
        result.put("basePath", root);
        return result;
    }

    private Map<String, Object> check(CollectionDirectoryPreparationService.Target target, String kind, String value, String root) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("channelKey", (target.legacy() ? "legacy:" : "source:") + target.code());
        issue.put("directoryKind", kind);
        issue.put("subCategoryCode", target.code());
        issue.put("subCategoryName", target.legacy() ? CODE_NAME_MAP.getOrDefault(target.code(), target.code()) : target.name());
        issue.put("configImportPath", value);
        issue.put("exists", false);
        try {
            Path path = target.legacy() ? CollectionDirectoryPreparationService.resolve(root, value)
                    : CollectionDirectoryPreparationService.resolveSource(root, value);
            issue.put("resolvedPath", path.toString());
            if (Files.isDirectory(path) && Files.isReadable(path) && Files.isWritable(path)) return null;
            issue.put("exists", Files.exists(path));
            issue.put("errorType", Files.exists(path) ? "DIR_UNAVAILABLE" : "DIR_NOT_FOUND");
            issue.put("message", kind + (Files.exists(path) ? "目录不可用或无读写权限" : "目录不存在"));
        } catch (Exception e) {
            issue.put("resolvedPath", value == null ? "" : value);
            issue.put("errorType", "INVALID_PATH");
            issue.put("message", kind + "目录配置无效：" + e.getMessage());
        }
        issue.put("suggestion", "请检查目录及读写权限；可在「配置管理 → 采集配置」重新保存文件根目录，系统会准备已启用通道的扫描和归档目录。修改根目录不会迁移旧文件。");
        return issue;
    }
}
