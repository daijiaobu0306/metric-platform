package com.metric.security.repository;

import com.metric.security.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long>, JpaSpecificationExecutor<UserSession> {

    Optional<UserSession> findBySessionKey(String sessionKey);

    List<UserSession> findByIsActiveOrderByLoginTimeDesc(Integer isActive);

    @Query("SELECT COUNT(u) FROM UserSession u WHERE u.isActive = 1")
    long countActive();

    @Query("SELECT u FROM UserSession u WHERE u.isActive = 1 AND u.lastHeartbeat < :threshold")
    List<UserSession> findStaleSessions(@Param("threshold") LocalDateTime threshold);

    @Modifying
    @Query(value = "UPDATE user_session SET is_active = 0, logout_time = :now, duration_minutes = GREATEST(0, CEIL(TIMESTAMPDIFF(SECOND, login_time, :now) / 60)) WHERE is_active = 1 AND last_heartbeat < :threshold", nativeQuery = true)
    int markStaleInactive(@Param("threshold") LocalDateTime threshold, @Param("now") LocalDateTime now);

    // ============================================================
    // 登录日志统计看板（Issue 1B）
    //   - summary: 总数 / 总时长 / 去重人数（按筛选条件过滤，与 /api/session/logs 一致）
    //   - daily:   按天 group by，加 count / 总时长 / 去重人数
    //   - 所有空值参数按"无此限制"处理
    // ============================================================

    /**
     * 概要：会话总数 / 总时长 / 去重登录人数。
     * username / browserName / osName / deviceType / clientIp 任一非空都参与过滤。
     * loginTimeStart / loginTimeEnd 控制时间窗。
     *
     * 注：native SQL aggregate 即使单行也按 List&lt;Object[]&gt; 返回，外层 = 行数，内层 = 列值。
     */
    @Query(value =
        "SELECT " +
        "  COUNT(*) AS total_count, " +
        "  COALESCE(SUM(u.duration_minutes), 0) AS total_duration_minutes, " +
        "  COUNT(DISTINCT u.username) AS distinct_user_count " +
        "FROM user_session u " +
        "WHERE (:startTime IS NULL OR u.login_time >= :startTime) " +
        "  AND (:endTime IS NULL OR u.login_time <= :endTime) " +
        "  AND (:username IS NULL OR u.username LIKE CONCAT('%', :username, '%')) " +
        "  AND (:browserName IS NULL OR u.browser_name = :browserName) " +
        "  AND (:osName IS NULL OR u.os_name = :osName) " +
        "  AND (:deviceType IS NULL OR u.device_type = :deviceType) " +
        "  AND (:clientIp IS NULL OR u.client_ip LIKE CONCAT('%', :clientIp, '%'))",
        nativeQuery = true)
    List<Object[]> getSessionLogSummary(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("username") String username,
            @Param("browserName") String browserName,
            @Param("osName") String osName,
            @Param("deviceType") String deviceType,
            @Param("clientIp") String clientIp);

    /**
     * 按天聚合：每日 登录次数 / 总登录时长 / 去重登录人数。
     * 返回列：date（yyyy-MM-dd）/ count / duration_minutes / user_count
     */
    @Query(value =
        "SELECT " +
        "  DATE(u.login_time) AS day, " +
        "  COUNT(*) AS cnt, " +
        "  COALESCE(SUM(u.duration_minutes), 0) AS dur, " +
        "  COUNT(DISTINCT u.username) AS user_cnt " +
        "FROM user_session u " +
        "WHERE (:startTime IS NULL OR u.login_time >= :startTime) " +
        "  AND (:endTime IS NULL OR u.login_time <= :endTime) " +
        "  AND (:username IS NULL OR u.username LIKE CONCAT('%', :username, '%')) " +
        "  AND (:browserName IS NULL OR u.browser_name = :browserName) " +
        "  AND (:osName IS NULL OR u.os_name = :osName) " +
        "  AND (:deviceType IS NULL OR u.device_type = :deviceType) " +
        "  AND (:clientIp IS NULL OR u.client_ip LIKE CONCAT('%', :clientIp, '%')) " +
        "GROUP BY DATE(u.login_time) " +
        "ORDER BY day ASC",
        nativeQuery = true)
    List<Object[]> getSessionLogDaily(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("username") String username,
            @Param("browserName") String browserName,
            @Param("osName") String osName,
            @Param("deviceType") String deviceType,
            @Param("clientIp") String clientIp);
}
