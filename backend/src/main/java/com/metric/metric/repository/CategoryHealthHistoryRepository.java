package com.metric.metric.repository;

import com.metric.metric.entity.CategoryHealthHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryHealthHistoryRepository extends JpaRepository<CategoryHealthHistory, Long> {

    Optional<CategoryHealthHistory> findByCategoryNameAndRecordTime(String categoryName, LocalDateTime recordTime);

    @Query("SELECT c FROM CategoryHealthHistory c WHERE c.categoryName = :categoryName " +
           "AND c.recordTime >= :startTime AND c.recordTime <= :endTime " +
           "ORDER BY c.recordTime ASC")
    List<CategoryHealthHistory> findCategoryHealthTrend(
            @Param("categoryName") String categoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    List<CategoryHealthHistory> findByCategoryNameOrderByRecordTimeDesc(String categoryName);

    @Query("SELECT c FROM CategoryHealthHistory c WHERE c.categoryName = :categoryName " +
           "ORDER BY c.recordTime DESC LIMIT 1")
    Optional<CategoryHealthHistory> findLatestByCategoryName(@Param("categoryName") String categoryName);

    @Query("SELECT c FROM CategoryHealthHistory c WHERE c.categoryName IN :categoryNames " +
           "AND c.recordTime IN (SELECT MAX(c2.recordTime) FROM CategoryHealthHistory c2 WHERE c2.categoryName IN :categoryNames GROUP BY c2.categoryName)")
    List<CategoryHealthHistory> findLatestByCategoryNames(@Param("categoryNames") List<String> categoryNames);

    @Query("SELECT c FROM CategoryHealthHistory c WHERE c.categoryName IN :categoryNames " +
           "AND c.recordTime >= :startTime AND c.recordTime <= :endTime " +
           "ORDER BY c.categoryName, c.recordTime ASC")
    List<CategoryHealthHistory> findCategoryHealthTrendForCategories(
            @Param("categoryNames") List<String> categoryNames,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);
}