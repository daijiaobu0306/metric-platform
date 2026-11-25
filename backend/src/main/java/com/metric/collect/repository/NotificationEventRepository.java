package com.metric.collect.repository;

import com.metric.collect.entity.NotificationEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationEventRepository extends JpaRepository<NotificationEvent, Long> {

    /**
     * 取 sinceId 之后、且创建时间晚于 loginTime 的事件。
     * loginTime 用来防止跨用户复用本地 lastId 时弹历史事件（每会话只看自己上线之后的事件）。
     */
    @Query("SELECT n FROM NotificationEvent n WHERE n.id > :sinceId AND n.createdAt > :loginTime ORDER BY n.id ASC")
    List<NotificationEvent> findRecentSince(@Param("sinceId") Long sinceId,
                                            @Param("loginTime") LocalDateTime loginTime,
                                            Pageable pageable);

    /** 清理早于指定时间的事件，返回删除条数（通知表按 7 天保留）。 */
    @Modifying
    @Transactional
    @Query("DELETE FROM NotificationEvent n WHERE n.createdAt < :deadline")
    int deleteOlderThan(@Param("deadline") LocalDateTime deadline);

    // ==================== 历史通知管理（V47） ====================
    // 说明：这里的查询/删除只服务「通知历史」管理界面，
    // 与前端轮询的 /recent（带 loginTime 过滤）互不影响。

    /**
     * 历史通知分页查询。
     * 所有条件都可为空：eventType 精确匹配，keyword 同时模糊匹配标题与正文。
     */
    @Query("SELECT n FROM NotificationEvent n WHERE "
            + "(:eventType IS NULL OR n.eventType = :eventType) "
            + "AND (:keyword IS NULL OR n.title LIKE CONCAT('%', :keyword, '%') "
            + "     OR n.content LIKE CONCAT('%', :keyword, '%')) "
            + "AND (:startTime IS NULL OR n.createdAt >= :startTime) "
            + "AND (:endTime IS NULL OR n.createdAt <= :endTime) "
            + "ORDER BY n.id DESC")
    Page<NotificationEvent> search(@Param("eventType") String eventType,
                                   @Param("keyword") String keyword,
                                   @Param("startTime") LocalDateTime startTime,
                                   @Param("endTime") LocalDateTime endTime,
                                   Pageable pageable);

    /** 按条件统计条数（批量删除前给用户看清楚会删多少）。 */
    @Query("SELECT COUNT(n) FROM NotificationEvent n WHERE "
            + "(:eventType IS NULL OR n.eventType = :eventType) "
            + "AND (:keyword IS NULL OR n.title LIKE CONCAT('%', :keyword, '%') "
            + "     OR n.content LIKE CONCAT('%', :keyword, '%')) "
            + "AND (:startTime IS NULL OR n.createdAt >= :startTime) "
            + "AND (:endTime IS NULL OR n.createdAt <= :endTime)")
    long countByFilter(@Param("eventType") String eventType,
                       @Param("keyword") String keyword,
                       @Param("startTime") LocalDateTime startTime,
                       @Param("endTime") LocalDateTime endTime);

    /** 勾选批量删除。 */
    @Modifying
    @Transactional
    @Query("DELETE FROM NotificationEvent n WHERE n.id IN :ids")
    int deleteByIds(@Param("ids") List<Long> ids);

    /**
     * 按条件清理：eventType 为空表示不限类型，deadline 为空表示不限时间。
     * 用原生 SQL，避免空参数在 JPQL 批量删除里的类型推断问题。
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM notification_event WHERE (:eventType IS NULL OR event_type = :eventType) "
            + "AND (:deadline IS NULL OR created_at < :deadline)",
            nativeQuery = true)
    int deleteByFilter(@Param("eventType") String eventType, @Param("deadline") LocalDateTime deadline);

    /** 统计某类型（或全部）事件条数。 */
    @Query("SELECT COUNT(n) FROM NotificationEvent n WHERE (:eventType IS NULL OR n.eventType = :eventType)")
    long countByType(@Param("eventType") String eventType);
}
