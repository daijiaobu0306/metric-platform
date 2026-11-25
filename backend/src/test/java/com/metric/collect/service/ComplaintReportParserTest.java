package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ComplaintReportParserTest {
    private byte[] fixture(String missing, boolean duplicate) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(ComplaintReportParser.SHEET);
            var headers = sheet.createRow(0); headers.createCell(0).setCellValue("投诉类型");
            for (int c = 0; c < ComplaintReportParser.HEADERS.size(); c++) headers.createCell(c + 1).setCellValue(ComplaintReportParser.HEADERS.get(c));
            for (int r = 0; r < ComplaintReportParser.TYPES.size(); r++) {
                var row = sheet.createRow(r + 1); row.createCell(0).setCellValue(ComplaintReportParser.TYPES.get(duplicate && r == 2 ? 1 : r));
                for (int c = 0; c < ComplaintReportParser.HEADERS.size(); c++) {
                    String name = ComplaintReportParser.HEADERS.get(c);
                    String value = name.endsWith("率") ? "50.00%" : name.contains("时长") ? "2.50" : "100";
                    if (name.equals("及时回复工单数")) value = "50";
                    if (name.equals("多渠道重复投诉量")) value = "738";
                    if (r == 0 && name.equals("回退量")) value = "0";
                    if (r == 2 && name.equals(missing)) value = "—";
                    row.createCell(c + 1).setCellValue(value);
                }
            }
            workbook.write(output); return output.toByteArray();
        }
    }
    @Test void sumsOnlyThreeTypesAndRecomputesTimelyRatio() throws Exception {
        var report = ComplaintReportParser.parse(new ByteArrayInputStream(fixture("", false)));
        assertEquals(new BigDecimal("300"), ComplaintReportParser.aggregate(report, "工单累计量").value());
        assertEquals(new BigDecimal("50.0000"), ComplaintReportParser.aggregate(report, "及时率").value());
        assertEquals(8, report.rows().size());
    }
    @Test void preservesDashAndZeroAndDoesNotPartiallySum() throws Exception {
        var report = ComplaintReportParser.parse(new ByteArrayInputStream(fixture("工单累计量", false)));
        assertNull(report.rows().get(ComplaintReportParser.TRIANGLE.get(1)).values().get("工单累计量").number());
        assertEquals(BigDecimal.ZERO, report.rows().get("在线客服").values().get("回退量").number());
        assertNull(ComplaintReportParser.aggregate(report, "工单累计量").value());
        assertEquals("MISSING", ComplaintReportParser.aggregate(report, "工单累计量").status());
    }
    @Test void doesNotInventDenominatorsOrSumSharedDeduplicatedCount() throws Exception {
        var report = ComplaintReportParser.parse(new ByteArrayInputStream(fixture("", false)));
        assertNull(ComplaintReportParser.aggregate(report, "满意率").value());
        assertNull(ComplaintReportParser.aggregate(report, "一次解决率").value());
        assertNull(ComplaintReportParser.aggregate(report, "处理时长（小时）").value());
        assertEquals("PENDING_SCOPE", ComplaintReportParser.aggregate(report, "多渠道重复投诉量").status());
    }
    @Test void rejectsDuplicateTypeBeforeIngestion() throws Exception {
        byte[] data = fixture("", true);
        assertThrows(IllegalArgumentException.class, () -> ComplaintReportParser.parse(new ByteArrayInputStream(data)));
    }
    @Test void usesCatalogNumeratorsInsteadOfSourceRatesAndRejectsMissingDenominator() throws Exception {
        var report = ComplaintReportParser.parse(new ByteArrayInputStream(fixture("回退率", false)));
        // The source rate is missing, but all count components required by the final catalog exist.
        assertEquals(new BigDecimal("100.0000"), ComplaintReportParser.aggregate(report, "回退率").value());
        report = ComplaintReportParser.parse(new ByteArrayInputStream(fixture("回复工单数", false)));
        assertNull(ComplaintReportParser.aggregate(report, "及时率").value());
        assertNull(ComplaintReportParser.aggregate(report, "回退率").value());
    }
    @Test void doesNotDivideByZeroOrTurnInvalidRatioIntoHealthyZero() throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(fixture("", false))); var output = new ByteArrayOutputStream()) {
            workbook.getSheet(ComplaintReportParser.SHEET).getRow(1).getCell(10).setCellValue("0");
            workbook.write(output);
            var report = ComplaintReportParser.parse(new ByteArrayInputStream(output.toByteArray()));
            assertNull(ComplaintReportParser.ratio(report, java.util.List.of("在线客服"), "及时回复工单数", "回复工单数").value());
        }
    }
    @Test void mapsZongdiaoByGroupDespiteErroneousCatalogScope() throws Exception {
        var metric = new ObjectMapper().readTree("{\"groupName\":\"综调工单处理流程\",\"sourceScope\":\"倒三角工单处理流程（事件性质=服务咨询）\"}");
        assertEquals("综调", ComplaintReportService.mappedType(metric));
    }
}
