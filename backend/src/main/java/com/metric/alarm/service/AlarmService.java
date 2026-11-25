package com.metric.alarm.service;

import com.metric.alarm.entity.Alarm;
import com.metric.alarm.entity.ShieldRule;
import com.metric.alarm.event.AlarmCreatedEvent;
import com.metric.alarm.repository.AlarmRepository;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.alarm.repository.ShieldRuleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AlarmService {

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private ShieldRuleService shieldRuleService;

    @Autowired
    private AlarmAuditService alarmAuditService;

    @Autowired
    private ShieldRuleRepository shieldRuleRepository;

    @Autowired
    private MetricCategoryRepository metricCategoryRepository;

    @Autowired
    private com.metric.security.service.CurrentUserProvider currentUserProvider;

    public String currentOperator() {
        String name = currentUserProvider.operatorName();
        // 告警处理人与审计字段上限为50；超长显示名回落到唯一登录名。
        return name.length() <= 50 ? name : currentUserProvider.currentUser()
                .map(com.metric.security.entity.SysUser::getUsername).orElse("system");
    }

    public Page<Alarm> findAll(Pageable pageable) {
        return alarmRepository.findAll(pageable);
    }

    public List<Alarm> findAll() {
        return alarmRepository.findAll();
    }

    public Optional<Alarm> findById(Long id) {
        return alarmRepository.findById(id);
    }

    public Page<Alarm> findByProcessStatus(Integer processStatus, Pageable pageable) {
        return alarmRepository.findByProcessStatus(processStatus, pageable);
    }

    public Page<Alarm> findBySystemId(Long systemId, Pageable pageable) {
        return alarmRepository.findBySystemId(systemId, pageable);
    }

    public List<Alarm> findByAlarmTimeBetween(LocalDateTime startTime, LocalDateTime endTime) {
        return alarmRepository.findByAlarmTimeBetween(startTime, endTime);
    }

    public Page<Alarm> search(
            Long systemId,
            Long categoryId,
            Long metricId,
            String alarmType,
            Integer processStatus,
            Integer shieldStatus,
            String alarmContent,
            String alarmTimeStart,
            String alarmTimeEnd,
            Pageable pageable) {
        // 将空字符串转为null
        String pAlarmType = (alarmType != null && !alarmType.trim().isEmpty()) ? alarmType.trim() : null;
        String pAlarmContent = (alarmContent != null && !alarmContent.trim().isEmpty()) ? "%" + alarmContent.trim() + "%" : null;

        // 转换日期字符串为LocalDateTime
        LocalDateTime pAlarmTimeStart = null;
        LocalDateTime pAlarmTimeEnd = null;
        DateTimeFormatter dtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        if (alarmTimeStart != null && !alarmTimeStart.trim().isEmpty()) {
            String start = alarmTimeStart.trim();
            if (start.contains(" ")) {
                // 带时间的格式如 "2026-04-09 16:00" 或 "2026-04-09 16:00:00"
                // 根据长度判断格式
                if (start.length() == 19) {
                    // 完整格式 "2026-04-09 16:00:00"
                    pAlarmTimeStart = LocalDateTime.parse(start, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else {
                    // 简化格式 "2026-04-09 16:00"
                    pAlarmTimeStart = LocalDateTime.parse(start, dtFormatter);
                }
            } else {
                pAlarmTimeStart = LocalDateTime.parse(start + "T00:00:00");
            }
        }
        if (alarmTimeEnd != null && !alarmTimeEnd.trim().isEmpty()) {
            String end = alarmTimeEnd.trim();
            if (end.contains(" ")) {
                // 带时间的格式如 "2026-04-09 16:00" 或 "2026-04-09 16:59:59"
                // 根据长度判断格式
                if (end.length() == 19) {
                    // 完整格式 "2026-04-09 16:59:59"
                    pAlarmTimeEnd = LocalDateTime.parse(end, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else {
                    // 简化格式 "2026-04-09 16:00"
                    pAlarmTimeEnd = LocalDateTime.parse(end, dtFormatter);
                }
            } else {
                pAlarmTimeEnd = LocalDateTime.parse(end + "T23:59:59");
            }
        }

        // 如果所有过滤条件都为空，则返回全部
        if (systemId == null && categoryId == null && metricId == null && pAlarmType == null && processStatus == null && shieldStatus == null
            && pAlarmContent == null && pAlarmTimeStart == null && pAlarmTimeEnd == null) {
            return alarmRepository.findAll(pageable);
        }
        return alarmRepository.search(systemId, categoryId, metricId, pAlarmType, processStatus, shieldStatus, pAlarmContent, pAlarmTimeStart, pAlarmTimeEnd, pageable);
    }

    @Transactional
    public Alarm save(Alarm alarm) {
        Alarm saved = alarmRepository.save(alarm);
        eventPublisher.publishEvent(new AlarmCreatedEvent(this, saved));
        return saved;
    }

    @Transactional
    public void deleteById(Long id) {
        alarmRepository.deleteById(id);
    }

    @Transactional
    public Alarm updateProcessStatus(Long id, Integer processStatus, String processUser) {
        if (processUser != null && processUser.length() > 50) throw new IllegalArgumentException("处理人不能超过50字");
        Optional<Alarm> alarmOpt = alarmRepository.findById(id);
        if (alarmOpt.isPresent()) {
            Alarm alarm = alarmOpt.get();
            Integer oldStatus = alarm.getProcessStatus();
            alarm.setProcessStatus(processStatus);
            alarm.setProcessTime(LocalDateTime.now());
            alarm.setProcessUser(processUser);
            Alarm saved = alarmRepository.save(alarm);
            // 记录操作日志
            alarmAuditService.logAlarmOperation(
                processStatus == 1 ? AlarmAuditService.OP_PROCESS : AlarmAuditService.OP_CANCEL_PROCESS,
                id,
                processUser,
                String.valueOf(oldStatus),
                String.valueOf(processStatus),
                processStatus == 1 ? "处理告警" : "取消处理",
                alarm.getAlarmContent()
            );
            return saved;
        }
        return null;
    }

    @Transactional
    public Alarm updateShieldStatus(Long id, Integer shieldStatus, LocalDateTime shieldEndTime) {
        Optional<Alarm> alarmOpt = alarmRepository.findById(id);
        if (alarmOpt.isPresent()) {
            Alarm alarm = alarmOpt.get();
            Integer oldStatus = alarm.getShieldStatus();
            alarm.setShieldStatus(shieldStatus);
            alarm.setShieldEndTime(shieldEndTime);
            // 开始屏蔽时设置屏蔽开始时间
            if (shieldStatus == 1 && alarm.getShieldTime() == null) {
                alarm.setShieldTime(LocalDateTime.now());
            }
            // 解除屏蔽时记录解除时间和方式
            if (shieldStatus == 0) {
                alarm.setShieldUnshieldTime(LocalDateTime.now());
                alarm.setShieldUnshieldType("MANUAL");
                alarm.setShieldTime(null);
            }
            Alarm saved = alarmRepository.save(alarm);

            // 屏蔽时创建屏蔽规则，阻止同目标的新告警产生
            if (shieldStatus == 1) {
                createShieldRuleForAlarm(alarm, shieldEndTime);
            }

            // 记录操作日志
            String opType = shieldStatus == 1 ? AlarmAuditService.OP_SHIELD : AlarmAuditService.OP_UNSHIELD;
            String changeContent = shieldStatus == 1 ? "屏蔽告警" : "解除屏蔽";
            String extraInfo = shieldStatus == 1 && shieldEndTime != null ? "屏蔽至: " + shieldEndTime : null;
            alarmAuditService.logAlarmOperation(
                opType,
                id,
                "system",
                String.valueOf(oldStatus),
                String.valueOf(shieldStatus),
                changeContent,
                extraInfo
            );
            return saved;
        }
        return null;
    }

    /**
     * 为告警创建屏蔽规则：优先屏蔽最具体的目标（metric > category）
     * 注意：仅有 systemId 时不创建系统级规则（太宽泛，会误杀所有健康度告警）
     */
    private void createShieldRuleForAlarm(Alarm alarm, LocalDateTime shieldEndTime) {
        try {
            ShieldRule rule = new ShieldRule();
            if (alarm.getMetricId() != null) {
                rule.setShieldType("METRIC");
                rule.setTargetId(alarm.getMetricId());
                rule.setTargetName(alarm.getMetricName());
            } else if (alarm.getCategoryId() != null) {
                rule.setShieldType("CATEGORY");
                rule.setTargetId(alarm.getCategoryId());
                rule.setTargetName(alarm.getCategoryName());
            } else if (alarm.getCategoryName() != null) {
                // 兼容旧告警：categoryId 为 null 时通过 categoryName 查找
                Optional<Long> catId = metricCategoryRepository.findByCategoryName(alarm.getCategoryName())
                    .map(cat -> cat.getId());
                if (catId.isPresent()) {
                    rule.setShieldType("CATEGORY");
                    rule.setTargetId(catId.get());
                    rule.setTargetName(alarm.getCategoryName());
                } else {
                    return;
                }
            } else {
                return;
            }
            rule.setShieldEndTime(shieldEndTime);
            // 检查是否已存在相同屏蔽规则（避免重复创建）
            boolean exists = shieldRuleRepository.existsByShieldTypeAndTargetIdAndShieldEndTimeIsNullOrShieldEndTimeAfter(
                rule.getShieldType(), rule.getTargetId(), LocalDateTime.now());
            if (!exists) {
                shieldRuleRepository.save(rule);
                System.out.println("已创建屏蔽规则: " + rule.getShieldType() + " targetId=" + rule.getTargetId());
            }
        } catch (Exception e) {
            System.err.println("创建屏蔽规则失败: " + e.getMessage());
        }
    }

    @Transactional
    public void processExpiredShields() {
        List<Alarm> expiredAlarms = alarmRepository.findExpiredShields(LocalDateTime.now());
        for (Alarm alarm : expiredAlarms) {
            alarm.setShieldStatus(0);
            alarm.setShieldUnshieldTime(LocalDateTime.now());
            alarm.setShieldUnshieldType("AUTO");
            alarmRepository.save(alarm);
            // 记录操作日志
            alarmAuditService.logAlarmOperation(
                AlarmAuditService.OP_UNSHIELD,
                alarm.getId(),
                "system",
                "1",
                "0",
                "自动解除屏蔽（过期）",
                "屏蔽过期自动解除"
            );
        }
    }

    public boolean isShieldedByRule(Alarm alarm) {
        if (shieldRuleService != null) {
            return shieldRuleService.isAlarmShielded(
                alarm.getSystemId(),
                alarm.getCategoryId(),
                alarm.getMetricId()
            );
        }
        return false;
    }

    @Transactional
    public Alarm updateTagStatus(Long id, Integer tagStatus, Long relatedIssueId) {
        Optional<Alarm> alarmOpt = alarmRepository.findById(id);
        if (alarmOpt.isPresent()) {
            Alarm alarm = alarmOpt.get();
            alarm.setTagStatus(tagStatus);
            if (tagStatus == 1) {
                alarm.setTagTime(LocalDateTime.now());
                alarm.setRelatedIssueId(relatedIssueId);
            }
            Alarm saved = alarmRepository.save(alarm);
            // 记录操作日志
            alarmAuditService.logAlarmOperation(
                AlarmAuditService.OP_TAG_TO_ISSUE,
                id,
                "system",
                String.valueOf(alarm.getTagStatus()),
                String.valueOf(tagStatus),
                "转问题",
                relatedIssueId != null ? "关联问题ID: " + relatedIssueId : null
            );
            return saved;
        }
        return null;
    }

    public Long countByProcessStatus(Integer processStatus) {
        return alarmRepository.countByProcessStatus(processStatus);
    }

    public Long countByAlarmTimeAfter(LocalDateTime startTime) {
        return alarmRepository.countByAlarmTimeAfter(startTime);
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
            start = LocalDate.parse(startDate).atStartOfDay();
            end = LocalDate.parse(endDate).atTime(23, 59, 59);
            byHour = "today".equals(dimension);
            labelFormatter = byHour ? DateTimeFormatter.ofPattern("HH:00")
                    : ("year".equals(dimension) ? DateTimeFormatter.ofPattern("yyyy-MM") : DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }

        // 查询并处理结果
        Integer processStatus = null;
        if ("unprocessed".equals(type)) {
            processStatus = 0;
        } else if ("processed".equals(type)) {
            processStatus = 1;
        }

        List<Object[]> results;
        if (processStatus != null) {
            results = byHour
                    ? alarmRepository.getTrendByHourWithStatus(start, end, processStatus)
                    : ("year".equals(dimension)
                            ? alarmRepository.getTrendByMonthWithStatus(start, end, processStatus)
                            : alarmRepository.getTrendByDayWithStatus(start, end, processStatus));
        } else {
            results = byHour
                    ? alarmRepository.getTrendByHour(start, end)
                    : ("year".equals(dimension)
                            ? alarmRepository.getTrendByMonth(start, end)
                            : alarmRepository.getTrendByDay(start, end));
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
                    // "2026-04-09 05:00:00" -> "2026-04-09 16:00"
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
