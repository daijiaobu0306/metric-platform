package com.metric.metric.repository;

import com.metric.metric.entity.MetricErrorBizReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricErrorBizReturnRepository extends JpaRepository<MetricErrorBizReturn, Long> {
    List<MetricErrorBizReturn> findByInsertTime(LocalDateTime insertTime);
    void deleteByInsertTime(LocalDateTime insertTime);
}