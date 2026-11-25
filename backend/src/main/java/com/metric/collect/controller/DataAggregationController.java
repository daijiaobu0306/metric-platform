package com.metric.collect.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.audit.entity.MetricAuditLog;
import com.metric.metric.entity.MetricItem;
import com.metric.audit.repository.MetricAuditLogRepository;
import com.metric.metric.repository.MetricItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/data")
public class DataAggregationController {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private MetricAuditLogRepository auditLogRepository;

    @Autowired
    private MetricItemRepository metricItemRepository;
    @Autowired
    private com.metric.metric.service.BusinessValueMetricService businessValueMetrics;

    // subCategoryName(前端) → inner 表 sub_category 值
    private static final Map<String, String> SUBCAT_MAP = Map.of(
        "查询类", "query",
        "办理类-单业务", "crm",
        "办理类-融合业务", "chbn"
    );

    @GetMapping("/categories")
    public ApiResponse<List<Map<String, Object>>> getCategories() {
        List<Map<String, Object>> categories = new ArrayList<>();
        String[] names = {"对外部客户", "对内部人员", "运行效率", "计划执行", "合规管理", "业务价值", "数据质量"};
        for (int i = 0; i < names.length; i++) {
            Map<String, Object> cat = new HashMap<>();
            cat.put("id", i + 1);
            cat.put("name", names[i]);
            cat.put("code", names[i]);
            categories.add(cat);
        }
        return ApiResponse.success(categories);
    }

    @GetMapping("/subcategories")
    public ApiResponse<List<Map<String, Object>>> getSubcategories(@RequestParam String categoryName) {
        List<Map<String, Object>> subcategories = new ArrayList<>();
        if ("对外部客户".equals(categoryName)) {
            String[] subs = {"缴费开机", "流量提醒"};
            for (int i = 0; i < subs.length; i++) {
                Map<String, Object> sub = new HashMap<>();
                sub.put("id", i + 1);
                sub.put("name", subs[i]);
                sub.put("code", subs[i]);
                subcategories.add(sub);
            }
        } else if ("合规管理".equals(categoryName)) {
            for (String name : List.of("月数据清单", "工单明细")) {
                Map<String, Object> sub = new HashMap<>();
                sub.put("id", subcategories.size() + 1);
                sub.put("name", name);
                sub.put("code", name);
                subcategories.add(sub);
            }
        } else if ("对内部人员".equals(categoryName)) {
            String[] subs = {"查询类", "办理类-单业务", "办理类-融合业务"};
            for (int i = 0; i < subs.length; i++) {
                Map<String, Object> sub = new HashMap<>();
                sub.put("id", i + 1);
                sub.put("name", subs[i]);
                sub.put("code", subs[i]);
                subcategories.add(sub);
            }
        } else if ("运行效率".equals(categoryName)) {
            String[] subs = {"系统性能", "系统异常"};
            for (int i = 0; i < subs.length; i++) {
                Map<String, Object> sub = new HashMap<>();
                sub.put("id", i + 1);
                sub.put("name", subs[i]);
                sub.put("code", subs[i]);
                subcategories.add(sub);
            }
        } else if ("业务价值".equals(categoryName)) {
            for (String name : List.of("运营成效", "报表看板"))
                subcategories.add(Map.of("id",subcategories.size()+1,"name",name,"code",name));
        } else if ("计划执行".equals(categoryName)) {
            Map<String, Object> sub = new HashMap<>();
            sub.put("id", 1);
            sub.put("name", "工单执行");
            sub.put("code", "工单执行");
            subcategories.add(sub);
        } else {
            // 兜底：其余大类（如数据质量）按数据库里配置的小类返回，避免每增一个大类都要改代码
            for (var row : jdbcTemplate.queryForList("""
                    SELECT s.sub_category_code, s.sub_category_name
                    FROM metric_sub_category s
                    JOIN metric_category c ON c.id = s.category_id
                    WHERE c.category_name = ? AND (s.status IS NULL OR s.status = 'enabled')
                    ORDER BY s.id""", categoryName)) {
                Map<String, Object> sub = new HashMap<>();
                sub.put("id", subcategories.size() + 1);
                sub.put("name", row.get("sub_category_name"));
                sub.put("code", row.get("sub_category_code"));
                subcategories.add(sub);
            }
        }
        return ApiResponse.success(subcategories);
    }

    /**
     * 获取指标属性列表
     * tableName: system_jhd(默认) | inner_query | inner_step_response | inner_button_count | inner_business_duration
     */
    @GetMapping("/attributes/{subCategoryName}")
    public ApiResponse<List<Map<String, Object>>> getAttributes(
            @PathVariable String subCategoryName,
            @RequestParam(required = false, defaultValue = "system_jhd") String tableName,
            @RequestParam(required = false) String stepType) {

        if ("system_jhd".equals(tableName)) {
            String sql = "SELECT first_classify, second_classify FROM system_jhd " +
                         "WHERE second_classify IS NOT NULL AND second_classify != '' " +
                         "GROUP BY first_classify, second_classify ORDER BY first_classify, MAX(id) DESC";
            return queryGroupedAttrs(sql);
        }
        if ("inner_query".equals(tableName)) {
            String sql = "SELECT business_name AS first_classify, step_name AS second_classify " +
                         "FROM inner_query WHERE step_name IS NOT NULL AND step_name != '' " +
                         "GROUP BY business_name, step_name ORDER BY business_name, MAX(id)";
            return queryGroupedAttrs(sql);
        }
        if ("inner_step_response".equals(tableName)) {
            String innerCat = SUBCAT_MAP.getOrDefault(subCategoryName, "");
            String sql;
            if (stepType != null && !stepType.isEmpty()) {
                sql = "SELECT business_name, step_name FROM inner_step_response " +
                      "WHERE sub_category = ? AND step_type = ? AND step_name IS NOT NULL AND step_name != '' " +
                      "GROUP BY business_name, step_name ORDER BY business_name, MAX(id)";
                return queryGroupedAttrsWithTwoParams(sql, innerCat, stepType);
            } else {
                sql = "SELECT business_name, step_name FROM inner_step_response " +
                      "WHERE sub_category = ? AND step_name IS NOT NULL AND step_name != '' " +
                      "GROUP BY business_name, step_name ORDER BY business_name, MAX(id)";
                return queryGroupedAttrsWithParam(sql, innerCat);
            }
        }
        if ("inner_button_count".equals(tableName)) {
            String innerCat = SUBCAT_MAP.getOrDefault(subCategoryName, "");
            String sql = "SELECT business_name AS first_classify, business_name AS second_classify " +
                         "FROM inner_button_count WHERE sub_category = ? " +
                         "GROUP BY business_name ORDER BY business_name";
            return queryGroupedAttrsWithParam(sql, innerCat);
        }
        if ("system_run_summary".equals(tableName) || "system_run_detail".equals(tableName)) {
            String sql = "SELECT metric_attr AS first_classify, metric_name AS second_classify " +
                         "FROM metric_system_run_summary " +
                         "WHERE metric_attr IS NOT NULL AND metric_attr != '' " +
                         "GROUP BY metric_attr, metric_name ORDER BY metric_attr, MAX(id)";
            return queryGroupedAttrs(sql);
        }
        // 系统稽核-工单明细
        if ("system_jhd_detail".equals(tableName)) {
            String sql = "SELECT first_level_category AS first_classify, second_level_category AS second_classify " +
                         "FROM system_jhd_detail " +
                         "WHERE second_level_category IS NOT NULL AND second_level_category != '' " +
                         "GROUP BY first_level_category, second_level_category ORDER BY first_level_category, MAX(id) DESC";
            return queryGroupedAttrs(sql);
        }
        return ApiResponse.success(new ArrayList<>());
    }

    /**
     * 查询数据明细
     * tableName: system_jhd(默认) | inner_query | inner_step_response | inner_button_count | inner_business_duration
     * 如果传了 metricId，则根据 指标数据来源 自动派发到正确的表（不依赖前端推断）
     */
    @GetMapping("/detail")
    public ApiResponse<Map<String, Object>> getDetail(
            @RequestParam(required = false) Long metricId,
            @RequestParam(required = false) String categoryName,
            @RequestParam(required = false) String subCategoryName,
            @RequestParam(required = false) String secondClassify,
            @RequestParam(required = false) String checkCode,
            @RequestParam(required = false) String checkName,
            @RequestParam(required = false) String businessOwner,
            @RequestParam(required = false) String checkFreq,
            @RequestParam(required = false) String abnormalDate,
            @RequestParam(required = false, defaultValue = "system_jhd") String tableName,
            @RequestParam(required = false) String stepType,
            @RequestParam(required = false) String dataTime,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String firstCategory,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // 如果传了 metricId，按指标数据来源派发到正确的表（优先级高于 tableName 参数）
        if (metricId != null) {
            Optional<MetricItem> itemOpt = metricItemRepository.findById(metricId);
            if (itemOpt.isPresent() && com.metric.metric.service.BusinessValueMetricService.supports(itemOpt.get())) {
                var content=businessValueMetrics.raw(metricId,page,size);
                return ApiResponse.success(Map.of("metricId",metricId,"metricName",itemOpt.get().getMetricName(),
                    "tableName","collection_row","content",content,"total",businessValueMetrics.rawCount(metricId)));
            }
            if (itemOpt.isPresent() && getRawDataTable(itemOpt.get().getDataSource()) != null) {
                String resolvedTable = getRawDataTable(itemOpt.get().getDataSource());
                // 用指标自己的 subCategoryName 覆盖前端传入的（避免前端推断错）
                if (itemOpt.get().getSubCategoryName() != null) {
                    subCategoryName = itemOpt.get().getSubCategoryName();
                }
                Map<String, Object> r = new HashMap<>();
                r.put("metricId", metricId);
                r.put("metricName", itemOpt.get().getMetricName());
                r.put("tableName", resolvedTable);
                // 派发到对应 query helper
                ApiResponse<Map<String, Object>> dispatched = dispatchToQueryHelper(
                        resolvedTable, subCategoryName, secondClassify, stepType, dataTime,
                        checkCode, checkName, businessOwner, checkFreq, abnormalDate,
                        department, firstCategory, page, size);
                if (dispatched != null) {
                    Map<String, Object> data = dispatched.getData();
                    if (data != null) {
                        r.put("content", data.get("content"));
                        r.put("total", data.get("total"));
                    } else {
                        r.put("content", new ArrayList<>());
                        r.put("total", 0L);
                    }
                    return ApiResponse.success(r);
                }
            }
        }

        if ("system_jhd".equals(tableName)) {
            return querySystemJhd(secondClassify, checkCode, checkName, businessOwner, checkFreq, abnormalDate, page, size);
        }
        if ("inner_query".equals(tableName)) {
            return queryInnerQueryTable(secondClassify, stepType, dataTime, page, size);
        }
        if ("inner_step_response".equals(tableName)) {
            String innerCat = SUBCAT_MAP.getOrDefault(subCategoryName, "");
            return queryInnerStepResponseTable(innerCat, "step_name", "business_name", secondClassify, stepType, dataTime, page, size);
        }
        if ("inner_button_count".equals(tableName)) {
            String innerCat = SUBCAT_MAP.getOrDefault(subCategoryName, "");
            return queryInnerTable("inner_button_count", innerCat, "business_name", null, secondClassify, dataTime, page, size);
        }
        if ("inner_business_duration".equals(tableName)) {
            String innerCat = SUBCAT_MAP.getOrDefault(subCategoryName, "");
            Map<String, Object> r = new HashMap<>();
            r.put("content", queryInnerBusinessDurationData(innerCat, "", page, size));
            return ApiResponse.success(r);
        }
        if ("system_run_summary".equals(tableName)) {
            return querySystemRunSummary(secondClassify, dataTime, page, size);
        }
        if ("system_run_detail".equals(tableName)) {
            return querySystemRunDetail(secondClassify, dataTime, page, size);
        }
        // 系统异常-接口原始数据
        if ("metric_error_interface".equals(tableName)) {
            return queryMetricErrorInterface(secondClassify, dataTime, page, size);
        }
        // 系统异常-实例异常
        if ("metric_error_instance".equals(tableName)) {
            return queryMetricErrorInstance(secondClassify, dataTime, page, size);
        }
        // 系统异常-JS异常
        if ("metric_error_js".equals(tableName)) {
            return queryMetricErrorJs(secondClassify, dataTime, page, size);
        }
        // 系统异常-业务返回异常
        if ("metric_error_biz_return".equals(tableName)) {
            return queryMetricErrorBizReturn(secondClassify, dataTime, page, size);
        }
        // 系统异常-返回码
        if ("metric_error_return_code".equals(tableName)) {
            return queryMetricErrorReturnCode(secondClassify, dataTime, page, size);
        }
        // 系统稽核-工单明细
        if ("system_jhd_detail".equals(tableName)) {
            return querySystemJhdDetail(secondClassify, checkCode, checkName, businessOwner, dataTime, department, firstCategory, page, size);
        }

        Map<String, Object> empty = new HashMap<>();
        empty.put("content", new ArrayList<>());
        empty.put("total", 0L);
        empty.put("page", page);
        empty.put("size", size);
        return ApiResponse.success(empty);
    }

    /**
     * 按已配置的数据来源派发到对应 query helper（仅 metricId 派发用）
     * 返回 null 表示无法识别
     */
    private ApiResponse<Map<String, Object>> dispatchToQueryHelper(
            String tableName, String subCategoryName, String secondClassify, String stepType,
            String dataTime, String checkCode, String checkName, String businessOwner,
            String checkFreq, String abnormalDate, String department, String firstCategory,
            int page, int size) {
        if ("system_jhd".equals(tableName)) {
            return querySystemJhd(secondClassify, checkCode, checkName, businessOwner, checkFreq, abnormalDate, page, size);
        }
        if ("inner_query".equals(tableName)) {
            return queryInnerQueryTable(secondClassify, stepType, dataTime, page, size);
        }
        if ("inner_step_response".equals(tableName)) {
            String innerCat = SUBCAT_MAP.getOrDefault(subCategoryName, "");
            return queryInnerStepResponseTable(innerCat, "step_name", "business_name", secondClassify, stepType, dataTime, page, size);
        }
        if ("inner_button_count".equals(tableName)) {
            String innerCat = SUBCAT_MAP.getOrDefault(subCategoryName, "");
            return queryInnerTable("inner_button_count", innerCat, "business_name", null, secondClassify, dataTime, page, size);
        }
        if ("inner_business_duration".equals(tableName)) {
            String innerCat = SUBCAT_MAP.getOrDefault(subCategoryName, "");
            Map<String, Object> r = new HashMap<>();
            r.put("content", queryInnerBusinessDurationData(innerCat, "", page, size));
            r.put("total", 0L);
            return ApiResponse.success(r);
        }
        if ("system_run_summary".equals(tableName)) {
            return querySystemRunSummary(secondClassify, dataTime, page, size);
        }
        if ("system_run_detail".equals(tableName)) {
            return querySystemRunDetail(secondClassify, dataTime, page, size);
        }
        if ("metric_error_interface".equals(tableName)) {
            return queryMetricErrorInterface(secondClassify, dataTime, page, size);
        }
        if ("metric_error_instance".equals(tableName)) {
            return queryMetricErrorInstance(secondClassify, dataTime, page, size);
        }
        if ("metric_error_js".equals(tableName)) {
            return queryMetricErrorJs(secondClassify, dataTime, page, size);
        }
        if ("metric_error_biz_return".equals(tableName)) {
            return queryMetricErrorBizReturn(secondClassify, dataTime, page, size);
        }
        if ("metric_error_return_code".equals(tableName)) {
            return queryMetricErrorReturnCode(secondClassify, dataTime, page, size);
        }
        if ("system_jhd_detail".equals(tableName)) {
            return querySystemJhdDetail(secondClassify, checkCode, checkName, businessOwner, dataTime, department, firstCategory, page, size);
        }
        return null;
    }

    /**
     * 指标下钻查询 - 根据指标ID跳转到数据明细
     * 用 指标数据来源 派发到正确的表（不再用 metricName 字符串推断）
     * 返回结构：{ metricId, metricName, subCategoryName, tableName, content, rowCount, reason }
     */
    @GetMapping("/drilldown")
    public ApiResponse<Map<String, Object>> drilldown(
            @RequestParam Long metricId,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metricId", metricId);

        // 1. 根据metricId获取指标配置
        Optional<MetricItem> metricOpt = metricItemRepository.findById(metricId);
        if (metricOpt.isEmpty()) {
            result.put("content", new ArrayList<>());
            result.put("rowCount", 0);
            result.put("reason", "指标不存在 metricId=" + metricId);
            return ApiResponse.success(result);
        }

        MetricItem metric = metricOpt.get();
        if (com.metric.metric.service.BusinessValueMetricService.supports(metric)) {
            var content=businessValueMetrics.raw(metricId,page,size);
            result.put("metricName",metric.getMetricName()); result.put("subCategoryName",metric.getSubCategoryName());
            result.put("tableName","collection_row");
            result.put("content",content); result.put("rowCount",businessValueMetrics.rawCount(metricId));
            return ApiResponse.success(result);
        }
        String subCategoryName = metric.getSubCategoryName();
        String metricName = metric.getMetricName();
        // 优先用指标数据来源（这是配置时设定的），其次用 subCategoryName 派发
        String tableName = getRawDataTable(metric.getDataSource());
        if (tableName == null || tableName.isEmpty()) {
            tableName = getTableNameBySubCategory(subCategoryName);
        }

        result.put("metricName", metricName);
        result.put("subCategoryName", subCategoryName);
        result.put("tableName", tableName);

        // 2. 按 tableName 派发到对应 query helper
        List<Map<String, Object>> content = new ArrayList<>();
        if ("inner_query".equals(tableName)) {
            content = queryInnerQueryData(null, null, page, size);
        } else if ("inner_step_response".equals(tableName)) {
            String innerCat = SUBCAT_MAP.getOrDefault(subCategoryName, "");
            content = queryInnerStepResponseData(innerCat, null, null, page, size);
        } else if ("inner_button_count".equals(tableName)) {
            String innerCat = SUBCAT_MAP.getOrDefault(subCategoryName, "");
            content = queryInnerButtonCountData(innerCat, null, page, size);
        } else if ("inner_business_duration".equals(tableName)) {
            content = queryInnerBusinessDurationData("", "", page, size);
        } else {
            result.put("content", content);
            result.put("rowCount", 0);
            result.put("reason", "该指标配置的表 " + tableName + " 暂不支持 drill-down（仅 inner_* 4 表支持）");
            return ApiResponse.success(result);
        }
        result.put("content", content);
        result.put("rowCount", content.size());
        if (content.isEmpty()) {
            result.put("reason", "原始表 " + tableName + " 当前无数据（未导入或表为空）");
        }
        return ApiResponse.success(result);
    }

    /**
     * 根据 subCategoryName 派发到 inner_* 表名（兜底逻辑）
     */
    private String getRawDataTable(String dataSource) {
        if (dataSource == null) return null;
        return switch (dataSource) {
            case "system_jhd", "system_jhd_detail", "system_run_summary", "system_run_detail",
                 "inner_query", "inner_step_response", "inner_button_count", "inner_business_duration",
                 "metric_error_interface", "metric_error_instance", "metric_error_js",
                 "metric_error_biz_return", "metric_error_return_code" -> dataSource;
            default -> null;
        };
    }

    private String getTableNameBySubCategory(String subCategoryName) {
        if (subCategoryName == null) return null;
        if (subCategoryName.contains("查询类")) return "inner_query";
        if (subCategoryName.contains("办理类")) return "inner_step_response";
        return null;
    }

    /**
     * 根据指标小类和指标名称确定原始数据表名
     * @deprecated 已废弃，由 drilldown 用 指标数据来源 派发
     */
    @Deprecated
    private String getTableNameForMetric(String subCategoryName, String metricName) {
        if ("查询类".equals(subCategoryName)) {
            return "inner_query";
        }
        String lowerName = metricName.toLowerCase();
        if (lowerName.contains("页面加载") || lowerName.contains("步骤响应") || lowerName.contains("步骤间隔")) {
            return "inner_step_response";
        }
        if (lowerName.contains("按钮")) {
            return "inner_button_count";
        }
        if (lowerName.contains("全程")) {
            return "inner_business_duration";
        }
        return "inner_step_response"; // 默认
    }

    /**
     * 从指标名称中提取过滤值（业务名）
     * @deprecated 已废弃
     */
    @Deprecated
    private String extractFilterValue(String metricName) {
        // 指标名称格式如 "页面加载时长" "查询时长" 等
        // 对于按钮和全程类，需要按业务名过滤
        return null; // 让查询返回该小类下所有业务数据
    }

    /**
     * 从指标名称中提取stepType
     * @deprecated 已废弃
     */
    @Deprecated
    private String extractStepType(String metricName) {
        String lowerName = metricName.toLowerCase();
        if (lowerName.contains("页面加载")) {
            return "页面加载";
        }
        if (lowerName.contains("查询时长") || lowerName.contains("query")) {
            return "查询时长";
        }
        if (lowerName.contains("步骤响应")) {
            return "步骤时长";
        }
        if (lowerName.contains("步骤间隔")) {
            return "步骤时长";
        }
        return null;
    }

    private List<Map<String, Object>> queryInnerQueryData(String secondClassify, String stepType, int page, int size) {
        List<Map<String, Object>> dataList = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            StringBuilder sql = new StringBuilder("SELECT * FROM inner_query WHERE 1=1");
            List<Object> params = new ArrayList<>();

            if (stepType != null && !stepType.isEmpty()) {
                sql.append(" AND step_type = ?");
                params.add(stepType);
            }
            sql.append(" ORDER BY insert_time DESC, id LIMIT ? OFFSET ?");
            params.add(size);
            params.add(page * size);

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    dataList.add(row);
                }
            }
        } catch (SQLException e) {
            logError("SQL_ERROR", "drilldown_query", null, null, e.getMessage());
        }
        return dataList;
    }

    private List<Map<String, Object>> queryInnerStepResponseData(String subCat, String filterValue, String stepType, int page, int size) {
        List<Map<String, Object>> dataList = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            StringBuilder sql = new StringBuilder("SELECT * FROM inner_step_response WHERE sub_category = ?");
            List<Object> params = new ArrayList<>();
            params.add(subCat);

            if (stepType != null && !stepType.isEmpty()) {
                sql.append(" AND step_type = ?");
                params.add(stepType);
            }
            sql.append(" ORDER BY insert_time DESC, id LIMIT ? OFFSET ?");
            params.add(size);
            params.add(page * size);

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    dataList.add(row);
                }
            }
        } catch (SQLException e) {
            logError("SQL_ERROR", "drilldown_step", null, null, e.getMessage());
        }
        return dataList;
    }

    private List<Map<String, Object>> queryInnerButtonCountData(String subCat, String filterValue, int page, int size) {
        List<Map<String, Object>> dataList = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT * FROM inner_button_count WHERE sub_category = ? ORDER BY insert_time DESC, id LIMIT ? OFFSET ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, subCat);
                ps.setInt(2, size);
                ps.setInt(3, page * size);
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    dataList.add(row);
                }
            }
        } catch (SQLException e) {
            logError("SQL_ERROR", "drilldown_button", null, null, e.getMessage());
        }
        return dataList;
    }

    private List<Map<String, Object>> queryInnerBusinessDurationData(String subCat, String filterValue, int page, int size) {
        List<Map<String, Object>> dataList = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT * FROM inner_business_duration WHERE 1=1";
            List<Object> params = new ArrayList<>();
            if ("crm".equals(subCat)) {
                sql += " AND business_name NOT LIKE '%chbn%'";
            } else if ("chbn".equals(subCat)) {
                sql += " AND business_name LIKE '%chbn%'";
            }
            sql += " ORDER BY insert_time DESC, id LIMIT ? OFFSET ?";
            params.add(size);
            params.add(page * size);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    dataList.add(row);
                }
            }
        } catch (SQLException e) {
            logError("SQL_ERROR", "drilldown_duration", null, null, e.getMessage());
        }
        return dataList;
    }

    // ======================== 私有方法 ========================

    private ApiResponse<Map<String, Object>> querySystemJhd(
            String secondClassify, String checkCode, String checkName,
            String businessOwner, String checkFreq, String abnormalDate, int page, int size) {

        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM system_jhd WHERE 1=1");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM system_jhd WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (secondClassify != null && !secondClassify.isEmpty()) {
            if (secondClassify.contains(",")) {
                String[] parts = secondClassify.split(",");
                countSql.append(" AND second_classify IN (");
                dataSql.append(" AND second_classify IN (");
                for (int i = 0; i < parts.length; i++) {
                    countSql.append("?"); dataSql.append("?");
                    params.add(parts[i].trim());
                    if (i < parts.length - 1) { countSql.append(","); dataSql.append(","); }
                }
                countSql.append(")"); dataSql.append(")");
            } else {
                countSql.append(" AND second_classify = ?");
                dataSql.append(" AND second_classify = ?");
                params.add(secondClassify);
            }
        }
        if (checkCode != null && !checkCode.isEmpty()) {
            countSql.append(" AND check_code LIKE ?"); dataSql.append(" AND check_code LIKE ?");
            params.add("%" + checkCode + "%");
        }
        if (checkName != null && !checkName.isEmpty()) {
            countSql.append(" AND check_name LIKE ?"); dataSql.append(" AND check_name LIKE ?");
            params.add("%" + checkName + "%");
        }
        if (businessOwner != null && !businessOwner.isEmpty()) {
            countSql.append(" AND business_owner LIKE ?"); dataSql.append(" AND business_owner LIKE ?");
            params.add("%" + businessOwner + "%");
        }
        if (checkFreq != null && !checkFreq.isEmpty()) {
            countSql.append(" AND check_freq = ?"); dataSql.append(" AND check_freq = ?");
            params.add(checkFreq);
        }
        if (abnormalDate != null && !abnormalDate.isEmpty()) {
            countSql.append(" AND abnormal_date = ?"); dataSql.append(" AND abnormal_date = ?");
            params.add(abnormalDate);
        }
        if (secondClassify != null && !secondClassify.isEmpty() && secondClassify.contains(",")) {
            String orderField = "insert_time DESC, first_classify,FIELD(second_classify,";
            String[] parts = secondClassify.split(",");
            for (int i = 0; i < parts.length; i++) {
                orderField += "'" + parts[i].trim() + "'";
                if (i < parts.length - 1) orderField += ",";
            }
            orderField += "),id DESC";
            dataSql.append(" ORDER BY ").append(orderField).append(" LIMIT ? OFFSET ?");
        } else {
            dataSql.append(" ORDER BY insert_time DESC, first_classify, id DESC LIMIT ? OFFSET ?");
        }
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    private ApiResponse<Map<String, Object>> queryInnerTable(
            String tableName, String subCatValue, String filterField, String groupField,
            String secondClassify, String dataTime, int page, int size) {

        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM " + tableName + " WHERE sub_category = ?");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM " + tableName + " WHERE sub_category = ?");
        List<Object> params = new ArrayList<>();
        params.add(subCatValue);

        // 添加 dataTime 过滤（最新批次）
        if (dataTime != null && !dataTime.isEmpty()) {
            countSql.append(" AND DATE(insert_time) = DATE(?)");
            dataSql.append(" AND DATE(insert_time) = DATE(?)");
            params.add(dataTime);
        }

        if (filterField != null && secondClassify != null && !secondClassify.isEmpty()) {
            if (secondClassify.contains(",")) {
                String[] parts = secondClassify.split(",");
                countSql.append(" AND ").append(filterField).append(" IN (");
                dataSql.append(" AND ").append(filterField).append(" IN (");
                for (int i = 0; i < parts.length; i++) {
                    countSql.append("?"); dataSql.append("?");
                    params.add(parts[i].trim());
                    if (i < parts.length - 1) { countSql.append(","); dataSql.append(","); }
                }
                countSql.append(")"); dataSql.append(")");
            } else {
                countSql.append(" AND ").append(filterField).append(" = ?");
                dataSql.append(" AND ").append(filterField).append(" = ?");
                params.add(secondClassify);
            }
        }
        if (groupField != null) {
            dataSql.append(" ORDER BY insert_time DESC, ").append(groupField).append(" LIMIT ? OFFSET ?");
        } else {
            dataSql.append(" ORDER BY insert_time DESC, id LIMIT ? OFFSET ?");
        }
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    private ApiResponse<Map<String, Object>> queryInnerQueryTable(String secondClassify, String stepType, String dataTime, int page, int size) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM inner_query WHERE 1=1");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM inner_query WHERE 1=1");
        List<Object> params = new ArrayList<>();

        // 添加 dataTime 过滤（最新批次）
        if (dataTime != null && !dataTime.isEmpty()) {
            countSql.append(" AND DATE(insert_time) = DATE(?)");
            dataSql.append(" AND DATE(insert_time) = DATE(?)");
            params.add(dataTime);
        }

        // 添加 stepType 过滤（支持逗号分隔的多值）
        if (stepType != null && !stepType.isEmpty()) {
            if (stepType.contains(",")) {
                String[] parts = stepType.split(",");
                countSql.append(" AND step_type IN (");
                dataSql.append(" AND step_type IN (");
                for (int i = 0; i < parts.length; i++) {
                    countSql.append("?"); dataSql.append("?");
                    params.add(parts[i].trim());
                    if (i < parts.length - 1) { countSql.append(","); dataSql.append(","); }
                }
                countSql.append(")"); dataSql.append(")");
            } else {
                countSql.append(" AND step_type = ?");
                dataSql.append(" AND step_type = ?");
                params.add(stepType);
            }
        }

        if (secondClassify != null && !secondClassify.isEmpty()) {
            if (secondClassify.contains(",")) {
                String[] parts = secondClassify.split(",");
                countSql.append(" AND (business_name, step_name) IN (");
                dataSql.append(" AND (business_name, step_name) IN (");
                for (int i = 0; i < parts.length; i++) {
                    String[] nameStep = parts[i].trim().split("\\|\\|");
                    if (nameStep.length == 2) {
                        if (i > 0) { countSql.append(","); dataSql.append(","); }
                        countSql.append("(?, ?)"); dataSql.append("(?, ?)");
                        params.add(nameStep[0]); params.add(nameStep[1]);
                    }
                }
                countSql.append(")"); dataSql.append(")");
            } else {
                String[] nameStep = secondClassify.split("\\|\\|");
                if (nameStep.length == 2) {
                    countSql.append(" AND business_name = ? AND step_name = ?");
                    dataSql.append(" AND business_name = ? AND step_name = ?");
                    params.add(nameStep[0]); params.add(nameStep[1]);
                }
            }
        }
        dataSql.append(" ORDER BY insert_time DESC, id LIMIT ? OFFSET ?");
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    private ApiResponse<Map<String, Object>> queryInnerStepResponseTable(
            String subCatValue, String filterField, String groupField,
            String secondClassify, String stepType, String dataTime, int page, int size) {

        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM inner_step_response WHERE sub_category = ?");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM inner_step_response WHERE sub_category = ?");
        List<Object> params = new ArrayList<>();
        params.add(subCatValue);

        // 添加 dataTime 过滤（最新批次）
        if (dataTime != null && !dataTime.isEmpty()) {
            countSql.append(" AND DATE(insert_time) = DATE(?)");
            dataSql.append(" AND DATE(insert_time) = DATE(?)");
            params.add(dataTime);
        }

        // 添加 stepType 过滤（支持逗号分隔的多值）
        if (stepType != null && !stepType.isEmpty()) {
            if (stepType.contains(",")) {
                String[] parts = stepType.split(",");
                countSql.append(" AND step_type IN (");
                dataSql.append(" AND step_type IN (");
                for (int i = 0; i < parts.length; i++) {
                    countSql.append("?"); dataSql.append("?");
                    params.add(parts[i].trim());
                    if (i < parts.length - 1) { countSql.append(","); dataSql.append(","); }
                }
                countSql.append(")"); dataSql.append(")");
            } else {
                countSql.append(" AND step_type = ?");
                dataSql.append(" AND step_type = ?");
                params.add(stepType);
            }
        }

        if (filterField != null && secondClassify != null && !secondClassify.isEmpty()) {
            if (secondClassify.contains(",")) {
                String[] parts = secondClassify.split(",");
                countSql.append(" AND ").append(filterField).append(" IN (");
                dataSql.append(" AND ").append(filterField).append(" IN (");
                for (int i = 0; i < parts.length; i++) {
                    countSql.append("?"); dataSql.append("?");
                    params.add(parts[i].trim());
                    if (i < parts.length - 1) { countSql.append(","); dataSql.append(","); }
                }
                countSql.append(")"); dataSql.append(")");
            } else {
                countSql.append(" AND ").append(filterField).append(" = ?");
                dataSql.append(" AND ").append(filterField).append(" = ?");
                params.add(secondClassify);
            }
        }
        if (groupField != null) {
            dataSql.append(" ORDER BY insert_time DESC, ").append(groupField).append(" LIMIT ? OFFSET ?");
        } else {
            dataSql.append(" ORDER BY insert_time DESC, id LIMIT ? OFFSET ?");
        }
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    // 查询系统运行汇总指标
    private ApiResponse<Map<String, Object>> querySystemRunSummary(String metricNames, String dataTime, int page, int size) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM metric_system_run_summary WHERE 1=1");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM metric_system_run_summary WHERE 1=1");
        List<Object> params = new ArrayList<>();

        // 按 metric_name 过滤
        if (metricNames != null && !metricNames.isEmpty()) {
            if (metricNames.contains(",")) {
                String[] parts = metricNames.split(",");
                countSql.append(" AND metric_name IN (");
                dataSql.append(" AND metric_name IN (");
                for (int i = 0; i < parts.length; i++) {
                    countSql.append("?"); dataSql.append("?");
                    params.add(parts[i].trim());
                    if (i < parts.length - 1) { countSql.append(","); dataSql.append(","); }
                }
                countSql.append(")"); dataSql.append(")");
            } else {
                countSql.append(" AND metric_name = ?");
                dataSql.append(" AND metric_name = ?");
                params.add(metricNames);
            }
        }

        // 按 dataTime 过滤最新批次
        if (dataTime != null && !dataTime.isEmpty()) {
            countSql.append(" AND DATE(insert_time) = DATE(?)");
            dataSql.append(" AND DATE(insert_time) = DATE(?)");
            params.add(dataTime);
        }

        dataSql.append(" ORDER BY insert_time DESC, metric_attr, metric_name, id LIMIT ? OFFSET ?");
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    // 查询系统运行明细指标
    private ApiResponse<Map<String, Object>> querySystemRunDetail(String metricNames, String dataTime, int page, int size) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM metric_system_run_detail WHERE 1=1");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM metric_system_run_detail WHERE 1=1");
        List<Object> params = new ArrayList<>();

        // 按 metric_name 过滤（明细指标名称，如"加载时长大于3秒的页面数"、"响应时长大于3秒的请求数"）
        if (metricNames != null && !metricNames.isEmpty()) {
            if (metricNames.contains(",")) {
                String[] parts = metricNames.split(",");
                countSql.append(" AND metric_name IN (");
                dataSql.append(" AND metric_name IN (");
                for (int i = 0; i < parts.length; i++) {
                    countSql.append("?"); dataSql.append("?");
                    params.add(parts[i].trim());
                    if (i < parts.length - 1) { countSql.append(","); dataSql.append(","); }
                }
                countSql.append(")"); dataSql.append(")");
            } else {
                countSql.append(" AND metric_name = ?");
                dataSql.append(" AND metric_name = ?");
                params.add(metricNames);
            }
        }

        // 按 dataTime 过滤最新批次
        if (dataTime != null && !dataTime.isEmpty()) {
            countSql.append(" AND DATE(insert_time) = DATE(?)");
            dataSql.append(" AND DATE(insert_time) = DATE(?)");
            params.add(dataTime);
        }

        dataSql.append(" ORDER BY insert_time DESC, metric_attr, metric_name, object_name, id LIMIT ? OFFSET ?");
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    // 查询系统异常-接口原始数据
    private ApiResponse<Map<String, Object>> queryMetricErrorInterface(String filterField, String dataTime, int page, int size) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM metric_error_interface WHERE 1=1");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM metric_error_interface WHERE 1=1");
        List<Object> params = new ArrayList<>();

        // 按字段过滤（接口URL或成功率等）
        if (filterField != null && !filterField.isEmpty()) {
            if (filterField.contains(",")) {
                String[] parts = filterField.split(",");
                countSql.append(" AND (interface_url IN (");
                dataSql.append(" AND (interface_url IN (");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) {
                        countSql.append(",");
                        dataSql.append(",");
                    }
                    countSql.append("?");
                    dataSql.append("?");
                    params.add(parts[i]);
                }
                countSql.append(") OR success_rate IN (");
                dataSql.append(") OR success_rate IN (");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) {
                        countSql.append(",");
                        dataSql.append(",");
                    }
                    countSql.append("?");
                    dataSql.append("?");
                    params.add(parts[i]);
                }
                countSql.append("))");
                dataSql.append("))");
            } else {
                countSql.append(" AND (interface_url LIKE ? OR success_rate LIKE ?)");
                dataSql.append(" AND (interface_url LIKE ? OR success_rate LIKE ?)");
                params.add("%" + filterField + "%");
                params.add("%" + filterField + "%");
            }
        }

        // 按 dataTime 过滤最新批次
        if (dataTime != null && !dataTime.isEmpty()) {
            countSql.append(" AND DATE(insert_time) = DATE(?)");
            dataSql.append(" AND DATE(insert_time) = DATE(?)");
            params.add(dataTime);
        }

        dataSql.append(" ORDER BY insert_time DESC, id LIMIT ? OFFSET ?");
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    // 查询系统异常-实例异常
    private ApiResponse<Map<String, Object>> queryMetricErrorInstance(String filterField, String dataTime, int page, int size) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM metric_error_instance WHERE 1=1");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM metric_error_instance WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (filterField != null && !filterField.isEmpty()) {
            if (filterField.contains(",")) {
                String[] parts = filterField.split(",");
                countSql.append(" AND (app_name IN (");
                dataSql.append(" AND (app_name IN (");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) {
                        countSql.append(",");
                        dataSql.append(",");
                    }
                    countSql.append("?");
                    dataSql.append("?");
                    params.add(parts[i]);
                }
                countSql.append(") OR error_name IN (");
                dataSql.append(") OR error_name IN (");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) {
                        countSql.append(",");
                        dataSql.append(",");
                    }
                    countSql.append("?");
                    dataSql.append("?");
                    params.add(parts[i]);
                }
                countSql.append("))");
                dataSql.append("))");
            } else {
                countSql.append(" AND (app_name LIKE ? OR error_name LIKE ?)");
                dataSql.append(" AND (app_name LIKE ? OR error_name LIKE ?)");
                params.add("%" + filterField + "%");
                params.add("%" + filterField + "%");
            }
        }

        if (dataTime != null && !dataTime.isEmpty()) {
            countSql.append(" AND DATE(insert_time) = DATE(?)");
            dataSql.append(" AND DATE(insert_time) = DATE(?)");
            params.add(dataTime);
        }

        dataSql.append(" ORDER BY insert_time DESC, id LIMIT ? OFFSET ?");
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    // 查询系统异常-JS异常
    private ApiResponse<Map<String, Object>> queryMetricErrorJs(String filterField, String dataTime, int page, int size) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM metric_error_js WHERE 1=1");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM metric_error_js WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (filterField != null && !filterField.isEmpty()) {
            countSql.append(" AND error_name LIKE ?");
            dataSql.append(" AND error_name LIKE ?");
            params.add("%" + filterField + "%");
        }

        if (dataTime != null && !dataTime.isEmpty()) {
            countSql.append(" AND DATE(insert_time) = DATE(?)");
            dataSql.append(" AND DATE(insert_time) = DATE(?)");
            params.add(dataTime);
        }

        dataSql.append(" ORDER BY insert_time DESC, id LIMIT ? OFFSET ?");
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    // 查询系统异常-业务返回异常
    private ApiResponse<Map<String, Object>> queryMetricErrorBizReturn(String filterField, String dataTime, int page, int size) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM metric_error_biz_return WHERE 1=1");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM metric_error_biz_return WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (filterField != null && !filterField.isEmpty()) {
            if (filterField.contains(",")) {
                String[] parts = filterField.split(",");
                countSql.append(" AND (system_name IN (");
                dataSql.append(" AND (system_name IN (");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) {
                        countSql.append(",");
                        dataSql.append(",");
                    }
                    countSql.append("?");
                    dataSql.append("?");
                    params.add(parts[i]);
                }
                countSql.append(") OR return_info IN (");
                dataSql.append(") OR return_info IN (");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) {
                        countSql.append(",");
                        dataSql.append(",");
                    }
                    countSql.append("?");
                    dataSql.append("?");
                    params.add(parts[i]);
                }
                countSql.append("))");
                dataSql.append("))");
            } else {
                countSql.append(" AND (system_name LIKE ? OR return_info LIKE ?)");
                dataSql.append(" AND (system_name LIKE ? OR return_info LIKE ?)");
                params.add("%" + filterField + "%");
                params.add("%" + filterField + "%");
            }
        }

        if (dataTime != null && !dataTime.isEmpty()) {
            countSql.append(" AND DATE(insert_time) = DATE(?)");
            dataSql.append(" AND DATE(insert_time) = DATE(?)");
            params.add(dataTime);
        }

        dataSql.append(" ORDER BY insert_time DESC, id LIMIT ? OFFSET ?");
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    // 查询系统异常-返回码
    private ApiResponse<Map<String, Object>> queryMetricErrorReturnCode(String filterField, String dataTime, int page, int size) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM metric_error_return_code WHERE 1=1");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM metric_error_return_code WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (filterField != null && !filterField.isEmpty()) {
            countSql.append(" AND return_code LIKE ?");
            dataSql.append(" AND return_code LIKE ?");
            params.add("%" + filterField + "%");
        }

        if (dataTime != null && !dataTime.isEmpty()) {
            countSql.append(" AND DATE(insert_time) = DATE(?)");
            dataSql.append(" AND DATE(insert_time) = DATE(?)");
            params.add(dataTime);
        }

        dataSql.append(" ORDER BY insert_time DESC, id LIMIT ? OFFSET ?");
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    // 查询系统稽核-工单明细
    private ApiResponse<Map<String, Object>> querySystemJhdDetail(
            String secondClassify, String checkCode, String checkName,
            String businessOwner, String dataTime, String department, String firstCategory, int page, int size) {

        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM system_jhd_detail WHERE 1=1");
        StringBuilder dataSql = new StringBuilder("SELECT * FROM system_jhd_detail WHERE 1=1");
        List<Object> params = new ArrayList<>();

        // 按二级分类（second_level_category）过滤
        if (secondClassify != null && !secondClassify.isEmpty()) {
            if (secondClassify.contains(",")) {
                String[] parts = secondClassify.split(",");
                countSql.append(" AND second_level_category IN (");
                dataSql.append(" AND second_level_category IN (");
                for (int i = 0; i < parts.length; i++) {
                    countSql.append("?"); dataSql.append("?");
                    params.add(parts[i].trim());
                    if (i < parts.length - 1) { countSql.append(","); dataSql.append(","); }
                }
                countSql.append(")"); dataSql.append(")");
            } else {
                countSql.append(" AND second_level_category = ?");
                dataSql.append(" AND second_level_category = ?");
                params.add(secondClassify);
            }
        }
        // 按稽核点编号（audit_id）过滤
        if (checkCode != null && !checkCode.isEmpty()) {
            countSql.append(" AND audit_id LIKE ?"); dataSql.append(" AND audit_id LIKE ?");
            params.add("%" + checkCode + "%");
        }
        // 按稽核点名称（audit_name）过滤
        if (checkName != null && !checkName.isEmpty()) {
            countSql.append(" AND audit_name LIKE ?"); dataSql.append(" AND audit_name LIKE ?");
            params.add("%" + checkName + "%");
        }
        // 按负责主管（business_supervisor_name）过滤
        if (businessOwner != null && !businessOwner.isEmpty()) {
            countSql.append(" AND business_supervisor_name LIKE ?"); dataSql.append(" AND business_supervisor_name LIKE ?");
            params.add("%" + businessOwner + "%");
        }
        // 按工单月份（rsrv_str1）过滤
        if (dataTime != null && !dataTime.isEmpty()) {
            countSql.append(" AND rsvr_str1 = ?"); dataSql.append(" AND rsvr_str1 = ?");
            params.add(dataTime);
        }
        // 按部门（department）过滤
        if (department != null && !department.isEmpty()) {
            countSql.append(" AND department LIKE ?"); dataSql.append(" AND department LIKE ?");
            params.add("%" + department + "%");
        }
        // 按一级分类（first_level_category）过滤
        if (firstCategory != null && !firstCategory.isEmpty()) {
            countSql.append(" AND first_level_category LIKE ?"); dataSql.append(" AND first_level_category LIKE ?");
            params.add("%" + firstCategory + "%");
        }

        dataSql.append(" ORDER BY insert_time DESC, first_level_category, second_level_category, id DESC LIMIT ? OFFSET ?");
        return executeQuery(countSql.toString(), dataSql.toString(), params, page, size);
    }

    private ApiResponse<Map<String, Object>> executeQuery(
            String countSql, String dataSql, List<Object> params, int page, int size) {
        Map<String, Object> result = new HashMap<>();
        long total = 0;
        List<Map<String, Object>> dataList = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                ResultSet rs = ps.executeQuery();
                if (rs.next()) total = rs.getLong(1);
            }
            List<Object> dataParams = new ArrayList<>(params);
            dataParams.add(size); dataParams.add(page * size);
            try (PreparedStatement ps = conn.prepareStatement(dataSql)) {
                for (int i = 0; i < dataParams.size(); i++) ps.setObject(i + 1, dataParams.get(i));
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    dataList.add(row);
                }
            }
        } catch (SQLException e) {
            logError("SQL_ERROR", "data_aggregation", null, null, e.getMessage());
            result.put("content", dataList);
            result.put("total", total);
            result.put("page", page);
            result.put("size", size);
            result.put("error", "数据库查询失败: " + e.getMessage());
            return ApiResponse.success(result);
        }
        result.put("content", dataList);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    private void logError(String entityType, String operationType, Long entityId, String oldValue, String errorMessage) {
        try {
            MetricAuditLog log = new MetricAuditLog();
            log.setOperationType(operationType);
            log.setEntityType(entityType);
            log.setEntityId(entityId);
            log.setOldValue(oldValue);
            log.setChangeContent(errorMessage);
            log.setOperator("system");
            log.setCreateTime(LocalDateTime.now());
            auditLogRepository.save(log);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private ApiResponse<List<Map<String, Object>>> queryGroupedAttrs(String sql) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            String current = null;
            Map<String, Object> group = null;
            while (rs.next()) {
                String g = rs.getString("first_classify");
                String item = rs.getString("second_classify");
                if (g == null || g.isEmpty()) continue;
                if (!g.equals(current)) {
                    current = g; group = new HashMap<>();
                    group.put("first_classify", g);
                    group.put("second_classifies", new ArrayList<String>());
                    result.add(group);
                }
                if (group != null && item != null && !item.isEmpty()) {
                    ((List<String>) group.get("second_classifies")).add(item);
                }
            }
        } catch (SQLException e) {
            logError("SQL_ERROR", "query_grouped_attrs", null, null, e.getMessage());
            result.clear();
            result.add(createErrorGroup("查询属性失败: " + e.getMessage()));
        }
        return ApiResponse.success(result);
    }

    private Map<String, Object> createErrorGroup(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("first_classify", "错误");
        error.put("second_classifies", new ArrayList<String>());
        error.put("error_message", message);
        return error;
    }

    private ApiResponse<List<Map<String, Object>>> queryGroupedAttrsWithParam(String sql, String param) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            ResultSet rs = ps.executeQuery();
            String current = null;
            Map<String, Object> group = null;
            while (rs.next()) {
                String g = rs.getString(1);
                String item = rs.getString(2);
                if (g == null || g.isEmpty()) continue;
                if (!g.equals(current)) {
                    current = g; group = new HashMap<>();
                    group.put("first_classify", g);
                    group.put("second_classifies", new ArrayList<String>());
                    result.add(group);
                }
                if (group != null && item != null && !item.isEmpty()) {
                    ((List<String>) group.get("second_classifies")).add(item);
                }
            }
        } catch (SQLException e) {
            logError("SQL_ERROR", "query_grouped_attrs_param", null, null, e.getMessage());
            result.clear();
            result.add(createErrorGroup("查询属性失败: " + e.getMessage()));
        }
        return ApiResponse.success(result);
    }

    private ApiResponse<List<Map<String, Object>>> queryGroupedAttrsWithTwoParams(String sql, String param1, String param2) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param1);
            ps.setString(2, param2);
            ResultSet rs = ps.executeQuery();
            String current = null;
            Map<String, Object> group = null;
            while (rs.next()) {
                String g = rs.getString(1);
                String item = rs.getString(2);
                if (g == null || g.isEmpty()) continue;
                if (!g.equals(current)) {
                    current = g; group = new HashMap<>();
                    group.put("first_classify", g);
                    group.put("second_classifies", new ArrayList<String>());
                    result.add(group);
                }
                if (group != null && item != null && !item.isEmpty()) {
                    ((List<String>) group.get("second_classifies")).add(item);
                }
            }
        } catch (SQLException e) {
            logError("SQL_ERROR", "query_grouped_attrs_two_params", null, null, e.getMessage());
            result.clear();
            result.add(createErrorGroup("查询属性失败: " + e.getMessage()));
        }
        return ApiResponse.success(result);
    }
}
