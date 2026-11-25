package com.metric.metric.repository;

import com.metric.metric.entity.MetricCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MetricCategoryRepository extends JpaRepository<MetricCategory, Long> {

    List<MetricCategory> findBySystemId(Long systemId);

    List<MetricCategory> findBySystemIdAndStatus(Long systemId, Integer status);

    Optional<MetricCategory> findBySystemIdAndCategoryCode(Long systemId, String categoryCode);

    /**
     * 2026-10-28 导入改造新增：Excel 的「指标清单」只给大类编码、不给系统编码，
     * 需要按大类编码反查。category_code 在库里全局唯一（metric_g1/g2/g4/system_run 等）。
     */
    Optional<MetricCategory> findByCategoryCode(String categoryCode);

    Optional<MetricCategory> findByCategoryName(String categoryName);
}
