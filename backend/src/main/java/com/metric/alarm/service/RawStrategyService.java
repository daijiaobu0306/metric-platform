package com.metric.alarm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metric.alarm.entity.*;
import com.metric.alarm.repository.*;
import com.metric.gongdan.entity.Gongdan;
import com.metric.gongdan.service.GongdanService;
import com.metric.gongdan.service.GongdanIntegrationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 原始数据策略：只读受控来源，复用现有配置JSON与观察状态，不改变明细表。 */
@Service
public class RawStrategyService {
    public record Source(String key, String group, String name, String table, List<String> values, List<String> filters) {}
    private static Source source(String table, String group, String name, String values, String filters) {
        return new Source(table, group, name, table, List.of(values.split(",")), List.of(filters.split(",")));
    }
    private static final List<Source> SOURCES = List.of(
        source("inner_query", "对内部人员", "查询类 · 查询记录", "avg_duration_sec", "business_name,step_name,step_type"),
        source("inner_step_response", "对内部人员", "办理类 · 步骤响应", "avg_response_sec,interval_sec", "sub_category,business_name,step_name"),
        source("inner_button_count", "对内部人员", "办理类 · 按钮数量", "button_count", "sub_category,business_name"),
        source("inner_business_duration", "对内部人员", "办理类 · 业务耗时", "full_duration_sec,full_interval_sec,full_service_sec,failure_count", "sub_category,business_name"),
        source("metric_system_run_detail", "运行效率-系统性能", "系统性能对象明细", "metric_value", "metric_name,metric_attr,object_url,object_name"),
        source("metric_system_run_summary", "运行效率-系统性能", "系统性能汇总", "metric_value", "metric_name,metric_attr,object_name"),
        source("metric_error_interface", "运行效率-系统异常", "接口异常", "failure_count,error_count,error_rate,success_rate,avg_response_time", "interface_url,interface_name"),
        source("metric_error_instance", "运行效率-系统异常", "应用实例异常", "error_count", "app_name,error_name,instance_name"),
        source("metric_error_js", "运行效率-系统异常", "JavaScript异常", "error_count,total_error_count", "error_name,error_message"),
        source("metric_error_biz_return", "运行效率-系统异常", "业务返回异常", "count,error_count", "system_name,return_code,return_info"),
        source("metric_error_return_code", "运行效率-系统异常", "返回码异常", "count,error_count", "return_code,return_message"),
        source("system_jhd", "合规管理", "月数据清单", "abnormal_count,order_2month,unclosed_2month,close_order_count,close_order_rate", "check_code,check_name,business_module"),
        source("system_jhd_detail", "合规管理", "工单明细", "fix_count", "audit_id,audit_name,department,status"),
        source("satisfaction_report_raw", "满意度及投诉服务体验", "投诉服务原文数值", "metric_value", "source_type,metric_name"),
        source("satisfaction_report_result", "满意度及投诉服务体验", "投诉服务指标结果", "metric_value", "metric_code,metric_name,group_name"),
        source("collection:external_customer", "对外部客户", "采集指标原始记录", "metric_value", "metric_code,metric_name,sub_category_name"),
        source("collection:resource_input", "资源投入", "采集指标原始记录", "metric_value", "metric_code,metric_name,sub_category_name"),
        source("collection:business_value", "业务价值", "采集指标原始记录", "metric_value", "metric_code,metric_name,sub_category_name"),
        source("collection:running_efficiency", "运行效率-系统性能", "采集指标原始记录", "metric_value", "metric_code,metric_name,sub_category_name")
    );
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AlarmStrategyRepository strategies;
    private final RawDataAlarmConfigRepository configs;
    private final StrategyRuleService rules;
    private final AlarmService alarms;
    private final GongdanService tickets;
    private final GongdanIntegrationService integration;
    private final org.springframework.transaction.support.TransactionTemplate newTransaction;

    public RawStrategyService(JdbcTemplate jdbc, ObjectMapper mapper, AlarmStrategyRepository strategies,
            RawDataAlarmConfigRepository configs, StrategyRuleService rules, AlarmService alarms,
            GongdanService tickets, GongdanIntegrationService integration, org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc=jdbc; this.mapper=mapper; this.strategies=strategies; this.configs=configs;
        this.rules=rules; this.alarms=alarms; this.tickets=tickets; this.integration=integration;
        this.newTransaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public List<Source> catalog() { return SOURCES; }
    private Source find(String key) {
        return SOURCES.stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请选择有效的数据来源"));
    }
    @Transactional
    public void delete(Long id) {
        configs.deleteAll(configs.findByStrategyId(id));
        jdbc.update("DELETE FROM alarm_observation_state WHERE strategy_id=? AND (scope_key='raw-cursor' OR scope_key LIKE 'raw-count:%')",id);
        strategies.deleteById(id);
    }
    public JsonNode definition(Long id) {
        return configs.findByStrategyId(id).stream().filter(this::unified).findFirst()
                .map(c -> json(c.getDimensionThresholds())).orElseThrow(() -> new IllegalArgumentException("此策略没有新版原始数据配置"));
    }
    public boolean unified(RawDataAlarmConfig config) {
        return config.getDimensionThresholds()!=null && !config.getDimensionThresholds().isBlank() && json(config.getDimensionThresholds())!=null && json(config.getDimensionThresholds()).path("version").asInt()==2;
    }
    private JsonNode json(String value) {
        try { return mapper.readTree(value); } catch(Exception e) { throw new IllegalArgumentException("原始数据配置格式无效",e); }
    }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("配置序列化失败",e); }
    }
    @Transactional
    public ObjectNode validate(AlarmStrategy strategy, JsonNode input) {
        AlarmStrategy existing = strategy.getId()==null ? null : strategies.findById(strategy.getId())
                .orElseThrow(() -> new IllegalArgumentException("策略不存在"));
        if(existing!=null && !"raw".equals(existing.getStrategyType())) throw new IllegalArgumentException("请新增原始数据策略，不能变更既有策略类型");
        if(input==null || !input.isObject()) throw new IllegalArgumentException("请填写原始数据配置");
        ObjectNode definition=input.deepCopy(); definition.put("version",2);
        Source source=find(definition.path("source").asText());
        if(!source.values().contains(definition.path("valueField").asText())) throw new IllegalArgumentException("检查字段无效");
        validateFilter(source,definition.path("filter"));
        if(!definition.path("overrides").isMissingNode() && !definition.path("overrides").isArray()) throw new IllegalArgumentException("个性化规则格式无效");
        if(definition.path("overrides").size()>30) throw new IllegalArgumentException("个性化规则最多30条");
        for(JsonNode override:definition.path("overrides")) {
            validateFilter(source,override.path("filter"));
            if(override.path("filter").path("value").asText().isBlank()) throw new IllegalArgumentException("个性化规则必须填写匹配范围");
            AlarmStrategy custom = customRules(override);
            custom.setAlarmEnabled(strategy.getAlarmEnabled()); custom.setGongdanEnabled(strategy.getGongdanEnabled());
            rules.prepare(custom,null);
        }
        strategy.setStrategyType("raw"); strategy.setTargetType("raw");
        strategy.setTargetName(source.group()+" / "+source.name());
        if(strategy.getStrategyName()==null || strategy.getStrategyName().isBlank() || strategy.getStrategyName().length()>100)
            throw new IllegalArgumentException("策略名称必填，最多100字");
        if(existing!=null) strategy.setCreateTime(existing.getCreateTime());
        if(strategy.getAlarmRules()==null || strategy.getGongdanRules()==null) throw new IllegalArgumentException("请使用完整的三档规则编辑器保存");
        if(strategy.getAlarmEnabled()==null || strategy.getGongdanEnabled()==null || !Set.of(0,1).contains(strategy.getAlarmEnabled()) || !Set.of(0,1).contains(strategy.getGongdanEnabled()) || strategy.getStatus()==null || !Set.of(0,1).contains(strategy.getStatus()))
            throw new IllegalArgumentException("策略和规则开关必须为启用或停用");
        rules.prepare(strategy,existing);
        return definition;
    }
    @Transactional
    public AlarmStrategy save(AlarmStrategy strategy, JsonNode input) {
        ObjectNode definition=validate(strategy,input);
        Source source=find(definition.path("source").asText());
        // 配置与策略在同一事务保存；仅新建时建立游标，不补算已有模拟数据。
        strategies.saveAndFlush(strategy);
        RawDataAlarmConfig config=configs.findByStrategyId(strategy.getId()).stream().filter(this::unified).findFirst().orElseGet(RawDataAlarmConfig::new);
        config.setStrategyId(strategy.getId()); config.setTableName(source.table().startsWith("collection:") ? "collection_row" : source.table());
        config.setMetricField(definition.path("valueField").asText()); config.setDimensionThresholds(encode(definition));
        config.setAlarmStatus(strategy.getStatus()); config.setAggMethod("NONE"); config.setGlobalThreshold(null);
        configs.save(config);
        initialize(strategy.getId(),source);
        strategy.setTargetId(config.getId());
        return strategies.save(strategy);
    }
    private AlarmStrategy customRules(JsonNode override) {
        AlarmStrategy custom=new AlarmStrategy();
        if(!override.path("alarmRules").isArray() || !override.path("gongdanRules").isArray())
            throw new IllegalArgumentException("个性化规则必须包含告警和工单规则列表");
        custom.setAlarmRules(mapper.convertValue(override.path("alarmRules"),new com.fasterxml.jackson.core.type.TypeReference<List<StrategyRule>>(){}));
        custom.setGongdanRules(mapper.convertValue(override.path("gongdanRules"),new com.fasterxml.jackson.core.type.TypeReference<List<StrategyRule>>(){}));
        return custom;
    }
    public String tryMatch(JsonNode definition,String sample) {
        Source source=find(definition.path("source").asText());
        if(sample.length()>2000) throw new IllegalArgumentException("试匹配内容最多2000字");
        for(String mode:List.of("exact","contains","regex")) {
            int index=0;
            for(JsonNode custom:definition.path("overrides")) {
                index++;validateFilter(source,custom.path("filter"));
                if(mode.equals(custom.path("filter").path("mode").asText()) && matches(Map.of(custom.path("filter").path("field").asText(),sample),custom.path("filter")))
                    return "命中例外 "+index+"（字段："+custom.path("filter").path("field").asText()+"）；试匹配仅检查例外范围，实际记录还需满足适用范围。";
            }
        }
        return "未命中例外，使用默认规则；实际记录还需满足适用范围。";
    }
    private void validateFilter(Source source, JsonNode filter) {
        if(filter.isMissingNode() || filter.isNull() || filter.path("value").asText().isBlank()) return;
        if(!source.filters().contains(filter.path("field").asText())) throw new IllegalArgumentException("筛选字段无效");
        String mode=filter.path("mode").asText();
        if(!Set.of("exact","contains","regex").contains(mode)) throw new IllegalArgumentException("匹配方式无效");
        String value=filter.path("value").asText();
        if(value.length()>200) throw new IllegalArgumentException("匹配内容最多200字");
        if("regex".equals(mode)) {
            // 使用RE2线性引擎，避免用户表达式阻塞扫描线程。
            try { com.google.re2j.Pattern.compile(value); } catch(Exception e) { throw new IllegalArgumentException("正则表达式无效："+e.getMessage()); }
        }
    }
    private boolean matches(Map<String,Object> row, JsonNode filter) {
        String expected=filter.path("value").asText(); if(expected.isBlank()) return true;
        Object value=row.get(filter.path("field").asText()); if(value==null) return false;
        String actual=value.toString();
        return switch(filter.path("mode").asText()) {
            case "exact" -> actual.equals(expected);
            case "contains" -> actual.contains(expected);
            case "regex" -> com.google.re2j.Pattern.compile(expected).matcher(actual).find();
            default -> false;
        };
    }
    private String query(Source s) {
        if(s.key().startsWith("collection:")) return "SELECT r.data_row_number AS _row,r.batch_id AS _batch,f.collected_at AS _time,r.data_json FROM collection_row r JOIN collection_file f ON f.id=r.batch_id WHERE f.source_code=?";
        if(s.table().startsWith("satisfaction_report_")) return "SELECT r.*,r.source_row AS _row,r.batch_id AS _batch,f.imported_at AS _time FROM "+s.table()+" r JOIN satisfaction_report_batch f ON f.id=r.batch_id WHERE 1=1";
        return "SELECT * FROM `"+s.table()+"` WHERE 1=1";
    }
    private List<Object> parameters(Source s) {
        return new ArrayList<>(s.key().startsWith("collection:") ? List.of(s.key().substring(11)) : List.of());
    }
    private boolean composite(Source s) { return s.key().startsWith("collection:") || s.table().startsWith("satisfaction_report_"); }
    private ObjectNode baseline(Source s) {
        ObjectNode state=mapper.createObjectNode();
        if(!composite(s)) state.put("id",jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM `"+s.table()+"`",Long.class));
        else {
            // 以入库时间基线开始，仅处理保存后收到的批次。
            state.put("time",jdbc.queryForObject("SELECT NOW(6)",String.class));state.put("batch","");state.put("row",0);state.put("measure","");
        }
        return state;
    }
    private void initialize(Long id,Source s) {
        jdbc.update("INSERT INTO alarm_observation_state(strategy_id,scope_key,state_json,updated_at) VALUES (?,'raw-cursor',?,NOW(6)) ON DUPLICATE KEY UPDATE state_json=VALUES(state_json),updated_at=NOW(6)",id,encode(baseline(s)));
        jdbc.update("DELETE FROM alarm_observation_state WHERE strategy_id=? AND scope_key LIKE 'raw-count:%'",id);
    }
    @Transactional
    public int scan(Long id) {
        AlarmStrategy strategy=strategies.findById(id).orElse(null);
        if(strategy==null || !"raw".equals(strategy.getStrategyType()) || !Integer.valueOf(1).equals(strategy.getStatus())) return 0;
        JsonNode d=definition(id); Source s=find(d.path("source").asText());
        jdbc.update("INSERT IGNORE INTO alarm_observation_state(strategy_id,scope_key,state_json,updated_at) VALUES (?,'raw-cursor',?,NOW(6))",id,encode(baseline(s)));
        ObjectNode cursor=(ObjectNode)json(jdbc.queryForObject("SELECT state_json FROM alarm_observation_state WHERE strategy_id=? AND scope_key='raw-cursor' FOR UPDATE",String.class,id));
        List<Object> args=parameters(s); String sql=query(s);
        if(composite(s)) {
            String time=s.key().startsWith("collection:") ? "f.collected_at" : "f.imported_at";
            String row=s.key().startsWith("collection:") ? "r.data_row_number" : "r.source_row";
            String measure=s.key().startsWith("collection:") ? "''" : s.table().endsWith("result") ? "r.metric_code" : "CONCAT(r.source_type,CHAR(31),r.metric_name)";
            sql+=" AND ("+time+",r.batch_id,"+row+","+measure+") > (?,?,?,?) ORDER BY "+time+",r.batch_id,"+row+","+measure+" LIMIT 500";
            args.add(cursor.path("time").asText());args.add(cursor.path("batch").asText());args.add(cursor.path("row").asInt());args.add(cursor.path("measure").asText());
        } else { sql+=" AND id>? ORDER BY id LIMIT 500";args.add(cursor.path("id").asLong()); }
        int generated=0;
        for(Map<String,Object> raw:jdbc.queryForList(sql,args.toArray())) {
            Map<String,Object> record=new HashMap<>(raw);
            if(s.key().startsWith("collection:")) record.putAll(mapper.convertValue(json(raw.get("data_json").toString()),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
            if(matches(record,d.path("filter"))) {
                JsonNode selected=null;
                // 精确范围优先，其次其他匹配按配置顺序，最后默认规则。
                for(String mode:List.of("exact","contains","regex")) {
                    for(JsonNode custom:d.path("overrides")) if(selected==null && mode.equals(custom.path("filter").path("mode").asText()) && matches(record,custom.path("filter"))) selected=custom;
                    if(selected!=null) break;
                }
                AlarmStrategy active=selected==null ? strategy : customRules(selected);
                BigDecimal value=null; Object original=record.get(d.path("valueField").asText());
                if(original!=null) try { value=new BigDecimal(original.toString()); } catch(NumberFormatException ignored) { /* 缺失和非数值不补零 */ }
                if(value!=null && (value.stripTrailingZeros().scale()>4 || value.abs().compareTo(new BigDecimal("100000000000"))>=0))
                    throw new IllegalArgumentException("来源数值超出告警存储精度（11位整数、4位小数），记录："+Objects.toString(record.get("id"),Objects.toString(record.get("_batch"))));
                ObjectNode counts=counts(strategy.getId(),s,record);
                for(boolean ticket:List.of(false,true)) {
                        if(!Integer.valueOf(1).equals(ticket ? strategy.getGongdanEnabled() : strategy.getAlarmEnabled())) continue;
                        StrategyRule match=null;
                        for(StrategyRule rule:StrategyRuleService.effectiveRules(active,ticket)) {
                            String key=(ticket ? "ticket:" : "alarm:")+rule.getLevel();
                            int count=value!=null && condition(value,rule) ? (int)Math.min(Integer.MAX_VALUE,(long)counts.path(key).asInt()+1) : 0;
                            counts.put(key,count);
                            if(count>=rule.getConsecutiveTimes() && (match==null || rule.getLevel()>match.getLevel())) match=rule;
                        }
                        if(match!=null && !shielded(strategy,record) && (!ticket || !openTicket(strategy,record,match))) {
                            generate(strategy,s,record,value,match,ticket,selected!=null,counts.path((ticket ? "ticket:" : "alarm:")+match.getLevel()).asInt());generated++;
                        }
                    }
                jdbc.update("UPDATE alarm_observation_state SET state_json=?,updated_at=NOW(6) WHERE strategy_id=? AND scope_key=?",encode(counts),id,countScope(s,record));
            }
            if(composite(s)) {
                cursor.put("time",raw.get("_time").toString()); cursor.put("batch",raw.get("_batch").toString());cursor.put("row",((Number)raw.get("_row")).intValue());
                cursor.put("measure",s.key().startsWith("collection:") ? "" : s.table().endsWith("result") ? Objects.toString(raw.get("metric_code"),"") : Objects.toString(raw.get("source_type"),"")+"\u001f"+Objects.toString(raw.get("metric_name"),""));
            } else cursor.put("id",((Number)raw.get("id")).longValue());
        }
        jdbc.update("UPDATE alarm_observation_state SET state_json=?,updated_at=NOW(6) WHERE strategy_id=? AND scope_key='raw-cursor'",encode(cursor),id);
        return generated;
    }
    private String countScope(Source source,Map<String,Object> record) {
        // 全部受控对象维度参与身份，避免同一应用下不同异常、同一URL下不同指标串计。
        List<Object> identity=new ArrayList<>();identity.add(source.key());
        for(String field:source.filters()) { identity.add(field);identity.add(record.get(field)); }
        try {
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(encode(identity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "raw-count:"+HexFormat.of().formatHex(hash);
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private ObjectNode counts(Long strategyId,Source source,Map<String,Object> record) {
        String scope=countScope(source,record);
        jdbc.update("INSERT IGNORE INTO alarm_observation_state(strategy_id,scope_key,state_json,updated_at) VALUES (?,?, '{}',NOW(6))",strategyId,scope);
        return (ObjectNode)json(jdbc.queryForObject("SELECT state_json FROM alarm_observation_state WHERE strategy_id=? AND scope_key=? FOR UPDATE",String.class,strategyId,scope));
    }
    private boolean openTicket(AlarmStrategy strategy,Map<String,Object> record,StrategyRule rule) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM gongdan WHERE strategy_id=? AND alarm_object=? AND alarm_level=? AND closed_status=0",Integer.class,strategy.getId(),cut(object(record),200),rule.getLevel());
        return count!=null && count>0;
    }
    private boolean shielded(AlarmStrategy strategy,Map<String,Object> record) {
        String object=object(record);
        Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM alarm WHERE strategy_id=? AND business_name=? AND shield_status=1 AND (shield_end_time IS NULL OR shield_end_time>NOW())",Integer.class,strategy.getId(),cut(object,200));
        return n!=null && n>0;
    }
    private boolean condition(BigDecimal value,StrategyRule rule) {
        if(rule.getThreshold()==null || rule.getCondition()==null) return false;
        int c=value.compareTo(rule.getThreshold());
        return switch(rule.getCondition()) { case ">" -> c>0;case ">=" -> c>=0;case "<" -> c<0;case "<=" -> c<=0;case "=" -> c==0;default -> false; };
    }
    private String object(Map<String,Object> row) {
        for(String field:List.of("object_url","interface_url","business_name","app_name","audit_id","metric_code","object_name","metric_name","error_name","return_code"))
            if(row.get(field)!=null && !row.get(field).toString().isBlank()) {
                List<String> parts=new ArrayList<>();parts.add(row.get(field).toString());
                for(String dimension:List.of("step_name","error_name","return_code","metric_name","metric_attr","sub_category"))
                    if(row.get(dimension)!=null && !parts.contains(row.get(dimension).toString())) parts.add(row.get(dimension).toString());
                return String.join(" / ",parts);
            }
        return Objects.toString(row.get("id"),Objects.toString(row.get("_batch"),"未命名对象"));
    }
    private String cut(String s,int length) { return s.length()>length ? s.substring(0,length) : s; }
    private void generate(AlarmStrategy strategy,Source s,Map<String,Object> row,BigDecimal value,StrategyRule rule,boolean ticket,boolean custom,int consecutiveCount) {
        String object=object(row), time=Objects.toString(row.get("data_time"),Objects.toString(row.get("insert_time"),Objects.toString(row.get("_time"),"未提供")));
        String ref=composite(s) ? row.get("_batch")+" / "+row.get("_row") : Objects.toString(row.get("id"));
        Alarm alarm=new Alarm();alarm.setStrategyId(strategy.getId());alarm.setAlarmType(ticket ? "原始数据工单" : "原始数据告警");
        alarm.setAlarmLevel(rule.getLevel());alarm.setMetricValue(value);alarm.setThresholdValue(rule.getThreshold());alarm.setUnit("ms");
        alarm.setSourceTable(s.key().startsWith("collection:") ? "collection_row" : s.table());
        if(!composite(s)) alarm.setSourceId(((Number)row.get("id")).longValue());
        alarm.setCategoryName(s.group().split("-")[0]);alarm.setSubCategoryName(s.group());alarm.setMetricName(cut(s.name(),200));alarm.setBusinessName(cut(object,200));
        alarm.setAlarmTime(LocalDateTime.now());alarm.setProcessStatus(ticket ? 3 : 0);alarm.setShieldStatus(0);alarm.setTagStatus(0);
        alarm.setAlarmContent("["+strategy.getStrategyName()+"] 对象="+object+"；发生值="+value.stripTrailingZeros().toPlainString()+"ms；条件="+rule.getCondition()+rule.getThreshold().stripTrailingZeros().toPlainString()+"ms；数据时间="+time+"；来源="+alarm.getSourceTable()+" / "+ref+"；"+(custom ? "个性化规则" : "默认规则")+"；连续异常="+consecutiveCount+"条新记录（要求"+rule.getConsecutiveTimes()+"条）；重复扫描不计次");
        if(ticket) {
            Gongdan g=new Gongdan();g.setGongdanId("GD"+UUID.randomUUID().toString().replace("-",""));g.setStrategyId(strategy.getId());
            g.setGongdanTitle(cut("【指标体系平台】"+strategy.getStrategyName()+"："+object,200));g.setGongdanContent(alarm.getAlarmContent());
            g.setGongdanType("自动");g.setCurrentStep("待处理");g.setClosedStatus(0);g.setAlarmObject(cut(object,200));g.setAlarmLevel(rule.getLevel());g.setAlarmFirstTime(alarm.getAlarmTime());g.setAlarmLastTime(alarm.getAlarmTime());g.setAlarmCount(1);
            tickets.save(g);alarm.setRelatedGongdanId(g.getId());alarms.save(alarm);
            // 与已有自动工单一样由集成服务保留推送结果，失败不丢失本地工单。
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { try { newTransaction.execute(status -> integration.pushToBomc(g.getId())); } catch(Exception e) { org.slf4j.LoggerFactory.getLogger(RawStrategyService.class).warn("原始数据工单推送失败，工单ID={}",g.getId(),e); } }
            });
        } else alarms.save(alarm);
    }
}
