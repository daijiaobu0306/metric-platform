package com.metric.issue.repository;

import com.metric.issue.entity.Issue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface IssueRepository extends JpaRepository<Issue, Long> {

    Optional<Issue> findByIssueCode(String issueCode);

    Page<Issue> findByResolveStatus(String resolveStatus, Pageable pageable);

    Page<Issue> findBySystemId(Long systemId, Pageable pageable);

    @Query("SELECT i FROM Issue i WHERE i.registerTime BETWEEN :startTime AND :endTime")
    List<Issue> findByRegisterTimeBetween(@Param("startTime") LocalDateTime startTime,
                                           @Param("endTime") LocalDateTime endTime);

    @Query("SELECT i FROM Issue i WHERE " +
            "(:resolveStatus IS NULL OR i.resolveStatus = :resolveStatus) AND " +
            "(:systemId IS NULL OR i.systemId = :systemId) AND " +
            "(:categoryId IS NULL OR i.categoryId = :categoryId) AND " +
            "(:metricId IS NULL OR i.metricId = :metricId) AND " +
            "(:issueType IS NULL OR i.issueType = :issueType) AND " +
            "(:issueSource IS NULL OR i.issueSource = :issueSource) AND " +
            "(:issueAttribute IS NULL OR i.issueAttribute = :issueAttribute) AND " +
            "(:discoverer IS NULL OR i.discoverer LIKE :discoverer) AND " +
            "(:responsiblePerson IS NULL OR i.responsiblePerson LIKE :responsiblePerson) AND " +
            "(:keyword IS NULL OR i.issueCode LIKE :keyword OR i.issueDescription LIKE :keyword) AND " +
            "(:registerTimeStart IS NULL OR i.registerTime >= :registerTimeStart) AND " +
            "(:registerTimeEnd IS NULL OR i.registerTime <= :registerTimeEnd) AND " +
            "(:hasAttachment IS NULL OR " +
            "  (:hasAttachment = true AND i.attachmentPath IS NOT NULL) OR " +
            "  (:hasAttachment = false AND i.attachmentPath IS NULL)) AND " +
            "(:hasCheckReason IS NULL OR " +
            "  (:hasCheckReason = true AND i.checkReason IS NOT NULL AND i.checkReason <> '') OR " +
            "  (:hasCheckReason = false AND (i.checkReason IS NULL OR i.checkReason = ''))) AND " +
            "(:tagStatus IS NULL OR i.tagStatus = :tagStatus)")
    Page<Issue> search(
            @Param("resolveStatus") String resolveStatus,
            @Param("systemId") Long systemId,
            @Param("categoryId") Long categoryId,
            @Param("metricId") Long metricId,
            @Param("issueType") String issueType,
            @Param("issueSource") String issueSource,
            @Param("issueAttribute") String issueAttribute,
            @Param("discoverer") String discoverer,
            @Param("responsiblePerson") String responsiblePerson,
            @Param("keyword") String keyword,
            @Param("registerTimeStart") LocalDateTime registerTimeStart,
            @Param("registerTimeEnd") LocalDateTime registerTimeEnd,
            @Param("hasAttachment") Boolean hasAttachment,
            @Param("hasCheckReason") Boolean hasCheckReason,
            @Param("tagStatus") Integer tagStatus,
            Pageable pageable);

    Long countByResolveStatus(String resolveStatus);

    Long countByRegisterTimeAfter(LocalDateTime startTime);

    @Query("SELECT i FROM Issue i WHERE i.resolveStatus != '已解决' AND i.planResolveTime IS NOT NULL AND i.planResolveTime < :now")
    List<Issue> findOverdueUnresolvedIssues(@Param("now") LocalDateTime now);

    // 趋势图查询 - 按小时
    @Query(value = "SELECT DATE_FORMAT(i.register_time, '%Y-%m-%d %H:00:00') AS time_point, COUNT(*) FROM issue i WHERE i.register_time >= :start AND i.register_time <= :end GROUP BY DATE_FORMAT(i.register_time, '%Y-%m-%d %H:00:00') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByHour(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // 趋势图查询 - 按天
    @Query(value = "SELECT DATE(i.register_time) AS time_point, COUNT(*) FROM issue i WHERE i.register_time >= :start AND i.register_time <= :end GROUP BY DATE(i.register_time) ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByDay(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // 趋势图查询 - 按月
    @Query(value = "SELECT DATE_FORMAT(i.register_time, '%Y-%m') AS time_point, COUNT(*) FROM issue i WHERE i.register_time >= :start AND i.register_time <= :end GROUP BY DATE_FORMAT(i.register_time, '%Y-%m') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByMonth(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // 趋势图查询 - 按小时 + 状态筛选
    @Query(value = "SELECT DATE_FORMAT(i.register_time, '%Y-%m-%d %H:00:00') AS time_point, COUNT(*) FROM issue i WHERE i.register_time >= :start AND i.register_time <= :end AND i.resolve_status = :resolveStatus GROUP BY DATE_FORMAT(i.register_time, '%Y-%m-%d %H:00:00') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByHourWithStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("resolveStatus") String resolveStatus);

    // 趋势图查询 - 按天 + 状态筛选
    @Query(value = "SELECT DATE(i.register_time) AS time_point, COUNT(*) FROM issue i WHERE i.register_time >= :start AND i.register_time <= :end AND i.resolve_status = :resolveStatus GROUP BY DATE(i.register_time) ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByDayWithStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("resolveStatus") String resolveStatus);

    // 趋势图查询 - 按月 + 状态筛选
    @Query(value = "SELECT DATE_FORMAT(i.register_time, '%Y-%m') AS time_point, COUNT(*) FROM issue i WHERE i.register_time >= :start AND i.register_time <= :end AND i.resolve_status = :resolveStatus GROUP BY DATE_FORMAT(i.register_time, '%Y-%m') ORDER BY time_point", nativeQuery = true)
    List<Object[]> getTrendByMonthWithStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("resolveStatus") String resolveStatus);

    // 系统名建议：distinct + LIKE 模糊匹配 + 按字母升序 + 上限 limit
    @Query(value = "SELECT i.system_name FROM issue i " +
            "WHERE i.system_name IS NOT NULL AND i.system_name <> '' " +
            "AND (:keyword IS NULL OR i.system_name LIKE :keyword) " +
            "GROUP BY i.system_name " +
            "ORDER BY i.system_name ASC " +
            "LIMIT :limit", nativeQuery = true)
    List<String> findDistinctSystemNames(@Param("keyword") String keyword,
                                        @Param("limit") int limit);

    // 指标大类建议：distinct + LIKE 模糊匹配 + 按字母升序 + 上限 limit
    @Query(value = "SELECT i.category_name FROM issue i " +
            "WHERE i.category_name IS NOT NULL AND i.category_name <> '' " +
            "AND (:keyword IS NULL OR i.category_name LIKE :keyword) " +
            "GROUP BY i.category_name " +
            "ORDER BY i.category_name ASC " +
            "LIMIT :limit", nativeQuery = true)
    List<String> findDistinctCategoryNames(@Param("keyword") String keyword,
                                           @Param("limit") int limit);

    // 指标小类建议：distinct + LIKE 模糊匹配 + 按字母升序 + 上限 limit
    @Query(value = "SELECT i.sub_category_name FROM issue i " +
            "WHERE i.sub_category_name IS NOT NULL AND i.sub_category_name <> '' " +
            "AND (:keyword IS NULL OR i.sub_category_name LIKE :keyword) " +
            "GROUP BY i.sub_category_name " +
            "ORDER BY i.sub_category_name ASC " +
            "LIMIT :limit", nativeQuery = true)
    List<String> findDistinctSubCategoryNames(@Param("keyword") String keyword,
                                              @Param("limit") int limit);

    // 按发现者聚合：问题总数 / 核查原因非空数 / 附件数；按问题数降序，返回 Top N
    // 注意：发现者字段对前后空格做 TRIM 合并，避免 "张三" / " 张三" / "张三 " 算成 3 条记录
    @Query(value = "SELECT " +
            "  TRIM(i.discoverer) AS discoverer, " +
            "  COUNT(*) AS total, " +
            "  SUM(CASE WHEN i.check_reason IS NOT NULL AND i.check_reason <> '' THEN 1 ELSE 0 END) AS with_check_reason, " +
            "  SUM(CASE WHEN i.attachment_path IS NOT NULL AND i.attachment_path <> '' THEN 1 ELSE 0 END) AS with_attachment " +
            "FROM issue i " +
            "WHERE i.discoverer IS NOT NULL AND TRIM(i.discoverer) <> '' " +
            "  AND (:startTime IS NULL OR i.register_time >= :startTime) " +
            "  AND (:endTime IS NULL OR i.register_time <= :endTime) " +
            "GROUP BY TRIM(i.discoverer) " +
            "ORDER BY total DESC, TRIM(i.discoverer) ASC " +
            "LIMIT :limit", nativeQuery = true)
    List<Object[]> getDiscovererStats(@Param("startTime") LocalDateTime startTime,
                                      @Param("endTime") LocalDateTime endTime,
                                      @Param("limit") int limit);
}
