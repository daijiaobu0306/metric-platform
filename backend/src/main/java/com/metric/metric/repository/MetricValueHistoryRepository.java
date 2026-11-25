package com.metric.metric.repository;

import com.metric.metric.entity.MetricValueHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetricValueHistoryRepository extends JpaRepository<MetricValueHistory, Long> {

    Optional<MetricValueHistory> findByMetricIdAndDataTime(Long metricId, LocalDateTime dataTime);

    List<MetricValueHistory> findByMetricIdAndRecordTimeBetweenOrderByRecordTimeAsc(
            Long metricId, LocalDateTime startTime, LocalDateTime endTime);

    @Query("SELECT m FROM MetricValueHistory m WHERE m.metricId = :metricId " +
           "AND m.recordTime >= :startTime AND m.recordTime <= :endTime " +
           "ORDER BY m.recordTime ASC")
    List<MetricValueHistory> findMetricValueTrend(
            @Param("metricId") Long metricId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT m FROM MetricValueHistory m WHERE m.categoryName = :categoryName " +
           "AND m.recordTime >= :startTime AND m.recordTime <= :endTime " +
           "ORDER BY m.recordTime ASC")
    List<MetricValueHistory> findCategoryValueTrend(
            @Param("categoryName") String categoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT AVG(m.metricValue) FROM MetricValueHistory m " +
           "WHERE m.metricId = :metricId AND m.recordTime >= :since")
    Double calculateAvgValue(@Param("metricId") Long metricId, @Param("since") LocalDateTime since);

    @Query("SELECT STDDEV(m.metricValue) FROM MetricValueHistory m " +
           "WHERE m.metricId = :metricId AND m.recordTime >= :since")
    Double calculateStdDev(@Param("metricId") Long metricId, @Param("since") LocalDateTime since);

    @Query("SELECT m FROM MetricValueHistory m WHERE m.subCategoryName = :subCategoryName " +
           "AND m.recordTime >= :startTime AND m.recordTime <= :endTime " +
           "ORDER BY m.recordTime ASC")
    List<MetricValueHistory> findMetricValueTrendBySubCategory(
            @Param("subCategoryName") String subCategoryName,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按小时分桶聚合（24h 时段使用，~48 点）
     */
    @Query(value = "SELECT " +
           "  DATE_FORMAT(record_time, '%Y-%m-%d %H:00:00') AS bucket, " +
           "  AVG(metric_value) AS avg_value " +
           "FROM metric_value_history " +
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
     * 按 4 小时分桶聚合（7d 时段使用，~42 点）
     */
    @Query(value = "SELECT " +
           "  DATE_FORMAT(record_time, '%Y-%m-%d') AS bucket_date, " +
           "  (FLOOR(HOUR(record_time) / 4) * 4) AS bucket_hour, " +
           "  AVG(metric_value) AS avg_value " +
           "FROM metric_value_history " +
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
     * 按 12 小时分桶聚合（15d 时段使用，~30 点）
     */
    @Query(value = "SELECT " +
           "  DATE_FORMAT(record_time, '%Y-%m-%d') AS bucket_date, " +
           "  (FLOOR(HOUR(record_time) / 12) * 12) AS bucket_hour, " +
           "  AVG(metric_value) AS avg_value " +
           "FROM metric_value_history " +
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
     * 按天分桶聚合（30d 时段使用，~30 点）
     */
    @Query(value = "SELECT " +
           "  DATE_FORMAT(record_time, '%Y-%m-%d 00:00:00') AS bucket, " +
           "  AVG(metric_value) AS avg_value " +
           "FROM metric_value_history " +
           "WHERE metric_id = :metricId " +
           "  AND record_time >= :startTime AND record_time <= :endTime " +
           "GROUP BY bucket " +
           "ORDER BY bucket ASC",
           nativeQuery = true)
    List<Object[]> aggregateByDay(
            @Param("metricId") Long metricId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);
}