package com.metric.observe.repository;

import com.metric.observe.entity.HealthSnapshot1d;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HealthSnapshot1dRepository extends JpaRepository<HealthSnapshot1d, Long> {

    @Query("SELECT s FROM HealthSnapshot1d s WHERE s.levelType = 'system' " +
           "AND s.systemId = :systemId " +
           "AND s.snapshotTime >= :startTime AND s.snapshotTime < :endTime " +
           "ORDER BY s.snapshotTime ASC")
    List<HealthSnapshot1d> findSystemTrend(
            @Param("systemId") Long systemId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT s FROM HealthSnapshot1d s WHERE s.levelType = 'category' " +
           "AND s.categoryName = :categoryName " +
           "AND s.snapshotTime >= :startTime AND s.snapshotTime < :endTime " +
           "ORDER BY s.snapshotTime ASC")
    List<HealthSnapshot1d> findCategoryTrend(
            @Param("categoryName") String categoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT s FROM HealthSnapshot1d s WHERE s.levelType = 'category' " +
           "AND s.categoryName IN :categoryNames " +
           "AND s.snapshotTime >= :startTime AND s.snapshotTime < :endTime " +
           "ORDER BY s.snapshotTime ASC, s.categoryName ASC")
    List<HealthSnapshot1d> findCategoryTrends(
            @Param("categoryNames") List<String> categoryNames,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT s FROM HealthSnapshot1d s WHERE s.levelType = 'subcategory' " +
           "AND s.categoryName = :categoryName AND s.subcategoryName = :subCategoryName " +
           "AND s.snapshotTime >= :startTime AND s.snapshotTime < :endTime " +
           "ORDER BY s.snapshotTime ASC")
    List<HealthSnapshot1d> findSubcategoryTrend(
            @Param("categoryName") String categoryName,
            @Param("subCategoryName") String subCategoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT s FROM HealthSnapshot1d s WHERE s.levelType = 'metric' " +
           "AND s.metricId = :metricId " +
           "AND s.snapshotTime >= :startTime AND s.snapshotTime < :endTime " +
           "ORDER BY s.snapshotTime ASC")
    List<HealthSnapshot1d> findMetricTrend(
            @Param("metricId") Long metricId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Modifying
    @Query("DELETE FROM HealthSnapshot1d s WHERE s.snapshotTime < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
