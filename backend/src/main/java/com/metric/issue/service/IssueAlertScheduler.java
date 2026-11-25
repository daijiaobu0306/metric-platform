package com.metric.issue.service;

import com.metric.alarm.entity.Alarm;
import com.metric.issue.entity.Issue;
import com.metric.alarm.repository.AlarmRepository;
import com.metric.issue.repository.IssueRepository;
import com.metric.alarm.service.AlarmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class IssueAlertScheduler {

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private AlarmService alarmService;

    // 每天检查一次逾期未解决问题并生成告警
    @Scheduled(cron = "0 0 9 * * ?") // 默认每天9点执行
    @Transactional
    public void checkOverdueIssues() {
        LocalDateTime now = LocalDateTime.now();

        // 查找所有预计解决时间已过期但状态仍为未解决的问题
        List<Issue> overdueIssues = issueRepository.findOverdueUnresolvedIssues(now);

        for (Issue issue : overdueIssues) {
            // 检查是否已经存在相同问题的告警（避免重复生成）
            boolean exists = alarmRepository.existsByAlarmContentContainingAndAlarmTimeAfter(
                    "问题逾期提醒:" + issue.getId(),
                    now.minusDays(1)
            );

            if (!exists) {
                // 计算逾期天数（最小为1天）
                long overdueDays = ChronoUnit.DAYS.between(issue.getPlanResolveTime(), now);
                if (overdueDays < 1) overdueDays = 1;

                String planTimeStr = issue.getPlanResolveTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String alarmContent = String.format("问题逾期提醒: %s (预计解决时间: %s, 已逾期 %d 天)",
                        issue.getIssueDescription() != null ? issue.getIssueDescription() : "问题" + issue.getId(),
                        planTimeStr, overdueDays);

                Alarm alarm = new Alarm();
                alarm.setSystemId(issue.getSystemId());
                alarm.setCategoryId(issue.getCategoryId());
                alarm.setMetricId(issue.getMetricId());
                alarm.setCategoryName(issue.getCategoryName());
                alarm.setMetricName(issue.getMetricName());
                alarm.setAlarmType("问题逾期告警");
                alarm.setMetricValue(new java.math.BigDecimal(overdueDays));
                alarm.setThresholdValue(java.math.BigDecimal.ZERO);
                alarm.setHealthScore(null);  // 用null表示"/"
                alarm.setAlarmContent(alarmContent);
                alarm.setAlarmTime(now);
                alarm.setProcessStatus(0);
                alarm.setShieldStatus(0);
                alarm.setTagStatus(0);

                alarmService.save(alarm);
            }
        }
    }

    // 手动触发生成逾期告警（供API调用）
    @Transactional
    public int generateOverdueAlerts() {
        LocalDateTime now = LocalDateTime.now();
        List<Issue> overdueIssues = issueRepository.findOverdueUnresolvedIssues(now);

        int count = 0;
        for (Issue issue : overdueIssues) {
            boolean exists = alarmRepository.existsByAlarmContentContainingAndAlarmTimeAfter(
                    "问题逾期提醒:" + issue.getId(),
                    now.minusDays(1)
            );

            if (!exists) {
                // 计算逾期天数（最小为1天）
                long overdueDays = ChronoUnit.DAYS.between(issue.getPlanResolveTime(), now);
                if (overdueDays < 1) overdueDays = 1;

                String planTimeStr = issue.getPlanResolveTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String alarmContent = String.format("问题逾期提醒: %s (预计解决时间: %s, 已逾期 %d 天)",
                        issue.getIssueDescription() != null ? issue.getIssueDescription() : "问题" + issue.getId(),
                        planTimeStr, overdueDays);

                Alarm alarm = new Alarm();
                alarm.setSystemId(issue.getSystemId());
                alarm.setCategoryId(issue.getCategoryId());
                alarm.setMetricId(issue.getMetricId());
                alarm.setCategoryName(issue.getCategoryName());
                alarm.setMetricName(issue.getMetricName());
                alarm.setAlarmType("问题逾期告警");
                alarm.setMetricValue(new java.math.BigDecimal(overdueDays));
                alarm.setThresholdValue(java.math.BigDecimal.ZERO);
                alarm.setHealthScore(null);  // 用null表示"/"
                alarm.setAlarmContent(alarmContent);
                alarm.setAlarmTime(now);
                alarm.setProcessStatus(0);
                alarm.setShieldStatus(0);
                alarm.setTagStatus(0);

                alarmService.save(alarm);
                count++;
            }
        }
        return count;
    }
}
