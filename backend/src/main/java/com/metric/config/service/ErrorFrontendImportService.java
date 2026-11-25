package com.metric.config.service;

import com.metric.metric.entity.MetricErrorBizReturn;
import com.metric.metric.entity.MetricErrorReturnCode;
import com.metric.metric.repository.MetricErrorBizReturnRepository;
import com.metric.metric.repository.MetricErrorReturnCodeRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;

@Service
public class ErrorFrontendImportService {

    @Autowired
    private MetricErrorBizReturnRepository bizReturnRepository;

    @Autowired
    private MetricErrorReturnCodeRepository returnCodeRepository;

    /**
     * 导入Excel文件（前端页面异常）
     * @param file Excel文件
     * @param sourceFileName 来源文件名
     * @return 导入的记录数
     */
    @Transactional
    public int importExcel(MultipartFile file, String sourceFileName) throws IOException {
        LocalDateTime dataTime = LocalDateTime.now();
        int totalCount = 0;
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                totalCount += processSheet(sheet.getSheetName(), sheet, dataTime, sourceFileName);
            }
        }
        return totalCount;
    }

    private int processSheet(String sheetName, Sheet sheet, LocalDateTime dataTime, String sourceFile) {
        if (sheet.getLastRowNum() < 1) return 0;

        return switch (sheetName) {
            case "前端页面-业务自定义返回异常" -> processBizReturn(sheet, dataTime, sourceFile);
            case "前端页面-业务自定义返回码" -> processReturnCode(sheet, dataTime, sourceFile);
            default -> { System.out.println("[ErrorFrontendImport] Unknown sheet: " + sheetName); yield 0; }
        };
    }

    // 前端页面-业务自定义返回异常
    // 表头: 系统, 自定义返回信息, 返回次数
    private int processBizReturn(Sheet sheet, LocalDateTime dataTime, String sourceFile) {
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            MetricErrorBizReturn entity = new MetricErrorBizReturn();
            entity.setInsertTime(dataTime);
            entity.setSystemName(getStringCellValue(row.getCell(0)));
            entity.setReturnInfo(getStringCellValue(row.getCell(1)));
            entity.setCount(getLongCellValue(row.getCell(2)));
            entity.setSourceFile(sourceFile);
            bizReturnRepository.save(entity);
            count++;
        }
        return count;
    }

    // 前端页面-业务自定义返回码
    // 表头: 业务自定义返回码, 返回次数
    private int processReturnCode(Sheet sheet, LocalDateTime dataTime, String sourceFile) {
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            MetricErrorReturnCode entity = new MetricErrorReturnCode();
            entity.setInsertTime(dataTime);
            entity.setReturnCode(getStringCellValue(row.getCell(0)));
            entity.setCount(getLongCellValue(row.getCell(1)));
            entity.setSourceFile(sourceFile);
            returnCodeRepository.save(entity);
            count++;
        }
        return count;
    }

    private String getStringCellValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> null;
        };
    }

    private Long getLongCellValue(Cell cell) {
        if (cell == null) return null;
        try {
            return switch (cell.getCellType()) {
                case NUMERIC -> (long) cell.getNumericCellValue();
                case STRING -> Long.parseLong(cell.getStringCellValue().trim().replace(",", ""));
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
