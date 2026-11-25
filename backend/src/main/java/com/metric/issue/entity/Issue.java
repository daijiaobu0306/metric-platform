package com.metric.issue.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "issue", indexes = {
    @Index(name = "idx_register_time", columnList = "register_time"),
    @Index(name = "idx_resolve_status", columnList = "resolve_status"),
    @Index(name = "idx_system_id", columnList = "system_id")
})
public class Issue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_code", length = 50)
    private String issueCode;

    @Column(name = "issue_type", length = 50)
    private String issueType;

    @Column(name = "system_id")
    private Long systemId;

    @Column(name = "system_name", length = 100)
    private String systemName;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "category_name", length = 100)
    private String categoryName;

    @Column(name = "sub_category_name", length = 100)
    private String subCategoryName;

    @Column(name = "metric_id")
    private Long metricId;

    @Column(name = "metric_name", length = 100)
    private String metricName;

    @Column(name = "issue_description", columnDefinition = "TEXT")
    private String issueDescription;

    @Column(name = "issue_attribute", length = 50)
    private String issueAttribute;

    @Column(name = "issue_source", length = 50)
    private String issueSource;

    @Column(name = "discoverer", length = 50)
    private String discoverer;

    @Column(name = "responsible_person", length = 50)
    private String responsiblePerson;

    @Column(name = "check_reason", columnDefinition = "TEXT")
    private String checkReason;

    @Column(name = "plan_resolve_time")
    private LocalDateTime planResolveTime;

    @Column(name = "resolve_status", length = 50)
    private String resolveStatus;

    @Column(name = "resolve_time")
    private LocalDateTime resolveTime;

    @Column(name = "post_evaluation", columnDefinition = "TEXT")
    private String postEvaluation;

    @Column(name = "tag_status")
    private Integer tagStatus;

    @Column(name = "tag_time")
    private LocalDateTime tagTime;

    @Column(name = "register_time")
    private LocalDateTime registerTime;

    @Column(name = "modify_time")
    private LocalDateTime modifyTime;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(name = "attachment_path")
    private String attachmentPath;

    @Column(name = "attachment_name")
    private String attachmentName;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (registerTime == null) {
            registerTime = LocalDateTime.now();
        }
        modifyTime = LocalDateTime.now();
        if (resolveStatus == null) {
            resolveStatus = "未解决";
        }
        if (tagStatus == null) {
            tagStatus = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
        modifyTime = LocalDateTime.now();
    }
}
