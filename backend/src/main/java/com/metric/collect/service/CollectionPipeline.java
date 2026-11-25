package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Service
public class CollectionPipeline {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CollectionPipeline.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BasePathProvider paths;
    private final CollectionDirectoryGuard directories;
    @org.springframework.beans.factory.annotation.Autowired
    private CollectionWorkerService workers;
    @org.springframework.beans.factory.annotation.Autowired
    private CollectionScriptCatalog scripts;
    @org.springframework.beans.factory.annotation.Autowired
    private CollectionStorageRoots storageRoots;
    @org.springframework.beans.factory.annotation.Autowired
    private com.metric.security.service.CurrentUserProvider currentUser;
    public CollectionPipeline(JdbcTemplate jdbc, ObjectMapper mapper, BasePathProvider paths,
            CollectionDirectoryGuard directories) {
        this.jdbc = jdbc; this.mapper = mapper; this.paths = paths;
        this.directories = directories;
    }
    public List<Map<String, Object>> sources() {
        var rows = jdbc.queryForList("SELECT code,name,config_json AS configJson,enabled,next_scan_at AS nextScanAt,last_scan_at AS lastScanAt,last_error AS lastError FROM collection_source ORDER BY code");
        rows.forEach(row -> { try { row.put("config", mapper.readTree(row.remove("configJson").toString())); }
            catch (Exception e) { throw new IllegalStateException("采集配置JSON损坏", e); } });
        return rows;
    }
    private CollectionSource source(String code) {
        var rows = jdbc.queryForList("SELECT config_json FROM collection_source WHERE code=?", code);
        if (rows.isEmpty()) throw new IllegalArgumentException("采集任务不存在");
        try { return mapper.readValue(rows.get(0).get("config_json").toString(), CollectionSource.class); }
        catch (Exception e) { throw new IllegalArgumentException("采集配置无效", e); }
    }
    private Path directory(CollectionSource source,String value) {
        if (value == null || value.isBlank() || Path.of(value).isAbsolute()) throw new IllegalArgumentException("目录必须填写相对于文件根目录的路径");
        Path base = Path.of(storageRoots==null?paths.getBasePath():storageRoots.forSource(source.code())).toAbsolutePath().normalize();
        Path result = base.resolve(value).normalize();
        if (!result.startsWith(base) || result.equals(base)) throw new IllegalArgumentException("目录超出文件根目录");
        // Check existing ancestors too, so symlinks cannot escape the configured root.
        try {
            Path ancestor = result;
            while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
            if (!ancestor.toRealPath().startsWith(base.toRealPath())) throw new IllegalArgumentException("目录链接超出文件根目录");
        } catch (java.io.IOException e) { throw new IllegalArgumentException("文件根目录不可用", e); }
        return result;
    }
    @org.springframework.transaction.annotation.Transactional
    public synchronized void save(CollectionSource config) {
        validate(config);
        CollectionCategoryScope.requireSingleTask(jdbc,config);
        if (workers!=null) {
            jdbc.queryForList("SELECT code FROM collection_source WHERE code=? FOR UPDATE",config.code());
            workers.requireIdle(config.code());
        }
        var oldRows=jdbc.queryForList("SELECT code FROM collection_source WHERE code=?",config.code());
        String previousVersion=oldRows.isEmpty()?null:Optional.ofNullable(source(config.code()).worker()).map(CollectionSource.WorkerBinding::version).orElse(null);
        if("metric_g1".equals(config.worker().categoryCode()) && !Objects.equals(previousVersion,config.worker().version())
                && scripts.metadata(config.worker().version()).get("datasetCode").isBlank())
            throw new IllegalArgumentException("合规脚本未声明数据集，不能切换到该版本");
        // Keep the source identity immutable once it has imported data.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM collection_file WHERE source_code=?", Integer.class, config.code()) > 0) {
            if (jdbc.queryForList("SELECT code FROM collection_source WHERE code=?", config.code()).isEmpty())
                throw new IllegalArgumentException("该任务编码已有历史采集记录，请使用新的任务编码");
            var old = source(config.code());
            if (!old.archivePath().equals(config.archivePath()))
                throw new IllegalArgumentException("已有采集记录的任务不可更换归档目录，请新建任务");
        }
        if ("python-worker".equals(config.parser())) {
            if ("running_efficiency".equals(config.code()) && !"system_run".equals(config.worker().categoryCode()))
                throw new IllegalArgumentException("现有来源必须绑定原正式指标大类");
            var previous=jdbc.queryForList("SELECT config_json FROM collection_source WHERE code=?",config.code());
            if (!previous.isEmpty()) {
                var old=source(config.code());
                if(old.worker()!=null && old.worker().categoryCode()!=null && !old.worker().categoryCode().isBlank()
                        && !old.worker().categoryCode().equals(config.worker().categoryCode()))
                    throw new IllegalArgumentException("已绑定Worker任务不可更换指标大类，请新建任务");
            }
        }
        directories.checkSource(config);
        try {
            CollectionDirectoryPreparationService.ensureDirectory(directory(config,config.importPath()));
            CollectionDirectoryPreparationService.ensureDirectory(directory(config,config.archivePath()));
            jdbc.update("INSERT INTO collection_source(code,name,config_json,enabled,next_scan_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),config_json=VALUES(config_json),enabled=VALUES(enabled),next_scan_at=VALUES(next_scan_at)",
                config.code(), config.name(), mapper.writeValueAsString(config), config.enabled(), CollectionSchedule.initial(config,LocalDateTime.now(ZONE)));
            if (previousVersion != null && !previousVersion.equals(config.worker().version())) {
                log.info("采集任务切换脚本版本 task={} operator={} previous={} current={}",
                    config.code(), currentUser.operatorName(), previousVersion, config.worker().version());
            }
        } catch (Exception e) { throw new IllegalArgumentException("保存配置失败：" + e.getMessage(), e); }
    }
    /** Change only scheduling state; pausing must also work when a plugin is unavailable. */
    @org.springframework.transaction.annotation.Transactional
    public synchronized void setEnabled(String code,boolean enabled) {
        if(jdbc.queryForList("SELECT code FROM collection_source WHERE code=? FOR UPDATE",code).isEmpty())
            throw new NoSuchElementException("采集任务不存在");
        workers.requireIdle(code);
        if(!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT IS_FREE_LOCK(?)",Integer.class,"collect:"+code)))
            throw new IllegalStateException("任务正在执行，请本轮结束后再切换启停状态");
        var config=source(code).withEnabled(enabled);
        if(enabled) { validate(config); directories.checkSource(config); }
        try {
            jdbc.update("UPDATE collection_source SET enabled=?,config_json=?,next_scan_at=? WHERE code=?",
                enabled,mapper.writeValueAsString(config),enabled?CollectionSchedule.initial(config,LocalDateTime.now(ZONE)):LocalDateTime.now(ZONE),code);
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("任务配置序列化失败",e); }
    }
    /** Remove configuration only. Historical batches, raw rows, attempts and files remain intact. */
    @org.springframework.transaction.annotation.Transactional
    public synchronized void delete(String code) {
        if(workers!=null) {
            jdbc.queryForList("SELECT code FROM collection_source WHERE code=? FOR UPDATE",code);
            workers.requireIdle(code);
        }
        if (code == null || !code.matches("[a-z][a-z0-9_]{0,49}"))
            throw new IllegalArgumentException("任务编码无效");
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            String lock = "collect:" + code;
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
                statement.setString(1, lock);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1)
                        throw new IllegalStateException("任务正在采集中，请稍后删除");
                }
            }
            try {
                if (jdbc.update("DELETE FROM collection_source WHERE code=?", code) == 0)
                    throw new NoSuchElementException("采集任务不存在或已删除");
                return null;
            } finally {
                try (var release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, lock); release.execute();
                }
            }
        });
    }
    private static boolean overlap(Path a, Path b) { return a.startsWith(b) || b.startsWith(a); }
    private void validate(CollectionSource c) {
        if (c.parser() == null || c.format() == null || c.dateMode() == null) throw new IllegalArgumentException("请填写解析方式、格式和日期来源");
        if (!"python-worker".equals(c.parser())) throw new IllegalArgumentException("旧采集方式已下线，请使用Python脚本加配置");
        if (c.code() == null || !c.code().matches("[a-z][a-z0-9_]{0,49}") || c.name() == null || c.name().isBlank() || c.name().length() > 100)
            throw new IllegalArgumentException("任务编码须为小写英文/数字/下划线，名称不能为空且不超过100字");
        if ("business_value".equals(c.code()) || "mock_business_value".equals(c.code()))
            throw new IllegalArgumentException("业务价值由平台统计，已停用文件采集");
        CollectionSchedule.validate(c);
        int minimumSettle = c.usesIngestionTime() ? 0 : 5;
        if (c.settleSeconds() < minimumSettle || c.settleSeconds() > 3600)
            throw new IllegalArgumentException("文件稳定等待需为"+minimumSettle+"～3600秒");
        workers.validate(c);
        if (!Set.of("xlsx", "csv", "txt").contains(c.format()) || c.filePattern() == null || c.filePattern().length() > 100 || c.filePattern().contains("/") || c.filePattern().contains("\\"))
            throw new IllegalArgumentException("文件格式或匹配规则无效");
        FileSystems.getDefault().getPathMatcher("glob:" + c.filePattern());
        if (overlap(directory(c,c.importPath()), directory(c,c.archivePath()))) throw new IllegalArgumentException("投递与归档目录不可重叠");
        // 明细行协议（raw-rows）的脚本直接产出明细数据，不产生指标值，因此不做指标字段映射。
        if (!c.usesRawRows()) {
            if (c.fields() == null || c.fields().isEmpty() || c.fields().size() > 200) throw new IllegalArgumentException("请配置1～200个映射字段");
            Set<String> targets = new HashSet<>();
            for (var f : c.fields()) {
                if (f.source() == null || f.source().isBlank() || f.target() == null || f.type() == null || !f.target().matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || !targets.add(f.target()) || !Set.of("string", "decimal", "long", "date", "datetime").contains(f.type()))
                    throw new IllegalArgumentException("字段配置无效或目标字段重复");
            }
            if (!targets.containsAll(Set.of("metric_code","metric_value")))
                throw new IllegalArgumentException("请配置指标编码和指标值映射");
            if (c.usesIngestionTime() == targets.contains("data_time"))
                throw new IllegalArgumentException(c.usesIngestionTime() ? "按入库时间采集无需数据时间映射" : "请配置数据时间映射");
        }
        if (!c.format().equals("xlsx")) {
            if (c.delimiter() == null || c.delimiter().isEmpty() || c.delimiter().length() > 16 || c.delimiter().chars().anyMatch(ch -> ch == '\r' || ch == '\n' || ch == '"' || ch == 0))
                throw new IllegalArgumentException("分隔符须为1～16个字符，不能包含换行、引号或空字符");
            java.nio.charset.Charset.forName(c.encoding());
        }
    }
    public synchronized Map<String, Object> scan(String code) {
        if (!"python-worker".equals(source(code).parser())) throw new IllegalArgumentException("旧采集方式已下线，请重新配置脚本采集任务");
        workers.requestScan(code);
        return Map.of("queued",true,"importedFiles",0,"duplicateFiles",0,"pendingFiles",0,"errors",List.of());
    }
    public List<Map<String, Object>> batches(String code) {
        return jdbc.queryForList("SELECT id,source_file AS sourceFile,business_date AS businessDate,row_count AS rowCount,collected_at AS collectedAt,archived FROM collection_file WHERE source_code=? ORDER BY collected_at DESC LIMIT 100", code);
    }
    public List<Map<String,Object>> categoryOptions() { return CollectionCategoryScope.options(jdbc); }
    public List<Map<String,String>> scriptCatalog() { return scripts.catalog(); }
    public List<String> scriptVersions() { return scripts.versions(); }
    public List<Map<String,Object>> workerRuns(String code) { return workers.runs(code); }
    public List<Map<String, Object>> attempts(String code) {
        return jdbc.queryForList("SELECT source_file AS sourceFile,status,message,attempted_at AS attemptedAt FROM collection_attempt WHERE source_code=? ORDER BY id DESC LIMIT 100", code);
    }
    public List<Map<String, Object>> rows(String batch) {
        return jdbc.queryForList("SELECT data_row_number AS rowNumber,data_json AS dataJson FROM collection_row WHERE batch_id=? ORDER BY data_row_number LIMIT 200", batch);
    }
}
