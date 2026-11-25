package com.metric.collect.repository;

import com.metric.collect.entity.OriginalDataImportLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface OriginalDataImportLogRepository extends JpaRepository<OriginalDataImportLog, Long> {

    Page<OriginalDataImportLog> findByOrderByImportTimeDesc(Pageable pageable);

    Page<OriginalDataImportLog> findBySubCategoryCodeOrderByImportTimeDesc(String subCategoryCode, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT o.fileName) FROM OriginalDataImportLog o WHERE o.importTime >= :startTime")
    Long countDistinctFilesByImportTimeAfter(LocalDateTime startTime);

    /**
     * 全时段累计采集文件数（不去重）
     * 平台数据流总览的"采集文件"应该用全时段，避免 0 点重置看不到数字
     */
    @Query("SELECT COUNT(DISTINCT o.fileName) FROM OriginalDataImportLog o")
    Long countAllDistinctFiles();

    @Query("SELECT COUNT(DISTINCT o.tableName) FROM OriginalDataImportLog o")
    Long countDistinctTables();

    @Query("SELECT COALESCE(SUM(o.recordCount), 0) FROM OriginalDataImportLog o WHERE o.importTime >= :startTime")
    Long sumRecordCountByImportTimeAfter(LocalDateTime startTime);

    Page<OriginalDataImportLog> findByImportTimeAfterOrderByImportTimeDesc(LocalDateTime startTime, Pageable pageable);
}