package com.metric.alarm.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/** 汇总、明细和组操作共用同一份筛选快照，防止跨分页漏计或操作范围漂移。 */
@Data
public class AlarmSummaryRequest {
    private Long systemId;
    private Long categoryId;
    private Long metricId;
    private String alarmType;
    private Integer processStatus;
    private Integer shieldStatus;
    private String alarmContent;
    private String alarmTimeStart;
    private String alarmTimeEnd;
    private Long snapshotId;
    private int page;
    private int size = 10;
    private String sortBy = "alarmTime";
    private String sortOrder = "desc";
    private String groupKey;
    private List<Selection> groups;
    private String action;
    private Integer newProcessStatus;
    private Integer newShieldStatus;
    private LocalDateTime shieldEndTime;

    @Data
    public static class Selection {
        private String groupKey;
        private long expectedCount;
    }
}