package com.metric.config.service;

import com.metric.shared.util.BasePathProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/**
 * system_config 表驱动的文件根目录提供器实现
 *
 * 优先级：system_config['upload.base_path'] > application.yml 默认值。
 * 默认值走 ${UPLOAD_BASE_PATH} → ${SCRIPT_STORAGE_PATH} → /app/files 三重兜底。
 *
 * 热更新：保存 upload.base_path 后由 ConfigService 调用 {@link #invalidate()}，
 *        下个 5 秒缓存窗口内生效。
 *
 * 静态方法 {@link #validate(String)} 用于保存前的合法性校验。
 */
@Component
public class SystemConfigBasePathProvider implements BasePathProvider {

    /** system_config 表里的配置 key。 */
    public static final String CONFIG_KEY = "upload.base_path";

    private static final long CACHE_TTL_MS = 5_000L;

    @Autowired
    private ConfigService configService;

    /**
     * application.yml 默认值兜底；保留与历史一致的 UPLOAD_BASE_PATH/SCRIPT_STORAGE_PATH 链。
     */
    @Value("${upload.base-path:${UPLOAD_BASE_PATH:${SCRIPT_STORAGE_PATH:/app/files}}}")
    private String defaultBasePath;

    /** 缓存 (绝对路径, 加载时间)。null 表示下次需要重新计算。 */
    private final AtomicReference<CachedPath> cache = new AtomicReference<>();

    @Override
    public String getBasePath() {
        CachedPath hit = cache.get();
        long now = System.currentTimeMillis();
        if (hit != null && (now - hit.loadedAt) < CACHE_TTL_MS) {
            return hit.value;
        }
        String computed = computeBasePath();
        cache.set(new CachedPath(computed, now));
        return computed;
    }

    @Override
    public void invalidate() {
        cache.set(null);
    }

    private String computeBasePath() {
        String dbValue = configService.getConfigValue(CONFIG_KEY, null);
        String raw;
        if (dbValue != null && !dbValue.trim().isEmpty()) {
            raw = dbValue.trim();
        } else {
            raw = defaultBasePath;
        }
        try {
            return Paths.get(raw).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            // 兜底：如果解析失败（不该发生），返回 yml 默认值原样
            return raw;
        }
    }

    /**
     * 校验用户提交的路径是否可用：必须存在或可创建、是目录、可写，且不含 '..'。
     *
     * @param path 候选路径（可为 null/空）
     * @return null 表示合法；非 null 为错误描述
     */
    public static String validate(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "路径不能为空";
        }
        String trimmed = path.trim();
        if (trimmed.contains("..")) {
            return "路径不允许包含 '..'（防止跳出根目录）";
        }
        Path p;
        try {
            p = Paths.get(trimmed).toAbsolutePath().normalize();
        } catch (Exception e) {
            return "路径格式不合法: " + e.getMessage();
        }
        if (!Files.exists(p)) {
            try {
                Files.createDirectories(p);
            } catch (Exception e) {
                return "目录不存在且无法创建: " + p + " (" + e.getMessage() + ")";
            }
        }
        if (!Files.isDirectory(p)) {
            return "路径不是目录: " + p;
        }
        if (!Files.isWritable(p)) {
            return "目录不可写: " + p;
        }
        return null;
    }

    private record CachedPath(String value, long loadedAt) {}
}
