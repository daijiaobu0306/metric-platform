package com.metric.metric.repository;

import com.metric.metric.entity.MetricSubCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MetricSubCategoryRepository extends JpaRepository<MetricSubCategory, Long> {

    List<MetricSubCategory> findByCategoryId(Long categoryId);

    List<MetricSubCategory> findByCategoryIdAndStatus(Long categoryId, String status);

    Optional<MetricSubCategory> findByCategoryIdAndSubCategoryCode(Long categoryId, String subCategoryCode);

    @Query("SELECT DISTINCT m.subCategoryCode FROM MetricSubCategory m WHERE m.categoryId = :categoryId")
    List<String> findSubCategoryCodesByCategoryId(@Param("categoryId") Long categoryId);

    boolean existsByCategoryIdAndSubCategoryCode(Long categoryId, String subCategoryCode);
}
