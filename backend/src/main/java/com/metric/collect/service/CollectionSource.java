package com.metric.collect.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A source is a file protocol, not a metric category. Paths are relative to upload.base_path. */
public record CollectionSource(String code, String name, String importPath, String archivePath,
        int intervalSeconds, int settleSeconds, String parser, String format, String filePattern,
        String dateMode, String dateRegex, String dateFormat, boolean enabled,
        String sheet, String delimiter, String encoding, List<Field> fields, WorkerBinding worker, Schedule schedule, Arrival arrival, List<java.util.Map<String,Object>> metricRules,
        @JsonInclude(JsonInclude.Include.NON_NULL) String protocol) {
    /** 指标值协议：脚本产出指标编码与指标值，产出指标值与指标明细。 */
    public static final String PROTOCOL_METRIC_VALUES = "metric-values";
    /** 明细行协议：脚本只产出明细行，只回写明细，不产生指标值，不需要字段映射。 */
    public static final String PROTOCOL_RAW_ROWS = "raw-rows";

    public CollectionSource(String code, String name, String importPath, String archivePath,
            int intervalSeconds, int settleSeconds, String parser, String format, String filePattern,
            String dateMode, String dateRegex, String dateFormat, boolean enabled,
            String sheet, String delimiter, String encoding, List<Field> fields, WorkerBinding worker, Schedule schedule, Arrival arrival) {
        this(code,name,importPath,archivePath,intervalSeconds,settleSeconds,parser,format,filePattern,
            dateMode,dateRegex,dateFormat,enabled,sheet,delimiter,encoding,fields,worker,schedule,arrival,null,null);
    }
    boolean usesIngestionTime() {
        return "ingestion_time".equals(dateMode);
    }
    /** 明细行协议的任务不产生指标值，因此不做指标字段映射。 */
    boolean usesRawRows() {
        return PROTOCOL_RAW_ROWS.equals(protocol);
    }

    public CollectionSource withMetricRules(List<java.util.Map<String,Object>> rules) {
        return new CollectionSource(code,name,importPath,archivePath,intervalSeconds,settleSeconds,parser,format,
            filePattern,dateMode,dateRegex,dateFormat,enabled,sheet,delimiter,encoding,fields,worker,schedule,arrival,rules,protocol);
    }
    public CollectionSource(String code, String name, String importPath, String archivePath,
            int intervalSeconds, int settleSeconds, String parser, String format, String filePattern,
            String dateMode, String dateRegex, String dateFormat, boolean enabled,
            String sheet, String delimiter, String encoding, List<Field> fields, WorkerBinding worker, Schedule schedule) {
        this(code,name,importPath,archivePath,intervalSeconds,settleSeconds,parser,format,filePattern,
            dateMode,dateRegex,dateFormat,enabled,sheet,delimiter,encoding,fields,worker,schedule,null,null,null);
    }
    public CollectionSource(String code, String name, String importPath, String archivePath,
            int intervalSeconds, int settleSeconds, String parser, String format, String filePattern,
            String dateMode, String dateRegex, String dateFormat, boolean enabled,
            String sheet, String delimiter, String encoding, List<Field> fields, WorkerBinding worker) {
        this(code,name,importPath,archivePath,intervalSeconds,settleSeconds,parser,format,filePattern,
            dateMode,dateRegex,dateFormat,enabled,sheet,delimiter,encoding,fields,worker,null,null,null,null);
    }
    // Preserve callers and existing configurations written before Python support.
    public CollectionSource(String code, String name, String importPath, String archivePath,
            int intervalSeconds, int settleSeconds, String parser, String format, String filePattern,
            String dateMode, String dateRegex, String dateFormat, boolean enabled,
            String sheet, String delimiter, String encoding, List<Field> fields) {
        this(code,name,importPath,archivePath,intervalSeconds,settleSeconds,parser,format,filePattern,
            dateMode,dateRegex,dateFormat,enabled,sheet,delimiter,encoding,fields,null,null,null,null,null);
    }
    public record WorkerBinding(String version, String categoryCode, int timeoutSeconds) {}
    public record Schedule(String type, String time, String retryUntil, Integer retryIntervalMinutes) {
        public Schedule(String type,String time) { this(type,time,null,null); }
    }
    public record Arrival(Integer lagDays, String dueTime) {}
    public record Field(String source, String target, String type, boolean required) {}
    public CollectionSource withEnabled(boolean value) {
        return new CollectionSource(code,name,importPath,archivePath,intervalSeconds,settleSeconds,parser,format,
            filePattern,dateMode,dateRegex,dateFormat,value,sheet,delimiter,encoding,fields,worker,schedule,arrival,metricRules,protocol);
    }
}
