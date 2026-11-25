package com.metric.observe.repository;

import com.metric.observe.entity.HealthSnapshot1h;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HealthSnapshot1hRepository extends JpaRepository<HealthSnapshot1h, Long> {

    /** system 层：按 systemId + 时间窗 */
    @Query("SELECT s FROM HealthSnapshot1h s WHERE s.levelType = 'system' " +
           "AND s.systemId = :systemId " +
           "AND s.snapshotTime >= :startTime AND s.snapshotTime < :endTime " +
           "ORDER BY s.snapshotTime ASC")
    List<HealthSnapshot1h> findSystemTrend(
            @Param("systemId") Long systemId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** category 层：按 categoryName + 时间窗 */
    @Query("SELECT s FROM HealthSnapshot1h s WHERE s.levelType = 'category' " +
           "AND s.categoryName = :categoryName " +
           "AND s.snapshotTime >= :startTime AND s.snapshotTime < :endTime " +
           "ORDER BY s.snapshotTime ASC")
    List<HealthSnapshot1h> findCategoryTrend(
            @Param("categoryName") String categoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** category 层：按多个 categoryName（用于系统级聚合） */
    @Query("SELECT s FROM HealthSnapshot1h s WHERE s.levelType = 'category' " +
           "AND s.categoryName IN :categoryNames " +
           "AND s.snapshotTime >= :startTime AND s.snapshotTime < :endTime " +
           "ORDER BY s.snapshotTime ASC, s.categoryName ASC")
    List<HealthSnapshot1h> findCategoryTrends(
            @Param("categoryNames") List<String> categoryNames,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** subcategory 层：按 categoryName + subCategoryName + 时间窗 */
    @Query("SELECT s FROM HealthSnapshot1h s WHERE s.levelType = 'subcategory' " +
           "AND s.categoryName = :categoryName AND s.subcategoryName = :subCategoryName " +
           "AND s.snapshotTime >= :startTime AND s.snapshotTime < :endTime " +
           "ORDER BY s.snapshotTime ASC")
    List<HealthSnapshot1h> findSubcategoryTrend(
            @Param("categoryName") String categoryName,
            @Param("subCategoryName") String subCategoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** metric 层：按 metricId + 时间窗 */
    @Query("SELECT s FROM HealthSnapshot1h s WHERE s.levelType = 'metric' " +
           "AND s.metricId = :metricId " +
           "AND s.snapshotTime >= :startTime AND s.snapshotTime < :endTime " +
           "ORDER BY s.snapshotTime ASC")
    List<HealthSnapshot1h> findMetricTrend(
            @Param("metricId") Long metricId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** 清理过期数据 */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM HealthSnapshot1h s WHERE s.snapshotTime < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
