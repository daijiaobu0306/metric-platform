package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.*;

/** Reserve inboxes and archives across both collection systems, including disabled tasks. */
@Service
public class CollectionDirectoryGuard {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BasePathProvider paths;
    @org.springframework.beans.factory.annotation.Autowired
    private CollectionStorageRoots storageRoots;

    public CollectionDirectoryGuard(JdbcTemplate jdbc, ObjectMapper mapper, BasePathProvider paths) {
        this.jdbc = jdbc; this.mapper = mapper; this.paths = paths;
    }

    public void checkSource(CollectionSource source) {
        String root=storageRoots==null?paths.getBasePath():storageRoots.forSource(source.code());
        check(source.importPath(), source.archivePath(), source.code(), null, root,paths.getBasePath());
    }

    public void checkLegacy(String code, String inbox, String archive) {
        check(inbox, archive, null, code, paths.getBasePath(),paths.getBasePath());
    }

    public void checkAtRoot(String root, String inbox, String archive, String sourceCode, String legacyCode) {
        String targetRoot=storageRoots!=null && storageRoots.isExternal(sourceCode)?storageRoots.forSource(sourceCode):root;
        check(inbox, archive, sourceCode, legacyCode, targetRoot,root);
    }

    private void check(String inbox, String archive, String sourceCode, String legacyCode, String root,String normalRoot) {
        Path input = canonical(inbox, root), output = canonical(archive, root);
        if (overlap(input, output)) throw new IllegalArgumentException("投递与归档目录不可重叠");
        for(String reserved:new String[]{"collection-scripts","collection-worker"}) {
            Path scripts=canonical(reserved,root);
            if(overlap(input,scripts) || overlap(output,scripts))
                throw new IllegalArgumentException("采集目录不得与插件发布或Worker状态目录重叠");
        }
        // 旧通道只校验「启用中」的记录。
        // 背景：metric_import_config 里 16 条旧通道全部 enabled=0，Java 解析器已下线，
        // 不会再移动文件或写指标；但它们仍占用着 raw_data/import/<小类>/ 这类目录，
        // 会让「按任务 code 扁平命名」的脚本任务被判为重叠而无法启动。
        // 只把启用的通道计入冲突校验：一旦有人重新启用，重叠仍会被拦下。
        for (var row : jdbc.queryForList(
                "SELECT sub_category_code,import_path,archive_path FROM metric_import_config WHERE enabled=1")) {
            String code = row.get("sub_category_code").toString();
            if (!code.equals(legacyCode)) {
                checkPair(input, output, canonical((String) row.get("import_path"), normalRoot),
                    canonical((String) row.get("archive_path"), normalRoot), "旧采集通道 " + code);
            }
        }
        for (var row : jdbc.queryForList("SELECT code,config_json FROM collection_source")) {
            String code = row.get("code").toString();
            if (code.equals(sourceCode)) continue;
            try {
                var other = mapper.readValue(row.get("config_json").toString(), CollectionSource.class);
                String otherRoot=storageRoots!=null && storageRoots.isExternal(code)?storageRoots.forSource(code):normalRoot;
                checkPair(input, output, canonical(other.importPath(), otherRoot), canonical(other.archivePath(), otherRoot), "采集任务 " + code);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("采集任务配置损坏：" + code, e);
            }
        }
    }

    private void checkPair(Path input, Path output, Path otherInput, Path otherOutput, String owner) {
        if (overlap(input, otherInput) || overlap(input, otherOutput) ||
            overlap(output, otherInput) || overlap(output, otherOutput))
            throw new IllegalArgumentException("目录与" + owner + "重叠，请使用独立的投递和归档目录");
    }

    private Path canonical(String value, String root) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("投递和归档目录不能为空");
        Path path = Path.of(value);
        if (!path.isAbsolute()) path = Path.of(root).resolve(path);
        path = path.toAbsolutePath().normalize();
        Path ancestor = path;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor == null) throw new IllegalArgumentException("目录所在文件系统不可用：" + value);
        try { return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize(); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("目录不可访问：" + value, e); }
    }

    private static boolean overlap(Path a, Path b) { return a.startsWith(b) || b.startsWith(a); }
}
