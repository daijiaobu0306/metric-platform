package com.metric.gongdan.repository;

import com.metric.gongdan.entity.Gongdan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GongdanRepository extends JpaRepository<Gongdan, Long> {

    Page<Gongdan> findByClosedStatus(Integer closedStatus, Pageable pageable);

    Page<Gongdan> findBySystemId(Long systemId, Pageable pageable);

    @Query("SELECT g FROM Gongdan g WHERE g.gongdanTime BETWEEN :startTime AND :endTime")
    List<Gongdan> findByGongdanTimeBetween(@Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT g FROM Gongdan g WHERE " +
            "(:gongdanId IS NULL OR g.gongdanId LIKE :gongdanId) AND " +
            "(:gongdanTitle IS NULL OR g.gongdanTitle LIKE :gongdanTitle) AND " +
            "(:gongdanContent IS NULL OR g.gongdanContent LIKE :gongdanContent) AND " +
            "(:gongdanType IS NULL OR g.gongdanType = :gongdanType) AND " +
            "(:currentStep IS NULL OR g.currentStep LIKE :currentStep) AND " +
            "(:currentHandler IS NULL OR g.currentHandler LIKE :currentHandler) AND " +
            "(:gongdanTimeStart IS NULL OR g.gongdanTime >= :gongdanTimeStart) AND " +
            "(:gongdanTimeEnd IS NULL OR g.gongdanTime <= :gongdanTimeEnd) AND " +
            "(:closedStatus IS NULL OR g.closedStatus = :closedStatus) AND " +
            "(:externalId IS NULL OR g.externalId LIKE :externalId) AND " +
            "(:alarmObject IS NULL OR g.alarmObject LIKE :alarmObject) AND " +
            "(:alarmLevel IS NULL OR g.alarmLevel = :alarmLevel) AND " +
            "(:synced IS NULL OR g.synced = :synced)")
    Page<Gongdan> search(
            @Param("gongdanId") String gongdanId,
            @Param("gongdanTitle") String gongdanTitle,
            @Param("gongdanContent") String gongdanContent,
            @Param("gongdanType") String gongdanType,
            @Param("currentStep") String currentStep,
            @Param("currentHandler") String currentHandler,
            @Param("gongdanTimeStart") LocalDateTime gongdanTimeStart,
            @Param("gongdanTimeEnd") LocalDateTime gongdanTimeEnd,
            @Param("closedStatus") Integer closedStatus,
            @Param("externalId") String externalId,
            @Param("alarmObject") String alarmObject,
            @Param("alarmLevel") Integer alarmLevel,
            @Param("synced") Integer synced,
            Pageable pageable);

    @Query("SELECT g FROM Gongdan g WHERE " +
            "(:gongdanId IS NULL OR g.gongdanId LIKE :gongdanId) AND " +
            "(:gongdanTitle IS NULL OR g.gongdanTitle LIKE :gongdanTitle) AND " +
            "(:gongdanContent IS NULL OR g.gongdanContent LIKE :gongdanContent) AND " +
            "(:gongdanType IS NULL OR g.gongdanType = :gongdanType) AND " +
            "(:currentStep IS NULL OR g.currentStep LIKE :currentStep) AND " +
            "(:currentHandler IS NULL OR g.currentHandler LIKE :currentHandler) AND " +
            "(:gongdanTimeStart IS NULL OR g.gongdanTime >= :gongdanTimeStart) AND " +
            "(:gongdanTimeEnd IS NULL OR g.gongdanTime <= :gongdanTimeEnd) AND " +
            "(:closedStatus IS NULL OR g.closedStatus = :closedStatus)")
    Page<Gongdan> search(
            @Param("gongdanId") String gongdanId,
            @Param("gongdanTitle") String gongdanTitle,
            @Param("gongdanContent") String gongdanContent,
            @Param("gongdanType") String gongdanType,
            @Param("currentStep") String currentStep,
            @Param("currentHandler") String currentHandler,
            @Param("gongdanTimeStart") LocalDateTime gongdanTimeStart,
            @Param("gongdanTimeEnd") LocalDateTime gongdanTimeEnd,
            @Param("closedStatus") Integer closedStatus,
            Pageable pageable);

    Long countByClosedStatus(Integer closedStatus);

    /** 按 BOMC 同步状态统计（0=未推送/推送失败，1=已推送成功） */
    Long countBySynced(Integer synced);

    /** 指定时间之后创建、且未成功推送 BOMC 的工单（按创建时间倒序），用于补推 */
    List<Gongdan> findBySyncedAndCreateTimeAfterOrderByCreateTimeDesc(Integer synced, LocalDateTime createTime);

    Long countByGongdanTimeAfter(LocalDateTime startTime);

    List<Gongdan> findByMetricIdOrderByGongdanTimeDesc(Long metricId);

    List<Gongdan> findBySyncedOrderByCreateTimeDesc(Integer synced);

    // 趋势图查询 - 按小时
    @Query(value = "SELECT DATE_FORMAT(g.gongdan_time, '%Y-%m-%d %H:00:00') AS time_point, COUNT(*) FROM gongdan g WHERE g.gongdan_time >= :start AND g.gongdan_time <= :end GROUP BY DATE_FORMAT(g.gongdan_time, '%Y-%m-%d %H:00:00') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByHour(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // 趋势图查询 - 按天
    @Query(value = "SELECT DATE(g.gongdan_time) AS time_point, COUNT(*) FROM gongdan g WHERE g.gongdan_time >= :start AND g.gongdan_time <= :end GROUP BY DATE(g.gongdan_time) ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByDay(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // 趋势图查询 - 按月
    @Query(value = "SELECT DATE_FORMAT(g.gongdan_time, '%Y-%m') AS time_point, COUNT(*) FROM gongdan g WHERE g.gongdan_time >= :start AND g.gongdan_time <= :end GROUP BY DATE_FORMAT(g.gongdan_time, '%Y-%m') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByMonth(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // 趋势图查询 - 按小时 + 状态筛选
    @Query(value = "SELECT DATE_FORMAT(g.gongdan_time, '%Y-%m-%d %H:00:00') AS time_point, COUNT(*) FROM gongdan g WHERE g.gongdan_time >= :start AND g.gongdan_time <= :end AND g.closed_status = :closedStatus GROUP BY DATE_FORMAT(g.gongdan_time, '%Y-%m-%d %H:00:00') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByHourWithStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("closedStatus") Integer closedStatus);

    // 趋势图查询 - 按天 + 状态筛选
    @Query(value = "SELECT DATE(g.gongdan_time) AS time_point, COUNT(*) FROM gongdan g WHERE g.gongdan_time >= :start AND g.gongdan_time <= :end AND g.closed_status = :closedStatus GROUP BY DATE(g.gongdan_time) ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByDayWithStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("closedStatus") Integer closedStatus);

    // 趋势图查询 - 按月 + 状态筛选
    @Query(value = "SELECT DATE_FORMAT(g.gongdan_time, '%Y-%m') AS time_point, COUNT(*) FROM gongdan g WHERE g.gongdan_time >= :start AND g.gongdan_time <= :end AND g.closed_status = :closedStatus GROUP BY DATE_FORMAT(g.gongdan_time, '%Y-%m') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByMonthWithStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("closedStatus") Integer closedStatus);

    // 按策略ID去重查询未关单工单
    List<Gongdan> findByStrategyIdAndClosedStatus(Long strategyId, Integer closedStatus);
}
