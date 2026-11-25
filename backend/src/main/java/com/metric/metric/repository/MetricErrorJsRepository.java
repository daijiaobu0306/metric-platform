package com.metric.metric.repository;

import com.metric.metric.entity.MetricErrorJs;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricErrorJsRepository extends JpaRepository<MetricErrorJs, Long> {
    List<MetricErrorJs> findByInsertTime(LocalDateTime insertTime);
    void deleteByInsertTime(LocalDateTime insertTime);
}