package com.metric.collect.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.*;

/** Read models and bounded history pages for the management UI; never consumes files. */
@Service
public class CollectionTaskManagementService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.fasterxml.jackson.databind.ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    private CollectionWorkerPresence presence;
    private final JdbcTemplate jdbc;
    private final CollectionPipeline pipeline;
    public CollectionTaskManagementService(JdbcTemplate jdbc,CollectionPipeline pipeline) { this.jdbc=jdbc;this.pipeline=pipeline; }
    private static void normalizeTimes(Map<String,Object> row) {
        row.replaceAll((key,value) -> value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime().toString()
            : value instanceof java.sql.Date date ? date.toLocalDate().toString() : value);
    }
    public Map<String,Object> overview() {
        var now=jdbc.queryForObject("SELECT NOW()",LocalDateTime.class);
        var tasks=pipeline.sources();
        var stats=new HashMap<String,Map<String,Object>>();
        for(var row:jdbc.queryForList("""
            SELECT source_code,COUNT(*) AS batchCount,MAX(collected_at) AS lastCollectedAt,MAX(business_date) AS latestBusinessDate,
              SUM(CASE WHEN archived=0 THEN 1 ELSE 0 END) AS pendingArchives
            FROM collection_file GROUP BY source_code
            """)) stats.put(row.get("source_code").toString(),row);
        var runs=new HashMap<String,Map<String,Object>>();
        for(var row:jdbc.queryForList("""
            SELECT source_code,state,worker_id AS workerId,lease_until AS leaseUntil,started_at AS startedAt,
              finished_at AS finishedAt,last_error AS lastError,
              CASE WHEN state='CLAIMED' AND lease_until>NOW(6) THEN 1 ELSE 0 END AS running,
              CASE WHEN state='CLAIMED' AND lease_until<=NOW(6) THEN 1 ELSE 0 END AS expired
            FROM (SELECT r.*,ROW_NUMBER() OVER(PARTITION BY source_code ORDER BY started_at DESC,id DESC) AS run_rank
              FROM collection_worker_run r) latest WHERE run_rank=1
            """)) runs.put(row.get("source_code").toString(),row);
        var executing=new HashSet<String>();
        for(var row:jdbc.queryForList("SELECT code FROM collection_source WHERE IS_USED_LOCK(CONCAT('collect:',code)) IS NOT NULL"))
            executing.add(row.get("code").toString());
        for(var task:tasks) {
            String code=task.get("code").toString();var stat=stats.getOrDefault(code,Map.of());var run=runs.getOrDefault(code,Map.of());
            task.put("batchCount",stat.getOrDefault("batchCount",0));
            task.put("lastCollectedAt",stat.get("lastCollectedAt"));
            task.put("pendingArchives",stat.getOrDefault("pendingArchives",0));
            Object day=stat.get("latestBusinessDate");
            task.put("latestBusinessDate",day==null?null:day.toString());
            task.put("workerRun",run);
            task.put("running",executing.contains(code) || ((Number)run.getOrDefault("running",0)).intValue()==1);
            normalizeTimes(task);
            if(!run.isEmpty()) normalizeTimes(run);
            try {
                var config=mapper.convertValue(task.get("config"),CollectionSource.class);
                CollectionSchedule.validate(config);
                task.put("freshness",CollectionFreshness.describe(config,day==null?null:LocalDate.parse(day.toString()),now));
                if("SUCCEEDED".equals(run.get("state"))) {
                    var imported=task.get("lastCollectedAt");
                    var started=run.get("startedAt");var finished=run.get("finishedAt");
                    boolean newData=imported!=null && started!=null && finished!=null
                        && !LocalDateTime.parse(imported.toString()).isBefore(LocalDateTime.parse(started.toString()))
                        && !LocalDateTime.parse(imported.toString()).isAfter(LocalDateTime.parse(finished.toString()));
                    task.put("lastScanResult",newData?"上次扫描已入库新文件":"上次扫描未入库新文件");
                }
            } catch(Exception e) { task.put("freshness",Map.of("status","invalid","label","应到配置有误","note","请检查任务的调度及应到规则配置")); }
        }
        return Map.of("tasks",tasks,"serverTime",now,"workerPresence",presence==null?Map.of("status","unknown"):presence.snapshot());
    }
    public Map<String,Object> history(String code,String type,int page,int size,String status,String keyword) {
        page=Math.max(1,Math.min(page,1000000));size=Math.max(1,Math.min(size,100));
        String table,columns,order,statusColumn;
        switch(type) {
            case "batches" -> {
                table="collection_file";columns="id AS batchId,source_file AS sourceFile,business_date AS businessDate,row_count AS rowCount,collected_at AS collectedAt,archived";
                order="collected_at DESC,id DESC";statusColumn="archived";
            }
            case "attempts" -> {
                table="collection_attempt";columns="id,source_file AS sourceFile,status,message,attempted_at AS attemptedAt";
                order="id DESC";statusColumn="status";
            }
            case "runs" -> {
                table="collection_worker_run";columns="id,worker_id AS workerId,CASE WHEN state='CLAIMED' AND lease_until<=NOW(6) THEN 'EXPIRED' ELSE state END AS state,lease_until AS leaseUntil,started_at AS startedAt,finished_at AS finishedAt,last_error AS lastError,JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.version')) AS scriptVersion";
                order="started_at DESC,id DESC";statusColumn="state";
            }
            default -> throw new IllegalArgumentException("记录类型无效");
        }
        var parameters=new ArrayList<Object>();parameters.add(code);
        String where=" WHERE source_code=?";
        if(status!=null && !status.isBlank()) {
            if(type.equals("batches")) {
                if(!Set.of("0","1").contains(status)) throw new IllegalArgumentException("归档状态无效");
                where+=" AND archived=?";parameters.add(Integer.parseInt(status));
            } else if(type.equals("runs") && status.equals("EXPIRED")) where+=" AND (state='EXPIRED' OR (state='CLAIMED' AND lease_until<=NOW(6)))";
            else if(type.equals("runs") && status.equals("CLAIMED")) where+=" AND state='CLAIMED' AND lease_until>NOW(6)";
            else { where+=" AND "+statusColumn+"=?";parameters.add(status); }
        }
        if(keyword!=null && !keyword.isBlank()) {
            if(keyword.length()>200) throw new IllegalArgumentException("搜索内容超过200字");
            where+=" AND LOCATE(?,"+(type.equals("runs")?"worker_id":"source_file")+")>0";parameters.add(keyword.trim());
        }
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+where,Long.class,parameters.toArray());
        parameters.add(size);parameters.add((page-1)*size);
        var records=jdbc.queryForList("SELECT "+columns+" FROM "+table+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",parameters.toArray());
        records.forEach(CollectionTaskManagementService::normalizeTimes);
        return Map.of("records",records,"total",total==null?0:total,"page",page,"size",size);
    }
    public Map<String,Object> rows(String batch,int page,int size) {
        size=Math.max(1,Math.min(size,100));page=Math.max(1,Math.min(page,1000000));
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM collection_row WHERE batch_id=?",Long.class,batch);
        var records=jdbc.queryForList("SELECT data_row_number AS rowNumber,data_json AS dataJson FROM collection_row WHERE batch_id=? ORDER BY data_row_number LIMIT ? OFFSET ?",batch,size,(page-1)*size);
        return Map.of("records",records,"total",total==null?0:total,"page",page,"size",size);
    }
}
