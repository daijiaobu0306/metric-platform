package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.metric.service.LegacyImportSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Prepares directories before saving settings; health checks never create them. */
@Service
public class CollectionDirectoryPreparationService {
    @org.springframework.beans.factory.annotation.Autowired
    private CollectionStorageRoots storageRoots;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CollectionDirectoryGuard guard;

    public CollectionDirectoryPreparationService(JdbcTemplate jdbc, ObjectMapper mapper, CollectionDirectoryGuard guard) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.guard = guard;
    }

    public record Target(String code, String name, String importPath, String archivePath, boolean legacy) {}

    public List<Target> enabledTargets() {
        List<Target> targets = new ArrayList<>();
        for (var row : jdbc.queryForList("SELECT sub_category_code,import_path,archive_path FROM metric_import_config WHERE enabled=1")) {
            String code = row.get("sub_category_code").toString();
            if (LegacyImportSupport.available(code)) {
                targets.add(new Target(code, code, (String) row.get("import_path"), (String) row.get("archive_path"), true));
            }
        }
        for (var row : jdbc.queryForList("SELECT code,config_json FROM collection_source WHERE enabled=1")) {
            String code = row.get("code").toString();
            try {
                var source = mapper.readValue(row.get("config_json").toString(), CollectionSource.class);
                if (source.enabled()) targets.add(new Target(code, source.name(), source.importPath(), source.archivePath(), false));
            } catch (Exception e) {
                throw new IllegalArgumentException("采集任务配置损坏：" + code, e);
            }
        }
        return targets;
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> prepare(String root) {
        var targets = enabledTargets();
        // Validate all ownership before touching any child directories, using the candidate root.
        for (var target : targets) {
            String targetRoot=storageRoots!=null && !target.legacy() && storageRoots.isExternal(target.code())?storageRoots.forSource(target.code()):root;
            if (!target.legacy()) {
                resolveSource(targetRoot, target.importPath());
                resolveSource(targetRoot, target.archivePath());
            }
            guard.checkAtRoot(root, target.importPath(), target.archivePath(),
                    target.legacy() ? null : target.code(), target.legacy() ? target.code() : null);
        }
        var created = new LinkedHashSet<String>();
        var existing = new LinkedHashSet<String>();
        for (var target : targets) {
            String targetRoot=storageRoots!=null && !target.legacy() && storageRoots.isExternal(target.code())?storageRoots.forSource(target.code()):root;
            for (String value : new String[] {target.importPath(), target.archivePath()}) {
                Path path = resolve(targetRoot, value);
                boolean existed = Files.exists(path);
                ensureDirectory(path);
                if (existed && !created.contains(path.toString())) existing.add(path.toString());
                else if (!existed) created.add(path.toString());
            }
        }
        List<String> disabled = new ArrayList<>();
        for (var row : jdbc.queryForList("SELECT sub_category_code FROM metric_import_config WHERE enabled=1")) {
            String code = row.get("sub_category_code").toString();
            if (!LegacyImportSupport.available(code)) {
                jdbc.update("UPDATE metric_import_config SET enabled=0 WHERE sub_category_code=? AND enabled=1", code);
                disabled.add(code);
            }
        }
        return Map.of("disabledLegacy", List.copyOf(disabled), "created", List.copyOf(created), "existing", List.copyOf(existing), "channelCount", targets.size());
    }

    public static Path resolve(String root, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("扫描和归档目录不能为空");
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : Path.of(root).resolve(path)).toAbsolutePath().normalize();
    }

    public static Path resolveSource(String root, String value) {
        Path path = resolve(root, value);
        Path base = Path.of(root).toAbsolutePath().normalize();
        if (Path.of(value).isAbsolute() || !path.startsWith(base) || path.equals(base)) {
            throw new IllegalArgumentException("采集任务目录须位于文件根目录下：" + value);
        }
        try {
            Path ancestor = path;
            while (!Files.exists(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
            if (!ancestor.toRealPath().startsWith(base.toRealPath())) {
                throw new IllegalArgumentException("目录链接超出文件根目录：" + path);
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("目录不可访问：" + path + "；原因：" + e.getMessage(), e);
        }
        return path;
    }

    public static void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
            if (!Files.isDirectory(path) || !Files.isReadable(path) || !Files.isWritable(path)) {
                throw new IllegalArgumentException("目录不可读写");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("目录准备失败：" + path + "；原因：" + e.getMessage(), e);
        }
    }
}
