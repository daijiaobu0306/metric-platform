package com.metric.metric.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "metric_item", indexes = {
    @Index(name = "idx_category_id", columnList = "category_id")
})
public class MetricItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    // 指标小类（分组）
    @Column(name = "sub_category_name", length = 100)
    private String subCategoryName;

    @Column(name = "sub_category_code", length = 50)
    private String subCategoryCode;

    @Column(name = "metric_code", nullable = false, length = 50)
    private String metricCode;

    @Column(name = "metric_name", nullable = false, length = 100)
    private String metricName;

    @Column(name = "metric_type", length = 50)
    private String metricType;

    @Column(name = "metric_definition", length = 500)
    private String metricDefinition;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "data_source", length = 100)
    private String dataSource;

    @Column(name = "collect_frequency", length = 50)
    private String collectFrequency;

    @Column(name = "baseline_value", precision = 10, scale = 2)
    private BigDecimal baselineValue;

    @Column(name = "normal_min", precision = 10, scale = 2)
    private BigDecimal normalMin;

    @Column(name = "normal_max", precision = 10, scale = 2)
    private BigDecimal normalMax;

    /**
     * 面向人员（如网格长、营业员、客户经理、直销人员）
     *
     * <p>展示用文本，写入时由 audience_code 对应的岗位名称回填，保证两列不矛盾。
     */
    @Column(name = "responsible_person", length = 100)
    private String responsiblePerson;

    /**
     * 面向人员（岗位）编码，指向 metric_audience.audience_code。
     *
     * <p>2026-11-17 新增：岗位是业务标签，用于运营观测/专屏按人员维度聚合与过滤，
     * 与 sys_role（平台权限角色）无关。一个指标只挂一个岗位。
     */
    @Column(name = "audience_code", length = 50)
    private String audienceCode;

    /**
     * 统计口径
     */
    @Column(name = "statistics_caliber", length = 500)
    private String statisticsCaliber;

    /**
     * 数据对接模式（如 prometheus）
     */
    @Column(name = "data_sync_mode", length = 50)
    private String dataSyncMode;

    /**
     * 采集状态（如 已采集 / 未对接）
     */
    @Column(name = "collect_status", length = 50)
    private String collectStatus;

    /**
     * 部室
     */
    @Column(name = "department", length = 100)
    private String department;

    /**
     * 填报人
     */
    @Column(name = "reporter", length = 50)
    private String reporter;

    /*
     * 说明（2026-10-28 指标与告警解耦）：
     * 指标只描述"是什么"，不再承载告警 / 工单阈值。
     *   - 告警阈值、条件、连续次数 → 唯一来源 AlarmStrategy（告警配置模块）
     *   - 算分所需的基准值 → metric_score_baseline（与告警无关，算法专题将替换）
     * 历史上 alarm_threshold / alarm_threshold_type / gongdan_threshold /
     * gongdan_threshold_type / alarm_consecutive_times / gongdan_consecutive_times
     * 六列已由迁移 V20261028093500 删除。
     */

    @Column(name = "trend_direction", length = 20)
    private String trendDirection;

    @Column(name = "status")
    private Integer status;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    /*
     * ===== 以下 4 个为「只读展示字段」，不落库（@Transient）=====
     * 2026-10-28 指标与告警解耦后，阈值只存在于 alarm_strategy。
     * 列表页需要显示每个指标的「告警概览」（告警/工单是否配置、阈值多少），
     * 由 MetricService 查询策略快照后装配，随实体一起返回给前端，不写回数据库。
     * 口径与 AlarmStrategyQueryService 一致：只看启用策略、该字段有值、取 id 最小的一条。
     */
    @Transient
    private java.util.List<com.metric.alarm.entity.StrategyRule> alarmRules;

    @Transient
    private java.util.List<com.metric.alarm.entity.StrategyRule> gongdanRules;

    @Transient
    private BigDecimal alarmThreshold;

    @Transient
    private String alarmCondition;

    @Transient
    private BigDecimal gongdanThreshold;

    @Transient
    private String gongdanCondition;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
