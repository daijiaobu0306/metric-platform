package com.metric.metric.service;

import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.entity.SystemInfo;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.MetricItemRepository;
import com.metric.metric.repository.SystemInfoRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ============================================================================
 * 指标导入：预演（preview）+ 执行（execute）两阶段
 * ============================================================================
 * 【为什么要两阶段】
 * 原实现 `import-full` 是"清空重建"：deleteAll 指标/大类/系统后重新插入。
 * 重建会让 metric_item.id 全部变成新值，而 17 张关联表（metric_realtime、
 * metric_value_history、alarm_trigger_history、alarm_strategy 等，约 17 万行）
 * 存的是旧 id → 历史断裂、策略失效。
 *
 * 现改为：
 *   1. 按 (大类编码 + 指标编码) **原地 UPSERT**，命中就保留原 id ——
 *      只要编码不变，策略和历史全部不受影响；
 *   2. 导入前先算出一份「变更计划」给你看（新增/更新/删除各多少、删哪些），
 *      确认后才落库。
 *
 * 【两种模式】
 *   - 增量导入 incremental：Excel 里有就新增/更新，Excel 里没有的一律不动。
 *   - 全量导入 full：以 Excel 为全集，库里存在但 Excel 没有的 → **真删除**
 *     （连同其历史数据），用户已确认要这个语义。
 *
 * 【删除边界】
 *   删除指标时清理"指标维度数据"（见 METRIC_DEPENDENT_TABLES），
 *   但 **alarm / gongdan / issue 三张表绝不动**——告警、工单、问题是业务留痕，
 *   删指标不该抹掉"某人某月处理过什么问题"。这三张表的 metric_id 允许 NULL，
 *   且都有 metric_name 等冗余列，界面仍可正常显示。
 *
 * 【安全护栏】
 *   1. Excel 缺少库里存在的整个大类 → 阻塞，必须显式勾选"允许删除整个大类"；
 *   2. 预演按大类分组列出删除清单；
 *   3. 删除量过大（>20 条 或 ≥总量的 30%）→ 必须输入确认词才能执行。
 * ============================================================================
 */
@Service
public class MetricImportPlanService {

    public static final String MODE_FULL = "full";
    public static final String MODE_INCREMENTAL = "incremental";

    /** 删除指标时需要一并清理的表（指标维度数据，指标没了就没有意义） */
    private static final String[] METRIC_DEPENDENT_TABLES = {
            "metric_value_history",
            "metric_health_history",
            "metric_realtime",
            "metric_monitor_config",
            "metric_score_baseline",
            "alarm_trigger_history",
            "health_snapshot_1h",
            "health_snapshot_6h",
            "health_snapshot_1d",
            "health_snapshot_7d",
            "health_snapshot_30d",
            "weight_config",
            "metric_error_keywords",
            "metric_data"
    };

    /** 绝不动用的表，仅用于代码里显式声明边界，避免后人误加 */
    private static final String[] BUSINESS_TABLES_KEPT = {"alarm", "gongdan", "issue"};

    /** 删除量达到此阈值要求输入确认词 */
    private static final int CONFIRM_WORD_MIN_DELETE = 20;
    private static final String CONFIRM_WORD = "确认删除";
    /** 计划有效期 */
    private static final long PLAN_TTL_MS = 10 * 60 * 1000L;

    @Autowired private SystemInfoRepository systemInfoRepository;
    @Autowired private MetricCategoryRepository categoryRepository;
    @Autowired private MetricItemRepository itemRepository;
    @Autowired private SystemService systemService;
    @Autowired private MetricService metricService;
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 岗位字典：导入时把「面向人员」原文归一成岗位编码。 */
    @Autowired private MetricAudienceService audienceService;

    private final Map<String, CachedPlan> planCache = new ConcurrentHashMap<>();

    // ========================================================================
    // 一、预演
    // ========================================================================

    public Map<String, Object> preview(MultipartFile file, String mode, boolean allowDeleteCategory) throws IOException {
        String normalizedMode = MODE_FULL.equalsIgnoreCase(mode) ? MODE_FULL : MODE_INCREMENTAL;
        Plan plan = buildPlan(file, normalizedMode, allowDeleteCategory);

        String token = UUID.randomUUID().toString();
        planCache.put(token, new CachedPlan(plan, System.currentTimeMillis() + PLAN_TTL_MS));
        cleanupExpired();

        Map<String, Object> result = describePlan(plan);
        result.put("token", token);
        result.put("expiresInSeconds", PLAN_TTL_MS / 1000);
        return result;
    }

    /** 把计划转成给前端展示的 Map */
    private Map<String, Object> describePlan(Plan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", plan.mode);

        long sysAdd = plan.systems.stream().filter(x -> x.id == null).count();
        long sysUpd = plan.systems.stream().filter(x -> x.id != null && !x.changedFields.isEmpty()).count();
        long sysSame = plan.systems.stream().filter(x -> x.id != null && x.changedFields.isEmpty()).count();
        long catAdd = plan.categories.stream().filter(x -> x.id == null).count();
        long catUpd = plan.categories.stream().filter(x -> x.id != null && !x.changedFields.isEmpty()).count();
        long catSame = plan.categories.stream().filter(x -> x.id != null && x.changedFields.isEmpty()).count();
        long mAdd = plan.metrics.stream().filter(x -> x.id == null).count();
        long mUpd = plan.metrics.stream().filter(x -> x.id != null && !x.changedFields.isEmpty()).count();
        long mSame = plan.metrics.stream().filter(x -> x.id != null && x.changedFields.isEmpty()).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("systemAdd", sysAdd);
        summary.put("systemUpdate", sysUpd);
        summary.put("systemUnchanged", sysSame);
        summary.put("systemDelete", plan.systemsToDelete.size());
        summary.put("categoryAdd", catAdd);
        summary.put("categoryUpdate", catUpd);
        summary.put("categoryUnchanged", catSame);
        summary.put("categoryDelete", plan.categoriesToDelete.size());
        summary.put("metricAdd", mAdd);
        summary.put("metricUpdate", mUpd);
        summary.put("metricUnchanged", mSame);
        summary.put("metricDelete", plan.metricsToDelete.size());
        summary.put("totalInDb", plan.totalMetricsInDb);
        summary.put("totalInExcel", plan.metrics.size());
        result.put("summary", summary);

        // 删除明细：按大类分组
        Map<Long, List<DeletedMetric>> byCat = new LinkedHashMap<>();
        for (DeletedMetric d : plan.metricsToDelete) {
            byCat.computeIfAbsent(d.categoryId, k -> new ArrayList<>()).add(d);
        }
        List<Map<String, Object>> grouped = new ArrayList<>();
        for (Map.Entry<Long, List<DeletedMetric>> e : byCat.entrySet()) {
            List<DeletedMetric> list = e.getValue();
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("categoryCode", list.get(0).categoryCode);
            g.put("categoryName", list.get(0).categoryName);
            g.put("count", list.size());
            List<Map<String, Object>> items = new ArrayList<>();
            for (DeletedMetric d : list) {
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("id", d.id);
                it.put("metricCode", d.metricCode);
                it.put("metricName", d.metricName);
                it.put("refRows", d.refRows);
                items.add(it);
            }
            g.put("items", items);
            grouped.add(g);
        }

        List<Map<String, Object>> catsDel = new ArrayList<>();
        for (MetricCategory c : plan.categoriesToDelete) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("categoryCode", c.getCategoryCode());
            it.put("categoryName", c.getCategoryName());
            catsDel.add(it);
        }
        List<Map<String, Object>> sysDel = new ArrayList<>();
        for (SystemInfo s : plan.systemsToDelete) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("systemCode", s.getSystemCode());
            it.put("systemName", s.getSystemName());
            sysDel.add(it);
        }

        Map<String, Object> deletions = new LinkedHashMap<>();
        deletions.put("metricsByCategory", grouped);
        deletions.put("categories", catsDel);
        deletions.put("systems", sysDel);
        deletions.put("totalRefRows", plan.metricsToDelete.stream().mapToLong(d -> d.refRows).sum());
        result.put("deletions", deletions);

        result.put("conflicts", plan.conflicts);
        result.put("warnings", plan.warnings);

        boolean needWord = plan.metricsToDelete.size() > CONFIRM_WORD_MIN_DELETE
                || (plan.totalMetricsInDb > 0 && plan.metricsToDelete.size() * 10L >= plan.totalMetricsInDb * 3L);
        result.put("needConfirmWord", needWord && !plan.metricsToDelete.isEmpty());
        result.put("confirmWord", CONFIRM_WORD);
        result.put("canExecute", plan.conflicts.isEmpty());
        return result;
    }

    // ========================================================================
    // 二、执行
    // ========================================================================

    @Transactional
    public Map<String, Object> execute(String token, String confirmWord) {
        CachedPlan cached = planCache.get(token);
        if (cached == null) {
            throw new IllegalStateException("导入计划已失效（超过 10 分钟或服务重启），请重新校验文件");
        }
        if (System.currentTimeMillis() > cached.expireAt) {
            planCache.remove(token);
            throw new IllegalStateException("导入计划已过期，请重新校验文件");
        }
        Plan plan = cached.plan;
        if (!plan.conflicts.isEmpty()) {
            throw new IllegalStateException("存在 " + plan.conflicts.size() + " 处冲突，不能执行，请先修正文件");
        }

        boolean needWord = plan.metricsToDelete.size() > CONFIRM_WORD_MIN_DELETE
                || (plan.totalMetricsInDb > 0 && plan.metricsToDelete.size() * 10L >= plan.totalMetricsInDb * 3L);
        if (needWord && !CONFIRM_WORD.equals(confirmWord == null ? null : confirmWord.trim())) {
            throw new IllegalStateException("本次删除量为 " + plan.metricsToDelete.size()
                    + " 条，属于较大变更，请输入「" + CONFIRM_WORD + "」确认");
        }

        Map<String, Object> stat = new LinkedHashMap<>();
        int sysChanged = 0, catChanged = 0, metricChanged = 0, metricDeleted = 0;

        // ---- 1. 系统 UPSERT ----
        Map<String, Long> sysCodeToId = new HashMap<>();
        for (SystemRow r : plan.systems) {
            if (r.id != null && r.changedFields.isEmpty()) {
                // 字段完全一致，不写库（避免产生"未检测到字段变更"的审计噪音）
                sysCodeToId.put(r.code, r.id);
                continue;
            }
            SystemInfo sys = new SystemInfo();
            if (r.id != null) sys.setId(r.id);
            sys.setSystemCode(r.code);
            sys.setSystemName(r.name != null ? r.name : r.code);
            sys.setDescription(r.description);
            sys.setStatus(r.status);
            SystemInfo saved = systemService.save(sys);
            sysCodeToId.put(r.code, saved.getId());
            sysChanged++;
        }
        // 库里已有但 Excel 未提及的系统，仍要能映射到 id（供大类使用）
        for (SystemInfo s : systemInfoRepository.findAll()) {
            sysCodeToId.putIfAbsent(s.getSystemCode(), s.getId());
        }

        // ---- 2. 大类 UPSERT ----
        Map<String, Long> catCodeToId = new HashMap<>();
        for (CategoryRow r : plan.categories) {
            Long systemId = sysCodeToId.get(r.systemCode);
            if (systemId == null) {
                throw new IllegalStateException("大类[" + r.code + "]的系统编码[" + r.systemCode + "]无法解析");
            }
            if (r.id != null && r.changedFields.isEmpty()) {
                catCodeToId.put(r.code, r.id);
                continue;
            }
            MetricCategory cat = new MetricCategory();
            if (r.id != null) cat.setId(r.id);
            cat.setSystemId(systemId);
            cat.setCategoryCode(r.code);
            cat.setCategoryName(r.name != null ? r.name : r.code);
            cat.setDescription(r.description);
            cat.setStatus(r.status);
            MetricCategory saved = metricService.saveCategory(cat);
            catCodeToId.put(r.code, saved.getId());
            catChanged++;
        }
        for (MetricCategory c : categoryRepository.findAll()) {
            catCodeToId.putIfAbsent(c.getCategoryCode(), c.getId());
        }

        // ---- 3. 指标 UPSERT ----
        for (MetricRow r : plan.metrics) {
            Long categoryId = catCodeToId.get(r.categoryCode);
            if (categoryId == null) {
                throw new IllegalStateException("指标[" + r.metricCode + "]的大类编码[" + r.categoryCode + "]无法解析");
            }
            MetricItem item = new MetricItem();
            if (r.id != null) item.setId(r.id);   // 关键：命中即保留原 id，关联数据不失效
            item.setCategoryId(categoryId);
            if (r.subCategoryName != null) {
                item.setSubCategoryName(r.subCategoryName);
                item.setSubCategoryCode(r.subCategoryName.replaceAll("\\s+", "_"));
            }
            item.setMetricCode(r.metricCode);
            // 更新场景下名称为空则保留原值，不要用编码覆盖
            if (r.metricName != null) {
                item.setMetricName(r.metricName);
            } else if (r.id == null) {
                item.setMetricName(r.metricCode);
            }
            if (r.id == null) item.setMetricType("导入");
            item.setResponsiblePerson(r.responsiblePerson);
            item.setAudienceCode(r.audienceCode);
            item.setMetricDefinition(r.metricDefinition);
            item.setStatisticsCaliber(r.statisticsCaliber);
            item.setDataSource(r.dataSource);
            item.setUnit(r.unit);
            item.setDataSyncMode(r.dataSyncMode);
            item.setCollectFrequency(r.collectFrequency);
            item.setCollectStatus(r.collectStatus);
            item.setDepartment(r.department);
            item.setReporter(r.reporter);
            item.setStatus(r.status);
            if (r.changedFields.isEmpty() && r.id != null) {
                continue;   // 无变化不写库，避免产生大量"未检测到字段变更"审计
            }
            metricService.saveItem(item);
            metricChanged++;
        }

        // ---- 4. 删除 Excel 缺失的指标（全量模式）----
        if (!plan.metricsToDelete.isEmpty()) {
            List<Long> ids = plan.metricsToDelete.stream().map(d -> d.id).collect(Collectors.toList());
            Map<String, Integer> clearedRefs = new LinkedHashMap<>();
            for (String table : METRIC_DEPENDENT_TABLES) {
                int n = deleteByMetricIds(table, ids);
                if (n > 0) clearedRefs.put(table, n);
            }
            stat.put("clearedRefs", clearedRefs);
            // 指标主表逐条删，复用审计日志
            for (Long id : ids) {
                metricService.deleteItemById(id);
                metricDeleted++;
            }
        }

        // ---- 5. 删除 Excel 缺失的大类 / 系统 ----
        for (MetricCategory c : plan.categoriesToDelete) {
            // 大类级权重配置一并清掉（这行的 metric_id 为空，前面按 metric_id 删不到）
            jdbcTemplate.update("DELETE FROM weight_config WHERE category_id = ?", c.getId());
            categoryRepository.deleteById(c.getId());
        }
        for (SystemInfo s : plan.systemsToDelete) {
            systemInfoRepository.deleteById(s.getId());
        }

        planCache.remove(token);

        stat.put("mode", plan.mode);
        stat.put("systemChanged", sysChanged);
        stat.put("categoryChanged", catChanged);
        stat.put("metricChanged", metricChanged);
        stat.put("metricDeleted", metricDeleted);
        stat.put("categoryDeleted", plan.categoriesToDelete.size());
        stat.put("systemDeleted", plan.systemsToDelete.size());
        stat.put("businessTablesKept", Arrays.asList(BUSINESS_TABLES_KEPT));
        return stat;
    }

    // ========================================================================
    // 三、解析 Excel → 变更计划
    // ========================================================================

    private Plan buildPlan(MultipartFile file, String mode, boolean allowDeleteCategory) throws IOException {
        Plan plan = new Plan();
        plan.mode = mode;

        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sysSheet = sheetOf(wb, "系统清单", 0);
            Sheet catSheet = sheetOf(wb, "指标大类清单", 1);
            Sheet itemSheet = sheetOf(wb, "指标清单", 2);

            if (sysSheet == null || catSheet == null || itemSheet == null) {
                plan.conflicts.add("文件缺少必要的工作表，需要包含「系统清单」「指标大类清单」「指标清单」三个 Sheet");
                return plan;
            }

            // ---- 系统 ----
            Map<String, Integer> sIdx = MetricImportService.headerIndex(sysSheet.getRow(0));
            Set<String> excelSystemCodes = new LinkedHashSet<>();
            for (int i = 1; i <= sysSheet.getLastRowNum(); i++) {
                Row row = sysSheet.getRow(i);
                if (row == null) continue;
                String code = MetricImportService.blankToNull(MetricImportService.cell(row, sIdx, "系统编码"));
                if (code == null) continue;
                if (!excelSystemCodes.add(code)) {
                    plan.conflicts.add("系统清单第 " + (i + 1) + " 行：系统编码[" + code + "]在文件中重复出现");
                    continue;
                }
                SystemRow r = new SystemRow();
                r.code = code;
                r.name = MetricImportService.blankToNull(MetricImportService.cell(row, sIdx, "系统名称"));
                r.description = MetricImportService.blankToNull(MetricImportService.cell(row, sIdx, "描述"));
                r.status = parseEnabled(MetricImportService.cell(row, sIdx, "状态"), "系统清单", i + 1, plan);
                systemInfoRepository.findBySystemCode(code).ifPresent(s -> {
                    r.id = s.getId();
                    r.changedFields = diffSystem(s, r);
                });
                plan.systems.add(r);
            }

            // ---- 大类 ----
            Map<String, Integer> cIdx = MetricImportService.headerIndex(catSheet.getRow(0));
            Set<String> excelCatCodes = new LinkedHashSet<>();
            for (int i = 1; i <= catSheet.getLastRowNum(); i++) {
                Row row = catSheet.getRow(i);
                if (row == null) continue;
                String code = MetricImportService.blankToNull(MetricImportService.cell(row, cIdx, "指标大类编码"));
                if (code == null) continue;
                if (!excelCatCodes.add(code)) {
                    plan.conflicts.add("指标大类清单第 " + (i + 1) + " 行：大类编码[" + code + "]在文件中重复出现");
                    continue;
                }
                CategoryRow r = new CategoryRow();
                r.code = code;
                r.systemCode = MetricImportService.blankToNull(MetricImportService.cell(row, cIdx, "系统编码"));
                r.name = MetricImportService.blankToNull(MetricImportService.cell(row, cIdx, "指标大类名称"));
                r.description = MetricImportService.blankToNull(MetricImportService.cell(row, cIdx, "描述"));
                r.status = parseEnabled(MetricImportService.cell(row, cIdx, "状态"), "指标大类清单", i + 1, plan);
                if (r.systemCode == null) {
                    plan.conflicts.add("指标大类清单第 " + (i + 1) + " 行：大类[" + code + "]缺少系统编码");
                } else if (!excelSystemCodes.contains(r.systemCode)
                        && systemInfoRepository.findBySystemCode(r.systemCode).isEmpty()) {
                    plan.conflicts.add("指标大类清单第 " + (i + 1) + " 行：系统编码[" + r.systemCode
                            + "]在文件与系统中都不存在");
                }
                // 命中已有大类：按系统 + 大类编码定位
                if (r.systemCode != null) {
                    Long systemId = systemInfoRepository.findBySystemCode(r.systemCode).map(SystemInfo::getId).orElse(null);
                    if (systemId != null) {
                        categoryRepository.findBySystemIdAndCategoryCode(systemId, code)
                                .ifPresent(c -> {
                                    r.id = c.getId();
                                    r.changedFields = diffCategory(c, r);
                                });
                    }
                }
                plan.categories.add(r);
            }

            // ---- 指标 ----
            Map<String, Integer> iIdx = MetricImportService.headerIndex(itemSheet.getRow(0));
            Set<String> excelMetricKeys = new LinkedHashSet<>();
            for (int i = 1; i <= itemSheet.getLastRowNum(); i++) {
                Row row = itemSheet.getRow(i);
                if (row == null) continue;
                String metricCode = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "指标编码"));
                if (metricCode == null) continue;   // 空行/备注行跳过
                String categoryCode = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "指标大类编码"));
                if (categoryCode == null) {
                    plan.conflicts.add("指标清单第 " + (i + 1) + " 行：指标[" + metricCode + "]缺少指标大类编码");
                    continue;
                }
                if (!excelCatCodes.contains(categoryCode) && categoryRepository.findByCategoryCode(categoryCode).isEmpty()) {
                    plan.conflicts.add("指标清单第 " + (i + 1) + " 行：大类编码[" + categoryCode
                            + "]在文件与系统中都不存在");
                    continue;
                }
                String key = categoryCode + "\u0000" + metricCode;
                if (!excelMetricKeys.add(key)) {
                    plan.conflicts.add("指标清单第 " + (i + 1) + " 行：大类[" + categoryCode + "]下的指标编码["
                            + metricCode + "]重复出现，无法确定以哪一行为准");
                    continue;
                }

                MetricRow r = new MetricRow();
                r.categoryCode = categoryCode;
                r.metricCode = metricCode;
                r.subCategoryName = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "指标小类"));
                r.metricName = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "指标名称"));
                r.responsiblePerson = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "面向人员"));
                // 岗位编码由原文归一得到；认不出留空，由页面手工指定，避免猜错污染岗位健康度
                r.audienceCode = audienceService.resolveCode(r.responsiblePerson);
                r.metricDefinition = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "指标定义"));
                r.statisticsCaliber = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "统计口径"));
                r.dataSource = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "取数来源"));
                try {
                    r.unit = MetricImportService.normalizeUnit(MetricImportService.cell(row, iIdx, "指标单位"));
                } catch (IllegalArgumentException e) {
                    plan.conflicts.add("指标清单第 " + (i + 1) + " 行：" + e.getMessage());
                }
                r.dataSyncMode = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "数据对接模式"));
                r.collectFrequency = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "采集频率"));
                r.collectStatus = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "采集状态"));
                r.department = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "部室"));
                r.reporter = MetricImportService.blankToNull(MetricImportService.cell(row, iIdx, "填报人"));
                try {
                    r.status = MetricImportService.parseItemStatus(MetricImportService.cell(row, iIdx, "状态"));
                } catch (IllegalArgumentException e) {
                    plan.conflicts.add("指标清单第 " + (i + 1) + " 行：" + e.getMessage());
                    r.status = 1;
                }

                // 命中已有指标：按大类 id + 指标编码定位
                MetricCategory cat = categoryRepository.findByCategoryCode(categoryCode).orElse(null);
                if (cat != null) {
                    itemRepository.findByCategoryIdAndMetricCode(cat.getId(), metricCode).ifPresent(existing -> {
                        r.id = existing.getId();
                        r.changedFields = diffFields(existing, r);
                    });
                }
                plan.metrics.add(r);
            }
        }

        // ---- 全量模式：计算要删除的指标 / 大类 / 系统 ----
        List<MetricItem> allInDb = itemRepository.findAll();
        plan.totalMetricsInDb = allInDb.size();
        Map<Long, MetricCategory> catById = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(MetricCategory::getId, c -> c, (a, b) -> a));

        if (MODE_FULL.equals(mode)) {
            Set<String> excelKeys = plan.metrics.stream()
                    .map(m -> m.categoryCode + "\u0000" + m.metricCode)
                    .collect(Collectors.toSet());

            // 注意：Excel 里的大类编码要映射成 id 才能与库中记录比对
            Set<Long> keepIds = new HashSet<>();
            for (MetricItem it : allInDb) {
                MetricCategory c = catById.get(it.getCategoryId());
                if (c == null) continue;
                if (excelKeys.contains(c.getCategoryCode() + "\u0000" + it.getMetricCode())) {
                    keepIds.add(it.getId());
                }
            }
            List<MetricItem> toDelete = allInDb.stream()
                    .filter(it -> !keepIds.contains(it.getId()))
                    .collect(Collectors.toList());

            List<Long> delIds = toDelete.stream().map(MetricItem::getId).collect(Collectors.toList());
            Map<String, Long> refTotals = countRefRows(delIds);
            for (MetricItem it : toDelete) {
                MetricCategory c = catById.get(it.getCategoryId());
                DeletedMetric d = new DeletedMetric();
                d.id = it.getId();
                d.metricCode = it.getMetricCode();
                d.metricName = it.getMetricName();
                d.categoryId = it.getCategoryId();
                d.categoryCode = c != null ? c.getCategoryCode() : "";
                d.categoryName = c != null ? c.getCategoryName() : "";
                d.refRows = refTotals.getOrDefault("total", 0L) / Math.max(1, toDelete.size());
                plan.metricsToDelete.add(d);
            }

            // 大类：Excel 里没提到，但库里存在
            Set<String> excelCatSet = plan.categories.stream().map(r -> r.code).collect(Collectors.toSet());
            for (MetricCategory c : catById.values()) {
                if (!excelCatSet.contains(c.getCategoryCode())) {
                    plan.categoriesToDelete.add(c);
                }
            }
            if (!plan.categoriesToDelete.isEmpty() && !allowDeleteCategory) {
                String names = plan.categoriesToDelete.stream()
                        .map(c -> c.getCategoryCode() + "(" + c.getCategoryName() + ")")
                        .limit(6).collect(Collectors.joining("、"));
                plan.conflicts.add("Excel 缺少 " + plan.categoriesToDelete.size() + " 个已在系统中的指标大类："
                        + names + (plan.categoriesToDelete.size() > 6 ? " 等" : "")
                        + "。继续导入会删除这些大类及其下全部指标。确认要删除请勾选「允许删除整个大类」");
            }

            // 系统：Excel 里没提到，但库里存在
            Set<String> excelSysSet = plan.systems.stream().map(r -> r.code).collect(Collectors.toSet());
            for (SystemInfo s : systemInfoRepository.findAll()) {
                if (!excelSysSet.contains(s.getSystemCode())) {
                    // 若该系统下还有要保留的大类，则不能删，只提示
                    boolean stillHasCategory = plan.categories.stream()
                            .anyMatch(r -> s.getSystemCode().equals(r.systemCode));
                    if (stillHasCategory) {
                        plan.warnings.add("系统[" + s.getSystemCode() + "]未出现在「系统清单」中，但其下仍有大类要导入，已跳过删除");
                    } else {
                        plan.systemsToDelete.add(s);
                    }
                }
            }

            // 提示删除规模
            if (!plan.metricsToDelete.isEmpty()) {
                plan.warnings.add("本次将删除 " + plan.metricsToDelete.size() + " 个指标，"
                        + "一并清理其历史数据（告警、工单、问题三类业务记录会保留）");
            }
        }

        return plan;
    }

    private Sheet sheetOf(Workbook wb, String name, int fallbackIndex) {
        Sheet s = wb.getSheet(name);
        if (s != null) return s;
        return fallbackIndex < wb.getNumberOfSheets() ? wb.getSheetAt(fallbackIndex) : null;
    }

    /** 系统/大类状态列：空 → 1（启用）；只认「启用」为启用，与既有行为保持一致 */
    private Integer parseEnabled(String raw, String sheetName, int rowNo, Plan plan) {
        String v = MetricImportService.blankToNull(raw);
        if (v == null) return 1;
        if ("启用".equals(v) || "开启".equals(v) || "1".equals(v) || "是".equals(v)) return 1;
        if ("停用".equals(v) || "禁用".equals(v) || "关闭".equals(v) || "0".equals(v) || "否".equals(v)) return 0;
        plan.warnings.add(sheetName + "第 " + rowNo + " 行：状态值[" + v + "]无法识别，已按「启用」处理");
        return 1;
    }

    /** 系统字段差异（用于预演计数准确、以及执行时跳过无变化写库） */
    private List<String> diffSystem(SystemInfo e, SystemRow r) {
        List<String> changed = new ArrayList<>();
        if (r.name != null && !r.name.equals(e.getSystemName())) changed.add("系统名称");
        if (r.description != null && !r.description.equals(e.getDescription())) changed.add("描述");
        if (r.status != null) {
            int cur = e.getStatus() == null ? 1 : e.getStatus();
            if (cur != r.status) changed.add("状态");
        }
        return changed;
    }

    /** 大类字段差异 */
    private List<String> diffCategory(MetricCategory e, CategoryRow r) {
        List<String> changed = new ArrayList<>();
        if (r.name != null && !r.name.equals(e.getCategoryName())) changed.add("大类名称");
        if (r.description != null && !r.description.equals(e.getDescription())) changed.add("描述");
        if (r.status != null) {
            int cur = e.getStatus() == null ? 1 : e.getStatus();
            if (cur != r.status) changed.add("状态");
        }
        return changed;
    }

    /** 比较 Excel 行的字段与库里记录的差异，用于统计"实际更新数"以及跳过无变化写库 */
    private List<String> diffFields(MetricItem e, MetricRow r) {
        List<String> changed = new ArrayList<>();
        if (r.metricName != null && !r.metricName.equals(e.getMetricName())) changed.add("指标名称");
        if (r.subCategoryName != null && !r.subCategoryName.equals(e.getSubCategoryName())) changed.add("指标小类");
        if (r.metricDefinition != null && !r.metricDefinition.equals(e.getMetricDefinition())) changed.add("指标定义");
        if (r.statisticsCaliber != null && !r.statisticsCaliber.equals(e.getStatisticsCaliber())) changed.add("统计口径");
        if (r.dataSource != null && !r.dataSource.equals(e.getDataSource())) changed.add("取数来源");
        if (r.unit != null && !r.unit.equals(e.getUnit())) changed.add("单位");
        if (r.dataSyncMode != null && !r.dataSyncMode.equals(e.getDataSyncMode())) changed.add("数据对接模式");
        if (r.collectFrequency != null && !r.collectFrequency.equals(e.getCollectFrequency())) changed.add("采集频率");
        if (r.collectStatus != null && !r.collectStatus.equals(e.getCollectStatus())) changed.add("采集状态");
        if (r.department != null && !r.department.equals(e.getDepartment())) changed.add("部室");
        if (r.reporter != null && !r.reporter.equals(e.getReporter())) changed.add("填报人");
        if (r.responsiblePerson != null && !r.responsiblePerson.equals(e.getResponsiblePerson())) changed.add("面向人员");
        if (r.status != null) {
            int cur = e.getStatus() == null ? 1 : e.getStatus();
            if (cur != r.status) changed.add("状态");
        }
        return changed;
    }

    /** 统计待删指标在各关联表中的行数（一次 IN 查询聚合，避免逐条 count） */
    private Map<String, Long> countRefRows(List<Long> ids) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            result.put("total", 0L);
            return result;
        }
        String in = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        long total = 0;
        for (String table : METRIC_DEPENDENT_TABLES) {
            try {
                Long n = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE metric_id IN (" + in + ")", Long.class, ids.toArray());
                long v = n == null ? 0 : n;
                if (v > 0) result.put(table, v);
                total += v;
            } catch (Exception ignore) {
                // 表或列不存在时跳过，不影响预演
            }
        }
        result.put("total", total);
        return result;
    }

    private int deleteByMetricIds(String table, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        String in = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        try {
            return jdbcTemplate.update("DELETE FROM " + table + " WHERE metric_id IN (" + in + ")", ids.toArray());
        } catch (Exception e) {
            System.err.println("清理关联表 " + table + " 失败: " + e.getMessage());
            return 0;
        }
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        planCache.entrySet().removeIf(e -> e.getValue().expireAt < now);
    }

    // ========================================================================
    // 四、内部数据结构
    // ========================================================================

    private static class CachedPlan {
        final Plan plan;
        final long expireAt;
        CachedPlan(Plan plan, long expireAt) { this.plan = plan; this.expireAt = expireAt; }
    }

    static class Plan {
        String mode;
        List<SystemRow> systems = new ArrayList<>();
        List<CategoryRow> categories = new ArrayList<>();
        List<MetricRow> metrics = new ArrayList<>();
        List<SystemInfo> systemsToDelete = new ArrayList<>();
        List<MetricCategory> categoriesToDelete = new ArrayList<>();
        List<DeletedMetric> metricsToDelete = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int totalMetricsInDb;
    }

    static class SystemRow {
        Long id;
        String code, name, description;
        Integer status = 1;
        List<String> changedFields = new ArrayList<>();
    }

    static class CategoryRow {
        Long id;
        String systemCode, code, name, description;
        Integer status = 1;
        List<String> changedFields = new ArrayList<>();
    }

    static class MetricRow {
        Long id;
        String categoryCode, metricCode, subCategoryName, metricName;
        String responsiblePerson, metricDefinition, statisticsCaliber, dataSource;
        /** 由「面向人员」原文归一的岗位编码，见 MetricAudienceService#resolveCode */
        String audienceCode;
        String unit, dataSyncMode, collectFrequency, collectStatus, department, reporter;
        Integer status = 1;
        List<String> changedFields = new ArrayList<>();
    }

    static class DeletedMetric {
        Long id, categoryId;
        String categoryCode, categoryName, metricCode, metricName;
        long refRows;
    }
}
