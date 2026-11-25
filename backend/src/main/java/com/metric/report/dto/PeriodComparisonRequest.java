package com.metric.report.dto;

import lombok.Data;

import java.util.List;

/**
 * 多维度环比分析请求。
 *
 * <p>三个维度可分别多选：{@code categoryNames}（大类健康度）、{@code subCategories}
 * （小类健康度，需带大类名避免同名小类串档）、{@code metricIds}（指标值对比）。
 * 统计方法 {@code method} 取 {@code avg}（平均）或 {@code median}（中位数），
 * 作用于单个时段内该对象的全部有效采样点，得到该时段的代表值，再做两时段对比。
 */
@Data
public class PeriodComparisonRequest {

    /** 小类引用：同一个小类名可能出现在不同大类，必须带大类名定位 */
    @Data
    public static class SubCategoryRef {
        private String categoryName;
        private String subCategoryName;
    }

    /** 指标大类名（多选），用于大类健康度环比 */
    private List<String> categoryNames;

    /** 指标小类（多选），用于小类健康度环比 */
    private List<SubCategoryRef> subCategories;

    /** 指标 ID（多选），用于指标值对比 */
    private List<Long> metricIds;

    /** 统计方法：avg=平均 / median=中位数 */
    private String method;

    private String period1Start;
    private String period1End;
    private String period2Start;
    private String period2End;
}
