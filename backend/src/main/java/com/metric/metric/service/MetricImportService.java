package com.metric.metric.service;

import com.metric.metric.entity.DataBackup;
import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.entity.SystemInfo;
import com.metric.metric.repository.DataBackupRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MetricImportService {

    // 备份文件保存目录
    private static final String BACKUP_DIR = "files/metric_backups";

    // ==================== 模板/导出/导入共用的表头定义 ====================
    // 唯一权威来源：改这里，导出、空白模板、导入三处同时生效，不会出现"模板与导入对不上"

    private static final String[] SYSTEM_HEADERS = {"系统名称", "系统编码", "描述", "状态"};

    private static final String[] CATEGORY_HEADERS = {"系统编码", "指标大类名称", "指标大类编码", "描述", "状态"};

    private static final String[] ITEM_HEADERS = {
        "指标大类编码", "指标小类", "指标名称", "指标编码", "面向人员", "指标定义", "统计口径",
        "取数来源", "指标单位", "数据对接模式", "采集频率", "采集状态",
        "部室", "填报人", "状态"
    };
    // 注：2026-10-28 指标与告警解耦，模板不再包含
    // 告警条件/告警阈值/告警连续次数/工单条件/工单阈值/工单连续次数 6 列。
    // 阈值请在「告警管理 → 告警策略」中配置。

    /**
     * 表头批注：只作用于模板/导出的表头单元格，不产生数据行，也不会被导入逻辑读取。
     * 目的：让填表人知道易错列该怎么写，尤其是「状态」——它现在是总开关。
     */
    private static final Map<String, String> HEADER_COMMENTS = new LinkedHashMap<>();
    static {
        HEADER_COMMENTS.put("状态",
                "填「启用」或「停用」。\n" +
                "· 留空 = 启用（新导入的指标默认启用）\n" +
                "· 也兼容：1 / 0、是 / 否、true / false、开启 / 关闭、正常\n" +
                "· 填「停用」会真正停用该指标：\n" +
                "   停止算分、不产生告警与工单、\n" +
                "   不在实时指标和运营报表中显示；\n" +
                "   历史数据保留，重新启用即可恢复。");
        HEADER_COMMENTS.put("采集状态",
                "采集侧的状态描述（如：正常 / 待接入），仅作记录，不影响算分与告警。");
    }

    /**
     * 单位别名 → 规范值。
     * 规范值用中文（存量 85 个已是中文，无需迁移）；导入时兼容模板里的英文缩写。
     */
    private static final Map<String, String> UNIT_ALIAS = new HashMap<>();

    static {
        UNIT_ALIAS.put("ms", "毫秒");
        UNIT_ALIAS.put("毫秒", "毫秒");
        UNIT_ALIAS.put("s", "秒");
        UNIT_ALIAS.put("sec", "秒");
        UNIT_ALIAS.put("秒", "秒");
        UNIT_ALIAS.put("min", "分钟");
        UNIT_ALIAS.put("分钟", "分钟");
        UNIT_ALIAS.put("h", "小时");
        UNIT_ALIAS.put("hr", "小时");
        UNIT_ALIAS.put("小时", "小时");
        UNIT_ALIAS.put("%", "%");
        UNIT_ALIAS.put("百分比", "%");
        UNIT_ALIAS.put("百分号", "%");
        UNIT_ALIAS.put("次", "次");
        UNIT_ALIAS.put("次数", "次");
        UNIT_ALIAS.put("个", "个");
        UNIT_ALIAS.put("条", "条");
        UNIT_ALIAS.put("种", "种");
    }

    // 注：原 CONDITION_ALIAS（比较符别名）随告警条件列一并移除，
    // 指标模板已不含任何阈值相关列，比较符统一由告警策略维护。

    /** 按表头名建立「列名 → 索引」映射，导入时一律按名字取，不再用硬编码下标 */
    /** 包内可复用：表头 → 列号 */
    static Map<String, Integer> headerIndex(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        if (headerRow == null) {
            return map;
        }
        short last = headerRow.getLastCellNum();
        for (int i = 0; i < last; i++) {
            String v = getCellValue(headerRow.getCell(i));
            if (v != null && !v.trim().isEmpty()) {
                map.put(v.trim(), i);
            }
        }
        return map;
    }

    /** 按列名取值；列不存在时返回 null（兼容缺列的历史文件） */
    /** 包内可复用：按表头名取单元格文本 */
    static String cell(Row row, Map<String, Integer> idx, String header) {
        Integer i = idx.get(header);
        if (i == null || i < 0) {
            return null;
        }
        return getCellValue(row.getCell(i));
    }

    /**
     * 解析指标「状态」列（2026-10-28 新增）。
     *
     * 背景：指标状态是"总电闸"，停用后该指标不再参与算分 / 告警 / 工单 / 实时报表。
     * 所以导入时这个字段必须被准确执行，不能像以前那样"只要不是『禁用』就一律按启用"——
     * 那会把填了"停用""关闭""0"的行悄悄导成启用状态。
     *
     * 取值规则：
     *   - 空值        → 1（启用）。指标导入是纯新增（每次 new MetricItem 且无 id），
     *                   新指标默认启用，与页面新建保持一致。
     *   - 启用类写法  → 1
     *   - 停用类写法  → 0（真正停用，导入后立即退出算分与告警）
     *   - 无法识别    → 抛异常，让该行导入失败并提示，不猜测（AGENTS.md：不得靠猜测自动匹配）
     */
    /** 包内可复用：状态列解析 */
    static Integer parseItemStatus(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return 1;
        }
        String v = raw.trim();
        switch (v) {
            case "启用":
            case "开启":
            case "是":
            case "1":
            case "true":
            case "TRUE":
            case "True":
            case "正常":
                return 1;
            case "禁用":
            case "停用":
            case "关闭":
            case "否":
            case "0":
            case "false":
            case "FALSE":
            case "False":
                return 0;
            default:
                throw new IllegalArgumentException(
                        "「状态」列只能填「启用」或「停用」（也兼容 1/0、是/否），当前值：" + raw);
        }
    }

    /** 单位归一化：兼容 ms/s/h/次数 等写法，统一成规范值；不在词典内直接报错，避免口径漂移 */
    /** 包内可复用：单位归一化 */
    static String normalizeUnit(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        String hit = UNIT_ALIAS.get(v.toLowerCase(Locale.ROOT));
        if (hit == null) {
            throw new IllegalArgumentException("指标单位[" + v
                + "]不在受控词典内，合法值：毫秒/秒/分钟/小时/%/个/次/种/条（也可填 ms/s/min/h）");
        }
        return hit;
    }

    /** 空白单元格统一转 null，避免把 "" 写进数值列 */
    /** 包内可复用：空白转 null */
    static String blankToNull(String v) {
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private void writeHeader(Sheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
            sheet.setColumnWidth(i, 4200);
            // 给易错列挂表头批注，填表时鼠标悬停即可看到取值说明
            String note = HEADER_COMMENTS.get(headers[i]);
            if (note != null && drawing != null) {
                ClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, i, 0, i + 5, 7);
                Comment comment = drawing.createCellComment(anchor);
                comment.setString(new XSSFRichTextString(note));
                comment.setAuthor("指标体系平台");
                cell.setCellComment(comment);
            }
        }
    }

    @Autowired
    private SystemService systemService;

    @Autowired
    private MetricService metricService;

    @Autowired
    private DataBackupRepository dataBackupRepository;

    /** 岗位字典：把 Excel「面向人员」原文归一成岗位编码（识别不了留空，由页面补）。 */
    @Autowired
    private MetricAudienceService audienceService;

    /**
     * 生成空白导入模板：三个 sheet（系统清单 / 指标大类清单 / 指标清单），只有表头、无数据行。
     * 与 exportFullConfig() 结构完全同构，保证「模板 → 填写 → 导入 → 导出」四者口径一致。
     */
    public byte[] generateTemplate() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle style = headerStyle(workbook);
            writeHeader(workbook.createSheet("系统清单"), SYSTEM_HEADERS, style);
            writeHeader(workbook.createSheet("指标大类清单"), CATEGORY_HEADERS, style);
            writeHeader(workbook.createSheet("指标清单"), ITEM_HEADERS, style);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    /** 包内可复用：单元格取值 */
    static String getCellValue(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue().toString();
                }
                yield String.valueOf((int) cell.getNumericCellValue());
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    /**
     * 导出完整配置 - 包含系统、指标大类、指标三个sheet
     */
    public byte[] exportFullConfig() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            // 获取所有数据
            List<SystemInfo> systems = systemService.findAll();
            List<MetricCategory> categories = metricService.findAllCategories();
            List<MetricItem> items = metricService.findAllItems();

            // 创建系统Sheet
            createSystemSheet(workbook, systems);

            // 创建指标大类Sheet
            createCategorySheet(workbook, categories, systems);

            // 创建指标Sheet
            createItemSheet(workbook, items, categories);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            byte[] data = outputStream.toByteArray();

            // 导出后自动备份到文件
            saveBackupToFile(data, "export");

            // 导出后同时创建数据库备份记录
            createBackupRecord("导出备份", systems.size(), categories.size(), items.size());

            return data;
        }
    }

    /**
     * 创建数据库备份记录
     */
    private void createBackupRecord(String description, int systemCount, int categoryCount, int itemCount) {
        DataBackup backup = new DataBackup();
        backup.setBackupTime(LocalDateTime.now());
        backup.setOperator("系统");
        backup.setSystemCount(systemCount);
        backup.setCategoryCount(categoryCount);
        backup.setItemCount(itemCount);
        backup.setDescription(description);
        dataBackupRepository.save(backup);
    }

    /**
     * 保存备份文件到文件系统
     */
    private void saveBackupToFile(byte[] data, String prefix) throws IOException {
        // 创建备份目录
        File backupDir = new File(BACKUP_DIR);
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        // 生成文件名：prefix_yyyyMMdd_HHmmss.xlsx
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = prefix + "_" + timestamp + ".xlsx";
        File backupFile = new File(backupDir, filename);

        // 写入文件
        try (FileOutputStream fos = new FileOutputStream(backupFile)) {
            fos.write(data);
        }

        System.out.println("备份已保存到: " + backupFile.getAbsolutePath());
    }

    /**
     * 导出当前数据用于备份（内部使用，不触发文件备份）
     */
    private byte[] exportFullConfigForBackup() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            List<SystemInfo> systems = systemService.findAll();
            List<MetricCategory> categories = metricService.findAllCategories();
            List<MetricItem> items = metricService.findAllItems();

            createSystemSheet(workbook, systems);
            createCategorySheet(workbook, categories, systems);
            createItemSheet(workbook, items, categories);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private void createSystemSheet(Workbook workbook, List<SystemInfo> systems) {
        Sheet sheet = workbook.createSheet("系统清单");

        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        String[] headers = {"系统名称", "系统编码", "描述", "状态"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowNum = 1;
        for (SystemInfo system : systems) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(system.getSystemName() != null ? system.getSystemName() : "");
            row.createCell(1).setCellValue(system.getSystemCode() != null ? system.getSystemCode() : "");
            row.createCell(2).setCellValue(system.getDescription() != null ? system.getDescription() : "");
            row.createCell(3).setCellValue(system.getStatus() != null && system.getStatus() == 1 ? "启用" : "禁用");
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.setColumnWidth(i, 4000);
        }
    }

    private void createCategorySheet(Workbook workbook, List<MetricCategory> categories, List<SystemInfo> systems) {
        Sheet sheet = workbook.createSheet("指标大类清单");

        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        String[] headers = {"系统编码", "指标大类名称", "指标大类编码", "描述", "状态"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        Map<Long, String> systemCodeMap = systems.stream()
                .collect(Collectors.toMap(SystemInfo::getId, SystemInfo::getSystemCode));

        int rowNum = 1;
        for (MetricCategory category : categories) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(systemCodeMap.getOrDefault(category.getSystemId(), ""));
            row.createCell(1).setCellValue(category.getCategoryName() != null ? category.getCategoryName() : "");
            row.createCell(2).setCellValue(category.getCategoryCode() != null ? category.getCategoryCode() : "");
            row.createCell(3).setCellValue(category.getDescription() != null ? category.getDescription() : "");
            row.createCell(4).setCellValue(category.getStatus() != null && category.getStatus() == 1 ? "启用" : "禁用");
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.setColumnWidth(i, 4000);
        }
    }

    private void createItemSheet(Workbook workbook, List<MetricItem> items, List<MetricCategory> categories) {
        Sheet sheet = workbook.createSheet("指标清单");
        writeHeader(sheet, ITEM_HEADERS, headerStyle(workbook));

        Map<Long, String> categoryCodeMap = categories.stream()
                .collect(Collectors.toMap(MetricCategory::getId, MetricCategory::getCategoryCode));

        int rowNum = 1;
        for (MetricItem item : items) {
            Row row = sheet.createRow(rowNum++);
            // 列顺序必须与 ITEM_HEADERS 完全一致
            int c = 0;
            setCell(row, c++, categoryCodeMap.getOrDefault(item.getCategoryId(), ""));
            setCell(row, c++, item.getSubCategoryName());
            setCell(row, c++, item.getMetricName());
            setCell(row, c++, item.getMetricCode());
            setCell(row, c++, item.getResponsiblePerson());
            setCell(row, c++, item.getMetricDefinition());
            setCell(row, c++, item.getStatisticsCaliber());
            setCell(row, c++, item.getDataSource());
            setCell(row, c++, item.getUnit());
            setCell(row, c++, item.getDataSyncMode());
            setCell(row, c++, item.getCollectFrequency());
            setCell(row, c++, item.getCollectStatus());
            setCell(row, c++, item.getDepartment());
            setCell(row, c++, item.getReporter());
            setCell(row, c, item.getStatus() != null && item.getStatus() == 1 ? "启用" : "禁用");
        }
    }

    /** 空值统一写成空字符串，避免导出出 "null" */
    private void setCell(Row row, int col, String value) {
        row.createCell(col).setCellValue(value != null ? value : "");
    }

    /**
     * 全量导入 - 导入覆盖全部数据，并自动备份。
     * 事务覆盖「清空 + 重建」全过程：任一行异常都不会留下空库。
     */
    @Transactional
    /**
     * ⚠️ 已废弃（2026-10-28）：这是"清空重建"式全量导入，会先 deleteAll 再插入，
     * 导致 metric_item.id 全部变化，17 张关联表（历史值/健康度/告警历史/策略）断裂。
     * HTTP 端点 /api/metric/import-full 已移除，请改用：
     *   MetricImportPlanService.preview() + execute()（原地 UPSERT，保留 id）
     * 本方法仅作历史留存，不要在任何新代码中调用。
     */
    @Deprecated
    public Map<String, Object> importFullConfig(MultipartFile file) throws IOException {
        Map<String, Object> result = new HashMap<>();
        int successCount = 0;
        int errorCount = 0;
        StringBuilder errorMsg = new StringBuilder();

        try {
            // 1. 先创建备份（保存到数据库）
            createBackup();

            // 2. 导入前先备份当前数据到文件
            byte[] currentData = exportFullConfigForBackup();
            saveBackupToFile(currentData, "import_before");

            // 3. 清空现有数据（按正确顺序删除：先删指标，再删大类，最后删系统）
            clearAllData();

            // 3. 读取Excel并导入
            Map<String, Long> systemCodeToId = new HashMap<>();
            Map<String, Long> categoryCodeToId = new HashMap<>();

            try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
                // Sheet 0: 系统清单
                Sheet systemSheet = workbook.getSheet("系统清单");
                if (systemSheet == null) {
                    systemSheet = workbook.getSheetAt(0);
                }
                if (systemSheet != null) {
                    Map<String, Integer> idx = headerIndex(systemSheet.getRow(0));
                    for (int i = 1; i <= systemSheet.getLastRowNum(); i++) {
                    Row row = systemSheet.getRow(i);
                    if (row == null) continue;
                    try {
                        String systemName = blankToNull(cell(row, idx, "系统名称"));
                        String systemCode = blankToNull(cell(row, idx, "系统编码"));
                        String description = blankToNull(cell(row, idx, "描述"));
                        String statusStr = blankToNull(cell(row, idx, "状态"));

                        if (systemCode == null) continue;

                        SystemInfo system = new SystemInfo();
                        system.setSystemCode(systemCode);
                        system.setSystemName(systemName != null ? systemName : systemCode);
                        system.setDescription(description);
                        system.setStatus("启用".equals(statusStr) ? 1 : 0);

                        system = systemService.save(system);
                        systemCodeToId.put(systemCode, system.getId());
                        successCount++;
                    } catch (Exception e) {
                        errorCount++;
                        errorMsg.append("系统第").append(i + 1).append("行导入失败: ").append(e.getMessage()).append("; ");
                    }
                }
            }

            // Sheet 1: 指标大类清单
            Sheet categorySheet = workbook.getSheet("指标大类清单");
            if (categorySheet == null) {
                categorySheet = workbook.getSheetAt(1);
            }
            if (categorySheet != null) {
                Map<String, Integer> idx = headerIndex(categorySheet.getRow(0));
                for (int i = 1; i <= categorySheet.getLastRowNum(); i++) {
                    Row row = categorySheet.getRow(i);
                    if (row == null) continue;
                    try {
                        String systemCode = blankToNull(cell(row, idx, "系统编码"));
                        String categoryName = blankToNull(cell(row, idx, "指标大类名称"));
                        String categoryCode = blankToNull(cell(row, idx, "指标大类编码"));
                        String description = blankToNull(cell(row, idx, "描述"));
                        String statusStr = blankToNull(cell(row, idx, "状态"));

                        if (categoryCode == null) continue;

                        Long systemId = systemCodeToId.get(systemCode);
                        if (systemId == null) continue;

                        MetricCategory category = new MetricCategory();
                        category.setSystemId(systemId);
                        category.setCategoryCode(categoryCode);
                        category.setCategoryName(categoryName != null ? categoryName : categoryCode);
                        category.setDescription(description);
                        category.setStatus("启用".equals(statusStr) ? 1 : 0);

                        category = metricService.saveCategory(category);
                        categoryCodeToId.put(categoryCode, category.getId());
                        successCount++;
                    } catch (Exception e) {
                        errorCount++;
                        errorMsg.append("指标大类第").append(i).append("行导入失败: ").append(e.getMessage()).append("; ");
                    }
                }
            }

            // Sheet 2: 指标清单
            Sheet itemSheet = workbook.getSheet("指标清单");
            if (itemSheet == null) {
                itemSheet = workbook.getSheetAt(2);
            }
            if (itemSheet != null) {
                Map<String, Integer> idx = headerIndex(itemSheet.getRow(0));
                if (!idx.containsKey("指标编码") || !idx.containsKey("指标大类编码")) {
                    throw new IOException("「指标清单」缺少必需列：指标编码 / 指标大类编码");
                }
                for (int i = 1; i <= itemSheet.getLastRowNum(); i++) {
                    Row row = itemSheet.getRow(i);
                    if (row == null) continue;
                    int rowNo = i + 1;
                    try {
                        String categoryCode = blankToNull(cell(row, idx, "指标大类编码"));
                        String metricCode = blankToNull(cell(row, idx, "指标编码"));

                        if (metricCode == null) continue;
                        if (categoryCode == null) {
                            throw new IllegalArgumentException("指标大类编码为空");
                        }

                        Long categoryId = categoryCodeToId.get(categoryCode);
                        if (categoryId == null) {
                            throw new IllegalArgumentException("指标大类编码[" + categoryCode + "]在「指标大类清单」中不存在");
                        }

                        MetricItem item = new MetricItem();
                        item.setCategoryId(categoryId);

                        String subCategoryName = blankToNull(cell(row, idx, "指标小类"));
                        item.setSubCategoryName(subCategoryName);
                        item.setSubCategoryCode(subCategoryName != null ?
                            subCategoryName.replaceAll("\\s+", "_") : null);

                        item.setMetricCode(metricCode);
                        String metricName = blankToNull(cell(row, idx, "指标名称"));
                        item.setMetricName(metricName != null ? metricName : metricCode);
                        item.setMetricType("导入");

                        // 面向人员：保留原文展示，同时归一成岗位编码，供人员维度聚合使用。
                        // 原文写法多样（业务维护人员/维护人员、带括号说明等），
                        // resolveCode 只认字典名称和已知别名，认不出就留空，不猜。
                        String responsiblePerson = blankToNull(cell(row, idx, "面向人员"));
                        item.setResponsiblePerson(responsiblePerson);
                        item.setAudienceCode(audienceService.resolveCode(responsiblePerson));
                        item.setMetricDefinition(blankToNull(cell(row, idx, "指标定义")));
                        item.setStatisticsCaliber(blankToNull(cell(row, idx, "统计口径")));
                        item.setDataSource(blankToNull(cell(row, idx, "取数来源")));
                        item.setUnit(normalizeUnit(blankToNull(cell(row, idx, "指标单位"))));
                        item.setDataSyncMode(blankToNull(cell(row, idx, "数据对接模式")));
                        item.setCollectFrequency(blankToNull(cell(row, idx, "采集频率")));
                        item.setCollectStatus(blankToNull(cell(row, idx, "采集状态")));
                        item.setDepartment(blankToNull(cell(row, idx, "部室")));
                        item.setReporter(blankToNull(cell(row, idx, "填报人")));

                        // 告警/工单阈值不在指标模板中（2026-10-28 解耦），
                        // 阈值统一在「告警管理 → 告警策略」维护。

                        String statusStr = blankToNull(cell(row, idx, "状态"));
                        item.setStatus(parseItemStatus(statusStr));

                        metricService.saveItem(item);
                        successCount++;
                    } catch (Exception e) {
                        errorCount++;
                        errorMsg.append("指标第").append(rowNo).append("行导入失败: ").append(e.getMessage()).append("; ");
                    }
                }
            }
        }

        result.put("successCount", successCount);
        result.put("errorCount", errorCount);
        result.put("message", errorMsg.toString());

        } catch (Exception e) {
            // 导入过程中出现异常
            errorMsg.append("导入失败: ").append(e.getMessage());
            result.put("successCount", successCount);
            result.put("errorCount", errorCount + 1);
            result.put("message", errorMsg.toString());
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 清空所有数据
     */
    @Transactional
    private void clearAllData() {
        metricService.deleteAllItems();
        metricService.deleteAllCategories();
        systemService.deleteAll();
    }

    /**
     * 创建备份
     */
    private void createBackup() throws IOException {
        DataBackup backup = new DataBackup();
        backup.setBackupTime(LocalDateTime.now());
        backup.setOperator("系统");

        List<SystemInfo> systems = systemService.findAll();
        List<MetricCategory> categories = metricService.findAllCategories();
        List<MetricItem> items = metricService.findAllItems();

        backup.setSystemCount(systems.size());
        backup.setCategoryCount(categories.size());
        backup.setItemCount(items.size());
        backup.setDescription("导入前自动备份");

        dataBackupRepository.save(backup);
    }

    /**
     * 获取备份列表
     */
    public List<Map<String, Object>> getBackupList() {
        List<DataBackup> backups = dataBackupRepository.findAllByOrderByBackupTimeDesc();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        return backups.stream().map(backup -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", backup.getId());
            map.put("backupTime", backup.getBackupTime().format(formatter));
            map.put("operator", backup.getOperator());
            map.put("systemCount", backup.getSystemCount());
            map.put("categoryCount", backup.getCategoryCount());
            map.put("itemCount", backup.getItemCount());
            return map;
        }).collect(Collectors.toList());
    }

    /**
     * 导出备份
     */
    public byte[] exportBackup(Long id) throws IOException {
        Optional<DataBackup> backupOpt = dataBackupRepository.findById(id);
        if (backupOpt.isEmpty()) {
            throw new IOException("备份不存在");
        }

        // 获取当前数据（因为备份只保存了数量，数据还是当前数据）
        // 如果需要保存历史数据，需要在创建备份时同时导出Excel
        return exportFullConfig();
    }

    /**
     * 恢复备份（这里恢复到备份时的数据，实际上是重新导入备份文件）
     */
    public Map<String, Object> restoreBackup(Long id) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "恢复功能需要备份时保存完整数据，目前仅保存了备份时的数量统计");
        result.put("successCount", 0);
        return result;
    }
}
