package com.metric.metric.repository;

import com.metric.metric.entity.MetricErrorReturnCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricErrorReturnCodeRepository extends JpaRepository<MetricErrorReturnCode, Long> {
    List<MetricErrorReturnCode> findByInsertTime(LocalDateTime insertTime);
    void deleteByInsertTime(LocalDateTime insertTime);
}