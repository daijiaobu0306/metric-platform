package com.metric.issue.service;

import com.metric.issue.entity.Issue;
import com.metric.issue.event.IssueCreatedEvent;
import com.metric.issue.repository.IssueRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class IssueImportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 导出问题列表为Excel
     * @param issues 要导出的问题列表
     * @return Excel文件字节数组
     */
    public byte[] exportIssues(List<Issue> issues) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("问题列表");

            // 创建表头样式
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // 表头（不包含问题编号）
            String[] headers = {
                "问题类型", "系统名称", "指标大类名称", "指标小类名称",
                "指标名称", "问题描述", "问题属性", "问题来源", "发现者", "责任人",
                "核查原因", "预计解决时间", "解决状态", "实际解决时间", "后评估",
                "是否关注", "登记时间", "修改时间"
            };

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 填充数据
            int rowIndex = 1;
            for (Issue issue : issues) {
                Row row = sheet.createRow(rowIndex++);
                int colIndex = 0;
                row.createCell(colIndex++).setCellValue(issue.getIssueType() != null ? issue.getIssueType() : "");
                row.createCell(colIndex++).setCellValue(issue.getSystemName() != null ? issue.getSystemName() : "");
                row.createCell(colIndex++).setCellValue(issue.getCategoryName() != null ? issue.getCategoryName() : "");
                row.createCell(colIndex++).setCellValue(issue.getSubCategoryName() != null ? issue.getSubCategoryName() : "");
                row.createCell(colIndex++).setCellValue(issue.getMetricName() != null ? issue.getMetricName() : "");
                row.createCell(colIndex++).setCellValue(issue.getIssueDescription() != null ? issue.getIssueDescription() : "");
                row.createCell(colIndex++).setCellValue(issue.getIssueAttribute() != null ? issue.getIssueAttribute() : "");
                row.createCell(colIndex++).setCellValue(issue.getIssueSource() != null ? issue.getIssueSource() : "");
                row.createCell(colIndex++).setCellValue(issue.getDiscoverer() != null ? issue.getDiscoverer() : "");
                row.createCell(colIndex++).setCellValue(issue.getResponsiblePerson() != null ? issue.getResponsiblePerson() : "");
                row.createCell(colIndex++).setCellValue(issue.getCheckReason() != null ? issue.getCheckReason() : "");
                row.createCell(colIndex++).setCellValue(issue.getPlanResolveTime() != null ? formatDateTime(issue.getPlanResolveTime()) : "");
                row.createCell(colIndex++).setCellValue(issue.getResolveStatus() != null ? issue.getResolveStatus() : "");
                row.createCell(colIndex++).setCellValue(issue.getResolveTime() != null ? formatDateTime(issue.getResolveTime()) : "");
                row.createCell(colIndex++).setCellValue(issue.getPostEvaluation() != null ? issue.getPostEvaluation() : "");
                row.createCell(colIndex++).setCellValue(issue.getTagStatus() != null && issue.getTagStatus() == 1 ? "关注" : "未关注");
                row.createCell(colIndex++).setCellValue(issue.getRegisterTime() != null ? formatDateTime(issue.getRegisterTime()) : "");
                row.createCell(colIndex).setCellValue(issue.getModifyTime() != null ? formatDateTime(issue.getModifyTime()) : "");
            }

            // 自动调整列宽
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                // 设置最小列宽
                if (sheet.getColumnWidth(i) < 3000) {
                    sheet.setColumnWidth(i, 3000);
                }
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    /**
     * 生成问题导入模板
     * @return Excel模板字节数组
     */
    public byte[] generateTemplate() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("问题导入模板");

            // 创建表头样式
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // 表头（不包含问题编号，避免用户误填导致覆盖）
            String[] headers = {
                "问题类型", "系统名称", "指标大类名称", "指标小类名称",
                "指标名称", "问题描述", "问题属性", "问题来源", "发现者", "责任人",
                "核查原因", "预计解决时间", "解决状态", "实际解决时间", "后评估",
                "是否关注", "登记时间"
            };

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 示例数据
            Row exampleRow = sheet.createRow(1);
            int colIndex = 0;
            exampleRow.createCell(colIndex++).setCellValue("故障");
            exampleRow.createCell(colIndex++).setCellValue("CRM系统");
            exampleRow.createCell(colIndex++).setCellValue("对外部客户");
            exampleRow.createCell(colIndex++).setCellValue("缴费业务");
            exampleRow.createCell(colIndex++).setCellValue("缴费上账平均时长");
            exampleRow.createCell(colIndex++).setCellValue("缴费上账平均时长超过阈值");
            exampleRow.createCell(colIndex++).setCellValue("新问题");
            exampleRow.createCell(colIndex++).setCellValue("告警");
            exampleRow.createCell(colIndex++).setCellValue("张三");
            exampleRow.createCell(colIndex++).setCellValue("李四");
            exampleRow.createCell(colIndex++).setCellValue("系统负载过高导致处理延迟");
            exampleRow.createCell(colIndex++).setCellValue("2026-06-30");
            exampleRow.createCell(colIndex++).setCellValue("未解决");
            exampleRow.createCell(colIndex++).setCellValue("");
            exampleRow.createCell(colIndex++).setCellValue("");
            exampleRow.createCell(colIndex++).setCellValue("未关注");
            exampleRow.createCell(colIndex).setCellValue("2026-06-17");

            // 自动调整列宽
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) < 3000) {
                    sheet.setColumnWidth(i, 3000);
                }
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    /**
     * 导入问题Excel文件
     * @param file 上传的Excel文件
     * @param overwrite 是否覆盖现有数据
     * @return 导入结果
     */
    @Transactional
    public Map<String, Object> importIssues(MultipartFile file, boolean overwrite) throws IOException {
        Map<String, Object> result = new HashMap<>();
        int successCount = 0;
        int errorCount = 0;
        StringBuilder errorMsg = new StringBuilder();

        // 如果选择覆盖，先清空现有数据
        if (overwrite) {
            issueRepository.deleteAll();
            issueRepository.flush();
        }

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            // 跳过表头，从第二行开始读取
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                try {
                    // 模板不包含问题编号列，从问题类型开始
                    String issueType = getCellValue(row.getCell(0));
                    String systemName = getCellValue(row.getCell(1));
                    String categoryName = getCellValue(row.getCell(2));
                    String subCategoryName = getCellValue(row.getCell(3));
                    String metricName = getCellValue(row.getCell(4));
                    String issueDescription = getCellValue(row.getCell(5));
                    String issueAttribute = getCellValue(row.getCell(6));
                    String issueSource = getCellValue(row.getCell(7));
                    String discoverer = getCellValue(row.getCell(8));
                    String responsiblePerson = getCellValue(row.getCell(9));
                    String checkReason = getCellValue(row.getCell(10));
                    String planResolveTimeStr = getCellValue(row.getCell(11));
                    String resolveStatus = getCellValue(row.getCell(12));
                    String resolveTimeStr = getCellValue(row.getCell(13));
                    String postEvaluation = getCellValue(row.getCell(14));
                    String tagStatusStr = getCellValue(row.getCell(15));
                    String registerTimeStr = getCellValue(row.getCell(16));

                    // 跳过空行
                    if (isEmptyRow(issueType, systemName, issueDescription)) {
                        continue;
                    }

                    // 始终创建新记录
                    Issue issue = new Issue();

                    // 设置字段
                    issue.setIssueType(issueType);
                    issue.setSystemName(systemName);
                    issue.setCategoryName(categoryName);
                    issue.setSubCategoryName(subCategoryName);
                    issue.setMetricName(metricName);
                    issue.setIssueDescription(issueDescription);
                    issue.setIssueAttribute(issueAttribute);
                    issue.setIssueSource(issueSource);
                    issue.setDiscoverer(discoverer);
                    issue.setResponsiblePerson(responsiblePerson);
                    issue.setCheckReason(checkReason);

                    // 解析日期
                    if (planResolveTimeStr != null && !planResolveTimeStr.isEmpty()) {
                        issue.setPlanResolveTime(parseDateTime(planResolveTimeStr));
                    }
                    if (resolveTimeStr != null && !resolveTimeStr.isEmpty()) {
                        issue.setResolveTime(parseDateTime(resolveTimeStr));
                    }
                    if (registerTimeStr != null && !registerTimeStr.isEmpty()) {
                        issue.setRegisterTime(parseDateTime(registerTimeStr));
                    }

                    issue.setResolveStatus(resolveStatus != null && !resolveStatus.isEmpty() ? resolveStatus : "未解决");
                    issue.setPostEvaluation(postEvaluation);

                    // 解析关注状态
                    if (tagStatusStr != null) {
                        if (tagStatusStr.equals("关注") || tagStatusStr.equals("1")) {
                            issue.setTagStatus(1);
                            if (issue.getTagTime() == null) {
                                issue.setTagTime(LocalDateTime.now());
                            }
                        } else {
                            issue.setTagStatus(0);
                        }
                    } else {
                        issue.setTagStatus(0);
                    }

                    issueRepository.save(issue);
                    // V46: Excel 导入路径也要发布问题创建事件，否则全局 Toast 不会弹
                    eventPublisher.publishEvent(new IssueCreatedEvent(this, issue));
                    successCount++;

                } catch (Exception e) {
                    errorCount++;
                    errorMsg.append("第").append(i + 1).append("行导入失败: ").append(e.getMessage()).append("; ");
                }
            }
        }

        result.put("successCount", successCount);
        result.put("errorCount", errorCount);
        result.put("message", errorMsg.toString());

        return result;
    }

    private String getCellValue(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue().toString();
                }
                // 处理数字格式的整数
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> null;
        };
    }

    private boolean isEmptyRow(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private LocalDateTime parseDateTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            // 尝试完整日期时间格式
            return LocalDateTime.parse(dateStr, DATE_FMT);
        } catch (Exception e) {
            try {
                // 尝试仅日期格式
                return LocalDateTime.parse(dateStr + " 00:00:00", DATE_FMT);
            } catch (Exception e2) {
                try {
                    // 尝试 yyyy-MM-dd 格式
                    return LocalDateTime.parse(dateStr + "T00:00:00");
                } catch (Exception e3) {
                    return null;
                }
            }
        }
    }

    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(DATE_FMT);
    }
}
