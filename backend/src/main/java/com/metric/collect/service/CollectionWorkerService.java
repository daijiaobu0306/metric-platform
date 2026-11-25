package com.metric.collect.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.metric.service.CollectedMetricService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Persistent task leases and standard batch ingestion, independent of plugin language. */
@Service
public class CollectionWorkerService {
    @org.springframework.beans.factory.annotation.Autowired
    private CollectionWorkerPresence presence;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CollectionScriptCatalog scripts;
    private final CollectionDirectoryGuard directories;
    private final CollectedMetricService metrics;
    @org.springframework.beans.factory.annotation.Autowired private SatisfactionScriptIngestionService satisfactionScripts;
    @org.springframework.beans.factory.annotation.Autowired private ComplianceDedicatedTableIngestionService complianceTables;
    @org.springframework.beans.factory.annotation.Autowired private CollectionStorageRoots storageRoots;
    public CollectionWorkerService(JdbcTemplate jdbc,ObjectMapper mapper,CollectionScriptCatalog scripts,
            CollectionDirectoryGuard directories,CollectedMetricService metrics) {
        this.jdbc=jdbc; this.mapper=mapper; this.scripts=scripts; this.directories=directories; this.metrics=metrics;
    }
    private String json(Object object) {
        try { return mapper.writeValueAsString(object); } catch(Exception e) { throw new IllegalArgumentException("数据序列化失败",e); }
    }
    private CollectionSource config(Object json) {
        try { return mapper.readValue(json.toString(),CollectionSource.class); }
        catch(Exception e) { throw new IllegalArgumentException("采集配置无效",e); }
    }
    public void validate(CollectionSource source) {
        CollectionSchedule.validate(source);
        var binding=source.worker();
        if(binding==null || binding.categoryCode()==null || !binding.categoryCode().matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")
                || binding.timeoutSeconds()<1 || binding.timeoutSeconds()>300)
            throw new IllegalArgumentException("请配置指标大类编码、插件版本和1～300秒超时");
        if(!CollectionCategoryScope.supports(binding.categoryCode()))
            throw new IllegalArgumentException("该指标大类未启用文件采集");
        if(source.usesIngestionTime()) {
            if(!"external_customer".equals(binding.categoryCode()) || !"csv".equals(source.format()))
                throw new IllegalArgumentException("平台入库时间模式仅用于对外部客户CSV");
        } else if(!"data_time".equals(source.dateMode())) {
            throw new IllegalArgumentException("采集日期来源无效");
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM metric_category WHERE category_code=?",Integer.class,binding.categoryCode())!=1)
            throw new IllegalArgumentException("指标大类编码不存在或不唯一");
        var metadata=scripts.metadata(binding.version());
        if(!binding.categoryCode().equals(metadata.get("categoryCode")))
            throw new IllegalArgumentException("脚本版本不属于当前指标大类，请选择该大类的命名脚本版本");
        String protocol=source.usesRawRows()?"raw-rows":"metric-values";
        if(!protocol.equals(metadata.get("protocol")))
            throw new IllegalArgumentException("脚本输出协议与任务不匹配");
        if("metric_g1".equals(binding.categoryCode())) {
            String dataset=source.usesRawRows()?"compliance_detail":"compliance";
            if(!metadata.get("datasetCode").isBlank() && !dataset.equals(metadata.get("datasetCode")))
                throw new IllegalArgumentException("合规脚本未声明匹配的数据集，无法绑定："+dataset);
        }
        // 数据质量已确认启用（使用模拟数据投递，仍走正常采集链路），此处不再拦截。
    }
    public void requireIdle(String code) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM collection_worker_run WHERE source_code=? AND state='CLAIMED' AND lease_until>NOW(6)",Integer.class,code)>0)
            throw new IllegalStateException("采集Worker正在执行，请等待本轮结束或租约到期");
    }
    @Transactional
    public Map<String,Object> claim(String workerId) {
        if(workerId==null || !workerId.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}")) throw new IllegalArgumentException("Worker标识无效");
        if(presence!=null) presence.record(workerId);
        var due=jdbc.queryForList("""
            SELECT code,config_json FROM collection_source WHERE enabled=1 AND next_scan_at<=NOW(6)
              AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.parser'))='python-worker'
            ORDER BY next_scan_at,code LIMIT 1 FOR UPDATE SKIP LOCKED
            """);
        if(due.isEmpty()) return Map.of();
        var row=due.get(0); String code=row.get("code").toString();
        if(!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT IS_FREE_LOCK(?)",Integer.class,"collect:"+code))) {
            jdbc.update("UPDATE collection_source SET next_scan_at=DATE_ADD(NOW(),INTERVAL 10 SECOND) WHERE code=?",code);
            return Map.of();
        }
        requireIdle(code);
        jdbc.update("UPDATE collection_worker_run SET state='EXPIRED',finished_at=NOW(6),last_error='租约过期' WHERE source_code=? AND state='CLAIMED' AND lease_until<=NOW(6)",code);
        var source=config(row.get("config_json"));
        try { validate(source); directories.checkSource(source); }
        catch(IllegalArgumentException e) {
            jdbc.update("UPDATE collection_source SET last_error=?,next_scan_at=? WHERE code=?",e.getMessage(),
                CollectionSchedule.afterConfigurationError(source,LocalDateTime.now(CollectionSchedule.ZONE)),code);
            attempt(code,"[worker]","FAILED",e.getMessage());
            return Map.of();
        }
        String id=UUID.randomUUID().toString(), token=UUID.randomUUID().toString();
        // Freeze the existing Java calculation parameters with the run, never invent defaults.
        if (source.worker().version().contains("-production-")) {
            var rules=jdbc.queryForList("""
                SELECT i.metric_code AS metricCode,i.status,CAST(b.baseline_value AS CHAR) AS baseline,
                    (SELECT JSON_ARRAYAGG(k.keyword) FROM metric_error_keywords k WHERE k.metric_id=i.id) AS keywords
                FROM metric_item i JOIN metric_category c ON c.id=i.category_id
                LEFT JOIN metric_score_baseline b ON b.metric_id=i.id
                WHERE c.category_code=? ORDER BY i.id
                """,source.worker().categoryCode());
            source=source.withMetricRules(rules);
        }
        String frozen=json(source), configHash=CollectionScriptCatalog.hash(frozen.getBytes(StandardCharsets.UTF_8));
        String scriptHash=scripts.manifestHash(source.worker().version());
        jdbc.update("""
            INSERT INTO collection_worker_run(id,source_code,worker_id,lease_token,state,config_json,config_hash,script_hash,lease_until,started_at)
            VALUES (?,?,?,?,'CLAIMED',?,?,?,DATE_ADD(NOW(6),INTERVAL 90 SECOND),NOW(6))
            """,id,code,workerId,token,frozen,configHash,scriptHash);
        jdbc.update("UPDATE collection_source SET last_scan_at=NOW(),next_scan_at=DATE_ADD(NOW(),INTERVAL 90 SECOND),last_error=NULL WHERE code=?",code);
        return Map.of("runId",id,"leaseToken",token,"leaseSeconds",90,"heartbeatSeconds",20,
              "config",source,"configHash",configHash,"manifestHash",scriptHash,
              "storageRootKey",storageRoots!=null && storageRoots.isExternal(code)?"external":"default");
    }
    private Map<String,Object> run(String id,String token,boolean active,boolean lock) {
        var entries=jdbc.queryForList("SELECT source_code FROM collection_worker_run WHERE id=?",id);
        if(entries.isEmpty()) throw new IllegalArgumentException("运行记录不存在");
        String code=entries.get(0).get("source_code").toString();
        if(lock) {
            if(jdbc.queryForList("SELECT code FROM collection_source WHERE code=? FOR UPDATE",code).isEmpty())
                throw new IllegalStateException("采集任务已删除");
        }
        var rows=jdbc.queryForList("SELECT *,CASE WHEN lease_until>NOW(6) THEN 1 ELSE 0 END AS validLease FROM collection_worker_run WHERE id=?"+(lock?" FOR UPDATE":""),id);
        var row=rows.get(0);
        if(token==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),row.get("lease_token").toString().getBytes(StandardCharsets.UTF_8)))
            throw new IllegalStateException("运行租约令牌无效");
        if(active && (!"CLAIMED".equals(row.get("state")) || ((Number)row.get("validLease")).intValue()!=1))
            throw new IllegalStateException("运行租约已结束或过期，请重新领取");
        return row;
    }
    @Transactional
    public Map<String,Object> heartbeat(String id,String token) {
        var run=run(id,token,true,true);
        if(presence!=null) presence.record(run.get("worker_id").toString());
        jdbc.update("UPDATE collection_worker_run SET lease_until=DATE_ADD(NOW(6),INTERVAL 90 SECOND) WHERE id=?",id);
        jdbc.update("UPDATE collection_source SET next_scan_at=DATE_ADD(NOW(),INTERVAL 90 SECOND) WHERE code=?",run.get("source_code"));
        return Map.of("leaseSeconds",90);
    }
    private static String text(JsonNode node,String field,int max) {
        if(!node.path(field).isTextual() || node.path(field).asText().isBlank() || node.path(field).asText().length()>max)
            throw new IllegalArgumentException("字段缺失或无效："+field);
        return node.path(field).asText();
    }
    private static String digest(JsonNode node,String field) {
        String value=text(node,field,64);
        if(!value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("摘要无效："+field);
        return value;
    }
    private static String deliveryId(String file) {
        if(file.isBlank() || file.length()>200 || file.contains("/") || file.contains("\\")
                || file.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("源文件名称无效");
        return CollectionScriptCatalog.hash(file.getBytes(StandardCharsets.UTF_8));
    }
    @Transactional
    public Map<String,Object> submit(String id,String token,JsonNode request) {
        var run=run(id,token,true,true);
        var source=config(run.get("config_json"));
        if(request.path("protocolVersion").asInt()!=1 || !request.path("records").isArray()
                || !request.path("rowCount").isIntegralNumber() || request.path("rowCount").asInt()<1
                || request.path("rowCount").asInt()>10000 || request.path("rowCount").asInt()!=request.path("records").size())
            throw new IllegalArgumentException("标准批次协议、记录数无效或超过10000条");
        String file=text(request,"sourceFile",200), hash=digest(request,"fileHash");
        if(file.contains("/") || file.contains("\\") || file.equals(".") || file.equals("..")
                || file.codePoints().anyMatch(Character::isISOControl) || !file.toLowerCase(Locale.ROOT).endsWith("."+source.format()))
            throw new IllegalArgumentException("源文件名称无效");
        if(!source.worker().version().equals(text(request,"scriptVersion",64))
                || !run.get("script_hash").equals(digest(request,"manifestHash"))
                || !run.get("config_hash").equals(digest(request,"configHash")))
            throw new IllegalArgumentException("提交的脚本或配置版本不属于当前运行快照");
        String outputType=request.path("outputType").asText("");
        validateOutputProtocol(source,outputType);
        if(source.usesRawRows()) return submitRawRows(source,request,file,hash);
        boolean ingestionTime=source.usesIngestionTime();
        LocalDateTime collectedAt=ingestionTime ? LocalDateTime.now(CollectionSchedule.ZONE).truncatedTo(ChronoUnit.MICROS) : null;
        LocalDate day=ingestionTime ? collectedAt.toLocalDate() : LocalDate.parse(text(request,"businessDate",10));
        String delivery=ingestionTime ? deliveryId(file) : "";
        var prior=receipt(source.code(),hash,ingestionTime?null:day,delivery);
        if(!prior.isEmpty()) return prior;
        List<Map<String,Object>> rows=new ArrayList<>(); LocalDate latest=null;
        for(var node:request.path("records")) {
            if(!node.isObject() || node.has("_collection") || !node.has("metric_value")
                    || (!node.path("metric_value").isTextual() && !node.path("metric_value").isNull()))
                throw new IllegalArgumentException("记录或数值格式无效，平台来源字段不可由插件填写");
            var row=mapper.convertValue(node,new TypeReference<Map<String,Object>>(){});
            if(ingestionTime) {
                row.put("data_time",collectedAt.truncatedTo(ChronoUnit.SECONDS).toString());
            } else {
                var time=LocalDateTime.parse(text(node,"data_time",40).replace(' ','T'));
                if(latest==null || time.toLocalDate().isAfter(latest)) latest=time.toLocalDate();
            }
            row.put("_collection",Map.of("category_code",source.worker().categoryCode(),"run_id",id,
                "script_version",source.worker().version(),"manifest_sha256",run.get("script_hash"),
                "config_sha256",run.get("config_hash"),"input_sha256",hash,"protocol_version",1));
            rows.add(row);
        }
        if(!ingestionTime && !day.equals(latest)) throw new IllegalArgumentException("批次日期必须为记录数据时间的最新日期");
        metrics.validate(source.worker().categoryCode(),rows);
        String batch=UUID.randomUUID().toString();
        String archive=day+"_"+(ingestionTime?batch:hash)+"."+source.format();
        jdbc.update("""
            INSERT INTO collection_file(id,source_code,file_hash,business_date,source_file,archive_file,row_count,collected_at,delivery_id)
            VALUES (?,?,?,?,?,?,?,COALESCE(?,NOW(6)),?)
            """,batch,source.code(),hash,day,file,archive,rows.size(),collectedAt,delivery);
        for(int index=0;index<rows.size();index++)
            jdbc.update("INSERT INTO collection_row(batch_id,data_row_number,data_json) VALUES (?,?,?)",batch,index+1,json(rows.get(index)));
        if("satisfaction".equals(source.worker().categoryCode())) satisfactionScripts.storeAndPublish(batch,hash,file,rows);
        else metrics.publish(source.worker().categoryCode());
        if("metric_g1".equals(source.worker().categoryCode())) complianceTables.storeMonthly(file,rows);
        attempt(source.code(),file,"IMPORTED",null);
        return receipt(source.code(),hash,ingestionTime?null:day,delivery);
    }
    static void validateOutputProtocol(CollectionSource source, String outputType) {
        String expected=source.usesRawRows()?CollectionSource.PROTOCOL_RAW_ROWS:CollectionSource.PROTOCOL_METRIC_VALUES;
        // Older metric-value plugins may omit the protocol field.
        String actual=outputType.isEmpty()?CollectionSource.PROTOCOL_METRIC_VALUES:outputType;
        if(!expected.equals(actual)) throw new IllegalArgumentException("脚本输出协议与采集任务不匹配：期望"+expected+"，实际"+actual);
    }

    /** 原始行归档批次（raw-rows）：只写归档记录与合规工单明细专表，不参与指标校验/发布。 */
    @Transactional
    public Map<String,Object> submitRawRows(CollectionSource source,JsonNode request,String file,String hash) {
        LocalDate day=LocalDate.parse(text(request,"businessDate",10));
        var prior=receipt(source.code(),hash,day,"");
        if(!prior.isEmpty()) return prior;
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var node:request.path("records")) {
            if(!node.isObject() || node.has("_collection"))
                throw new IllegalArgumentException("原始记录格式无效，平台来源字段不可由插件填写");
            rows.add(mapper.convertValue(node,new TypeReference<Map<String,Object>>(){}));
        }
        String batch=UUID.randomUUID().toString();
        String archive=day+"_"+hash+"."+source.format();
        jdbc.update("""
            INSERT INTO collection_file(id,source_code,file_hash,business_date,source_file,archive_file,row_count,collected_at,delivery_id)
            VALUES (?,?,?,?,?,?,?,NOW(6),'')
            """,batch,source.code(),hash,day,file,archive,rows.size());
        if("metric_g1".equals(source.worker().categoryCode())) complianceTables.storeDetail(file,rows);
        attempt(source.code(),file,"IMPORTED",null);
        return receipt(source.code(),hash,day,"");
    }
    private Map<String,Object> receipt(String source,String hash,LocalDate day,String delivery) {
        if(!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("文件摘要无效");
        String sql="SELECT id AS batchId,archive_file AS archiveFile,row_count AS rowCount,archived FROM collection_file WHERE source_code=? AND file_hash=? AND delivery_id=?";
        var rows=day==null ? jdbc.queryForList(sql,source,hash,delivery)
            : jdbc.queryForList(sql+" AND business_date=?",source,hash,delivery,day);
        if(rows.isEmpty()) return Map.of();
        var result=new LinkedHashMap<String,Object>(rows.get(0));result.put("state","COMMITTED");return result;
    }
    public Map<String,Object> status(String id,String token,String hash,String businessDate,String sourceFile) {
        var run=run(id,token,false,false);
        return deliveryReceipt(run.get("source_code").toString(),hash,businessDate,sourceFile);
    }
    private Map<String,Object> deliveryReceipt(String source,String hash,String businessDate,String sourceFile) {
        if(businessDate!=null) return receipt(source,hash,LocalDate.parse(businessDate),"");
        if(sourceFile==null) throw new IllegalArgumentException("查询批次需要数据日期或源文件名");
        return receipt(source,hash,null,deliveryId(sourceFile));
    }
    @Transactional
    public Map<String,Object> archived(String id,String token,JsonNode request) {
        var run=run(id,token,true,true);
        String hash=digest(request,"fileHash"), archiveHash=digest(request,"archiveHash");
        if(!hash.equals(archiveHash)) throw new IllegalArgumentException("归档文件摘要与输入不一致");
        var receipt=deliveryReceipt(run.get("source_code").toString(),hash,
            request.hasNonNull("businessDate")?text(request,"businessDate",10):null,
            request.hasNonNull("sourceFile")?text(request,"sourceFile",200):null);
        if(receipt.isEmpty()) throw new IllegalStateException("没有已提交的批次，不允许确认归档");
        jdbc.update("UPDATE collection_file SET archived=1 WHERE id=?",receipt.get("batchId"));
        return Map.of("archived",true);
    }
    @Transactional
    public Map<String,Object> finish(String id,String token,String error) {
        var run=run(id,token,true,true); var source=config(run.get("config_json"));
        if(error!=null && error.length()>4000) error=error.substring(0,4000);
        jdbc.update("UPDATE collection_worker_run SET state=?,finished_at=NOW(6),last_error=? WHERE id=?",error==null?"SUCCEEDED":"FAILED",error,id);
        LocalDate latest=null;
        if(CollectionSchedule.daily(source) && source.schedule().retryUntil()!=null)
            latest=jdbc.queryForObject("SELECT MAX(business_date) FROM collection_file WHERE source_code=?",LocalDate.class,source.code());
        jdbc.update("UPDATE collection_source SET last_error=?,next_scan_at=? WHERE code=?",error,
            CollectionSchedule.afterRun(source,LocalDateTime.now(CollectionSchedule.ZONE),latest,error!=null),source.code());
        if(error!=null) attempt(source.code(),"[worker]","FAILED",error);
        return Map.of("finished",true);
    }
    private void attempt(String source,String file,String status,String error) {
        jdbc.update("INSERT INTO collection_attempt(source_code,source_file,status,message,attempted_at) VALUES (?,?,?,?,NOW())",source,file,status,error);
    }
    public List<Map<String,Object>> runs(String code) {
        return jdbc.queryForList("""
            SELECT id,worker_id AS workerId,
              CASE WHEN state='CLAIMED' AND lease_until<=NOW(6) THEN 'EXPIRED' ELSE state END AS state,
              lease_until AS leaseUntil,started_at AS startedAt,
              finished_at AS finishedAt,last_error AS lastError,
              JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.version')) AS scriptVersion
            FROM collection_worker_run WHERE source_code=? ORDER BY started_at DESC LIMIT 100
            """,code);
    }
    @Transactional
    public void requestScan(String code) {
        var rows=jdbc.queryForList("SELECT config_json,enabled FROM collection_source WHERE code=? FOR UPDATE",code);
        if(rows.isEmpty()) throw new IllegalArgumentException("采集任务不存在");
        var source=config(rows.get(0).get("config_json"));
        if(!source.enabled()) throw new IllegalArgumentException("采集任务已停用");
        requireIdle(code);
        jdbc.update("UPDATE collection_source SET next_scan_at=NOW() WHERE code=?",code);
    }
}
