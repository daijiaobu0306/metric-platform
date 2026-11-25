package com.metric.observe.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "dashboard_view_config")
public class DashboardViewConfig {

    @Transient
    private String visibilitySummary;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "view_name", length = 100)
    private String viewName;

    @Column(name = "view_code", length = 50, unique = true)
    private String viewCode;

    @Column(name = "category_name", length = 50)
    private String categoryName;

    @Column(name = "sub_category_name", length = 50)
    private String subCategoryName;

    @Column(name = "layout_config", columnDefinition = "TEXT")
    private String layoutConfig;

    @Column(name = "module_config", columnDefinition = "TEXT")
    private String moduleConfig;

    @Column(name = "is_default")
    private Integer isDefault = 0;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "status")
    private Integer status = 1;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
