package com.metric.alarm.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "alarm_strategy", indexes = {
    @Index(name = "idx_target", columnList = "target_type, target_id")
})
public class AlarmStrategy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false, length = 100)
    private String strategyName;

    @Column(name = "strategy_type", nullable = false, length = 50)
    private String strategyType;

    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "target_name", length = 200)
    private String targetName;

    @Convert(converter = StrategyRulesConverter.class)
    @Column(name = "alarm_rules", columnDefinition = "TEXT")
    private java.util.List<StrategyRule> alarmRules;

    @Convert(converter = StrategyRulesConverter.class)
    @Column(name = "gongdan_rules", columnDefinition = "TEXT")
    private java.util.List<StrategyRule> gongdanRules;

    /** 仅扫描时使用的已选规则，不参与持久化或API序列化。 */
    @Transient
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Integer selectedRuleLevel;

    // ==================== 告警配置 ====================
    @Column(name = "alarm_enabled")
    private Integer alarmEnabled = 1;

    @Column(name = "alarm_threshold", precision = 15, scale = 4)
    private BigDecimal alarmThreshold;

    @Column(name = "alarm_condition", length = 20)
    private String alarmCondition = ">";

    @Column(name = "alarm_consecutive_times")
    private Integer alarmConsecutiveTimes = 1;

    // ==================== 工单配置 ====================
    @Column(name = "gongdan_enabled")
    private Integer gongdanEnabled = 1;

    @Column(name = "gongdan_threshold", precision = 15, scale = 4)
    private BigDecimal gongdanThreshold;

    @Column(name = "gongdan_condition", length = 20)
    private String gongdanCondition = ">";

    @Column(name = "gongdan_consecutive_times")
    private Integer gongdanConsecutiveTimes = 1;

    // ==================== 保留旧字段（兼容）====================
    /**
     * @deprecated 请使用 alarmThreshold/alarmCondition/alarmConsecutiveTimes 代替
     */
    @Column(name = "condition_type", length = 50)
    private String conditionType;

    /**
     * @deprecated 请使用 alarmThreshold/gongdanThreshold 代替
     */
    @Column(name = "threshold_value", precision = 10, scale = 2)
    private BigDecimal thresholdValue;

    /**
     * @deprecated 请使用 alarmConsecutiveTimes/gongdanConsecutiveTimes 代替
     */
    @Column(name = "consecutive_times")
    private Integer consecutiveTimes;

    /**
     * @deprecated 请使用 alarmEnabled/gongdanEnabled 代替
     */
    @Column(name = "generate_type", length = 50)
    private String generateType;

    @Column(name = "status")
    private Integer status;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (consecutiveTimes == null) {
            consecutiveTimes = 1;
        }
        if (generateType == null) {
            generateType = "alarm";
        }
        if (status == null) {
            status = 1;
        }
        if (alarmEnabled == null) {
            alarmEnabled = 1;
        }
        if (gongdanEnabled == null) {
            gongdanEnabled = 1;
        }
        if (alarmConsecutiveTimes == null) {
            alarmConsecutiveTimes = 1;
        }
        if (gongdanConsecutiveTimes == null) {
            gongdanConsecutiveTimes = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
