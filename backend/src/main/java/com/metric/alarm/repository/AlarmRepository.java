package com.metric.alarm.repository;

import com.metric.alarm.entity.Alarm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    @Query(value = "SELECT DATE_FORMAT(a.alarm_time, '%Y-%m-%d %H:00:00') AS time_point, COUNT(*) FROM alarm a WHERE a.alarm_time >= :start AND a.alarm_time <= :end GROUP BY DATE_FORMAT(a.alarm_time, '%Y-%m-%d %H:00:00') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByHour(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = "SELECT DATE(a.alarm_time) AS time_point, COUNT(*) FROM alarm a WHERE a.alarm_time >= :start AND a.alarm_time <= :end GROUP BY DATE(a.alarm_time) ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByDay(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = "SELECT DATE_FORMAT(a.alarm_time, '%Y-%m') AS time_point, COUNT(*) FROM alarm a WHERE a.alarm_time >= :start AND a.alarm_time <= :end GROUP BY DATE_FORMAT(a.alarm_time, '%Y-%m') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByMonth(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = "SELECT DATE_FORMAT(a.alarm_time, '%Y-%m-%d %H:00:00') AS time_point, COUNT(*) FROM alarm a WHERE a.alarm_time >= :start AND a.alarm_time <= :end AND a.process_status = :processStatus GROUP BY DATE_FORMAT(a.alarm_time, '%Y-%m-%d %H:00:00') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByHourWithStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("processStatus") Integer processStatus);

    @Query(value = "SELECT DATE(a.alarm_time) AS time_point, COUNT(*) FROM alarm a WHERE a.alarm_time >= :start AND a.alarm_time <= :end AND a.process_status = :processStatus GROUP BY DATE(a.alarm_time) ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByDayWithStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("processStatus") Integer processStatus);

    @Query(value = "SELECT DATE_FORMAT(a.alarm_time, '%Y-%m') AS time_point, COUNT(*) FROM alarm a WHERE a.alarm_time >= :start AND a.alarm_time <= :end AND a.process_status = :processStatus GROUP BY DATE_FORMAT(a.alarm_time, '%Y-%m') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByMonthWithStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("processStatus") Integer processStatus);

    Page<Alarm> findByProcessStatus(Integer processStatus, Pageable pageable);

    Page<Alarm> findBySystemId(Long systemId, Pageable pageable);

    Optional<Alarm> findBySourceTableAndSourceIdAndMetricIdAndProcessStatus(
            String sourceTable, Long sourceId, Long metricId, Integer processStatus);

    @Query("SELECT a FROM Alarm a WHERE a.categoryName = :categoryName AND a.subCategoryName = :subCategoryName ORDER BY a.alarmTime DESC")
    Page<Alarm> findByCategoryNameAndSubCategoryName(
            @Param("categoryName") String categoryName,
            @Param("subCategoryName") String subCategoryName,
            Pageable pageable);

    @Query("SELECT a FROM Alarm a WHERE a.categoryName = :categoryName ORDER BY a.alarmTime DESC")
    Page<Alarm> findByCategoryName(
            @Param("categoryName") String categoryName,
            Pageable pageable);

    @Query("SELECT a FROM Alarm a WHERE a.categoryName = :categoryName AND a.subCategoryName = :subCategoryName AND DATE(a.alarmTime) = :dataDate ORDER BY a.alarmTime DESC")
    Page<Alarm> findBySubCategoryNameWithPage(
            @Param("categoryName") String categoryName,
            @Param("subCategoryName") String subCategoryName,
            @Param("dataDate") String dataDate,
            Pageable pageable);

    @Query("SELECT a FROM Alarm a WHERE a.alarmTime BETWEEN :startTime AND :endTime")
    List<Alarm> findByAlarmTimeBetween(@Param("startTime") LocalDateTime startTime,
                                        @Param("endTime") LocalDateTime endTime);

    @Query("SELECT a FROM Alarm a WHERE a.systemId = :systemId AND a.categoryId = :categoryId")
    List<Alarm> findBySystemIdAndCategoryId(@Param("systemId") Long systemId,
                                             @Param("categoryId") Long categoryId);

    @Query("SELECT a FROM Alarm a WHERE " +
            "(:systemId IS NULL OR a.systemId = :systemId) AND " +
            "(:categoryId IS NULL OR a.categoryId = :categoryId) AND " +
            "(:metricId IS NULL OR a.metricId = :metricId) AND " +
            "(:alarmType IS NULL OR a.alarmType = :alarmType) AND " +
            "(:processStatus IS NULL OR a.processStatus = :processStatus) AND " +
            "(:shieldStatus IS NULL OR a.shieldStatus = :shieldStatus) AND " +
            "(:alarmContent IS NULL OR a.alarmContent LIKE :alarmContent) AND " +
            "(:alarmTimeStart IS NULL OR a.alarmTime >= :alarmTimeStart) AND " +
            "(:alarmTimeEnd IS NULL OR a.alarmTime <= :alarmTimeEnd)")
    Page<Alarm> search(
            @Param("systemId") Long systemId,
            @Param("categoryId") Long categoryId,
            @Param("metricId") Long metricId,
            @Param("alarmType") String alarmType,
            @Param("processStatus") Integer processStatus,
            @Param("shieldStatus") Integer shieldStatus,
            @Param("alarmContent") String alarmContent,
            @Param("alarmTimeStart") LocalDateTime alarmTimeStart,
            @Param("alarmTimeEnd") LocalDateTime alarmTimeEnd,
            Pageable pageable);

    Long countByProcessStatus(Integer processStatus);

    Long countByAlarmTimeAfter(LocalDateTime startTime);

    boolean existsByAlarmContentContainingAndAlarmTimeAfter(String content, LocalDateTime time);

    Optional<Alarm> findBySourceTableAndSourceIdAndMetricIdAndStrategyIdAndProcessStatus(
            String sourceTable, Long sourceId, Long metricId, Long strategyId, Integer processStatus);

    Page<Alarm> findByStrategyId(Long strategyId, Pageable pageable);

    Optional<Alarm> findBySourceTableAndSourceIdAndStrategyIdAndProcessStatus(
            String sourceTable, Long sourceId, Long strategyId, Integer processStatus);

    // 使用 existsBy 避免 (strategy_id, metric_id, processStatus) 上有多条不同 alarm_type 的行
    // 时触发 IncorrectResultSizeDataAccessException（之前 findBy 返回 Optional<Alarm> 在 mock 与新生成器并存场景下报"Query did not return a unique result"）
    boolean existsByStrategyIdAndMetricIdAndProcessStatus(
            Long strategyId, Long metricId, Integer processStatus);

    @Query("SELECT COUNT(a) > 0 FROM Alarm a WHERE a.strategyId = :strategyId AND a.metricId = :metricId AND a.processStatus IN :processStatuses")
    boolean existsByStrategyIdAndMetricIdAndProcessStatusIn(
            @Param("strategyId") Long strategyId,
            @Param("metricId") Long metricId,
            @Param("processStatuses") List<Integer> processStatuses);

    @Query("SELECT a FROM Alarm a WHERE a.shieldStatus = 1 AND a.shieldEndTime <= :now AND a.shieldUnshieldTime IS NULL")
    List<Alarm> findExpiredShields(@Param("now") LocalDateTime now);

    List<Alarm> findByStrategyIdAndProcessStatus(Long strategyId, Integer processStatus);

    // 健康度告警去重查询
    Optional<Alarm> findByStrategyIdAndSystemIdAndAlarmTypeAndProcessStatus(
            Long strategyId, Long systemId, String alarmType, Integer processStatus);

    Optional<Alarm> findByStrategyIdAndCategoryNameAndAlarmTypeAndProcessStatus(
            Long strategyId, String categoryName, String alarmType, Integer processStatus);

    Optional<Alarm> findByStrategyIdAndSubCategoryNameAndAlarmTypeAndProcessStatus(
            Long strategyId, String subCategoryName, String alarmType, Integer processStatus);

    Optional<Alarm> findByStrategyIdAndMetricIdAndAlarmTypeAndProcessStatus(
            Long strategyId, Long metricId, String alarmType, Integer processStatus);
}
