package com.metric.collect.listener;

import com.metric.alarm.entity.Alarm;
import com.metric.alarm.event.AlarmCreatedEvent;
import com.metric.gongdan.entity.Gongdan;
import com.metric.gongdan.event.GongdanEvent;
import com.metric.issue.entity.Issue;
import com.metric.issue.event.IssueCreatedEvent;
import com.metric.collect.service.NotificationEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Async;

/**
 * 全局动态通知 listener —— 订阅告警/工单/问题三类业务事件，写 notification_event 表供前端轮询。
 *
 * V47 调整：
 *  - 从 {@code @EventListener} 改为 {@code @TransactionalEventListener(AFTER_COMMIT)}：
 *    业务事务提交成功后才记录通知，事务回滚不会给用户弹「幽灵通知」；
 *    没有事务上下文时（fallbackExecution）照样执行，保证所有创建入口都能通知到。
 *  - 通知正文带上系统/大类/指标等定位信息，用户不点进去也能判断是什么事。
 *
 * 任何异常都吞掉，不影响主业务。
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private static final int MAX_TITLE_LEN = 50;
    private static final int MAX_CONTENT_LEN = 200;

    @Autowired
    private NotificationEventService notificationEventService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAlarmCreated(AlarmCreatedEvent event) {
        try {
            Alarm a = event.getAlarm();
            if (a == null) return;
            // 正文：系统 · 指标 · 大类/小类，便于直接判断影响面
            String content = joinNonNull(a.getSystemName(), a.getMetricName(), " · ");
            content = joinNonNull(content, joinNonNull(a.getCategoryName(), a.getSubCategoryName(), " / "), " · ");
            notificationEventService.record(
                    NotificationEventService.TYPE_ALARM,
                    "触发告警【" + truncate(a.getAlarmContent(), MAX_TITLE_LEN) + "】",
                    truncate(content, MAX_CONTENT_LEN),
                    1);
        } catch (Exception e) {
            log.warn("记录告警通知事件失败: {}", e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onGongdanEvent(GongdanEvent event) {
        if (!GongdanEvent.TYPE_CREATED.equals(event.getEventType())) {
            return; // 只关心新建，关单等不弹 toast
        }
        try {
            Gongdan g = event.getGongdan();
            if (g == null) return;
            notificationEventService.record(
                    NotificationEventService.TYPE_GONGDAN,
                    "新建工单【" + truncate(g.getGongdanTitle(), MAX_TITLE_LEN) + "】",
                    truncate(joinNonNull(g.getGongdanType(), g.getAlarmObject(), " · "), MAX_CONTENT_LEN),
                    1);
        } catch (Exception e) {
            log.warn("记录工单通知事件失败: {}", e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onIssueCreated(IssueCreatedEvent event) {
        try {
            Issue i = event.getIssue();
            if (i == null) return;
            String content = joinNonNull(i.getIssueType(), i.getSystemName(), " / ");
            notificationEventService.record(
                    NotificationEventService.TYPE_ISSUE,
                    "新建问题【" + truncate(i.getIssueDescription(), MAX_TITLE_LEN) + "】",
                    truncate(content, MAX_CONTENT_LEN),
                    1);
        } catch (Exception e) {
            log.warn("记录问题通知事件失败: {}", e.getMessage());
        }
    }

    // ----- helpers -----

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String joinNonNull(String a, String b, String sep) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + sep + b;
    }
}
