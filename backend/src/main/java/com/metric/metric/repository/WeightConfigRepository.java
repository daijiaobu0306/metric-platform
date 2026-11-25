package com.metric.metric.repository;

import com.metric.metric.entity.WeightConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WeightConfigRepository extends JpaRepository<WeightConfig, Long> {

    List<WeightConfig> findBySystemId(Long systemId);

    List<WeightConfig> findBySystemIdAndWeightType(Long systemId, String weightType);

    List<WeightConfig> findByCategoryId(Long categoryId);

    List<WeightConfig> findByCategoryIdAndWeightType(Long categoryId, String weightType);

    List<WeightConfig> findBySubCategoryNameAndWeightType(String subCategoryName, String weightType);

    List<WeightConfig> findByMetricId(Long metricId);

    Optional<WeightConfig> findBySystemIdAndCategoryIdAndWeightType(Long systemId, Long categoryId, String weightType);

    Optional<WeightConfig> findByCategoryIdAndSubCategoryNameAndWeightType(Long categoryId, String subCategoryName, String weightType);

    void deleteBySystemIdAndWeightType(Long systemId, String weightType);

    void deleteByCategoryIdAndWeightType(Long categoryId, String weightType);
}
