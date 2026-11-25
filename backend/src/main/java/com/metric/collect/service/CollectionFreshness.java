package com.metric.collect.service;

import java.time.*;
import java.util.*;

/** A batch-date arrival check, independent of metric value thresholds and completeness. */
final class CollectionFreshness {
    private CollectionFreshness() {}
    static Map<String,Object> describe(CollectionSource source,LocalDate latest,LocalDateTime now) {
        var result=new LinkedHashMap<String,Object>();
        result.put("latestBusinessDate",latest==null?null:latest.toString());
        result.put("expectedDate",null);result.put("dueAt",null);
        result.put("note","按已入库批次的最新业务日期判断，不代表全部指标齐全；不改写文件数据时间。");
        if(source.arrival()==null) { result.put("status","unconfigured");result.put("label","应到规则未配置");return result; }
        var expected=CollectionSchedule.expectedDate(source,now);
        var due=now.toLocalDate().atTime(LocalTime.parse(source.arrival().dueTime()));
        result.put("expectedDate",expected.toString());result.put("dueAt",due.toString());
        String status=!source.enabled()?"paused":latest!=null && !latest.isBefore(expected)?"current":now.isBefore(due)?"waiting":"late";
        result.put("status",status);
        result.put("label",switch(status) {case "paused"->"已暂停检查";case "current"->"数据已到";case "waiting"->"尚未到应到时间";default->"数据迟到";});
        return result;
    }
}
