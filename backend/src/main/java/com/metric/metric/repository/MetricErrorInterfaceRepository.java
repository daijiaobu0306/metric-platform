package com.metric.metric.repository;

import com.metric.metric.entity.MetricErrorInterface;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricErrorInterfaceRepository extends JpaRepository<MetricErrorInterface, Long> {
    List<MetricErrorInterface> findByInsertTime(LocalDateTime insertTime);
    void deleteByInsertTime(LocalDateTime insertTime);
}