package com.metric.observe.repository;

import com.metric.observe.entity.DashboardViewConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DashboardViewConfigRepository extends JpaRepository<DashboardViewConfig, Long> {

    List<DashboardViewConfig> findByStatusOrderBySortOrderAsc(Integer status);

    Optional<DashboardViewConfig> findByViewCode(String viewCode);

    Optional<DashboardViewConfig> findByIsDefault(Integer isDefault);

    List<DashboardViewConfig> findByCategoryNameOrderBySortOrderAsc(String categoryName);

    List<DashboardViewConfig> findByCategoryNameAndSubCategoryNameOrderBySortOrderAsc(String categoryName, String subCategoryName);
}
