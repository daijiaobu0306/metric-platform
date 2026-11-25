package com.metric.metric.repository;

import com.metric.metric.entity.MetricItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MetricItemRepository extends JpaRepository<MetricItem, Long> {

    List<MetricItem> findByCategoryId(Long categoryId);

    List<MetricItem> findByCategoryIdAndStatus(Long categoryId, Integer status);

    Optional<MetricItem> findByCategoryIdAndMetricCode(Long categoryId, String metricCode);

    List<MetricItem> findByStatus(Integer status);

    List<MetricItem> findBySubCategoryName(String subCategoryName);

    /**
     * 按小类名 + 状态查询。
     * 2026-10-28「指标状态=总电闸」改造新增：算分、健康度计算只应处理启用的指标，
     * 已禁用的指标必须完全退出计算链路，否则会出现"页面停用了但后台还在算分/告警"。
     */
    List<MetricItem> findBySubCategoryNameAndStatus(String subCategoryName, Integer status);

    List<MetricItem> findBySubCategoryCode(String subCategoryCode);

    long countByStatus(Integer status);

    @Query("SELECT COUNT(i) FROM MetricItem i WHERE i.status = 1 OR i.status IS NULL")
    long countEnabledItems();

    /**
     * 分页筛选（含「告警/工单是否已配置」筛选）。
     *
     * <p>alarmConfigured / gongdanConfigured 取值：null = 不筛，'yes' = 只看已配置，
     * 'no' = 只看未配置。「已配置」的口径与 AlarmStrategyQueryService 完全一致：
     * 存在启用的指标级策略（status 为空或非 0）、且对应阈值有值。
     * 用字符串而不是 Boolean 传参，避免 JPQL 布尔参数在不同方言下的比较歧义。
     */
    @Query("SELECT i FROM MetricItem i WHERE " +
           "(:systemId IS NULL OR EXISTS (SELECT 1 FROM MetricCategory c WHERE c.id = i.categoryId AND c.systemId = :systemId)) AND " +
           "(:categoryId IS NULL OR i.categoryId = :categoryId) AND " +
           "(:subCategoryName IS NULL OR i.subCategoryName = :subCategoryName) AND " +
           "(:metricType IS NULL OR i.metricType = :metricType) AND " +
           "(:status IS NULL OR i.status = :status) AND " +
           "(:metricName IS NULL OR i.metricName LIKE %:metricName%) AND " +
           "(:metricCode IS NULL OR i.metricCode LIKE %:metricCode%) AND " +
           "(:metricDefinition IS NULL OR i.metricDefinition LIKE %:metricDefinition%) AND " +
           "(:alarmConfigured IS NULL OR " +
           "   (:alarmConfigured = 'yes' AND EXISTS (SELECT 1 FROM AlarmStrategy s WHERE s.targetType = 'metric' AND s.strategyType = 'metric' AND s.targetId = i.id AND s.alarmThreshold IS NOT NULL AND (s.status IS NULL OR s.status <> 0))) OR " +
           "   (:alarmConfigured = 'no' AND NOT EXISTS (SELECT 1 FROM AlarmStrategy s WHERE s.targetType = 'metric' AND s.strategyType = 'metric' AND s.targetId = i.id AND s.alarmThreshold IS NOT NULL AND (s.status IS NULL OR s.status <> 0)))) AND " +
           "(:gongdanConfigured IS NULL OR " +
           "   (:gongdanConfigured = 'yes' AND EXISTS (SELECT 1 FROM AlarmStrategy s WHERE s.targetType = 'metric' AND s.strategyType = 'metric' AND s.targetId = i.id AND s.gongdanThreshold IS NOT NULL AND (s.status IS NULL OR s.status <> 0))) OR " +
           "   (:gongdanConfigured = 'no' AND NOT EXISTS (SELECT 1 FROM AlarmStrategy s WHERE s.targetType = 'metric' AND s.strategyType = 'metric' AND s.targetId = i.id AND s.gongdanThreshold IS NOT NULL AND (s.status IS NULL OR s.status <> 0))))")
    Page<MetricItem> findWithFilters(
            @Param("systemId") Long systemId,
            @Param("categoryId") Long categoryId,
            @Param("subCategoryName") String subCategoryName,
            @Param("metricType") String metricType,
            @Param("status") Integer status,
            @Param("metricName") String metricName,
            @Param("metricCode") String metricCode,
            @Param("metricDefinition") String metricDefinition,
            @Param("alarmConfigured") String alarmConfigured,
            @Param("gongdanConfigured") String gongdanConfigured,
            Pageable pageable);
}
