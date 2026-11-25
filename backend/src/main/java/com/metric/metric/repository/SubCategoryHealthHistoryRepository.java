package com.metric.metric.repository;

import com.metric.metric.entity.SubCategoryHealthHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubCategoryHealthHistoryRepository extends JpaRepository<SubCategoryHealthHistory, Long> {

    Optional<SubCategoryHealthHistory> findByCategoryNameAndSubCategoryNameAndRecordTime(
            String categoryName, String subCategoryName, LocalDateTime recordTime);

    @Query("SELECT s FROM SubCategoryHealthHistory s WHERE s.subCategoryName = :subCategoryName " +
           "AND s.recordTime >= :startTime AND s.recordTime <= :endTime " +
           "ORDER BY s.recordTime ASC")
    List<SubCategoryHealthHistory> findSubCategoryHealthTrend(
            @Param("subCategoryName") String subCategoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    List<SubCategoryHealthHistory> findByCategoryNameAndSubCategoryNameOrderByRecordTimeDesc(
            String categoryName, String subCategoryName);

    @Query("SELECT s FROM SubCategoryHealthHistory s WHERE s.categoryName = :categoryName " +
           "ORDER BY s.recordTime DESC LIMIT 1")
    Optional<SubCategoryHealthHistory> findLatestByCategoryName(@Param("categoryName") String categoryName);
}