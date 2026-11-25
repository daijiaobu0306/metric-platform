package com.metric.collect.service;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Workbook categories that still receive collected files. */
public final class CollectionCategoryScope {
    private CollectionCategoryScope() {}
    public static final List<String> CODES=List.of("external_customer","metric_g2","satisfaction","system_run",
        "metric_g1","resourceInput","dataQuality");

    public static boolean supports(String code) { return CODES.contains(code); }

    static List<Map<String,Object>> options(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
            SELECT c.id,c.category_code AS categoryCode,
              CASE WHEN c.category_code='system_run' THEN '运行效率' ELSE c.category_name END AS categoryName,
              (SELECT COUNT(*) FROM collection_source s WHERE JSON_UNQUOTE(JSON_EXTRACT(s.config_json,'$.worker.categoryCode'))=c.category_code) AS taskCount,
              (SELECT GROUP_CONCAT(s.code ORDER BY s.code) FROM collection_source s
                WHERE c.category_code='metric_g1' AND JSON_UNQUOTE(JSON_EXTRACT(s.config_json,'$.worker.categoryCode'))=c.category_code) AS taskCodes
            FROM metric_category c WHERE c.category_code IN (?,?,?,?,?,?,?) ORDER BY c.id
            """,CODES.toArray());
    }

    // 合规管理允许两条采集任务：数据集不同，不是重复采集同一数据集。
    // compliance=月数据清单（指标值协议），compliance_detail=工单明细（明细行协议）。
    // 其余大类仍然只允许一条任务；同一数据集不得重复建任务。
    private static final Map<String,Set<String>> ALLOWED_TASKS=Map.of("metric_g1",Set.of("compliance","compliance_detail"));

    // Called inside the task-save transaction. The category row serializes competing creates.
    static void requireSingleTask(JdbcTemplate jdbc,CollectionSource source) {
        String code=source.worker().categoryCode();
        if(!supports(code)) throw new IllegalArgumentException("该指标大类未启用文件采集");
        if(jdbc.queryForList("SELECT id FROM metric_category WHERE category_code=? FOR UPDATE",code).size()!=1)
            throw new IllegalArgumentException("指标大类不存在或不唯一");
        var existing=jdbc.queryForList("SELECT code FROM collection_source WHERE JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.categoryCode'))=? AND code<>?",code,source.code());
        if(existing.isEmpty()) return;
        Set<String> allowed=ALLOWED_TASKS.get(code);
        for(var row:existing) {
            String other=String.valueOf(row.get("code"));
            if(allowed==null || !allowed.contains(other) || !allowed.contains(source.code()))
                throw new IllegalArgumentException("该指标大类已有采集任务，请编辑现有任务："+other);
        }
    }
}
