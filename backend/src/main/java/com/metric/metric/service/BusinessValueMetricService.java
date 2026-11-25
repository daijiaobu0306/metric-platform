package com.metric.metric.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.TransientDataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Business value files feed the same metric definitions and realtime values used by reports. */
@Service
public class BusinessValueMetricService {
    private static final Logger log = LoggerFactory.getLogger(BusinessValueMetricService.class);
    private final Set<String> pendingStartupSources = new LinkedHashSet<>(List.of("running_efficiency"));
    private TransactionTemplate startupTransaction;

    @org.springframework.beans.factory.annotation.Autowired
    public void configureStartupTransaction(PlatformTransactionManager transactionManager) {
        startupTransaction = new TransactionTemplate(transactionManager);
        startupTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CollectedMetricService collected;
    public BusinessValueMetricService(JdbcTemplate jdbc, ObjectMapper mapper) { this(jdbc,mapper,new CollectedMetricService(jdbc)); }
    @org.springframework.beans.factory.annotation.Autowired
    public BusinessValueMetricService(JdbcTemplate jdbc,ObjectMapper mapper,CollectedMetricService collected) {
        this.jdbc=jdbc;this.mapper=mapper;this.collected=collected;
    }
    public static boolean supports(com.metric.metric.entity.MetricItem metric) {
        return "business_value".equals(metric.getDataSource()) || "running_efficiency".equals(metric.getDataSource())
            || "internal_staff".equals(metric.getDataSource());
    }
    public record Observation(BigDecimal value, LocalDateTime dataTime) {}
    private List<Map<String,Object>> definitions() { return definitions("business_value"); }
    private static String category(String source) {
        return switch(source) { case "business_value" -> "businessValue"; case "running_efficiency" -> "system_run";
            case "internal_staff" -> "metric_g2";
            default -> throw new IllegalArgumentException("指标值采集来源未配置"); };
    }
    private List<Map<String,Object>> definitions(String source) {
        String categoryCode=category(source);
        return collected.definitions(categoryCode);
    }
    public void validate(List<Map<String,Object>> rows) { validate("business_value",rows); }
    public void validate(String source,List<Map<String,Object>> rows) {
        var catalog=new HashMap<String,Map<String,Object>>();
        definitions(source).forEach(row -> catalog.put(row.get("metricCode").toString(),row));
        Set<String> seen=new HashSet<>();
        for (var row:rows) {
            String code=MetricCodeIdentity.canonical(category(source),Objects.toString(row.get("metric_code"),""));
            var definition=catalog.get(code);
            if (definition==null) throw new IllegalArgumentException("指标编码未配置或不属于当前采集来源："+code);
            String[][] fields={{"metric_name","metricName"},{"sub_category_name","subCategoryName"},{"unit","unit"},{"category_name","categoryName"}};
            for (var field:fields) {
                Object incoming = row.get(field[0]);
                boolean oldAlarmRateLabel = "business_value".equals(source) && "alarmHandleRate".equals(code)
                    && "metric_name".equals(field[0]) && "告警处理及时率".equals(incoming)
                    && "告警处理率".equals(definition.get(field[1]));
                if (incoming != null && !Objects.equals(incoming, definition.get(field[1])) && !oldAlarmRateLabel)
                    throw new IllegalArgumentException("指标名称、小类、大类或单位与指标体系不一致："+code);
            }
            if (row.get("metric_value")==null || row.get("data_time")==null)
                throw new IllegalArgumentException("指标值和数据时间不能为空："+code);
            BigDecimal value=new BigDecimal(row.get("metric_value").toString());
            if (value.signum()<0 || value.compareTo(new BigDecimal("99999999999.9999"))>0 || value.stripTrailingZeros().scale()>4
                    || ("%".equals(definition.get("unit")) && value.compareTo(BigDecimal.valueOf(100))>0)
                    || ("个".equals(definition.get("unit")) && value.stripTrailingZeros().scale()>0))
                throw new IllegalArgumentException("指标值超出范围或精度："+code);
            var time=LocalDateTime.parse(row.get("data_time").toString().replace(' ','T'));
            if (!seen.add(code+"/"+time)) throw new IllegalArgumentException("同一指标和数据时间重复："+code);
        }
    }
    public Observation latest(Long id) {
        var value=collected.latest(id);
        return value==null?null:new Observation(value.value(),value.dataTime());
    }
    public List<Map<String,Object>> raw(Long metricId,int page,int size) {return collected.raw(metricId,page,size);}
    public long rawCount(Long metricId) {return collected.rawCount(metricId);}
    public Map<String,Object> overview(String batchId) { return overview("business_value",batchId); }
    public Map<String,Object> overview(String source,String batchId) {
        category(source);
        var batches=jdbc.queryForList("SELECT id,source_file AS sourceFile,business_date AS businessDate,row_count AS rowCount,collected_at AS collectedAt,archived FROM collection_file WHERE source_code='"+source+"' ORDER BY business_date DESC,collected_at DESC,id DESC LIMIT 100");
        if (batchId==null || batchId.isBlank()) batchId=batches.isEmpty()?null:batches.get(0).get("id").toString();
        var rows=new ArrayList<Map<String,Object>>();
        if (batchId!=null) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM collection_file WHERE id=? AND source_code='"+source+"'",Integer.class,batchId)!=1)
                throw new IllegalArgumentException("采集批次不存在或不属于当前来源");
            var catalog=new HashMap<String,Map<String,Object>>();
            definitions(source).forEach(row -> catalog.put(row.get("metricCode").toString(),row));
            var latestRows=new LinkedHashMap<String,Map<String,Object>>();
            for (var raw:jdbc.queryForList("SELECT data_json FROM collection_row WHERE batch_id=? ORDER BY data_row_number",batchId)) {
                Map<String,Object> data;
                try { data=mapper.readValue(raw.get("data_json").toString(),new TypeReference<Map<String,Object>>(){}); }
                catch (Exception e) { throw new IllegalStateException("指标值采集记录损坏",e); }
                var definition=catalog.get(MetricCodeIdentity.canonical(category(source),Objects.toString(data.get("metric_code"),"")));
                if (definition==null) continue;
                var item=new LinkedHashMap<String,Object>(definition);
                item.put("metricValue",data.get("metric_value")); item.put("dataTime",data.get("data_time"));
                String code=definition.get("metricCode").toString();
                var previous=latestRows.get(code);
                if (previous==null || LocalDateTime.parse(item.get("dataTime").toString().replace(' ','T'))
                        .isAfter(LocalDateTime.parse(previous.get("dataTime").toString().replace(' ','T')))) latestRows.put(code,item);
            }
            rows.addAll(latestRows.values());
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("batchId",batchId); result.put("batches",batches); result.put("rows",rows); return result;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        retryStartupBackfill();
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public synchronized void retryStartupBackfill() {
        var sources = pendingStartupSources.iterator();
        while (sources.hasNext()) {
            String source = sources.next();
            try {
                // 每个来源独立提交，异常在事务边界外捕获，锁超时会完整回滚。
                startupTransaction.executeWithoutResult(status -> refresh(source));
                sources.remove();
                log.info("Startup metric backfill completed for {}", source);
            } catch (TransientDataAccessException exception) {
                log.warn("Startup metric backfill deferred for {}; will retry in 60 seconds", source, exception);
            }
        }
    }
    @Transactional
    public void refresh() { refresh("business_value"); }
    @Transactional
    public void refresh(String source) { collected.publish(category(source)); }
}
