package com.metric.alarm.entity;

import lombok.Data;
import java.math.BigDecimal;

/** 一条独立的分级规则。级别与工单/BOMC保持一致：2一般、3重要、4严重。 */
@Data
public class StrategyRule {
    private Integer level;
    private String condition;
    private BigDecimal threshold;
    private Integer consecutiveTimes;
}
