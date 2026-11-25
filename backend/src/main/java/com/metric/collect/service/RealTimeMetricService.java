package com.metric.collect.service;

import com.metric.metric.entity.MetricItem;
import com.metric.alarm.repository.AlarmRepository;
import com.metric.metric.repository.MetricItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;

@Service
public class RealTimeMetricService {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MetricItemRepository metricItemRepository;

    @Autowired
    private AlarmRepository alarmRepository;

    /** 告警阈值唯一来源：告警策略（指标表已不再承载阈值，2026-10-28 解耦） */
    @Autowired
    private com.metric.alarm.service.AlarmStrategyQueryService alarmStrategyQueryService;

    @Autowired
    private com.metric.metric.service.CollectedMetricService collectedMetrics;

    /**
     * 获取实时指标数据 - 业务视角
     * 按业务名称聚合，展示哪些业务超时了、超时多少
     * 用于实时指标页面（业务负责人视角）
     * 如果传了 metricId，则只取该指标的最新值（不再聚合 subcategory 下所有指标）
     */
    public Map<String, Object> getRealtimeMetricsByBusiness(Long metricId, String categoryName, String subCategoryName, String dataDate, int page, int size) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> businessData = new ArrayList<>();

        // 1. 获取指标配置；如果传了 metricId，则只查该指标
        List<MetricItem> metricConfigs;
        if (metricId != null) {
            Optional<MetricItem> opt = metricItemRepository.findById(metricId);
            if (opt.isEmpty()) {
                result.put("content", businessData);
                result.put("total", 0L);
                result.put("message", "未找到指标 metricId=" + metricId);
                return result;
            }
            metricConfigs = new ArrayList<>();
            metricConfigs.add(opt.get());
        } else {
            // 2026-10-28 指标状态=总电闸：已停用指标不再出现在实时明细中
            metricConfigs = metricItemRepository.findBySubCategoryNameAndStatus(subCategoryName, 1);
        }
        if (metricConfigs == null || metricConfigs.isEmpty()) {
            result.put("content", businessData);
            result.put("total", 0L);
            result.put("message", "未找到指标配置，请先在指标管理中配置" + subCategoryName + "的指标");
            return result;
        }

        if (metricId != null && collectedMetrics.supports(metricId)) {
            return Map.of("content",collectedMetrics.raw(metricId,page,size),
                "total",collectedMetrics.rawCount(metricId),"page",page,"size",size,"message","Python采集明细");
        }

        // 2. 获取对应的数据表
        String tableName = getTableNameBySubCategory(subCategoryName);
        if (tableName == null) {
            result.put("content", businessData);
            result.put("total", 0L);
            result.put("message", "未找到对应的数据表");
            return result;
        }

        // 3. 查询原始数据并按业务聚合
        Map<String, Object> queryResult = null;
        try {
            queryResult = queryAndAggregateByBusiness(tableName, subCategoryName, metricConfigs, page, size);
        } catch (SQLException e) {
            result.put("error", "查询失败: " + e.getMessage());
            return result;
        }

        businessData = (List<Map<String, Object>>) queryResult.get("data");
        long totalCount = ((Number) queryResult.get("total")).longValue();

        result.put("content", businessData);
        result.put("total", totalCount);
        result.put("page", page);
        result.put("size", size);
        result.put("message", "查询成功");

        return result;
    }

    /**
     * 根据指标小类获取对应的数据表名
     */
    private String getTableNameBySubCategory(String subCategoryName) {
        if ("查询类".equals(subCategoryName)) {
            return "inner_query";
        } else if ("办理类-单业务".equals(subCategoryName)) {
            return "inner_step_response";
        } else if ("办理类-融合业务".equals(subCategoryName)) {
            return "inner_step_response";
        } else if ("系统稽核".equals(subCategoryName)) {
            return "system_jhd";
        }
        return null;
    }

    /**
     * 查询并按业务聚合
     * 返回包含data和total的Map
     */
    private Map<String, Object> queryAndAggregateByBusiness(
            String tableName, String subCategoryName, List<MetricItem> metricConfigs, int page, int size) throws SQLException {

        Map<String, Object> returnResult = new HashMap<>();

        // 告警阈值来自告警策略：一次装载，避免循环内逐条查库
        com.metric.alarm.service.AlarmStrategyQueryService.MetricThresholdSnapshot thresholdSnapshot =
                alarmStrategyQueryService.loadSnapshot();

        // system_jhd 表使用不同的查询逻辑
        if ("system_jhd".equals(tableName)) {
            return querySystemJhd(tableName, subCategoryName, metricConfigs, page, size);
        }

        // 根据表名确定时长字段名
        String durationColumn = "inner_query".equals(tableName) ? "avg_duration_sec" : "avg_response_sec";

        // 构建基础SQL（不含LIMIT）
        StringBuilder baseSql = new StringBuilder();
        baseSql.append("SELECT business_name, step_name, step_type, ");
        baseSql.append("SUM(access_count) as total_access_count, ");
        baseSql.append("AVG(").append(durationColumn).append(") as avg_duration, ");
        baseSql.append("MAX(").append(durationColumn).append(") as max_duration, ");
        baseSql.append("MIN(").append(durationColumn).append(") as min_duration, ");
        baseSql.append("COUNT(*) as data_count, ");
        baseSql.append("MAX(insert_time) as last_insert_time ");
        baseSql.append("FROM ").append(tableName).append(" WHERE 1=1");

        // 添加 step_type 过滤
        if ("inner_query".equals(tableName)) {
            baseSql.append(" AND step_type IN ('页面加载', '查询时长') ");
        } else if ("inner_step_response".equals(tableName)) {
            // 办理类-单业务、办理类-融合业务：只返回步骤响应数据（页面加载、步骤时长）
            baseSql.append(" AND step_type IN ('页面加载', '步骤时长') ");
            // 添加 sub_category 过滤区分单业务和融合业务
            String subCategoryValue = getSubCategoryValue(subCategoryName);
            if (subCategoryValue != null) {
                baseSql.append(" AND sub_category = '").append(subCategoryValue).append("' ");
            }
        }

        // 按业务名称分组
        baseSql.append(" GROUP BY business_name, step_name, step_type ");

        // 先查总数
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM (");
        countSql.append(baseSql).append(") AS total_count");

        long totalCount = 0;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(countSql.toString())) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    totalCount = rs.getLong(1);
                }
            }
        }
        returnResult.put("total", totalCount);

        // 再查分页数据
        StringBuilder dataSql = new StringBuilder(baseSql);
        dataSql.append(" ORDER BY avg_duration DESC LIMIT ? OFFSET ?");

        List<Map<String, Object>> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(dataSql.toString())) {
            ps.setInt(1, size);
            ps.setInt(2, page * size);

            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                String businessName = rs.getString("business_name");
                String stepName = rs.getString("step_name");
                String stepType = rs.getString("step_type");
                Long totalAccessCount = rs.getLong("total_access_count");
                BigDecimal avgDuration = rs.getBigDecimal("avg_duration");
                BigDecimal maxDuration = rs.getBigDecimal("max_duration");
                int dataCount = rs.getInt("data_count");
                Timestamp lastInsertTime = rs.getTimestamp("last_insert_time");

                // 匹配对应的指标配置
                MetricItem matchedMetric = matchMetric(metricConfigs, stepType, stepName);
                if (matchedMetric == null) continue;

                // 判断是否超过阈值
                BigDecimal threshold = thresholdSnapshot.alarmThreshold(matchedMetric.getId());
                boolean isOverThreshold = avgDuration != null && threshold != null
                        && avgDuration.compareTo(threshold) > 0;

                BigDecimal overAmount = null;
                BigDecimal overPercent = null;
                if (isOverThreshold && threshold.compareTo(BigDecimal.ZERO) > 0) {
                    overAmount = avgDuration.subtract(threshold).setScale(4, RoundingMode.HALF_UP);
                    overPercent = overAmount.multiply(new BigDecimal("100"))
                            .divide(threshold, 2, RoundingMode.HALF_UP);
                }

                Map<String, Object> row = new HashMap<>();
                row.put("businessName", businessName);
                row.put("stepName", stepName);
                row.put("stepType", stepType);
                row.put("totalAccessCount", totalAccessCount);
                row.put("avgDuration", avgDuration != null ? avgDuration.setScale(4, RoundingMode.HALF_UP) : null);
                row.put("maxDuration", maxDuration != null ? maxDuration.setScale(4, RoundingMode.HALF_UP) : null);
                row.put("minDuration", rs.getBigDecimal("min_duration"));
                row.put("dataCount", dataCount);
                row.put("lastInsertTime", lastInsertTime != null ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(lastInsertTime) : "");

                // 指标配置信息
                row.put("metricName", matchedMetric.getMetricName());
                row.put("metricCode", matchedMetric.getMetricCode());
                row.put("alarmThreshold", threshold);
                row.put("unit", matchedMetric.getUnit());
                row.put("metricDefinition", matchedMetric.getMetricDefinition());

                // 告警状态
                row.put("isOverThreshold", isOverThreshold);
                row.put("overAmount", overAmount);
                row.put("overPercent", overPercent);
                row.put("status", isOverThreshold ? "超限" : "正常");

                result.add(row);
            }
        }

        returnResult.put("data", result);
        return returnResult;
    }

    /**
     * 查询 system_jhd 表（对外部客户-系统稽核）
     */
    private Map<String, Object> querySystemJhd(
            String tableName, String subCategoryName, List<MetricItem> metricConfigs, int page, int size) throws SQLException {

        Map<String, Object> returnResult = new HashMap<>();

        // 告警阈值来自告警策略：一次装载，避免循环内逐条查库
        com.metric.alarm.service.AlarmStrategyQueryService.MetricThresholdSnapshot thresholdSnapshot =
                alarmStrategyQueryService.loadSnapshot();

        // 构建基础SQL
        StringBuilder baseSql = new StringBuilder();
        baseSql.append("SELECT first_classify, second_classify, check_code, check_name, ");
        baseSql.append("abnormal_date, abnormal_count, business_owner, current_step, ");
        baseSql.append("insert_time as last_insert_time ");
        baseSql.append("FROM ").append(tableName).append(" WHERE 1=1");

        // 先查总数
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM ");
        countSql.append(tableName).append(" WHERE 1=1");

        long totalCount = 0;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(countSql.toString())) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    totalCount = rs.getLong(1);
                }
            }
        }
        returnResult.put("total", totalCount);

        // 再查分页数据
        StringBuilder dataSql = new StringBuilder(baseSql);
        dataSql.append(" ORDER BY abnormal_count DESC LIMIT ? OFFSET ?");

        List<Map<String, Object>> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(dataSql.toString())) {
            ps.setInt(1, size);
            ps.setInt(2, page * size);

            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                String firstClassify = rs.getString("first_classify");
                String secondClassify = rs.getString("second_classify");
                String checkCode = rs.getString("check_code");
                String checkName = rs.getString("check_name");
                String abnormalDate = rs.getString("abnormal_date");
                String abnormalCount = rs.getString("abnormal_count");
                String businessOwner = rs.getString("business_owner");
                String currentStep = rs.getString("current_step");
                Timestamp lastInsertTime = rs.getTimestamp("last_insert_time");

                // 匹配对应的指标配置
                MetricItem matchedMetric = matchMetricForJhd(metricConfigs, checkCode, checkName);
                if (matchedMetric == null) continue;

                BigDecimal threshold = thresholdSnapshot.alarmThreshold(matchedMetric.getId());
                BigDecimal abnormalCountDec = abnormalCount != null ? new BigDecimal(abnormalCount) : BigDecimal.ZERO;
                boolean isOverThreshold = threshold != null && abnormalCountDec.compareTo(threshold) > 0;

                BigDecimal overAmount = null;
                BigDecimal overPercent = null;
                if (isOverThreshold && threshold.compareTo(BigDecimal.ZERO) > 0) {
                    overAmount = abnormalCountDec.subtract(threshold).setScale(4, RoundingMode.HALF_UP);
                    overPercent = overAmount.multiply(new BigDecimal("100"))
                            .divide(threshold, 2, RoundingMode.HALF_UP);
                }

                Map<String, Object> row = new HashMap<>();
                row.put("businessName", firstClassify + " / " + secondClassify);
                row.put("stepName", checkName);
                row.put("stepType", "系统稽核");
                row.put("totalAccessCount", abnormalCountDec);
                row.put("avgDuration", abnormalCountDec);
                row.put("maxDuration", abnormalCountDec);
                row.put("minDuration", abnormalCountDec);
                row.put("dataCount", 1);
                row.put("lastInsertTime", lastInsertTime != null ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(lastInsertTime) : "");

                // 指标配置信息
                row.put("metricName", matchedMetric.getMetricName());
                row.put("metricCode", matchedMetric.getMetricCode());
                row.put("alarmThreshold", threshold);
                row.put("unit", matchedMetric.getUnit());
                row.put("metricDefinition", matchedMetric.getMetricDefinition());
                row.put("currentStep", currentStep);
                row.put("businessOwner", businessOwner);
                row.put("abnormalDate", abnormalDate);

                // 告警状态
                row.put("isOverThreshold", isOverThreshold);
                row.put("overAmount", overAmount);
                row.put("overPercent", overPercent);
                row.put("status", isOverThreshold ? "超限" : "正常");

                result.add(row);
            }
        }

        returnResult.put("data", result);
        return returnResult;
    }

    /**
     * 匹配对应的指标配置（对外部客户-系统稽核）
     */
    private MetricItem matchMetricForJhd(List<MetricItem> metrics, String checkCode, String checkName) {
        for (MetricItem metric : metrics) {
            String metricCode = metric.getMetricCode();
            if (metricCode != null && metricCode.equals(checkCode)) {
                return metric;
            }
        }
        // 兜底：返回第一个匹配的指标
        return metrics.isEmpty() ? null : metrics.get(0);
    }

    /**
     * 匹配对应的指标配置
     */
    private MetricItem matchMetric(List<MetricItem> metrics, String stepType, String stepName) {
        for (MetricItem metric : metrics) {
            String metricName = metric.getMetricName() != null ? metric.getMetricName().toLowerCase() : "";

            if ("页面加载".equals(stepType) && (metricName.contains("页面加载") || metricName.contains("page"))) {
                return metric;
            }
            if ("查询时长".equals(stepType) && (metricName.contains("查询时长") || metricName.contains("query"))) {
                return metric;
            }
        }
        // 兜底：返回第一个匹配的指标
        return metrics.isEmpty() ? null : metrics.get(0);
    }

    /**
     * 获取子类别在数据库中的值
     */
    private String getSubCategoryValue(String subCategoryName) {
        if ("查询类".equals(subCategoryName)) {
            return "query";
        } else if ("办理类-单业务".equals(subCategoryName)) {
            return "crm";
        } else if ("办理类-融合业务".equals(subCategoryName)) {
            return "chbn";
        }
        return null;
    }

    /**
     * 获取实时告警数据（从 alarm 表读取，用于展示）
     */
    public Map<String, Object> getRealtimeAlerts(String categoryName, String subCategoryName, String dataDate) {
        Map<String, Object> result = new HashMap<>();

        Page<com.metric.alarm.entity.Alarm> alarmPage;
        if (subCategoryName != null && !subCategoryName.isEmpty()) {
            alarmPage = alarmRepository.findByCategoryNameAndSubCategoryName(
                    categoryName, subCategoryName, PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "alarmTime"))
            );
        } else {
            alarmPage = alarmRepository.findByCategoryName(
                    categoryName, PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "alarmTime"))
            );
        }

        List<Map<String, Object>> content = new ArrayList<>();
        for (com.metric.alarm.entity.Alarm alarm : alarmPage.getContent()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", alarm.getId());
            row.put("businessName", alarm.getBusinessName());
            row.put("stepName", alarm.getStepName());
            row.put("accessCount", alarm.getAccessCount());
            row.put("metricValue", alarm.getMetricValue());
            row.put("alarmThreshold", alarm.getThresholdValue());
            row.put("unit", alarm.getUnit());
            row.put("alarmContent", alarm.getAlarmContent());
            row.put("alarmTime", alarm.getAlarmTime());
            row.put("processStatus", alarm.getProcessStatus());
            row.put("sourceTable", alarm.getSourceTable());
            content.add(row);
        }

        result.put("content", content);
        result.put("total", alarmPage.getTotalElements());
        result.put("message", "查询成功");

        return result;
    }

    /**
     * 获取所有实时指标配置
     */
    public List<MetricItem> getMetricConfigs(String categoryName) {
        return metricItemRepository.findByStatus(1);
    }
}