package com.metric.alarm.service;

import com.metric.alarm.dto.AlarmConversionRequest;
import com.metric.alarm.entity.Alarm;
import com.metric.alarm.repository.AlarmRepository;
import com.metric.gongdan.entity.Gongdan;
import com.metric.gongdan.service.GongdanService;
import com.metric.issue.entity.Issue;
import com.metric.issue.service.IssueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/** 本地创建与告警关联在同一事务提交，外部推送在提交之后执行。 */
@Service
public class AlarmConversionService {
    @Autowired private AlarmSummaryService summaries;
    @Autowired private AlarmRepository alarms;
    @Autowired private AlarmAuditService audit;
    @Autowired private IssueService issues;
    @Autowired private GongdanService gongdans;
    @Autowired private AlarmService alarmService;

    public record Related(Long id, String title) {}

    @Transactional(readOnly = true)
    public Map<String, Object> preview(AlarmConversionRequest request) {
        checkKind(request);
        List<Alarm> members = summaries.conversionMembers(request, false);
        List<Alarm> eligible = eligible(members, request.getKind());
        List<Related> related = members.stream().map(a -> link(a, request.getKind())).filter(Objects::nonNull).distinct()
                .map(id -> "issue".equals(request.getKind())
                        ? new Related(id, issues.findById(id).map(i -> Objects.toString(i.getIssueCode(), "问题 #" + id)).orElse("问题 #" + id + "（已删除）"))
                        : new Related(id, gongdans.findById(id).map(g -> Objects.toString(g.getGongdanId(), "工单 #" + id)).orElse("工单 #" + id + "（已删除）"))).toList();
        Alarm latest = latest(eligible.isEmpty() ? members : eligible);
        Map<String, Object> result = new HashMap<>();
        result.put("totalCount", members.size()); result.put("eligibleCount", eligible.size()); result.put("related", related);
        result.put("objectName", objectName(latest));
        result.put("description", description(eligible));
        result.put("title", clip("【告警转工单】" + objectName(latest), 200));
        result.put("alarmLevel", eligible.stream().map(Alarm::getAlarmLevel).filter(Objects::nonNull).max(Integer::compareTo).orElse(null));
        result.put("discoverer", alarmService.currentOperator());
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> convert(AlarmConversionRequest request) {
        checkKind(request);
        List<Alarm> members = summaries.conversionMembers(request, true);
        List<Alarm> eligible = eligible(members, request.getKind());
        if (eligible.isEmpty()) throw new IllegalArgumentException("本次记录均已转换或属于工单记录，请刷新后查看关联记录");
        Alarm latest = latest(eligible);
        String actor = alarmService.currentOperator();
        Long id;
        if ("issue".equals(request.getKind())) {
            Issue input = request.getIssue();
            if (input == null) throw new IllegalArgumentException("请填写问题信息");
            Issue issue = new Issue();
            issue.setIssueType(required(input.getIssueType(), 50, "问题类型"));
            issue.setIssueAttribute(required(input.getIssueAttribute(), 50, "问题属性"));
            issue.setIssueDescription(withScope(required(input.getIssueDescription(), 40000, "问题描述"), eligible));
            issue.setDiscoverer(actor); issue.setResponsiblePerson(optional(input.getResponsiblePerson(), 50, "责任人"));
            issue.setCheckReason(optional(input.getCheckReason(), 10000, "核查原因"));
            issue.setSystemId(latest.getSystemId()); issue.setSystemName(latest.getSystemName());
            issue.setCategoryId(latest.getCategoryId()); issue.setCategoryName(latest.getCategoryName());
            issue.setSubCategoryName(latest.getSubCategoryName()); issue.setMetricId(latest.getMetricId());
            issue.setMetricName(clip(latest.getMetricName(), 100)); issue.setIssueSource("告警");
            id = issues.save(issue).getId();
            for (Alarm alarm : eligible) alarms.findById(alarm.getId()).ifPresent(stored -> {
                stored.setRelatedIssueId(id); stored.setTagStatus(1); stored.setTagTime(LocalDateTime.now());
                audit.logAlarmOperation(AlarmAuditService.OP_TAG_TO_ISSUE, stored.getId(), actor, null, id.toString(), "转问题", "关联问题ID: " + id);
            });
        } else {
            Gongdan input = request.getGongdan();
            if (input == null) throw new IllegalArgumentException("请填写工单信息");
            Gongdan gongdan = new Gongdan();
            gongdan.setGongdanTitle(required(input.getGongdanTitle(), 200, "工单标题"));
            gongdan.setGongdanContent(withScope(required(input.getGongdanContent(), 40000, "事件描述"), eligible));
            if (input.getAlarmLevel() == null || !Set.of(2, 3, 4).contains(input.getAlarmLevel())) throw new IllegalArgumentException("请选择告警级别");
            gongdan.setAlarmLevel(input.getAlarmLevel());
            gongdan.setAlarmPriority(choice(input.getAlarmPriority(), Set.of("低", "中", "高"), "优先级"));
            gongdan.setAlarmImpact(choice(input.getAlarmImpact(), Set.of("一般", "重大", "严重", "无"), "影响度"));
            gongdan.setResponsiblePerson(optional(input.getResponsiblePerson(), 100, "主要责任人"));
            gongdan.setSecondResponsible(optional(input.getSecondResponsible(), 100, "次要责任人"));
            gongdan.setExecuteUser(optional(input.getExecuteUser(), 50, "BOMC处理人"));
            gongdan.setSystemId(latest.getSystemId()); gongdan.setCategoryId(latest.getCategoryId()); gongdan.setMetricId(latest.getMetricId());
            gongdan.setStrategyId(latest.getStrategyId()); gongdan.setGongdanType("手动");
            gongdan.setAlarmObject(clip(objectName(latest), 200)); gongdan.setAlarmCount(eligible.size());
            gongdan.setAlarmFirstTime(eligible.stream().map(Alarm::getAlarmTime).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null));
            gongdan.setAlarmLastTime(eligible.stream().map(Alarm::getAlarmTime).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
            id = gongdans.save(gongdan).getId();
            for (Alarm alarm : eligible) alarms.findById(alarm.getId()).ifPresent(stored -> {
                stored.setRelatedGongdanId(id);
                audit.logAlarmOperation(AlarmAuditService.OP_TAG_TO_GONGDAN, stored.getId(), actor, null, id.toString(), "转工单", "关联工单ID: " + id);
            });
        }
        // JPA 管理的实体统一落库；不发布重复的告警创建通知。
        alarms.flush();
        return Map.of("id", id, "affectedCount", eligible.size(), "skippedCount", members.size() - eligible.size(), "kind", request.getKind());
    }

    private void checkKind(AlarmConversionRequest request) {
        if (!Set.of("issue", "gongdan").contains(Objects.toString(request.getKind(), ""))) throw new IllegalArgumentException("转换类型无效");
    }
    private Long link(Alarm alarm, String kind) { return "issue".equals(kind) ? alarm.getRelatedIssueId() : alarm.getRelatedGongdanId(); }
    private List<Alarm> eligible(List<Alarm> members, String kind) {
        return members.stream().filter(a -> link(a, kind) == null && !("issue".equals(kind) && Integer.valueOf(1).equals(a.getTagStatus()))
                && !("gongdan".equals(kind) && Integer.valueOf(3).equals(a.getProcessStatus()))).toList();
    }
    private Alarm latest(List<Alarm> members) { return members.stream().max(Comparator.comparing(Alarm::getAlarmTime, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(Alarm::getId)).orElseThrow(); }
    private String objectName(Alarm alarm) { return String.join(" / ", java.util.stream.Stream.of(alarm.getSystemName(), alarm.getCategoryName(), alarm.getSubCategoryName(), alarm.getMetricName()).filter(s -> s != null && !s.isBlank()).toList()); }
    private String description(List<Alarm> members) {
        if (members.isEmpty()) return "";
        Alarm latest = latest(members);
        return "告警对象：" + objectName(latest) + "\n告警类型：" + latest.getAlarmType() + "\n最近告警：" + Objects.toString(latest.getAlarmContent(), "未记录");
    }
    private String withScope(String text, List<Alarm> members) {
        String details = "\n\n【来源告警】关联 " + members.size() + " 次；首次 "
                + members.stream().map(Alarm::getAlarmTime).filter(Objects::nonNull).min(LocalDateTime::compareTo).map(Object::toString).orElse("未记录")
                + "；末次 " + Objects.toString(latest(members).getAlarmTime(), "未记录")
                + "。记录ID：" + members.stream().limit(100).map(a -> a.getId().toString()).collect(java.util.stream.Collectors.joining(","))
                + (members.size() > 100 ? " 等（完整关联保存在告警记录中）" : "");
        String result = text + details;
        if (result.getBytes(StandardCharsets.UTF_8).length > 65000) throw new IllegalArgumentException("描述内容过长，请精简后重试");
        return result;
    }
    private String clip(String value, int max) { return value == null ? null : value.substring(0, Math.min(max, value.length())); }
    private String required(String value, int max, String field) { String result = optional(value, max, field); if (result == null) throw new IllegalArgumentException("请填写" + field); return result; }
    private String optional(String value, int max, String field) { if (value == null || value.isBlank()) return null; if (value.trim().length() > max) throw new IllegalArgumentException(field + "不能超过" + max + "字"); return value.trim(); }
    private String choice(String value, Set<String> choices, String field) { String result = optional(value, 20, field); if (result != null && !choices.contains(result)) throw new IllegalArgumentException(field + "无效"); return result; }
}
