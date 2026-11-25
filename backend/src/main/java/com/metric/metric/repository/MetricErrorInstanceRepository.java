package com.metric.metric.repository;

import com.metric.metric.entity.MetricErrorInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricErrorInstanceRepository extends JpaRepository<MetricErrorInstance, Long> {
    List<MetricErrorInstance> findByInsertTime(LocalDateTime insertTime);
    void deleteByInsertTime(LocalDateTime insertTime);
}