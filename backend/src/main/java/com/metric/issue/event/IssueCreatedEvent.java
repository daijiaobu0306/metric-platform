package com.metric.issue.event;

import com.metric.issue.entity.Issue;
import org.springframework.context.ApplicationEvent;

/**
 * 问题创建事件。
 * 镜像 AlarmCreatedEvent —— IssueService.save 不发事件是历史缺口，本次补齐。
 */
public class IssueCreatedEvent extends ApplicationEvent {

    private final Issue issue;

    public IssueCreatedEvent(Object source, Issue issue) {
        super(source);
        this.issue = issue;
    }

    public Issue getIssue() {
        return issue;
    }
}