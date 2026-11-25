package com.metric.metric.repository;

import com.metric.metric.entity.MetricImportConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetricImportConfigRepository extends JpaRepository<MetricImportConfig, Long> {
    Optional<MetricImportConfig> findBySubCategoryCode(String subCategoryCode);
    List<MetricImportConfig> findByEnabled(Integer enabled);
}