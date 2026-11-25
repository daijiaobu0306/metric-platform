package com.metric.collect.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/** Final catalog mapping. Raw provider fields remain separate from business results. */
public final class ComplaintReportMapping {
    public record Result(JsonNode metric, String scope, BigDecimal value, String unit,
                         String status, String note, String cells) {}
    private ComplaintReportMapping() {}

    public static Result map(ComplaintReportParser.Report report, JsonNode metric) {
        String name = metric.path("name").asText();
        String group = metric.path("groupName").asText();
        if (group.equals("倒三角工单处理流程")) {
            var aggregate = ComplaintReportParser.aggregate(report, name);
            return new Result(metric, "倒三角三类汇总", aggregate.value(), metric.path("unit").asText(),
                aggregate.status(), "按用户确认汇总三个倒三角小类，覆盖清单取数来源的小类限制。" + aggregate.note(), aggregate.cells());
        }
        String type = ComplaintReportService.mappedType(metric);
        var row = report.rows().get(type);
        if (row == null) throw new IllegalArgumentException("报表缺少投诉类型：" + type);
        var source = row.values().get(ComplaintReportParser.normalize(name));
        if (source == null) throw new IllegalArgumentException("清单指标未映射：" + name);
        if (name.equals("及时率") || name.equals("回退率")) {
            var ratio = ComplaintReportParser.ratio(report, java.util.List.of(type),
                name.equals("及时率") ? "及时回复工单数" : "回退量", "回复工单数");
            return new Result(metric, type, ratio.value(), source.unit(), ratio.status(),
                ratio.note() + (type.equals("综调") ? "。按用户确认取综调行，覆盖清单来源中的倒三角文字。" : ""), ratio.cells());
        }
        String note = "原报表值，未重算；报表口径：" + source.rule();
        if (type.equals("综调")) note += "。按用户确认映射综调，清单取数来源中的倒三角文字不用于映射。";
        if (type.equals("在线客服") && name.equals("重投率")) {
            return new Result(metric, type, null, source.unit(), "PENDING_SCOPE",
                "最终清单要求重投≥2次；对端客服按重投≥3次统计，缺满足最终口径的分子，按无数据处理。原报表百分比保留在原始报表。", source.cell());
        }
        return new Result(metric, type, source.number(), source.unit(),
            source.number() == null ? "SOURCE_MISSING" : "SOURCE", note, source.cell());
    }
}
