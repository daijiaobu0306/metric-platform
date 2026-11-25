package com.metric.collect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.metric.repository.MetricItemRepository;
import com.metric.metric.service.MetricMonitorService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Bridge normalized script observations to the existing daily/monthly satisfaction rule reader. */
@Service
public class SatisfactionScriptIngestionService {
    private final JdbcTemplate jdbc;
    private final MetricItemRepository items;
    private final MetricMonitorService monitor;
    private final Map<String,JsonNode> catalog=new LinkedHashMap<>();
    public SatisfactionScriptIngestionService(JdbcTemplate jdbc,ObjectMapper mapper,MetricItemRepository items,MetricMonitorService monitor) throws java.io.IOException {
        this.jdbc=jdbc;this.items=items;this.monitor=monitor;
        try(var input=new ClassPathResource("local/satisfaction-final-catalog.json").getInputStream()) {
            for(var metric:mapper.readTree(input).path("metrics")) catalog.put(metric.path("code").asText(),metric);
        }
    }
    public void storeAndPublish(String batch,String hash,String sourceFile,List<Map<String,Object>> rows) {
        Map<LocalDate,List<Map<String,Object>>> days=new TreeMap<>();
        for(var row:rows) {
            if(!catalog.containsKey(row.get("metric_code"))) throw new IllegalArgumentException("满意度指标不在已确认清单："+row.get("metric_code"));
            LocalDate date=LocalDateTime.parse(row.get("data_time").toString()).toLocalDate();
            days.computeIfAbsent(date,key -> new ArrayList<>()).add(row);
        }
        for(var day:days.entrySet()) {
            Set<Object> codes=new HashSet<>();day.getValue().forEach(row -> codes.add(row.get("metric_code")));
            if(day.getValue().size()!=catalog.size() || !codes.equals(catalog.keySet()))
                throw new IllegalArgumentException("满意度CSV每个数据日须完整提供已确认的17项指标，可保留缺失值，不能用旧批次补齐");
            String report=days.size()==1?batch:UUID.nameUUIDFromBytes((batch+day.getKey()).getBytes(StandardCharsets.UTF_8)).toString();
            jdbc.update("INSERT INTO satisfaction_report_batch(id,file_hash,source_file,business_date,date_basis,imported_at) VALUES (?,?,?,?,?,NOW())",
                report,hash,sourceFile,day.getKey(),"Python脚本输出的实际数据时间");
            for(var row:day.getValue()) {
                var metric=catalog.get(row.get("metric_code"));
                BigDecimal value=row.get("metric_value")==null?null:new BigDecimal(row.get("metric_value").toString());
                String location=Objects.toString(row.get("_source"),"CSV");
                jdbc.update("INSERT INTO satisfaction_report_result(batch_id,metric_code,source_row,group_name,source_scope,metric_name,metric_value,unit,value_status,calculation_note,source_cell,definition,statistical_rule,threshold_note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    report,row.get("metric_code"),metric.path("sourceRow").asInt(),metric.path("groupName").asText(),metric.path("sourceScope").asText(),row.get("metric_name"),value,row.get("unit"),value==null?"缺数":"已采集",
                    "来源CSV直接提供指标值；未从内部告警或工单重新计算",location,metric.path("definition").asText(),metric.path("statisticalRule").asText(),metric.path("thresholdNote").asText());
                jdbc.update("INSERT INTO satisfaction_report_raw(batch_id,source_type,source_row,metric_name,raw_text,metric_value,unit,source_rule) VALUES (?,?,?,?,?,?,?,?)",
                    report,metric.path("groupName").asText(),metric.path("sourceRow").asInt(),row.get("metric_name"),Objects.toString(row.get("metric_value"),""),value,row.get("unit"),"CSV直接指标值；"+location);
            }
        }
        for(var row:jdbc.queryForList("SELECT m.id FROM metric_item m JOIN metric_category c ON c.id=m.category_id WHERE c.category_code='satisfaction' AND m.data_source='satisfaction_report' AND m.status=1"))
            items.findById(((Number)row.get("id")).longValue()).ifPresent(monitor::processMetricItem);
    }
}
