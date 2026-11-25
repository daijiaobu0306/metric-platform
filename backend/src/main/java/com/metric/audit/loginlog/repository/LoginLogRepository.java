package com.metric.audit.loginlog.repository;

import com.metric.audit.loginlog.entity.LoginLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LoginLogRepository extends JpaRepository<LoginLog, Long>, JpaSpecificationExecutor<LoginLog> {

    /** 找某用户当前 ONLINE 的最新一条（用于 recordLogout 改 OFFLINE） */
    Optional<LoginLog> findFirstByUsernameAndStatusOrderByLoginTimeDesc(String username, String status);

    /** 超时扫描：找所有 status=ONLINE 且 login_time < threshold 的行 */
    List<LoginLog> findByStatusAndLoginTimeBefore(String status, LocalDateTime threshold);

    /** 看板：当前在线总人数 */
    long countByStatus(String status);

    /** 看板：自某时间起登录次数（仅 LOCAL/SSO，不计 LOGOUT） */
    @Query("SELECT COUNT(l) FROM LoginLog l WHERE l.loginTime >= :start AND l.source IN ('LOCAL','SSO')")
    long countLoginsSince(@Param("start") LocalDateTime start);

    /** 看板：自某时间起 distinct 用户数（仅 LOCAL/SSO） */
    @Query("SELECT COUNT(DISTINCT l.username) FROM LoginLog l WHERE l.loginTime >= :start AND l.source IN ('LOCAL','SSO')")
    long countDistinctUsersSince(@Param("start") LocalDateTime start);

    /** 看板：自某时间起登录总时长（分钟，NULL 忽略） */
    @Query("SELECT COALESCE(SUM(l.durationMinutes), 0) FROM LoginLog l " +
           "WHERE l.loginTime >= :start AND l.durationMinutes IS NOT NULL")
    long sumDurationSince(@Param("start") LocalDateTime start);

    /** 趋势：自某时间起每日 login 次数（native SQL 走 DATE(login_time)） */
    @Query(value = "SELECT DATE(login_time) AS d, COUNT(*) AS c FROM login_log " +
                   "WHERE login_time >= :start AND source IN ('LOCAL','SSO') " +
                   "GROUP BY DATE(login_time) ORDER BY d", nativeQuery = true)
    List<Object[]> dailyTrendSince(@Param("start") LocalDateTime start);

    /** 趋势：自某时间起每小时 login 次数 */
    @Query(value = "SELECT HOUR(login_time) AS h, COUNT(*) AS c FROM login_log " +
                   "WHERE login_time >= :start AND source IN ('LOCAL','SSO') " +
                   "GROUP BY HOUR(login_time) ORDER BY h", nativeQuery = true)
    List<Object[]> hourlyTrendSince(@Param("start") LocalDateTime start);
}