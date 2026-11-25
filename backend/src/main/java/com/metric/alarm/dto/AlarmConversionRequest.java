package com.metric.alarm.dto;

import com.metric.gongdan.entity.Gongdan;
import com.metric.issue.entity.Issue;
import lombok.Getter;
import lombok.Setter;

/** 单次转换使用 alarmId；整组转换沿用列表的筛选快照。 */
@Getter
@Setter
public class AlarmConversionRequest extends AlarmSummaryRequest {
    private Long alarmId;
    private String kind;
    private Issue issue;
    private Gongdan gongdan;
}
