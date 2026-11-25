package com.metric.metric.repository;

import com.metric.metric.entity.MetricHealthHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetricHealthHistoryRepository extends JpaRepository<MetricHealthHistory, Long> {

    Optional<MetricHealthHistory> findFirstByMetricIdOrderByRecordTimeDescIdDesc(Long metricId);

    Optional<MetricHealthHistory> findByMetricIdAndRecordTime(Long metricId, LocalDateTime recordTime);

    @Query("SELECT m FROM MetricHealthHistory m WHERE m.metricId = :metricId " +
           "AND m.recordTime >= :startTime AND m.recordTime <= :endTime " +
           "ORDER BY m.recordTime ASC")
    List<MetricHealthHistory> findMetricHealthTrend(
            @Param("metricId") Long metricId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT m FROM MetricHealthHistory m WHERE m.categoryName = :categoryName " +
           "AND m.recordTime >= :startTime AND m.recordTime <= :endTime " +
           "ORDER BY m.recordTime ASC")
    List<MetricHealthHistory> findCategoryHealthTrend(
            @Param("categoryName") String categoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT m FROM MetricHealthHistory m WHERE m.subCategoryName = :subCategoryName " +
           "AND m.recordTime >= :startTime AND m.recordTime <= :endTime " +
           "ORDER BY m.recordTime ASC")
    List<MetricHealthHistory> findSubCategoryHealthTrend(
            @Param("subCategoryName") String subCategoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT AVG(m.healthScore) FROM MetricHealthHistory m " +
           "WHERE m.categoryName = :categoryName AND m.recordTime >= :since")
    Double calculateCategoryAvgHealth(@Param("categoryName") String categoryName, @Param("since") LocalDateTime since);

    List<MetricHealthHistory> findByCategoryNameOrderByRecordTimeDesc(String categoryName);

    List<MetricHealthHistory> findByCategoryNameAndSubCategoryNameOrderByRecordTimeDesc(
            String categoryName, String subCategoryName);

    /**
     * 健康度按小时分桶聚合（24h 时段使用，~48 点）
     */
    @Query(value = "SELECT " +
           "  DATE_FORMAT(record_time, '%Y-%m-%d %H:00:00') AS bucket, " +
           "  AVG(health_score) AS avg_value " +
           "FROM metric_health_history " +
           "WHERE metric_id = :metricId " +
           "  AND record_time >= :startTime AND record_time <= :endTime " +
           "GROUP BY bucket " +
           "ORDER BY bucket ASC",
           nativeQuery = true)
    List<Object[]> aggregateByHour(
            @Param("metricId") Long metricId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 健康度按 4 小时分桶聚合（7d 时段使用）
     */
    @Query(value = "SELECT " +
           "  DATE_FORMAT(record_time, '%Y-%m-%d') AS bucket_date, " +
           "  (FLOOR(HOUR(record_time) / 4) * 4) AS bucket_hour, " +
           "  AVG(health_score) AS avg_value " +
           "FROM metric_health_history " +
           "WHERE metric_id = :metricId " +
           "  AND record_time >= :startTime AND record_time <= :endTime " +
           "GROUP BY bucket_date, bucket_hour " +
           "ORDER BY bucket_date, bucket_hour",
           nativeQuery = true)
    List<Object[]> aggregateBy4Hour(
            @Param("metricId") Long metricId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 健康度按 12 小时分桶聚合（15d 时段使用）
     */
    @Query(value = "SELECT " +
           "  DATE_FORMAT(record_time, '%Y-%m-%d') AS bucket_date, " +
           "  (FLOOR(HOUR(record_time) / 12) * 12) AS bucket_hour, " +
           "  AVG(health_score) AS avg_value " +
           "FROM metric_health_history " +
           "WHERE metric_id = :metricId " +
           "  AND record_time >= :startTime AND record_time <= :endTime " +
           "GROUP BY bucket_date, bucket_hour " +
           "ORDER BY bucket_date, bucket_hour",
           nativeQuery = true)
    List<Object[]> aggregateBy12Hour(
            @Param("metricId") Long metricId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 健康度按天分桶聚合（30d 时段使用）
     */
    @Query(value = "SELECT " +
           "  DATE_FORMAT(record_time, '%Y-%m-%d 00:00:00') AS bucket, " +
           "  AVG(health_score) AS avg_value " +
           "FROM metric_health_history " +
           "WHERE metric_id = :metricId " +
           "  AND record_time >= :startTime AND record_time <= :endTime " +
           "GROUP BY bucket " +
           "ORDER BY bucket ASC",
           nativeQuery = true)
    List<Object[]> aggregateByDay(
            @Param("metricId") Long metricId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 大类健康度按 5 分钟分桶聚合（1h 时段使用，~12 个点）
     */
    @Query(value = "SELECT " +
           "  CONCAT(DATE_FORMAT(record_time, '%Y-%m-%d %H:'), " +
           "         LPAD(FLOOR(MINUTE(record_time) / 5) * 5, 2, '0'), ':00') AS bucket, " +
           "  AVG(health_score) AS avg_value " +
           "FROM metric_health_history " +
           "WHERE category_name = :categoryName " +
           "  AND record_time >= :startTime AND record_time <= :endTime " +
           "GROUP BY bucket " +
           "ORDER BY bucket ASC",
           nativeQuery = true)
    List<Object[]> aggregateCategoryBy5Min(
            @Param("categoryName") String categoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 大类健康度按小时分桶聚合（6h/1d 时段使用）
     */
    @Query(value = "SELECT " +
           "  DATE_FORMAT(record_time, '%Y-%m-%d %H:00:00') AS bucket, " +
           "  AVG(health_score) AS avg_value " +
           "FROM metric_health_history " +
           "WHERE category_name = :categoryName " +
           "  AND record_time >= :startTime AND record_time <= :endTime " +
           "GROUP BY bucket " +
           "ORDER BY bucket ASC",
           nativeQuery = true)
    List<Object[]> aggregateCategoryByHour(
            @Param("categoryName") String categoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 大类健康度按天分桶聚合（7d/30d 时段使用）
     */
    @Query(value = "SELECT " +
           "  DATE_FORMAT(record_time, '%Y-%m-%d 00:00:00') AS bucket, " +
           "  AVG(health_score) AS avg_value " +
           "FROM metric_health_history " +
           "WHERE category_name = :categoryName " +
           "  AND record_time >= :startTime AND record_time <= :endTime " +
           "GROUP BY bucket " +
           "ORDER BY bucket ASC",
           nativeQuery = true)
    List<Object[]> aggregateCategoryByDay(
            @Param("categoryName") String categoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);
}