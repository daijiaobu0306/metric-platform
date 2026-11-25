package com.metric.alarm.repository;

import com.metric.alarm.entity.AlarmStrategy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlarmStrategyRepository extends JpaRepository<AlarmStrategy, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AlarmStrategy s WHERE s.targetType = :targetType AND s.targetId = :targetId ORDER BY s.id")
    List<AlarmStrategy> lockTargetStrategies(@Param("targetType") String targetType, @Param("targetId") Long targetId);

    List<AlarmStrategy> findByTargetTypeAndTargetId(String targetType, Long targetId);

    List<AlarmStrategy> findByStrategyType(String strategyType);

    List<AlarmStrategy> findByStatus(Integer status);

    List<AlarmStrategy> findByStatusAndStrategyType(Integer status, String strategyType);

    List<AlarmStrategy> findByTargetTypeAndTargetIdAndStatus(String targetType, Long targetId, Integer status);

    @Query("SELECT s FROM AlarmStrategy s WHERE " +
           "(s.id = :id OR :id IS NULL) AND " +
           "(s.strategyName LIKE CONCAT('%', :name, '%') OR :name IS NULL OR :name = '') AND " +
           "(s.strategyType = :strategyType OR :strategyType IS NULL OR :strategyType = '') AND " +
           "(s.targetType = :targetType OR :targetType IS NULL OR :targetType = '') AND " +
           "(s.status = :status OR :status IS NULL) AND " +
           "(s.alarmEnabled = :alarmEnabled OR :alarmEnabled IS NULL) AND " +
           "(s.gongdanEnabled = :gongdanEnabled OR :gongdanEnabled IS NULL)")
    Page<AlarmStrategy> findByFilters(
        @Param("id") Long id,
        @Param("name") String name,
        @Param("strategyType") String strategyType,
        @Param("targetType") String targetType,
        @Param("status") Integer status,
        @Param("alarmEnabled") Integer alarmEnabled,
        @Param("gongdanEnabled") Integer gongdanEnabled,
        Pageable pageable
    );
}
