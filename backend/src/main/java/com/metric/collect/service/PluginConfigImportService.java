package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.metric.shared.dto.PluginHeaderConfig;
import com.metric.shared.dto.PluginParamConfig;
import com.metric.shared.dto.PluginResponseParamConfig;
import com.metric.collect.entity.PluginAuthentication;
import com.metric.collect.entity.PluginConfig;
import com.metric.collect.repository.PluginAuthenticationRepository;
import com.metric.collect.repository.PluginConfigRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class PluginConfigImportService {

    @Autowired
    private PluginConfigRepository pluginConfigRepository;

    @Autowired
    private PluginAuthenticationRepository authenticationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---- Sheet 名常量（按顺序，便于通过 index 访问） ----
    private static final String SHEET_README = "使用说明";
    private static final String SHEET_BASIC = "插件基本信息";
    private static final String SHEET_HEADERS = "请求头";
    private static final String SHEET_PARAMS = "请求参数";
    private static final String SHEET_RESPONSES = "响应字段";
    private static final String SHEET_AUTHS = "认证配置库";

    // ---- 期望的 sheet 总数 ----
    private static final int EXPECTED_SHEET_COUNT = 6;  // 完整新版（带认证配置库）
    private static final int LEGACY_SHEET_COUNT = 5;     // 旧版（无认证配置库，但已是 5-sheet 结构）

    // ---- sheet 列定义（顺序敏感） ----
    private static final String[] BASIC_HEADERS = {
        "接口名称", "英文名称", "插件类型", "描述",
        "请求地址", "请求方法", "触发方式", "定时Cron", "使用认证配置",
        "请求头JSON", "请求参数JSON"
    };

    private static final String[] AUTH_SHEET_HEADERS = {
        "配置名称", "认证地址", "请求方法", "内容类型",
        "请求体模板", "请求头", "Token路径",
        "Header名", "值前缀", "缓存秒数", "启用"
    };

    private static final String[] HEADER_SHEET_HEADERS = {
        "接口名称", "请求头名称", "请求头值", "描述"
    };

    private static final String[] PARAM_SHEET_HEADERS = {
        "接口名称", "参数名称", "参数类型", "参数位置",
        "是否必填", "默认值", "参数来源", "描述"
    };

    private static final String[] RESPONSE_SHEET_HEADERS = {
        "接口名称", "响应字段名", "响应字段类型", "JSONPath", "描述"
    };

    // ---- 下拉选项 ----
    private static final String[] PLUGIN_TYPES = {"HTTP_API"};
    private static final String[] REQUEST_METHODS = {"GET", "POST", "PUT", "DELETE"};
    private static final String[] TRIGGER_MODES = {"MANUAL", "TIMER", "AUTO"};
    private static final String[] PARAM_TYPES = {"string", "number", "boolean"};
    private static final String[] PARAM_POSITIONS = {"query", "body", "path"};
    private static final String[] REQUIRED_OPTIONS = {"是", "否"};
    private static final String[] RESPONSE_TYPES = {"string", "number", "boolean", "object", "array"};

    // ==================== 模板生成 ====================

    public byte[] generateTemplate() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle bodyStyle = createBodyStyle(workbook);

            // ① 使用说明
            buildReadmeSheet(workbook, headerStyle, bodyStyle);

            // ② 插件基本信息
            buildBasicSheet(workbook, headerStyle, bodyStyle);

            // ③ 请求头
            buildHeaderSheet(workbook, headerStyle, bodyStyle);

            // ④ 请求参数
            buildParamSheet(workbook, headerStyle, bodyStyle);

            // ⑤ 响应字段
            buildResponseSheet(workbook, headerStyle, bodyStyle);

            // ⑥ 认证配置库（可选；如果业务插件不需要认证，可留空）
            buildAuthSheet(workbook, headerStyle, bodyStyle);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    // ---- 样式（按用户偏好：浅色底纹 + 普通字体，不加粗） ----
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        // 明确不设粗体 —— 用户偏好：feedback_xlsx_no_bold_header
        font.setBold(false);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        applyThinBorder(style);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle createBodyStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        applyThinBorder(style);
        return style;
    }

    private void applyThinBorder(CellStyle style) {
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private void buildReadmeSheet(Workbook workbook, CellStyle headerStyle, CellStyle bodyStyle) {
        Sheet sheet = workbook.createSheet(SHEET_README);
        sheet.setColumnWidth(0, 8000);
        sheet.setColumnWidth(1, 14000);

        Row title = sheet.createRow(0);
        Cell titleCell = title.createCell(0);
        titleCell.setCellValue("插件配置导入模板 · v2 使用说明");
        titleCell.setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

        String[][] rows = {
            {"⚠️ 重要提示", "本模板为多 sheet 结构（5 个 sheet），必须从『配置管理 → 插件配置 → 下载模板』获取最新版本。旧版单 sheet 模板将无法导入。"},
            {"", ""},
            {"Sheet ② 插件基本信息", "每个插件占一行，『接口名称』是后续 sheet 的关联键，必须填写且同一文件内不可重复。必填：接口名称、请求地址、请求方法、触发方式。"},
            {"请求头/请求参数录入", "推荐直接在『插件基本信息』填写『请求头JSON』和『请求参数JSON』；也可以使用 Sheet ③/④ 的表格方式，二选一。"},
            {"JSON 示例", "请求头：{\"Content-Type\":\"application/json\",\"X-Session-Key\":\"${auth.token}\"}\n请求参数：{\"systemId\":1,\"keyword\":\"CPU\"}"},
            {"Token路径填写", "Token路径使用 JSONPath：返回 {\"data\":{\"token\":\"abc\"}} 填 $.data.token；返回 {\"access_token\":\"abc\"} 填 $.access_token。"},
            {"Token请求头", "Authorization 通常使用前缀 Bearer 加一个空格；X-Session-Key、X-Access-Token 通常不填写前缀。业务请求头值使用 ${auth.token}。"},
            {"缓存秒数", "Token 缓存时间，填写 0 表示每次请求重新认证；普通 Token 可填写 3600，具体按外部平台有效期设置。"},
            {"互斥规则", "同一个插件不能同时填写 JSON 列和对应的明细 sheet；两边都填写会导入失败，避免配置来源不一致。"},
            {"Sheet ⑤ 响应字段", "一个响应字段占一行，通过『接口名称』关联到 sheet ②。"},
            {"", ""},
            {"关联键示例", "插件基本信息：工单创建接口 / 创建工单\n请求头：工单创建接口 / Authorization / Bearer ${token}\n请求参数：工单创建接口 / title / body\n响应字段：工单创建接口 / id / $.data.id"},
            {"变量语法", "请求头值、参数来源、默认值 中支持 ${xxx} 占位符，例如 ${token}、${gongdan.gongdanTitle}。"},
            {"下拉单元格", "灰色底色的单元格可点击下拉选择；下拉项外的值不会被后端拒绝，但请尽量按规范填写。"},
        };

        int rowIdx = 1;
        for (String[] kv : rows) {
            Row r = sheet.createRow(rowIdx++);
            Cell c0 = r.createCell(0);
            c0.setCellValue(kv[0]);
            c0.setCellStyle(bodyStyle);
            Cell c1 = r.createCell(1);
            c1.setCellValue(kv[1]);
            c1.setCellStyle(bodyStyle);
        }
    }

    private void buildBasicSheet(Workbook workbook, CellStyle headerStyle, CellStyle bodyStyle) {
        Sheet sheet = workbook.createSheet(SHEET_BASIC);
        String[] exampleRow = {
            "工单创建接口", "WorkOrder Create API", "HTTP_API", "用于创建外部工单",
            "http://api.example.com/workorder/create", "POST", "MANUAL", "", "BOMC认证",
            "{\"Content-Type\":\"application/json\",\"Authorization\":\"Bearer ${auth.token}\"}",
            "{\"title\":\"${gongdan.gongdanTitle}\"}"
        };
        buildDataSheet(workbook, sheet, BASIC_HEADERS, new String[][]{exampleRow}, headerStyle, bodyStyle, new int[]{4000, 4000, 3000, 5000, 6000, 3000, 3000, 4000, 4000, 10000, 10000});

        // 数据校验（下拉）
        applyDataValidation(sheet, 2, PLUGIN_TYPES);          // 插件类型
        applyDataValidation(sheet, 5, REQUEST_METHODS);       // 请求方法
        applyDataValidation(sheet, 6, TRIGGER_MODES);         // 触发方式
    }

    private void buildHeaderSheet(Workbook workbook, CellStyle headerStyle, CellStyle bodyStyle) {
        Sheet sheet = workbook.createSheet(SHEET_HEADERS);
        String[] exampleRow = {
            "工单创建接口", "Authorization", "Bearer ${token}", "认证令牌"
        };
        buildDataSheet(workbook, sheet, HEADER_SHEET_HEADERS, new String[][]{exampleRow}, headerStyle, bodyStyle, new int[]{4000, 4000, 5000, 5000});
    }

    private void buildParamSheet(Workbook workbook, CellStyle headerStyle, CellStyle bodyStyle) {
        Sheet sheet = workbook.createSheet(SHEET_PARAMS);
        String[] exampleRow = {
            "工单创建接口", "title", "string", "body", "是", "", "${gongdan.gongdanTitle}", "工单标题"
        };
        buildDataSheet(workbook, sheet, PARAM_SHEET_HEADERS, new String[][]{exampleRow}, headerStyle, bodyStyle, new int[]{4000, 4000, 3000, 3000, 3000, 4000, 4000, 5000});

        applyDataValidation(sheet, 2, PARAM_TYPES);
        applyDataValidation(sheet, 3, PARAM_POSITIONS);
        applyDataValidation(sheet, 4, REQUIRED_OPTIONS);
    }

    private void buildResponseSheet(Workbook workbook, CellStyle headerStyle, CellStyle bodyStyle) {
        Sheet sheet = workbook.createSheet(SHEET_RESPONSES);
        String[] exampleRow = {
            "工单创建接口", "id", "number", "$.data.id", "返回的工单ID"
        };
        buildDataSheet(workbook, sheet, RESPONSE_SHEET_HEADERS, new String[][]{exampleRow}, headerStyle, bodyStyle, new int[]{4000, 4000, 3000, 5000, 5000});

        applyDataValidation(sheet, 2, RESPONSE_TYPES);
    }

    private void buildAuthSheet(Workbook workbook, CellStyle headerStyle, CellStyle bodyStyle) {
        Sheet sheet = workbook.createSheet(SHEET_AUTHS);
        String[] exampleRow = {
            "BOMC认证", "http://bomc.example.com/auth/login", "POST", "application/json",
            "{\"username\":\"${auth.user}\",\"password\":\"${auth.pwd}\"}", "", "$.data.access_token",
            "Authorization", "Bearer ", "3600", "1"
        };
        buildDataSheet(workbook, sheet, AUTH_SHEET_HEADERS, new String[][]{exampleRow}, headerStyle, bodyStyle, new int[]{4000, 6000, 3000, 4000, 8000, 4000, 5000, 4000, 3000, 3000, 2000});

        // 数据校验
        applyDataValidation(sheet, 2, REQUEST_METHODS);  // 请求方法
        applyDataValidation(sheet, 10, REQUIRED_OPTIONS);  // 启用
    }

    private void buildDataSheet(Workbook workbook, Sheet sheet, String[] headers,
                                String[][] exampleRows, CellStyle headerStyle, CellStyle bodyStyle,
                                int[] columnWidths) {
        // 列宽
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i]);
        }

        // 表头
        Row headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(22);
        for (int i = 0; i < headers.length; i++) {
            Cell c = headerRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(headerStyle);
        }

        // 示例行
        for (int r = 0; r < exampleRows.length; r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < exampleRows[r].length; c++) {
                Cell cell = row.createCell(c);
                cell.setCellValue(exampleRows[r][c]);
                cell.setCellStyle(bodyStyle);
            }
        }
    }

    /**
     * 给指定列附加下拉数据校验。
     * 范围：从第 2 行（跳过表头）到第 1000 行，覆盖常见导入规模。
     */
    private void applyDataValidation(Sheet sheet, int colIndex, String[] options) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(options);
        CellRangeAddressList range = new CellRangeAddressList(1, 1000, colIndex, colIndex);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setSuppressDropDownArrow(true);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    // ==================== 导入解析 ====================

    public Map<String, Object> importExcel(MultipartFile file) throws Exception {
        List<Map<String, String>> errors = new ArrayList<>();
        List<String> successRecords = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            int sheetCount = workbook.getNumberOfSheets();
            if (sheetCount < LEGACY_SHEET_COUNT) {
                Map<String, Object> result = new HashMap<>();
                result.put("successCount", 0);
                result.put("failCount", 1);
                result.put("errors", List.of(Map.of(
                    "row", "0",
                    "error", "请使用最新版插件配置模板（至少 5 个 sheet），从『配置管理 → 插件配置 → 下载模板』重新下载后填写。"
                )));
                result.put("successRecords", successRecords);
                return result;
            }

            // 0. 先解析认证配置库（如果有），返回 name -> authId 映射
            Map<String, Long> authNameToId = new HashMap<>();
            if (sheetCount >= EXPECTED_SHEET_COUNT) {
                authNameToId = parseAuthSheet(workbook.getSheet(SHEET_AUTHS), errors);
            }

            // 1. 读 sheet ② → 建立基础数据 map（按接口名称索引）
            Sheet basicSheet = workbook.getSheet(SHEET_BASIC);
            Map<String, PluginConfig> configMap = new LinkedHashMap<>();
            Map<Integer, String> nameOrder = new LinkedHashMap<>(); // 保留填写的原始顺序
            parseBasicSheet(basicSheet, configMap, nameOrder, authNameToId, errors);

            if (configMap.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("successCount", 0);
                result.put("failCount", 1);
                result.put("errors", List.of(Map.of(
                    "row", "0",
                    "error", "插件基本信息 sheet 中没有任何有效记录，请至少填写一个接口名称。"
                )));
                result.put("successRecords", successRecords);
                return result;
            }

            // 2. 读 sheet ③④⑤ → 按接口名称 group 进 configMap
            parseHeaderSheet(workbook.getSheet(SHEET_HEADERS), configMap, errors);
            parseParamSheet(workbook.getSheet(SHEET_PARAMS), configMap, errors);
            parseResponseSheet(workbook.getSheet(SHEET_RESPONSES), configMap, errors);

            // 3. 保存（按用户填写的原始顺序）
            for (Map.Entry<Integer, String> entry : nameOrder.entrySet()) {
                String name = entry.getValue();
                PluginConfig config = configMap.get(name);
                if (config == null) continue;
                try {
                    pluginConfigRepository.save(config);
                    successRecords.add(config.getName());
                } catch (Exception e) {
                    errors.add(Map.of(
                        "row", String.valueOf(entry.getKey() + 2),
                        "error", "保存失败: " + e.getMessage()
                    ));
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("successCount", successRecords.size());
        result.put("failCount", errors.size());
        result.put("errors", errors);
        result.put("successRecords", successRecords);
        return result;
    }

    private void parseBasicSheet(Sheet sheet, Map<String, PluginConfig> configMap,
                                 Map<Integer, String> nameOrder,
                                 Map<String, Long> authNameToId,
                                 List<Map<String, String>> errors) {
        if (sheet == null) return;
        Map<String, Integer> idx = buildHeaderIndex(sheet);

        Set<String> seenNames = new HashSet<>();

        int lastRow = sheet.getLastRowNum();
        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowBlank(row)) continue;

            String name = readCell(row, idx.get("接口名称"));
            if (name == null || name.isEmpty()) continue;

            if (seenNames.contains(name)) {
                errors.add(Map.of(
                    "row", String.valueOf(r + 1),
                    "error", "接口名称重复: " + name
                ));
                continue;
            }
            seenNames.add(name);

            PluginConfig config = new PluginConfig();
            config.setName(name);
            config.setNameEn(nullIfEmpty(readCell(row, idx.get("英文名称"))));
            config.setPluginType(defaultIfEmpty(readCell(row, idx.get("插件类型")), "HTTP_API"));
            config.setDescription(nullIfEmpty(readCell(row, idx.get("描述"))));
            config.setRequestUrl(readCell(row, idx.get("请求地址")));
            config.setRequestMethod(defaultIfEmpty(readCell(row, idx.get("请求方法")), "POST"));
            String triggerMode = defaultIfEmpty(readCell(row, idx.get("触发方式")), "MANUAL");
            config.setTriggerMode(triggerMode);
            String cron = readCell(row, idx.get("定时Cron"));
            if ("TIMER".equalsIgnoreCase(triggerMode) && (cron == null || cron.isEmpty())) {
                errors.add(Map.of(
                    "row", String.valueOf(r + 1),
                    "error", "接口 [" + name + "] 触发方式为 TIMER，必须填写『定时Cron』"
                ));
                continue;
            }
            config.setTimerCron(nullIfEmpty(cron));

            // 必填校验：请求地址 / 请求方法
            if (config.getRequestUrl() == null || config.getRequestUrl().isEmpty()) {
                errors.add(Map.of(
                    "row", String.valueOf(r + 1),
                    "error", "接口 [" + name + "] 缺少必填字段『请求地址』"
                ));
                continue;
            }
            if (config.getRequestMethod() == null || config.getRequestMethod().isEmpty()) {
                errors.add(Map.of(
                    "row", String.valueOf(r + 1),
                    "error", "接口 [" + name + "] 缺少必填字段『请求方法』"
                ));
                continue;
            }

            // 认证配置：按 name 反查
            String authName = nullIfEmpty(readCell(row, idx.get("使用认证配置")));
            if (authName != null) {
                Long authId = authNameToId.get(authName);
                if (authId == null) {
                    // 兼容：用户在 sheet ② 引用了 sheet ⑥ 没出现的 auth name
                    errors.add(Map.of(
                        "row", String.valueOf(r + 1),
                        "error", "接口 [" + name + "] 引用的认证配置 [" + authName + "] 在『认证配置库』sheet 中不存在"
                    ));
                    continue;
                }
                config.setAuthId(authId);
            }

            String headersJson = nullIfEmpty(readCell(row, idx.get("请求头JSON")));
            String paramsJson = nullIfEmpty(readCell(row, idx.get("请求参数JSON")));
            if (headersJson != null && !isJsonObjectOrArray(headersJson)) {
                errors.add(Map.of("row", String.valueOf(r + 1), "error", "接口 [" + name + "] 的『请求头JSON』必须是 JSON 对象或数组"));
                continue;
            }
            if (paramsJson != null && !isJsonObjectOrArray(paramsJson)) {
                errors.add(Map.of("row", String.valueOf(r + 1), "error", "接口 [" + name + "] 的『请求参数JSON』必须是 JSON 对象或数组"));
                continue;
            }

            config.setEnabled(1);
            config.setDebugStatus("PENDING");
            config.setCreateTime(LocalDateTime.now());
            config.setUpdateTime(LocalDateTime.now());

            config.setRequestHeaders(headersJson == null ? "[]" : headersJson);
            config.setRequestParams(paramsJson == null ? "[]" : paramsJson);
            config.setResponseParams("[]");

            configMap.put(name, config);
            nameOrder.put(r, name);
        }
    }

    /**
     * 解析『认证配置库』sheet，返回 name -> authId 映射。
     * 如果 sheet 为空（5-sheet 模板没有这一 sheet），返回空 map，调用方据此跳过 auth 解析。
     */
    private Map<String, Long> parseAuthSheet(Sheet sheet, List<Map<String, String>> errors) {
        Map<String, Long> nameToId = new HashMap<>();
        if (sheet == null) return nameToId;

        Map<String, Integer> idx = buildHeaderIndex(sheet);
        Set<String> seen = new HashSet<>();
        int lastRow = sheet.getLastRowNum();

        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowBlank(row)) continue;

            String name = readCell(row, idx.get("配置名称"));
            if (name == null || name.isEmpty()) continue;

            if (seen.contains(name)) {
                errors.add(Map.of("row", String.valueOf(r + 1), "error", "认证配置名称重复: " + name));
                continue;
            }
            seen.add(name);

            String loginUrl = readCell(row, idx.get("认证地址"));
            String tokenPath = readCell(row, idx.get("Token路径"));
            if (loginUrl == null || loginUrl.isEmpty()) {
                errors.add(Map.of("row", String.valueOf(r + 1), "error", "认证配置 [" + name + "] 缺少必填字段『认证地址』"));
                continue;
            }
            if (tokenPath == null || tokenPath.isEmpty()) {
                errors.add(Map.of("row", String.valueOf(r + 1), "error", "认证配置 [" + name + "] 缺少必填字段『Token路径』"));
                continue;
            }

            PluginAuthentication auth = new PluginAuthentication();
            auth.setName(name);
            auth.setLoginUrl(loginUrl);
            auth.setLoginMethod(defaultIfEmpty(readCell(row, idx.get("请求方法")), "POST"));
            auth.setLoginContentType(defaultIfEmpty(readCell(row, idx.get("内容类型")), "application/json"));
            auth.setLoginBody(nullIfEmpty(readCell(row, idx.get("请求体模板"))));
            auth.setLoginHeaders(nullIfEmpty(readCell(row, idx.get("请求头"))));
            auth.setTokenPath(tokenPath);
            auth.setTokenHeaderName(defaultIfEmpty(readCell(row, idx.get("Header名")), "Authorization"));
            auth.setTokenValuePrefix(readCell(row, idx.get("值前缀")));  // 允许 null（不强制前缀）
            String ttlStr = readCell(row, idx.get("缓存秒数"));
            try {
                auth.setTtlSeconds(ttlStr == null || ttlStr.isEmpty() ? 3600 : Integer.parseInt(ttlStr));
            } catch (NumberFormatException e) {
                auth.setTtlSeconds(3600);
            }
            String enabledStr = readCell(row, idx.get("启用"));
            // 兼容 "是" 和 "1" 两种写法（与插件请求参数"是否必填"列保持一致）
            auth.setEnabled(enabledStr == null || enabledStr.trim().isEmpty() || enabledStr.trim().equals("是") || enabledStr.trim().equals("1") ? 1 : 0);
            auth.setCreateTime(LocalDateTime.now());
            auth.setUpdateTime(LocalDateTime.now());

            try {
                // 幂等：按 name 查已存在则更新，避免重复导入冲突 UNIQUE 约束
                PluginAuthentication existing = authenticationRepository.findFirstByName(name).orElse(null);
                if (existing != null) {
                    auth.setId(existing.getId());
                    auth.setCreateTime(existing.getCreateTime());
                }
                PluginAuthentication saved = authenticationRepository.save(auth);
                nameToId.put(name, saved.getId());
            } catch (Exception e) {
                errors.add(Map.of("row", String.valueOf(r + 1), "error", "认证配置保存失败 [" + name + "]: " + e.getMessage()));
            }
        }
        return nameToId;
    }

    private void parseHeaderSheet(Sheet sheet, Map<String, PluginConfig> configMap,
                                  List<Map<String, String>> errors) {
        if (sheet == null) return;
        Map<String, Integer> idx = buildHeaderIndex(sheet);

        Map<String, List<PluginHeaderConfig>> pending = new HashMap<>();
        Map<String, Integer> pendingRows = new HashMap<>();

        int lastRow = sheet.getLastRowNum();
        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowBlank(row)) continue;

            String name = readCell(row, idx.get("接口名称"));
            String headerName = readCell(row, idx.get("请求头名称"));
            if ((name == null || name.isEmpty()) && (headerName == null || headerName.isEmpty())) continue;
            if (headerName == null || headerName.isEmpty()) {
                errors.add(Map.of("row", String.valueOf(r + 1), "error", "请求头行缺少『请求头名称』"));
                continue;
            }

            pending.computeIfAbsent(name, k -> new ArrayList<>());
            pendingRows.putIfAbsent(name, r + 1);

            PluginHeaderConfig h = new PluginHeaderConfig();
            h.setName(headerName.trim());
            h.setValue(nullIfEmpty(readCell(row, idx.get("请求头值"))));
            h.setDescription(nullIfEmpty(readCell(row, idx.get("描述"))));
            pending.get(name).add(h);
        }

        // 把 pending 应用到 configMap
        for (Map.Entry<String, List<PluginHeaderConfig>> e : pending.entrySet()) {
            PluginConfig target = configMap.get(e.getKey());
            if (target == null) {
                errors.add(Map.of(
                    "row", String.valueOf(pendingRows.get(e.getKey())),
                    "error", "请求头关联的接口名称 [" + e.getKey() + "] 在『插件基本信息』中不存在"
                ));
                continue;
            }
            if (target.getRequestHeaders() != null && !"[]".equals(target.getRequestHeaders())) {
                errors.add(Map.of("row", String.valueOf(pendingRows.get(e.getKey())),
                        "error", "接口 [" + e.getKey() + "] 同时填写了『请求头JSON』和请求头明细，二选一"));
                continue;
            }
            try {
                target.setRequestHeaders(objectMapper.writeValueAsString(e.getValue()));
            } catch (Exception ex) {
                errors.add(Map.of(
                    "row", String.valueOf(pendingRows.get(e.getKey())),
                    "error", "请求头序列化失败: " + ex.getMessage()
                ));
            }
        }
    }

    private void parseParamSheet(Sheet sheet, Map<String, PluginConfig> configMap,
                                 List<Map<String, String>> errors) {
        if (sheet == null) return;
        Map<String, Integer> idx = buildHeaderIndex(sheet);

        Map<String, List<PluginParamConfig>> pending = new HashMap<>();
        Map<String, Integer> pendingRows = new HashMap<>();

        int lastRow = sheet.getLastRowNum();
        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowBlank(row)) continue;

            String name = readCell(row, idx.get("接口名称"));
            String paramName = readCell(row, idx.get("参数名称"));
            if ((name == null || name.isEmpty()) && (paramName == null || paramName.isEmpty())) continue;
            if (paramName == null || paramName.isEmpty()) {
                errors.add(Map.of("row", String.valueOf(r + 1), "error", "参数行缺少『参数名称』"));
                continue;
            }

            pending.computeIfAbsent(name, k -> new ArrayList<>());
            pendingRows.putIfAbsent(name, r + 1);

            PluginParamConfig p = new PluginParamConfig();
            p.setName(paramName.trim());
            p.setType(defaultIfEmpty(readCell(row, idx.get("参数类型")), "string"));
            p.setPosition(defaultIfEmpty(readCell(row, idx.get("参数位置")), "query"));
            String requiredStr = readCell(row, idx.get("是否必填"));
            // 兼容 "是" 和 "1" 两种写法
            p.setRequired(requiredStr != null && (requiredStr.trim().equals("是") || requiredStr.trim().equals("1")));
            p.setDefaultValue(nullIfEmpty(readCell(row, idx.get("默认值"))));
            p.setSource(nullIfEmpty(readCell(row, idx.get("参数来源"))));
            p.setDescription(nullIfEmpty(readCell(row, idx.get("描述"))));
            pending.get(name).add(p);
        }

        for (Map.Entry<String, List<PluginParamConfig>> e : pending.entrySet()) {
            PluginConfig target = configMap.get(e.getKey());
            if (target == null) {
                errors.add(Map.of(
                    "row", String.valueOf(pendingRows.get(e.getKey())),
                    "error", "请求参数关联的接口名称 [" + e.getKey() + "] 在『插件基本信息』中不存在"
                ));
                continue;
            }
            if (target.getRequestParams() != null && !"[]".equals(target.getRequestParams())) {
                errors.add(Map.of("row", String.valueOf(pendingRows.get(e.getKey())),
                        "error", "接口 [" + e.getKey() + "] 同时填写了『请求参数JSON』和请求参数明细，二选一"));
                continue;
            }
            try {
                target.setRequestParams(objectMapper.writeValueAsString(e.getValue()));
            } catch (Exception ex) {
                errors.add(Map.of(
                    "row", String.valueOf(pendingRows.get(e.getKey())),
                    "error", "请求参数序列化失败: " + ex.getMessage()
                ));
            }
        }
    }

    private void parseResponseSheet(Sheet sheet, Map<String, PluginConfig> configMap,
                                    List<Map<String, String>> errors) {
        if (sheet == null) return;
        Map<String, Integer> idx = buildHeaderIndex(sheet);

        Map<String, List<PluginResponseParamConfig>> pending = new HashMap<>();
        Map<String, Integer> pendingRows = new HashMap<>();

        int lastRow = sheet.getLastRowNum();
        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowBlank(row)) continue;

            String name = readCell(row, idx.get("接口名称"));
            String fieldName = readCell(row, idx.get("响应字段名"));
            if ((name == null || name.isEmpty()) && (fieldName == null || fieldName.isEmpty())) continue;
            if (fieldName == null || fieldName.isEmpty()) {
                errors.add(Map.of("row", String.valueOf(r + 1), "error", "响应字段行缺少『响应字段名』"));
                continue;
            }

            pending.computeIfAbsent(name, k -> new ArrayList<>());
            pendingRows.putIfAbsent(name, r + 1);

            PluginResponseParamConfig resp = new PluginResponseParamConfig();
            resp.setName(fieldName.trim());
            resp.setType(defaultIfEmpty(readCell(row, idx.get("响应字段类型")), "string"));
            resp.setPath(nullIfEmpty(readCell(row, idx.get("JSONPath"))));
            resp.setDescription(nullIfEmpty(readCell(row, idx.get("描述"))));
            pending.get(name).add(resp);
        }

        for (Map.Entry<String, List<PluginResponseParamConfig>> e : pending.entrySet()) {
            PluginConfig target = configMap.get(e.getKey());
            if (target == null) {
                errors.add(Map.of(
                    "row", String.valueOf(pendingRows.get(e.getKey())),
                    "error", "响应字段关联的接口名称 [" + e.getKey() + "] 在『插件基本信息』中不存在"
                ));
                continue;
            }
            try {
                target.setResponseParams(objectMapper.writeValueAsString(e.getValue()));
            } catch (Exception ex) {
                errors.add(Map.of(
                    "row", String.valueOf(pendingRows.get(e.getKey())),
                    "error", "响应字段序列化失败: " + ex.getMessage()
                ));
            }
        }
    }

    // ==================== 通用工具 ====================

    /**
     * 从 sheet 第 0 行构建表头 → 列索引映射。
     */
    private Map<String, Integer> buildHeaderIndex(Sheet sheet) {
        Map<String, Integer> idx = new HashMap<>();
        Row header = sheet.getRow(0);
        if (header == null) return idx;
        short last = header.getLastCellNum();
        for (int i = 0; i < last; i++) {
            Cell cell = header.getCell(i);
            if (cell != null && cell.getCellType() == CellType.STRING) {
                String name = cell.getStringCellValue().trim();
                if (!name.isEmpty()) {
                    idx.put(name, i);
                }
            }
        }
        return idx;
    }

    private boolean isRowBlank(Row row) {
        short last = row.getLastCellNum();
        for (int i = 0; i < last; i++) {
            Cell c = row.getCell(i);
            if (c != null && c.getCellType() != CellType.BLANK) {
                String v = readCellAsString(c);
                if (v != null && !v.isEmpty()) return false;
            }
        }
        return true;
    }

    private String readCell(Row row, Integer index) {
        if (index == null) return null;
        Cell cell = row.getCell(index);
        return readCellAsString(cell);
    }

    private boolean isJsonObjectOrArray(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            return node != null && (node.isObject() || node.isArray());
        } catch (Exception e) {
            return false;
        }
    }

    private String readCellAsString(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            double n = cell.getNumericCellValue();
            if (n == Math.floor(n) && !Double.isInfinite(n)) {
                return String.valueOf((long) n);
            }
            return String.valueOf(n);
        }
        if (cell.getCellType() == CellType.STRING) {
            String v = cell.getStringCellValue();
            return v == null ? null : v.trim();
        }
        if (cell.getCellType() == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        if (cell.getCellType() == CellType.FORMULA) {
            // 公式单元格：尝试取缓存值，失败则返回 null
            try {
                return cell.getStringCellValue();
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private String defaultIfEmpty(String s, String def) {
        return (s == null || s.isEmpty()) ? def : s;
    }
}
