package com.metric.alarm.repository;

import com.metric.alarm.entity.AlarmTriggerHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TriggerHistoryRepository extends JpaRepository<AlarmTriggerHistory, Long> {

    @Query("SELECT h FROM AlarmTriggerHistory h WHERE h.strategyId = :strategyId " +
           "AND h.sourceTable = :sourceTable AND h.sourceId = :sourceId " +
           "AND h.triggerTime >= :windowStart ORDER BY h.triggerTime DESC")
    List<AlarmTriggerHistory> findByStrategyAndSource(
            @Param("strategyId") Long strategyId,
            @Param("sourceTable") String sourceTable,
            @Param("sourceId") Long sourceId,
            @Param("windowStart") LocalDateTime windowStart);

    @Query("SELECT h FROM AlarmTriggerHistory h WHERE h.strategyId = :strategyId " +
           "AND h.metricId = :metricId AND h.triggerTime >= :windowStart ORDER BY h.triggerTime DESC")
    List<AlarmTriggerHistory> findByStrategyAndMetric(
            @Param("strategyId") Long strategyId,
            @Param("metricId") Long metricId,
            @Param("windowStart") LocalDateTime windowStart);

    @Modifying
    @Query("DELETE FROM AlarmTriggerHistory h WHERE h.triggerTime < :before")
    void deleteOldRecords(@Param("before") LocalDateTime before);
}
