package com.metric.alarm.repository;

import com.metric.alarm.entity.RawDataAlarmConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RawDataAlarmConfigRepository extends JpaRepository<RawDataAlarmConfig, Long> {

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "strategy")
    List<RawDataAlarmConfig> findByAlarmStatus(Integer alarmStatus);

    List<RawDataAlarmConfig> findByTableNameAndAlarmStatus(String tableName, Integer alarmStatus);

    List<RawDataAlarmConfig> findByTableNameAndMetricField(String tableName, String metricField);

    List<RawDataAlarmConfig> findByStrategyId(Long strategyId);
}
