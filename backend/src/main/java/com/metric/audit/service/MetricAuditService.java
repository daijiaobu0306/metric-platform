package com.metric.audit.service;

import com.metric.audit.entity.MetricAuditLog;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.entity.MetricSubCategory;
import com.metric.audit.repository.MetricAuditLogRepository;
import com.metric.metric.repository.MetricItemRepository;
import com.metric.metric.repository.MetricSubCategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class MetricAuditService {

    @Autowired
    private MetricItemRepository metricItemRepository;

    @Autowired
    private MetricAuditLogRepository auditLogRepository;

    @Autowired
    private MetricSubCategoryRepository subCategoryRepository;

    @Autowired
    private javax.sql.DataSource dataSource;

    // 系统稽核相关配置 - 用编码关联代替中文关联
    private static final String JIANDU_SUBCATEGORY_CODE = "jihe";  // subCategoryCode字段值（与metric_sub_category表关联）
    private static final String JIANDU_SUBCATEGORY_NAME = "系统稽核";  // 显示用名称
    private static final Long JIANDU_CATEGORY_ID = 8L;  // 对外部客户大类ID

    /**
     * 同步系统稽核指标
     * 从system_jhd表获取所有稽核数据，比对metric_item表中的系统稽核指标，
     * 如果后者缺少前者中的指标，则新增
     */
    @Transactional
    public Map<String, Object> syncSystemAuditMetrics() {
        Map<String, Object> result = new HashMap<>();
        List<String> addedMetrics = new ArrayList<>();
        int addedCount = 0;
        String errorMessage = null;

        try {
            // 获取system_jhd中的所有稽核指标(check_code)
            Set<String> jhdCodes = getJhdAuditCodes();

            // 获取metric_item中subCategoryCode='系统稽核'的所有指标code（用编码关联）
            Set<String> existingCodes = getMetricItemAuditCodes();

            // 找出需要新增的指标
            for (String code : jhdCodes) {
                if (!existingCodes.contains(code)) {
                    // 从system_jhd获取该指标的详细信息
                    Map<String, String> metricInfo = getJhdMetricInfo(code);
                    if (metricInfo != null) {
                        MetricItem newItem = createMetricItem(metricInfo);
                        MetricItem saved = metricItemRepository.save(newItem);

                        // 记录审计日志
                        MetricAuditLog log = new MetricAuditLog();
                        log.setOperationType("新增");
                        log.setEntityType("metric_item");
                        log.setEntityId(saved.getId());
                        log.setChangeContent("同步新增系统稽核指标: " + code + " - " + metricInfo.get("check_name"));
                        auditLogRepository.save(log);

                        addedMetrics.add(code + " - " + metricInfo.get("check_name"));
                        addedCount++;
                    }
                }
            }

            result.put("addedCount", addedCount);
            result.put("addedMetrics", addedMetrics);
        } catch (Exception e) {
            errorMessage = e.getMessage();
            e.printStackTrace();

            // 记录错误日志
            MetricAuditLog errorLog = new MetricAuditLog();
            errorLog.setOperationType("错误");
            errorLog.setEntityType("metric_sync");
            errorLog.setChangeContent("系统稽核指标同步失败: " + errorMessage);
            auditLogRepository.save(errorLog);

            result.put("error", errorMessage);
        }
        return result;
    }

    private Set<String> getJhdAuditCodes() {
        Set<String> codes = new HashSet<>();
        try {
            java.sql.Connection conn = dataSource.getConnection();
            java.sql.Statement stmt = conn.createStatement();
            java.sql.ResultSet rs = stmt.executeQuery("SELECT DISTINCT check_code FROM system_jhd");
            while (rs.next()) {
                String code = rs.getString("check_code");
                if (code != null && !code.isEmpty()) {
                    codes.add(code);
                }
            }
            rs.close();
            stmt.close();
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return codes;
    }

    private Set<String> getMetricItemAuditCodes() {
        Set<String> codes = new HashSet<>();
        // 用subCategoryCode（编码）关联，而不是subCategoryName（中文名）
        List<MetricItem> items = metricItemRepository.findBySubCategoryCode(JIANDU_SUBCATEGORY_CODE);
        for (MetricItem item : items) {
            codes.add(item.getMetricCode());
        }
        return codes;
    }

    private Map<String, String> getJhdMetricInfo(String code) {
        Map<String, String> info = new HashMap<>();
        try {
            java.sql.Connection conn = dataSource.getConnection();
            java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT check_code, check_name, check_rule, check_freq FROM system_jhd WHERE check_code = ? LIMIT 1");
            ps.setString(1, code);
            java.sql.ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                info.put("check_code", rs.getString("check_code"));
                info.put("check_name", rs.getString("check_name"));
                info.put("check_rule", rs.getString("check_rule"));
                info.put("check_freq", rs.getString("check_freq"));
            }
            rs.close();
            ps.close();
            conn.close();
            return info.isEmpty() ? null : info;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private MetricItem createMetricItem(Map<String, String> info) {
        MetricItem item = new MetricItem();
        item.setCategoryId(JIANDU_CATEGORY_ID);  // 使用常量
        item.setSubCategoryCode(JIANDU_SUBCATEGORY_CODE);  // 用编码关联
        item.setSubCategoryName(JIANDU_SUBCATEGORY_NAME);  // 显示用名称
        item.setMetricCode(info.get("check_code"));
        item.setMetricName(info.get("check_name"));
        item.setMetricDefinition(info.get("check_rule"));
        item.setMetricType("系统稽核");
        // 指标不再承载告警/工单阈值（2026-10-28 解耦）；系统稽核的告警阈值请在告警策略中配置。
        item.setStatus(1);
        item.setDataSource("system_jhd");
        item.setCollectFrequency(info.get("check_freq") != null ? info.get("check_freq") : "日");
        return item;
    }

    /**
     * 获取审计日志列表
     */
    public Page<MetricAuditLog> getAuditLogs(String operationType, Pageable pageable) {
        if (operationType == null || operationType.isEmpty() || "全部".equals(operationType)) {
            return auditLogRepository.findAllByOrderByCreateTimeDesc(pageable);
        }
        return auditLogRepository.findByOperationTypeOrderByCreateTimeDesc(operationType, pageable);
    }

    public void batchDelete(List<Long> ids) {
        auditLogRepository.deleteAllById(ids);
    }
}
