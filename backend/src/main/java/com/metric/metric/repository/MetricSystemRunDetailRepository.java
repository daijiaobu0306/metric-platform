package com.metric.metric.repository;

import com.metric.metric.entity.MetricSystemRunDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricSystemRunDetailRepository extends JpaRepository<MetricSystemRunDetail, Long> {

    List<MetricSystemRunDetail> findByInsertTime(LocalDateTime insertTime);

    void deleteByInsertTime(LocalDateTime insertTime);
}