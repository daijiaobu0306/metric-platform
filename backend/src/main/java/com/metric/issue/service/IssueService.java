package com.metric.issue.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.issue.entity.Issue;
import com.metric.issue.event.IssueCreatedEvent;
import com.metric.issue.repository.IssueRepository;
import com.metric.security.service.CurrentUserProvider;
import com.metric.audit.service.EntityAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class IssueService {

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private EntityAuditService entityAuditService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String toJson(Issue issue) {
        try {
            return objectMapper.writeValueAsString(issue);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String str) {
        if (str == null) return "";
        return str.length() > 20 ? str.substring(0, 20) + "..." : str;
    }

    public Page<Issue> findAll(Pageable pageable) {
        return issueRepository.findAll(pageable);
    }

    public List<Issue> findAll() {
        return issueRepository.findAll();
    }

    public Optional<Issue> findById(Long id) {
        return issueRepository.findById(id);
    }

    public Page<Issue> findByResolveStatus(String resolveStatus, Pageable pageable) {
        return issueRepository.findByResolveStatus(resolveStatus, pageable);
    }

    public Page<Issue> findBySystemId(Long systemId, Pageable pageable) {
        return issueRepository.findBySystemId(systemId, pageable);
    }

    public List<Issue> findByRegisterTimeBetween(LocalDateTime startTime, LocalDateTime endTime) {
        return issueRepository.findByRegisterTimeBetween(startTime, endTime);
    }

    public Page<Issue> search(
            String resolveStatus,
            Long systemId,
            Long categoryId,
            Long metricId,
            String issueType,
            String issueSource,
            String issueAttribute,
            String discoverer,
            String responsiblePerson,
            String keyword,
            String registerTimeStart,
            String registerTimeEnd,
            Boolean hasAttachment,
            Boolean hasCheckReason,
            Integer tagStatus,
            Pageable pageable) {
        // 将空字符串转为null
        String pIssueType = (issueType != null && !issueType.trim().isEmpty()) ? issueType.trim() : null;
        String pIssueSource = (issueSource != null && !issueSource.trim().isEmpty()) ? issueSource.trim() : null;
        String pIssueAttribute = (issueAttribute != null && !issueAttribute.trim().isEmpty()) ? issueAttribute.trim() : null;
        String pDiscoverer = (discoverer != null && !discoverer.trim().isEmpty()) ? "%" + discoverer.trim() + "%" : null;
        String pResponsiblePerson = (responsiblePerson != null && !responsiblePerson.trim().isEmpty()) ? "%" + responsiblePerson.trim() + "%" : null;
        String pKeyword = (keyword != null && !keyword.trim().isEmpty()) ? "%" + keyword.trim() + "%" : null;

        // 转换日期字符串为LocalDateTime
        LocalDateTime pRegisterTimeStart = null;
        LocalDateTime pRegisterTimeEnd = null;
        DateTimeFormatter dtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        if (registerTimeStart != null && !registerTimeStart.trim().isEmpty()) {
            String start = registerTimeStart.trim();
            if (start.contains(" ")) {
                // 带时间的格式如 "2026-04-09 16:00" 或 "2026-04-09 16:00:00"
                if (start.length() == 19) {
                    pRegisterTimeStart = LocalDateTime.parse(start, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else {
                    pRegisterTimeStart = LocalDateTime.parse(start, dtFormatter);
                }
            } else {
                pRegisterTimeStart = LocalDateTime.parse(start + "T00:00:00");
            }
        }
        if (registerTimeEnd != null && !registerTimeEnd.trim().isEmpty()) {
            String end = registerTimeEnd.trim();
            if (end.contains(" ")) {
                // 带时间的格式如 "2026-04-09 16:00" 或 "2026-04-09 16:59:59"
                if (end.length() == 19) {
                    pRegisterTimeEnd = LocalDateTime.parse(end, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else {
                    pRegisterTimeEnd = LocalDateTime.parse(end, dtFormatter);
                }
            } else {
                pRegisterTimeEnd = LocalDateTime.parse(end + "T23:59:59");
            }
        }

        // 如果所有过滤条件都为空，则返回全部
        if (resolveStatus == null && systemId == null && categoryId == null && metricId == null
            && pIssueType == null && pIssueSource == null && pIssueAttribute == null
            && pDiscoverer == null && pResponsiblePerson == null
            && pKeyword == null && pRegisterTimeStart == null && pRegisterTimeEnd == null
            && hasAttachment == null && hasCheckReason == null) {
            return issueRepository.findAll(pageable);
        }
        return issueRepository.search(resolveStatus, systemId, categoryId, metricId, pIssueType, pIssueSource, pIssueAttribute, pDiscoverer, pResponsiblePerson, pKeyword, pRegisterTimeStart, pRegisterTimeEnd, hasAttachment, hasCheckReason, tagStatus, pageable);
    }

    /**
     * 按发现者聚合统计：问题总数 / 核查原因非空数 / 附件数；按问题数降序，取 Top N
     * 用于问题发现页"发现者统计看板"
     * @param registerTimeStart 可选，yyyy-MM-dd 或 yyyy-MM-dd HH:mm；空字符串视为不限
     * @param registerTimeEnd   可选，yyyy-MM-dd 或 yyyy-MM-dd HH:mm；空字符串视为不限
     * @param limit             取 Top N
     */
    public List<Map<String, Object>> getDiscovererStats(String registerTimeStart,
                                                       String registerTimeEnd,
                                                       int limit) {
        LocalDateTime startTime = parseBoundDate(registerTimeStart, false);
        LocalDateTime endTime = parseBoundDate(registerTimeEnd, true);
        List<Object[]> rows = issueRepository.getDiscovererStats(startTime, endTime, limit);
        return rows.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("discoverer", r[0]);
            m.put("issueCount", ((Number) r[1]).intValue());
            m.put("checkReasonCount", ((Number) r[2]).intValue());
            m.put("attachmentCount", ((Number) r[3]).intValue());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * 边界日期解析：null/空 → null；yyyy-MM-dd → 当天 00:00:00（isEnd=false）或 23:59:59（isEnd=true）；带时间则原样解析
     */
    private LocalDateTime parseBoundDate(String raw, boolean isEnd) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim();
        try {
            if (s.contains(" ")) {
                if (s.length() == 19) {
                    return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                }
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
            return isEnd
                    ? LocalDateTime.parse(s + "T23:59:59")
                    : LocalDateTime.parse(s + "T00:00:00");
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 获取历史上登记过的系统名（distinct），用于问题登记弹窗的输入建议
     * 与指标系统表（systemApi）解耦：仅基于 issue 表自身的 system_name 字段
     */
    public List<String> findDistinctSystemNames(String keyword) {
        String pKeyword = (keyword != null && !keyword.trim().isEmpty())
                ? "%" + keyword.trim() + "%" : null;
        return issueRepository.findDistinctSystemNames(pKeyword, 100);
    }

    /**
     * 获取历史上登记过的指标大类名（distinct），用于问题登记弹窗的输入建议
     * 与指标大类表解耦：仅基于 issue 表自身的 category_name 字段
     */
    public List<String> findDistinctCategoryNames(String keyword) {
        String pKeyword = (keyword != null && !keyword.trim().isEmpty())
                ? "%" + keyword.trim() + "%" : null;
        return issueRepository.findDistinctCategoryNames(pKeyword, 100);
    }

    /**
     * 获取历史上登记过的指标小类名（distinct），用于问题登记弹窗的输入建议
     * 与指标小类表解耦：仅基于 issue 表自身的 sub_category_name 字段
     */
    public List<String> findDistinctSubCategoryNames(String keyword) {
        String pKeyword = (keyword != null && !keyword.trim().isEmpty())
                ? "%" + keyword.trim() + "%" : null;
        return issueRepository.findDistinctSubCategoryNames(pKeyword, 100);
    }

    @Transactional
    public Issue save(Issue issue) {
        boolean isNew = issue.getId() == null;
        Issue saved = null;
        String oldValue = null;
        String newValue = null;
        StringBuilder changeDesc = new StringBuilder();

        // 如果是更新操作，保留原有数据
        if (issue.getId() != null) {
            Optional<Issue> existingOpt = issueRepository.findById(issue.getId());
            if (existingOpt.isPresent()) {
                Issue existing = existingOpt.get();
                oldValue = toJson(existing);
                // 只更新非空字段，并记录变更
                if (issue.getSystemId() != null && !Objects.equals(issue.getSystemId(), existing.getSystemId())) {
                    changeDesc.append("系统ID从").append(existing.getSystemId()).append("改为").append(issue.getSystemId()).append(", ");
                    existing.setSystemId(issue.getSystemId());
                }
                if (issue.getSystemName() != null && !Objects.equals(issue.getSystemName(), existing.getSystemName())) {
                    changeDesc.append("系统从").append(existing.getSystemName()).append("改为").append(issue.getSystemName()).append(", ");
                    existing.setSystemName(issue.getSystemName());
                }
                if (issue.getCategoryId() != null && !Objects.equals(issue.getCategoryId(), existing.getCategoryId())) {
                    changeDesc.append("指标大类ID从").append(existing.getCategoryId()).append("改为").append(issue.getCategoryId()).append(", ");
                    existing.setCategoryId(issue.getCategoryId());
                }
                if (issue.getCategoryName() != null && !Objects.equals(issue.getCategoryName(), existing.getCategoryName())) {
                    changeDesc.append("指标大类从").append(existing.getCategoryName()).append("改为").append(issue.getCategoryName()).append(", ");
                    existing.setCategoryName(issue.getCategoryName());
                }
                if (issue.getSubCategoryName() != null && !Objects.equals(issue.getSubCategoryName(), existing.getSubCategoryName())) {
                    changeDesc.append("指标小类从").append(existing.getSubCategoryName()).append("改为").append(issue.getSubCategoryName()).append(", ");
                    existing.setSubCategoryName(issue.getSubCategoryName());
                }
                if (issue.getMetricId() != null && !Objects.equals(issue.getMetricId(), existing.getMetricId())) {
                    changeDesc.append("指标ID从").append(existing.getMetricId()).append("改为").append(issue.getMetricId()).append(", ");
                    existing.setMetricId(issue.getMetricId());
                }
                if (issue.getMetricName() != null && !Objects.equals(issue.getMetricName(), existing.getMetricName())) {
                    changeDesc.append("指标从").append(existing.getMetricName()).append("改为").append(issue.getMetricName()).append(", ");
                    existing.setMetricName(issue.getMetricName());
                }
                if (issue.getIssueType() != null && !issue.getIssueType().equals(existing.getIssueType())) {
                    changeDesc.append("问题类型从").append(existing.getIssueType()).append("改为").append(issue.getIssueType()).append(", ");
                    existing.setIssueType(issue.getIssueType());
                }
                if (issue.getIssueDescription() != null && !issue.getIssueDescription().equals(existing.getIssueDescription())) {
                    changeDesc.append("问题描述从").append(truncate(existing.getIssueDescription())).append("改为").append(truncate(issue.getIssueDescription())).append(", ");
                    existing.setIssueDescription(issue.getIssueDescription());
                }
                if (issue.getIssueAttribute() != null && !issue.getIssueAttribute().equals(existing.getIssueAttribute())) {
                    changeDesc.append("问题属性从").append(existing.getIssueAttribute()).append("改为").append(issue.getIssueAttribute()).append(", ");
                    existing.setIssueAttribute(issue.getIssueAttribute());
                }
                if (issue.getIssueSource() != null && !issue.getIssueSource().equals(existing.getIssueSource())) {
                    changeDesc.append("问题来源从").append(existing.getIssueSource()).append("改为").append(issue.getIssueSource()).append(", ");
                    existing.setIssueSource(issue.getIssueSource());
                }
                if (issue.getDiscoverer() != null && !issue.getDiscoverer().equals(existing.getDiscoverer())) {
                    changeDesc.append("发现者从").append(existing.getDiscoverer()).append("改为").append(issue.getDiscoverer()).append(", ");
                    existing.setDiscoverer(issue.getDiscoverer());
                }
                if (issue.getResponsiblePerson() != null && !issue.getResponsiblePerson().equals(existing.getResponsiblePerson())) {
                    changeDesc.append("责任人从").append(existing.getResponsiblePerson()).append("改为").append(issue.getResponsiblePerson()).append(", ");
                    existing.setResponsiblePerson(issue.getResponsiblePerson());
                }
                if (issue.getCheckReason() != null && !issue.getCheckReason().equals(existing.getCheckReason())) {
                    changeDesc.append("核查原因从").append(truncate(existing.getCheckReason())).append("改为").append(truncate(issue.getCheckReason())).append(", ");
                    existing.setCheckReason(issue.getCheckReason());
                }
                if (issue.getResolveStatus() != null && !issue.getResolveStatus().equals(existing.getResolveStatus())) {
                    changeDesc.append("「解决状态」从\"").append(existing.getResolveStatus()).append("\"改为\"").append(issue.getResolveStatus()).append("\", ");
                    existing.setResolveStatus(issue.getResolveStatus());
                    if ("已解决".equals(issue.getResolveStatus()) && existing.getResolveTime() == null) {
                        existing.setResolveTime(LocalDateTime.now());
                    }
                }
                if (issue.getPostEvaluation() != null && !issue.getPostEvaluation().equals(existing.getPostEvaluation())) {
                    changeDesc.append("后评估从").append(truncate(existing.getPostEvaluation())).append("改为").append(truncate(issue.getPostEvaluation())).append(", ");
                    existing.setPostEvaluation(issue.getPostEvaluation());
                }
                if (issue.getPlanResolveTime() != null && !issue.getPlanResolveTime().equals(existing.getPlanResolveTime())) {
                    changeDesc.append("预计解决时间从").append(existing.getPlanResolveTime()).append("改为").append(issue.getPlanResolveTime()).append(", ");
                    existing.setPlanResolveTime(issue.getPlanResolveTime());
                }
                if (issue.getTagStatus() != null && !Objects.equals(issue.getTagStatus(), existing.getTagStatus())) {
                    changeDesc.append("「是否关注」从\"").append(existing.getTagStatus() == 1 ? "关注" : "未关注").append("\"改为\"").append(issue.getTagStatus() == 1 ? "关注" : "未关注").append("\", ");
                    existing.setTagStatus(issue.getTagStatus());
                }
                existing.setUpdateTime(LocalDateTime.now());
                saved = issueRepository.save(existing);
                newValue = toJson(saved);
            }
        }

        if (saved == null) {
            saved = issueRepository.save(issue);
            newValue = toJson(saved);
            // V46: 问题创建事件，供全局 Toast 通知消费
            eventPublisher.publishEvent(new IssueCreatedEvent(this, saved));
        }

        // 记录操作日志
        String operator = currentUserProvider.operatorName();
        if (isNew) {
            entityAuditService.logIssueOperation(EntityAuditService.OP_CREATE, saved.getId(), operator,
                    null, newValue, "创建问题: " + truncate(saved.getIssueDescription()));
        } else {
            String changeContent = changeDesc.length() > 0
                ? "更新问题: " + truncate(saved.getIssueDescription()) + " - " + changeDesc.substring(0, changeDesc.length() - 2)
                : "更新问题: " + truncate(saved.getIssueDescription());
            entityAuditService.logIssueOperation(EntityAuditService.OP_UPDATE, saved.getId(), operator,
                    oldValue, newValue, changeContent);
        }

        return saved;
    }

    @Transactional
    public void deleteById(Long id) {
        Optional<Issue> issueOpt = issueRepository.findById(id);
        if (issueOpt.isPresent()) {
            Issue issue = issueOpt.get();
            entityAuditService.logIssueOperation(EntityAuditService.OP_DELETE, id, currentUserProvider.operatorName(),
                    toJson(issue), null, "删除问题: " + issue.getIssueDescription());
        }
        issueRepository.deleteById(id);
    }

    @Transactional
    public Issue updateResolveStatus(Long id, String resolveStatus) {
        Optional<Issue> issueOpt = issueRepository.findById(id);
        if (issueOpt.isPresent()) {
            Issue issue = issueOpt.get();
            String oldStatus = issue.getResolveStatus();
            String oldValue = toJson(issue);
            issue.setResolveStatus(resolveStatus);
            if ("已解决".equals(resolveStatus)) {
                issue.setResolveTime(LocalDateTime.now());
            }
            Issue saved = issueRepository.save(issue);
            entityAuditService.logIssueOperation(EntityAuditService.OP_RESOLVE_STATUS_CHANGE, id, currentUserProvider.operatorName(),
                    oldValue, toJson(saved), "将「解决状态」从\"" + oldStatus + "\"改为\"" + resolveStatus + "\"");
            return saved;
        }
        return null;
    }

    public Long countByResolveStatus(String resolveStatus) {
        return issueRepository.countByResolveStatus(resolveStatus);
    }

    public Long countByRegisterTimeAfter(LocalDateTime startTime) {
        return issueRepository.countByRegisterTimeAfter(startTime);
    }

    public List<Map<String, Object>> getTrend(String dimension, String type, String startDate, String endDate) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start;
        LocalDateTime end;
        DateTimeFormatter labelFormatter;
        boolean byHour = false;

        switch (dimension) {
            case "today":
                start = now.with(LocalTime.MIN);
                end = now;
                labelFormatter = DateTimeFormatter.ofPattern("HH:00");
                byHour = true;
                break;
            case "week":
                start = now.minusDays(7).with(LocalTime.MIN);
                end = now;
                labelFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                break;
            case "month":
                start = now.minusDays(30).with(LocalTime.MIN);
                end = now;
                labelFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                break;
            case "year":
                start = now.minusYears(1).with(LocalTime.MIN);
                end = now;
                labelFormatter = DateTimeFormatter.ofPattern("yyyy-MM");
                break;
            default:
                start = now.with(LocalTime.MIN);
                end = now;
                labelFormatter = DateTimeFormatter.ofPattern("HH:00");
                byHour = true;
        }

        // 如果有自定义日期范围，优先使用
        if (startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty()) {
            start = java.time.LocalDate.parse(startDate).atStartOfDay();
            end = java.time.LocalDate.parse(endDate).atTime(23, 59, 59);
            byHour = "today".equals(dimension);
            labelFormatter = byHour ? DateTimeFormatter.ofPattern("HH:00")
                    : ("year".equals(dimension) ? DateTimeFormatter.ofPattern("yyyy-MM") : DateTimeFormatter.ofPattern("MM-dd"));
        }

        // 查询并处理结果
        String resolveStatus = null;
        if ("未解决".equals(type)) {
            resolveStatus = "未解决";
        } else if ("已解决".equals(type)) {
            resolveStatus = "已解决";
        }

        List<Object[]> results;
        if (resolveStatus != null) {
            results = byHour
                    ? issueRepository.getTrendByHourWithStatus(start, end, resolveStatus)
                    : ("year".equals(dimension)
                            ? issueRepository.getTrendByMonthWithStatus(start, end, resolveStatus)
                            : issueRepository.getTrendByDayWithStatus(start, end, resolveStatus));
        } else {
            results = byHour
                    ? issueRepository.getTrendByHour(start, end)
                    : ("year".equals(dimension)
                            ? issueRepository.getTrendByMonth(start, end)
                            : issueRepository.getTrendByDay(start, end));
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (Object[] row : results) {
            Map<String, Object> point = new HashMap<>();
            String timeStr;
            if (row[0] instanceof java.sql.Date) {
                timeStr = ((java.sql.Date) row[0]).toLocalDate().format(labelFormatter);
            } else if (row[0] instanceof String) {
                String raw = (String) row[0];
                if (byHour && raw.contains(" ")) {
                    timeStr = raw.substring(0, 16);
                } else {
                    timeStr = raw;
                }
            } else {
                timeStr = row[0].toString();
            }
            point.put("time", timeStr);
            point.put("count", ((Number) row[1]).intValue());
            trend.add(point);
        }

        return trend;
    }
}
