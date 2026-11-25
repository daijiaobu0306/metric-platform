package com.metric.alarm.repository;

import com.metric.alarm.entity.AlarmAnalysisConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AlarmAnalysisConfigRepository extends JpaRepository<AlarmAnalysisConfig, Long> {
}
