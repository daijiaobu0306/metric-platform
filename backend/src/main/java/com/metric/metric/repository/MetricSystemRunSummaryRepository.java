package com.metric.metric.repository;

import com.metric.metric.entity.MetricSystemRunSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricSystemRunSummaryRepository extends JpaRepository<MetricSystemRunSummary, Long> {

    List<MetricSystemRunSummary> findByInsertTime(LocalDateTime insertTime);

    void deleteByInsertTime(LocalDateTime insertTime);
}