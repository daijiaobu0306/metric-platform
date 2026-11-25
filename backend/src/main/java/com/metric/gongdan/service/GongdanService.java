package com.metric.gongdan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.gongdan.entity.Gongdan;
import com.metric.gongdan.event.GongdanEvent;
import com.metric.gongdan.repository.GongdanRepository;
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
import java.util.Optional;

@Service
public class GongdanService {

    @Autowired
    private GongdanRepository gongdanRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    @Autowired
    private EntityAuditService entityAuditService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String toJson(Gongdan gongdan) {
        try {
            return objectMapper.writeValueAsString(gongdan);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String str) {
        if (str == null) return "";
        return str.length() > 20 ? str.substring(0, 20) + "..." : str;
    }

    /**
     * 生成工单号：GD + yyyyMMddHHmmss + 3位随机数。
     * 该工单号会作为推送 BOMC 时的端到端工单唯一标识(wgId)，必须唯一且稳定。
     */
    public String generateGongdanId() {
        String timePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int randomPart = java.util.concurrent.ThreadLocalRandom.current().nextInt(1000);
        return "GD" + timePart + String.format("%03d", randomPart);
    }

    public Page<Gongdan> findAll(Pageable pageable) {
        return gongdanRepository.findAll(pageable);
    }

    public List<Gongdan> findAll() {
        return gongdanRepository.findAll();
    }

    public Optional<Gongdan> findById(Long id) {
        return gongdanRepository.findById(id);
    }

    public Page<Gongdan> findByClosedStatus(Integer closedStatus, Pageable pageable) {
        return gongdanRepository.findByClosedStatus(closedStatus, pageable);
    }

    public Page<Gongdan> findBySystemId(Long systemId, Pageable pageable) {
        return gongdanRepository.findBySystemId(systemId, pageable);
    }

    public Page<Gongdan> search(
            String gongdanId,
            String gongdanTitle,
            String gongdanContent,
            String gongdanType,
            String currentStep,
            String currentHandler,
            String gongdanTimeStart,
            String gongdanTimeEnd,
            Integer closedStatus,
            String externalId,
            String alarmObject,
            Integer alarmLevel,
            Integer synced,
            Pageable pageable) {
        // 将空字符串转为null
        String pGongdanId = (gongdanId != null && !gongdanId.trim().isEmpty()) ? gongdanId.trim() : null;
        String pGongdanTitle = (gongdanTitle != null && !gongdanTitle.trim().isEmpty()) ? "%" + gongdanTitle.trim() + "%" : null;
        String pGongdanContent = (gongdanContent != null && !gongdanContent.trim().isEmpty()) ? "%" + gongdanContent.trim() + "%" : null;
        String pGongdanType = (gongdanType != null && !gongdanType.trim().isEmpty()) ? gongdanType.trim() : null;
        String pCurrentStep = (currentStep != null && !currentStep.trim().isEmpty()) ? "%" + currentStep.trim() + "%" : null;
        String pCurrentHandler = (currentHandler != null && !currentHandler.trim().isEmpty()) ? "%" + currentHandler.trim() + "%" : null;
        // BOMC 对接相关筛选条件
        String pExternalId = (externalId != null && !externalId.trim().isEmpty()) ? "%" + externalId.trim() + "%" : null;
        String pAlarmObject = (alarmObject != null && !alarmObject.trim().isEmpty()) ? "%" + alarmObject.trim() + "%" : null;

        // 转换日期字符串为LocalDateTime
        LocalDateTime pGongdanTimeStart = null;
        LocalDateTime pGongdanTimeEnd = null;
        DateTimeFormatter dtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        if (gongdanTimeStart != null && !gongdanTimeStart.trim().isEmpty()) {
            String start = gongdanTimeStart.trim();
            if (start.contains(" ")) {
                // 带时间的格式如 "2026-04-09 16:00" 或 "2026-04-09 16:00:00"
                if (start.length() == 19) {
                    pGongdanTimeStart = LocalDateTime.parse(start, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else {
                    pGongdanTimeStart = LocalDateTime.parse(start, dtFormatter);
                }
            } else {
                pGongdanTimeStart = LocalDateTime.parse(start + "T00:00:00");
            }
        }
        if (gongdanTimeEnd != null && !gongdanTimeEnd.trim().isEmpty()) {
            String end = gongdanTimeEnd.trim();
            if (end.contains(" ")) {
                // 带时间的格式如 "2026-04-09 16:00" 或 "2026-04-09 16:59:59"
                if (end.length() == 19) {
                    pGongdanTimeEnd = LocalDateTime.parse(end, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else {
                    pGongdanTimeEnd = LocalDateTime.parse(end, dtFormatter);
                }
            } else {
                pGongdanTimeEnd = LocalDateTime.parse(end + "T23:59:59");
            }
        }

        // 如果所有过滤条件都为空，则返回全部
        if (pGongdanId == null && pGongdanTitle == null && pGongdanContent == null && pGongdanType == null
            && pCurrentStep == null && pCurrentHandler == null && pGongdanTimeStart == null && pGongdanTimeEnd == null
            && closedStatus == null && pExternalId == null && pAlarmObject == null && alarmLevel == null && synced == null) {
            return gongdanRepository.findAll(pageable);
        }
        return gongdanRepository.search(pGongdanId, pGongdanTitle, pGongdanContent, pGongdanType, pCurrentStep,
                pCurrentHandler, pGongdanTimeStart, pGongdanTimeEnd, closedStatus,
                pExternalId, pAlarmObject, alarmLevel, synced, pageable);
    }

    public List<Gongdan> findByGongdanTimeBetween(LocalDateTime startTime, LocalDateTime endTime) {
        return gongdanRepository.findByGongdanTimeBetween(startTime, endTime);
    }

    @Transactional
    public Gongdan save(Gongdan gongdan) {
        boolean isNew = gongdan.getId() == null;
        // 新建工单若无工单号则自动生成，保证推送到 BOMC 时有稳定的端到端工单唯一标识(wgId)
        if (isNew && (gongdan.getGongdanId() == null || gongdan.getGongdanId().trim().isEmpty())) {
            gongdan.setGongdanId(generateGongdanId());
        }
        Gongdan saved = null;
        String oldValue = null;
        String newValue = null;
        StringBuilder changeDesc = new StringBuilder();

        // 如果是更新操作，保留原有数据
        if (gongdan.getId() != null) {
            Optional<Gongdan> existingOpt = gongdanRepository.findById(gongdan.getId());
            if (existingOpt.isPresent()) {
                Gongdan existing = existingOpt.get();
                oldValue = toJson(existing);
                // 只更新非空字段，并记录变更
                if (gongdan.getGongdanId() != null && !gongdan.getGongdanId().equals(existing.getGongdanId())) {
                    changeDesc.append("工单ID从").append(existing.getGongdanId()).append("改为").append(gongdan.getGongdanId()).append(", ");
                    existing.setGongdanId(gongdan.getGongdanId());
                }
                if (gongdan.getGongdanTitle() != null && !gongdan.getGongdanTitle().equals(existing.getGongdanTitle())) {
                    changeDesc.append("标题从").append(existing.getGongdanTitle()).append("改为").append(gongdan.getGongdanTitle()).append(", ");
                    existing.setGongdanTitle(gongdan.getGongdanTitle());
                }
                if (gongdan.getGongdanContent() != null && !gongdan.getGongdanContent().equals(existing.getGongdanContent())) {
                    changeDesc.append("内容从").append(truncate(existing.getGongdanContent())).append("改为").append(truncate(gongdan.getGongdanContent())).append(", ");
                    existing.setGongdanContent(gongdan.getGongdanContent());
                }
                if (gongdan.getGongdanType() != null && !gongdan.getGongdanType().equals(existing.getGongdanType())) {
                    changeDesc.append("类型从").append(existing.getGongdanType()).append("改为").append(gongdan.getGongdanType()).append(", ");
                    existing.setGongdanType(gongdan.getGongdanType());
                }
                if (gongdan.getCurrentStep() != null && !gongdan.getCurrentStep().equals(existing.getCurrentStep())) {
                    changeDesc.append("当前环节从").append(existing.getCurrentStep()).append("改为").append(gongdan.getCurrentStep()).append(", ");
                    existing.setCurrentStep(gongdan.getCurrentStep());
                }
                if (gongdan.getCurrentHandler() != null && !gongdan.getCurrentHandler().equals(existing.getCurrentHandler())) {
                    changeDesc.append("处理人从").append(existing.getCurrentHandler()).append("改为").append(gongdan.getCurrentHandler()).append(", ");
                    existing.setCurrentHandler(gongdan.getCurrentHandler());
                }
                // 告警业务字段（对接 BOMC 用，V51/V20261012190000 新增列）
                if (gongdan.getAlarmLevel() != null && !gongdan.getAlarmLevel().equals(existing.getAlarmLevel())) {
                    changeDesc.append("告警级别从").append(existing.getAlarmLevel()).append("改为").append(gongdan.getAlarmLevel()).append(", ");
                    existing.setAlarmLevel(gongdan.getAlarmLevel());
                }
                if (gongdan.getAlarmPriority() != null && !gongdan.getAlarmPriority().equals(existing.getAlarmPriority())) {
                    changeDesc.append("优先级从").append(existing.getAlarmPriority()).append("改为").append(gongdan.getAlarmPriority()).append(", ");
                    existing.setAlarmPriority(gongdan.getAlarmPriority());
                }
                if (gongdan.getAlarmImpact() != null && !gongdan.getAlarmImpact().equals(existing.getAlarmImpact())) {
                    changeDesc.append("影响度从").append(existing.getAlarmImpact()).append("改为").append(gongdan.getAlarmImpact()).append(", ");
                    existing.setAlarmImpact(gongdan.getAlarmImpact());
                }
                if (gongdan.getAlarmObject() != null && !gongdan.getAlarmObject().equals(existing.getAlarmObject())) {
                    changeDesc.append("告警对象从").append(existing.getAlarmObject()).append("改为").append(gongdan.getAlarmObject()).append(", ");
                    existing.setAlarmObject(gongdan.getAlarmObject());
                }
                if (gongdan.getAlarmFirstTime() != null) {
                    existing.setAlarmFirstTime(gongdan.getAlarmFirstTime());
                }
                if (gongdan.getAlarmLastTime() != null) {
                    existing.setAlarmLastTime(gongdan.getAlarmLastTime());
                }
                if (gongdan.getAlarmCount() != null) {
                    existing.setAlarmCount(gongdan.getAlarmCount());
                }
                if (gongdan.getResponsiblePerson() != null && !gongdan.getResponsiblePerson().equals(existing.getResponsiblePerson())) {
                    changeDesc.append("主要责任人从").append(existing.getResponsiblePerson()).append("改为").append(gongdan.getResponsiblePerson()).append(", ");
                    existing.setResponsiblePerson(gongdan.getResponsiblePerson());
                }
                if (gongdan.getSecondResponsible() != null && !gongdan.getSecondResponsible().equals(existing.getSecondResponsible())) {
                    changeDesc.append("次要责任人从").append(existing.getSecondResponsible()).append("改为").append(gongdan.getSecondResponsible()).append(", ");
                    existing.setSecondResponsible(gongdan.getSecondResponsible());
                }
                if (gongdan.getExecuteUser() != null && !gongdan.getExecuteUser().equals(existing.getExecuteUser())) {
                    changeDesc.append("BOMC处理人从").append(existing.getExecuteUser()).append("改为").append(gongdan.getExecuteUser()).append(", ");
                    existing.setExecuteUser(gongdan.getExecuteUser());
                }
                // 关联的指标对象（编辑弹窗可重新选择系统）
                if (gongdan.getSystemId() != null && !gongdan.getSystemId().equals(existing.getSystemId())) {
                    changeDesc.append("系统从").append(existing.getSystemId()).append("改为").append(gongdan.getSystemId()).append(", ");
                    existing.setSystemId(gongdan.getSystemId());
                }
                if (gongdan.getCategoryId() != null && !gongdan.getCategoryId().equals(existing.getCategoryId())) {
                    existing.setCategoryId(gongdan.getCategoryId());
                }
                if (gongdan.getMetricId() != null && !gongdan.getMetricId().equals(existing.getMetricId())) {
                    existing.setMetricId(gongdan.getMetricId());
                }
                if (gongdan.getClosedStatus() != null && !gongdan.getClosedStatus().equals(existing.getClosedStatus())) {
                    String oldStatus = existing.getClosedStatus() == 0 ? "未关单" : "已关单";
                    String newStatus = gongdan.getClosedStatus() == 0 ? "未关单" : "已关单";
                    changeDesc.append("「状态」从\"").append(oldStatus).append("\"改为\"").append(newStatus).append("\", ");
                    existing.setClosedStatus(gongdan.getClosedStatus());
                    if (gongdan.getClosedStatus() == 1 && existing.getClosedTime() == null) {
                        existing.setClosedTime(LocalDateTime.now());
                    }
                    if (gongdan.getClosedStatus() == 0) {
                        existing.setClosedTime(null);
                        // 重开时清除撤销/归档标记（BOMC 侧历史留痕在操作日志中）
                        existing.setBomcAction(null);
                        existing.setBomcActionReason(null);
                    }
                }
                saved = gongdanRepository.save(existing);
                newValue = toJson(saved);
            }
        }

        if (saved == null) {
            saved = gongdanRepository.save(gongdan);
            newValue = toJson(saved);
        }

        // 记录操作日志
        String operator = currentUserProvider.operatorName();
        if (isNew) {
            entityAuditService.logGongdanOperation(EntityAuditService.OP_CREATE, saved.getId(), operator,
                    null, newValue, "创建工单: " + saved.getGongdanTitle());
            // 发布创建事件
            eventPublisher.publishEvent(new GongdanEvent(this, saved, GongdanEvent.TYPE_CREATED));
        } else {
            String changeContent = changeDesc.length() > 0
                ? "更新工单: " + saved.getGongdanTitle() + " - " + changeDesc.substring(0, changeDesc.length() - 2)
                : "更新工单: " + saved.getGongdanTitle();
            entityAuditService.logGongdanOperation(EntityAuditService.OP_UPDATE, saved.getId(), operator,
                    oldValue, newValue, changeContent);
        }

        return saved;
    }

    @Transactional
    public void deleteById(Long id) {
        Optional<Gongdan> gongdanOpt = gongdanRepository.findById(id);
        if (gongdanOpt.isPresent()) {
            Gongdan gongdan = gongdanOpt.get();
            entityAuditService.logGongdanOperation(EntityAuditService.OP_DELETE, id, currentUserProvider.operatorName(),
                    toJson(gongdan), null, "删除工单: " + gongdan.getGongdanTitle());
        }
        gongdanRepository.deleteById(id);
    }

    @Transactional
    public Gongdan closeGongdan(Long id) {
        Optional<Gongdan> gongdanOpt = gongdanRepository.findById(id);
        if (gongdanOpt.isPresent()) {
            Gongdan gongdan = gongdanOpt.get();
            String oldValue = toJson(gongdan);
            gongdan.setClosedStatus(1);
            gongdan.setClosedTime(LocalDateTime.now());
            Gongdan saved = gongdanRepository.save(gongdan);
            entityAuditService.logGongdanOperation(EntityAuditService.OP_CLOSE, id, currentUserProvider.operatorName(),
                    oldValue, toJson(saved), "将「状态」从未关单改为已关单");
            // 发布关单事件
            eventPublisher.publishEvent(new GongdanEvent(this, saved, GongdanEvent.TYPE_CLOSED));
            return saved;
        }
        return null;
    }

    public Long countByClosedStatus(Integer closedStatus) {
        return gongdanRepository.countByClosedStatus(closedStatus);
    }

    public Long countByGongdanTimeAfter(LocalDateTime startTime) {
        return gongdanRepository.countByGongdanTimeAfter(startTime);
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
        Integer closedStatus = null;
        if ("未关单".equals(type)) {
            closedStatus = 0;
        } else if ("已关单".equals(type)) {
            closedStatus = 1;
        }

        List<Object[]> results;
        if (closedStatus != null) {
            results = byHour
                    ? gongdanRepository.getTrendByHourWithStatus(start, end, closedStatus)
                    : ("year".equals(dimension)
                            ? gongdanRepository.getTrendByMonthWithStatus(start, end, closedStatus)
                            : gongdanRepository.getTrendByDayWithStatus(start, end, closedStatus));
        } else {
            results = byHour
                    ? gongdanRepository.getTrendByHour(start, end)
                    : ("year".equals(dimension)
                            ? gongdanRepository.getTrendByMonth(start, end)
                            : gongdanRepository.getTrendByDay(start, end));
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
