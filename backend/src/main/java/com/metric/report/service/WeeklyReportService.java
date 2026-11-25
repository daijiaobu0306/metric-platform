package com.metric.report.service;

import com.metric.metric.service.MetricPeriodQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class WeeklyReportService {
    private final MetricPeriodQueryService metrics;
    public WeeklyReportService(MetricPeriodQueryService metrics) { this.metrics=metrics; }

    public Map<String,Object> preview(LocalDate start, LocalDate end, Long systemId) {
        validate(start,end);
        var rows=metrics.periodMetrics(start,end,systemId);
        long covered=rows.stream().filter(row -> MetricPeriodQueryService.number(row,"validCount")>0).count();
        long conflicts=rows.stream().filter(row -> Objects.toString(row.get("dataStatus"),"").contains("冲突")
            || Objects.toString(row.get("dataStatus"),"").contains("编码")).count();
        var result=new LinkedHashMap<String,Object>();
        result.put("title","端到端监控运营周报");
        result.put("startDate",start);
        result.put("endDate",end);
        result.put("endExclusive",end.plusDays(1));
        result.put("timezone","Asia/Shanghai");
        result.put("generatedAt",LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        result.put("metrics",rows);
        result.put("mappingIssues",metrics.mappingIssues(start,end,systemId));
        result.put("summary",Map.of("metricCount",rows.size(),"coveredMetricCount",covered,
            "withoutValidDataCount",rows.size()-covered,"conflictMetricCount",conflicts,
            "systemCount",rows.stream().map(row -> row.get("systemId")).distinct().count()));
        result.put("notes",List.of(
            "日期按北京时间计算，包含所选起止日；按原始数据时间筛选，导入时间仅用于选择修订。",
            "本页为实时查询生成的草稿，尚未定稿归档；补采、修订及规则调整后重新生成可能变化。",
            "逐条保留来源数值与有效零值；不把缺数当零、不以告警或问题数量替代文件指标值。",
            "周汇总口径尚待确认，未对明细求和或平均，也未判定整周正常、异常；阈值仅展示当前配置供核对。"
        ));
        return result;
    }

    public Map<String,Object> observations(Long metricId,LocalDate start,LocalDate end,int page,int size) {
        validate(start,end);
        if (metricId==null || metricId<=0 || page<0 || size<1 || size>100) throw new IllegalArgumentException("明细分页参数无效，每页最多100条");
        return metrics.observations(metricId,start,end,page,size);
    }

    private static void validate(LocalDate start,LocalDate end) {
        if (start==null || end==null || end.isBefore(start)) throw new IllegalArgumentException("请选择有效的报告起止日期");
        if (ChronoUnit.DAYS.between(start,end)>=31) throw new IllegalArgumentException("单次报告周期最多31天");
        if (end.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) throw new IllegalArgumentException("报告结束日期不能晚于今天");
    }
}
