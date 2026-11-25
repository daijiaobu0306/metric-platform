package com.metric.alarm.repository;

import com.metric.alarm.entity.ShieldRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShieldRuleRepository extends JpaRepository<ShieldRule, Long> {

    List<ShieldRule> findByShieldTypeAndTargetId(String shieldType, Long targetId);

    Optional<ShieldRule> findByShieldTypeAndTargetIdAndShieldEndTimeIsNullOrShieldEndTimeAfter(
            String shieldType, Long targetId, LocalDateTime now);

    @Query("SELECT s FROM ShieldRule s WHERE s.shieldType = :shieldType AND s.targetId = :targetId " +
           "AND (s.shieldEndTime IS NULL OR s.shieldEndTime > :now)")
    List<ShieldRule> findActiveRules(
            @Param("shieldType") String shieldType,
            @Param("targetId") Long targetId,
            @Param("now") LocalDateTime now);

    @Query("SELECT s FROM ShieldRule s WHERE s.shieldEndTime IS NOT NULL AND s.shieldEndTime <= :now")
    List<ShieldRule> findExpiredRules(@Param("now") LocalDateTime now);

    boolean existsByShieldTypeAndTargetIdAndShieldEndTimeIsNullOrShieldEndTimeAfter(
            String shieldType, Long targetId, LocalDateTime now);
}
