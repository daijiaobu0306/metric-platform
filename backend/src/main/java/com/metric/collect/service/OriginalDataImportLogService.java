package com.metric.collect.service;

import com.metric.collect.entity.OriginalDataImportLog;
import com.metric.collect.repository.OriginalDataImportLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.time.LocalTime;

@Service
public class OriginalDataImportLogService {

    @Autowired
    private OriginalDataImportLogRepository importLogRepository;

    public void logImport(String subCategoryCode, String tableName, String fileName, int recordCount) {
        OriginalDataImportLog log = new OriginalDataImportLog();
        log.setSubCategoryCode(subCategoryCode);
        log.setTableName(tableName);
        log.setFileName(fileName);
        log.setRecordCount(recordCount);
        log.setImportTime(LocalDateTime.now());
        importLogRepository.save(log);
    }

    public Page<OriginalDataImportLog> getImportLogs(int page, int size) {
        return importLogRepository.findByOrderByImportTimeDesc(PageRequest.of(page, size));
    }

    public Page<OriginalDataImportLog> getImportLogs(String subCategoryCode, int page, int size) {
        if (subCategoryCode == null || subCategoryCode.isEmpty()) {
            return getImportLogs(page, size);
        }
        return importLogRepository.findBySubCategoryCodeOrderByImportTimeDesc(subCategoryCode, PageRequest.of(page, size));
    }

    public Long getTodayFileCount() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return importLogRepository.countDistinctFilesByImportTimeAfter(startOfDay);
    }

    /**
     * 全时段累计采集文件数（数据流总览"采集文件"卡用）
     * 与 getTodayFileCount 的区别：这个不会被 0 点重置，只要曾经采集过就有数字
     */
    public Long getTotalFileCount() {
        try {
            Long total = importLogRepository.countAllDistinctFiles();
            if (total != null && total > 0) {
                return total;
            }
        } catch (Exception e) {
            System.err.println("getTotalFileCount 异常，回退到 todayFileCount: " + e.getMessage());
        }
        // 兜底：新 query 失败/为 0 时，用 todayFileCount
        return getTodayFileCount();
    }

    public Long getTableCount() {
        return importLogRepository.countDistinctTables();
    }

    public Long getTodayRecordCount() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        Long sum = importLogRepository.sumRecordCountByImportTimeAfter(startOfDay);
        return sum == null ? 0L : sum;
    }

    public Page<OriginalDataImportLog> getTodayImportLogs(int page, int size) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return importLogRepository.findByImportTimeAfterOrderByImportTimeDesc(startOfDay,
            PageRequest.of(page, size));
    }

    public void batchDelete(List<Long> ids) {
        importLogRepository.deleteAllById(ids);
    }
}