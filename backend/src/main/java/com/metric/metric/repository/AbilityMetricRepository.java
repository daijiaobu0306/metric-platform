package com.metric.metric.repository;

import com.metric.metric.entity.AbilityMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AbilityMetricRepository extends JpaRepository<AbilityMetric, Long>, JpaSpecificationExecutor<AbilityMetric> {

    Optional<AbilityMetric> findByUniqueKey(String uniqueKey);

    List<AbilityMetric> findByDataDate(String dataDate);

    List<AbilityMetric> findByUnifiedCapabilityCode(String unifiedCapabilityCode);

    List<AbilityMetric> findByAbilityUserCode(String abilityUserCode);

    @Query("SELECT a FROM AbilityMetric a WHERE a.dataDate BETWEEN :startDate AND :endDate ORDER BY a.dataDate ASC")
    List<AbilityMetric> findByDateRange(@Param("startDate") String startDate, @Param("endDate") String endDate);

    @Query("SELECT a.unifiedCapabilityCode, COUNT(a) FROM AbilityMetric a " +
           "WHERE a.dataDate BETWEEN :startDate AND :endDate GROUP BY a.unifiedCapabilityCode")
    List<Object[]> groupByCapabilityCode(@Param("startDate") String startDate, @Param("endDate") String endDate);

    @Query("SELECT a.unifiedCapabilityCode, a.abilityUserCode, a.dataDate, COUNT(a) FROM AbilityMetric a " +
           "WHERE a.dataDate BETWEEN :startDate AND :endDate GROUP BY a.unifiedCapabilityCode, a.abilityUserCode, a.dataDate")
    List<Object[]> groupByCapabilityUserDate(@Param("startDate") String startDate, @Param("endDate") String endDate);
}
