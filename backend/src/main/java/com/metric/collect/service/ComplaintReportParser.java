package com.metric.collect.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Reads the provider's aggregate report; keeps missing values and source ratios intact. */
public final class ComplaintReportParser {
    public static final String SHEET = "投诉工单效能报表";
    public static final List<String> TYPES = List.of("在线客服", "倒三角-服务咨询",
        "倒三角-绿通(不含个人未完工)", "倒三角-个人未完工", "综调", "升级判责", "挂起流程", "合计");
    public static final List<String> TRIANGLE = TYPES.subList(1, 4);
    public static final List<String> HEADERS = List.of("工单累计量", "工单处理量", "处理时长(小时)",
        "重投量", "重投率", "回退量", "回退率", "及时率", "及时回复工单数", "回复工单数",
        "超时回复工单量", "24小时超时量", "48小时超时量", "72小时超时量", "96小时超时量",
        "120小时超时量", "超时未解决量", "转三线核查量", "时限内解决", "延期解决", "未按时回复",
        "一次解决量", "一次解决率", "催单量", "催单处理时长(小时)", "催单及时率", "满意率", "多渠道重复投诉量");

    public record Value(String raw, BigDecimal number, String unit, String cell, String rule) {}
    public record SourceRow(String type, int row, Map<String, Value> values) {}
    public record Report(Map<String, SourceRow> rows) {}
    public record Aggregate(BigDecimal value, String status, String note, String cells) {}
    private ComplaintReportParser() {}
    public static String normalize(String text) {
        return text.strip().replace('（', '(').replace('）', ')').replaceAll("\\s+", "");
    }

    public static Report parse(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(SHEET);
            if (sheet == null || sheet.getRow(0) == null) throw new IllegalArgumentException("缺少投诉工单效能报表工作表或表头");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (Cell cell : sheet.getRow(0)) {
                String header = normalize(formatter.formatCellValue(cell));
                if (columns.put(header, cell.getColumnIndex()) != null) throw new IllegalArgumentException("重复报表字段：" + header);
            }
            if (!columns.containsKey("投诉类型") || HEADERS.stream().anyMatch(h -> !columns.containsKey(normalize(h)))) {
                throw new IllegalArgumentException("报表缺少必需字段");
            }
            Row ruleRow = null;
            for (Row row : sheet) {
                if ("指标口径".equals(formatter.formatCellValue(row.getCell(columns.get("投诉类型"))).strip())) ruleRow = row;
            }
            Map<String, SourceRow> rows = new LinkedHashMap<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String type = normalize(formatter.formatCellValue(row.getCell(columns.get("投诉类型"))));
                if (type.isEmpty() || type.equals("指标口径")) continue;
                if (!TYPES.contains(type)) throw new IllegalArgumentException("未知投诉类型：" + type);
                Map<String, Value> values = new LinkedHashMap<>();
                for (String header : HEADERS) {
                    int c = columns.get(normalize(header));
                    Cell cell = row.getCell(c);
                    if (cell != null && cell.getCellType() == CellType.FORMULA) {
                        throw new IllegalArgumentException("采集报表须为已导出的数值，不能含公式：" + cell.getAddress());
                    }
                    String raw = formatter.formatCellValue(cell).strip();
                    String unit = header.endsWith("率") ? "%" : header.contains("时长") ? "小时" : "个";
                    BigDecimal number = null;
                    if (!Set.of("", "—", "–", "-", "N/A").contains(raw)) {
                        String numeric = raw.replace(",", "").replace("%", "");
                        try {
                            number = new BigDecimal(numeric);
                            // Numeric cells with percent formatting contain a fraction in the workbook.
                            if (unit.equals("%") && !raw.contains("%") && cell.getCellType() == CellType.NUMERIC &&
                                cell.getCellStyle().getDataFormatString().contains("%")) number = number.multiply(BigDecimal.valueOf(100));
                        } catch (NumberFormatException e) { throw new IllegalArgumentException("非法数值：" + cell.getAddress()); }
                        if (number.signum() < 0 || number.scale() > 4 || number.compareTo(new BigDecimal("99999999999999.9999")) > 0 ||
                            (unit.equals("%") && number.compareTo(BigDecimal.valueOf(100)) > 0) ||
                            (unit.equals("个") && number.stripTrailingZeros().scale() > 0)) {
                            throw new IllegalArgumentException("数值或单位不合法：" + cell.getAddress());
                        }
                    }
                    String rule = ruleRow == null ? "" : formatter.formatCellValue(ruleRow.getCell(c));
                    values.put(normalize(header), new Value(raw, number, unit, new CellReference(r, c).formatAsString(), rule));
                }
                if (rows.put(type, new SourceRow(type, r + 1, values)) != null) throw new IllegalArgumentException("重复投诉类型：" + type);
            }
            if (!rows.keySet().containsAll(TYPES)) throw new IllegalArgumentException("报表须包含七个投诉小类及合计，当前缺少类型");
            return new Report(Collections.unmodifiableMap(rows));
        }
    }

    public static Aggregate aggregate(Report report, String metricName) {
        String key = normalize(metricName);
        if (key.equals("及时率")) return ratio(report, TRIANGLE, "及时回复工单数", "回复工单数");
        if (key.equals("回退率")) return ratio(report, TRIANGLE, "回退量", "回复工单数");
        List<Value> values = TRIANGLE.stream().map(type -> report.rows().get(type).values().get(key)).toList();
        String cells = String.join(",", values.stream().map(Value::cell).toList());
        if (values.stream().anyMatch(v -> v.number() == null)) return new Aggregate(null, "MISSING", "倒三角分项缺数，未按零处理", cells);
        if (metricName.equals("多渠道重复投诉量")) {
            return new Aggregate(null, "PENDING_SCOPE", "报表口径为90天内号码去重合计；缺按类型去重明细，不能相加或直接归属倒三角", cells);
        }
        if (values.get(0).unit().equals("个")) {
            BigDecimal sum = values.stream().map(Value::number).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Aggregate(sum, "CALCULATED", "仅三个倒三角小类相加；不含其他类型及原合计", cells);
        }
        return new Aggregate(null, "PENDING_DENOMINATOR", switch (key) {
            case "处理时长(小时)" -> "缺工单总处理时长及对应回复总数；报表回复工单数含时限剔除，不能假定为本项分母";
            case "催单处理时长(小时)" -> "缺催单已处理总时长及已处理量，不能将全部催单量假定为已处理量";
            case "一次解决率" -> "缺已完成工单数；一次解决量不能除以回复工单数替代";
            case "满意率" -> "缺有满意度评价的工单数和满意评价数";
            case "催单及时率" -> "缺催单及时处理量及在处理时限内工单量";
            case "重投率" -> "报表明确剔除部分业务，缺参与该率计算的有效工单分母";
            default -> "缺对应分子/分母，暂不合并百分比或平均时长";
        }, cells);
    }

    public static Aggregate ratio(Report report, List<String> types, String numeratorName, String denominatorName) {
        BigDecimal numerator = BigDecimal.ZERO, denominator = BigDecimal.ZERO;
        List<String> cells = new ArrayList<>();
        boolean missing = false;
        for (String type : types) {
            var values = report.rows().get(type).values();
            Value n = values.get(normalize(numeratorName)), d = values.get(normalize(denominatorName));
            cells.add(n.cell()); cells.add(d.cell());
            if (n.number() == null || d.number() == null) missing = true;
            else { numerator = numerator.add(n.number()); denominator = denominator.add(d.number()); }
        }
        String locations = String.join(",", cells);
        if (missing || denominator.signum() == 0)
            return new Aggregate(null, "PENDING_DENOMINATOR", "清单计算所需分子/分母缺数或分母为0，按无数据处理", locations);
        if (numerator.compareTo(denominator) > 0)
            return new Aggregate(null, "PENDING_SCOPE", "分子大于分母，源数据不满足清单占比口径，按无数据处理", locations);
        return new Aggregate(numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 4, RoundingMode.HALF_UP),
            "CALCULATED", "按最终清单：" + numeratorName + "之和 " + numerator.toPlainString() + " ÷ " + denominatorName + "之和 " + denominator.toPlainString() + " × 100%；不采用或平均原报表百分比", locations);
    }
}
