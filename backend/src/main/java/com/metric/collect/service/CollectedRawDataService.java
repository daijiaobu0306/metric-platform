package com.metric.collect.service;

import com.metric.shared.util.BasePathProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据汇聚页的"原始数据"读取：直接回放最近一次采集到库的原始文件/原始行，
 * 不做任何指标计算（计算后的结果只在运营报表展示）。
 */
@Service
public class CollectedRawDataService {
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final JdbcTemplate jdbc;
    private final CollectionStorageRoots roots;

    public CollectedRawDataService(JdbcTemplate jdbc, CollectionStorageRoots roots) {
        this.jdbc = jdbc;
        this.roots = roots;
    }

    public List<Map<String,Object>> catalog(String category) {
        return jdbc.queryForList("""
            SELECT m.id,m.metric_code AS code,m.metric_name AS name,m.sub_category_name AS subcategory,m.unit
            FROM metric_item m JOIN metric_category c ON c.id=m.category_id
            WHERE c.category_name=? AND m.status=1 ORDER BY m.sub_category_name,m.id
            """, category);
    }

    /** Read a metric's own latest observation, never the latest file of a mixed category. */
    public Map<String,Object> records(String category, long metricId, String date, String keyword, int page, int size) {
        return records(category, metricId, date, null, keyword, page, size);
    }

    /**
     * 回放单个指标的一次观测。
     *
     * <p>各采集大类的采集频率不同（对外部客户小时级、运行效率 5 分钟级、合规管理/资源投入按日），
     * 所以时间条件分两级：{@code date}（YYYY-MM-DD，限定某一天）与 {@code dataTime}
     * （YYYY-MM-DD HH:mm:ss，精确到某一批）。只给 date 时取当天最后一批，都不给取最新一批，
     * 保持原有行为不变。</p>
     */
    public Map<String,Object> records(String category, long metricId, String date, String dataTime,
                                      String keyword, int page, int size) {
        var metric = catalog(category).stream().filter(m -> ((Number)m.get("id")).longValue()==metricId)
            .findFirst().orElseThrow(() -> new IllegalArgumentException("指标不属于当前大类或未启用"));
        if (date!=null && !date.isBlank()) java.time.LocalDate.parse(date);
        if (dataTime!=null && !dataTime.isBlank()) parseDataTime(dataTime);
        String code=metric.get("code").toString();
        String day="";
        List<Object> args=new ArrayList<>(List.of(metricId,code));
        if(dataTime!=null && !dataTime.isBlank()) {
            day=" AND REPLACE(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.data_time')),'T',' ')=?";
            args.add(parseDataTime(dataTime).toString().replace('T',' '));
        } else if(date!=null && !date.isBlank()) {
            day=" AND DATE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.data_time')),'T',' '))=?";
            args.add(date);
        }
        // A legacy intraday empty export must not hide a populated T-1 export for the same day.
        String order = code.startsWith("INNER_CHBN_") ? """
            DATE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.data_time')),'T',' ')) DESC,
            CASE WHEN COALESCE(CAST(r.data_json->>'$.detail_row_count' AS UNSIGNED), JSON_LENGTH(r.data_json,'$.details'), 0)>0 THEN 0 ELSE 1 END,
            """ : "";
        var found=jdbc.queryForList("""
            SELECT r.data_json,f.source_code,f.archive_file,f.business_date,f.collected_at
            FROM collection_row r JOIN collection_file f ON f.id=r.batch_id
            JOIN metric_item m ON m.id=? JOIN metric_category c ON c.id=m.category_id
            WHERE r.metric_code_canonical_digest=UNHEX(SHA2(CONVERT(? USING utf8mb4),256))
              AND (JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$._collection.category_code'))=c.category_code
                OR (JSON_EXTRACT(r.data_json,'$._collection') IS NULL AND EXISTS (
                    SELECT 1 FROM collection_source s WHERE s.code=f.source_code
                    AND JSON_UNQUOTE(JSON_EXTRACT(s.config_json,'$.worker.categoryCode'))=c.category_code)))
            """+day+" ORDER BY "+order+" JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.data_time')) DESC,f.collected_at DESC,f.id DESC LIMIT 1",
            args.toArray());
        Map<String,Object> result=new LinkedHashMap<>();result.put("metric",metric);
        result.put("columns",List.of());result.put("rows",List.of());result.put("total",0);
        result.put("note","尚无该指标的采集数据");
        // 时间点候选：前端据此决定是否显示"时间点"下拉，只有多批时才出现。
        // 传了 dataTime 时用它的日期部分取该日候选，否则用 date；都没有则不限日期。
        String dayForTimes = date;
        if ((dayForTimes == null || dayForTimes.isBlank()) && dataTime != null && !dataTime.isBlank()) {
            dayForTimes = dataTime.trim().replace('T', ' ').substring(0, 10);
        }
        result.put("availableTimes",timePoints(code, Objects.toString(metric.get("name"),""), dayForTimes, 200));
        result.put("availableDates",availableDates(code, 60));
        if(found.isEmpty()) return result;
        var observation=found.get(0);
        try {
            var record=mapper.readTree(observation.get("data_json").toString());
            result.put("dataTime",record.path("data_time").asText());
            result.put("collectedAt",observation.get("collected_at"));
            List<Map<String,Object>> data=new ArrayList<>();
            String dataset=record.path("dataset").asText("");
            String note="";
            if(!dataset.isBlank()) {
                Path archive=archivedFile(observation.get("source_code").toString(),Objects.toString(observation.get("archive_file"),""));
                if(archive!=null) {
                    for(var row:readCsv(archive).rows())
                        if(dataset.equals(row.get("dataset")) && sameObservationTime(row.get("data_time"), record.path("data_time").asText())
                                && !"1".equals(row.get("_empty")) && matchesSelection(row,record.path("calculation"))) data.add(row);
                } else {
                    var details=record.path("details");
                    if(details.isArray()) for(var item:details)
                        data.add(mapper.convertValue(item,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
                    note="原始归档不可用，展示保留的明细样本（可能不完整）";
                }
            } else {
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("指标名称",metric.get("name"));
                row.put("指标值",record.path("metric_value").isNull()?null:record.path("metric_value").asText());
                row.put("单位",metric.get("unit"));row.put("数据时间",record.path("data_time").asText());
                data.add(row);
            }
            List<Map<String,Object>> display=new ArrayList<>();
            for(var raw:data) {
                Map<String,Object> row=new LinkedHashMap<>(raw);
                for(String key:List.of("dataset","data_time","_empty","_collection","_source","provenance","mock_run_id","mock_frequency")) row.remove(key);
                if(keyword==null || keyword.isBlank() || row.values().stream().anyMatch(v -> Objects.toString(v,"").toLowerCase(Locale.ROOT).contains(keyword.trim().toLowerCase(Locale.ROOT)))) display.add(row);
            }
            var columns=new LinkedHashSet<String>();display.forEach(r -> columns.addAll(r.keySet()));
            int limit=Math.max(1,Math.min(size,200));long offset=(long)(Math.max(1,page)-1)*limit;
            int from=(int)Math.min(offset,display.size());
            result.put("columns",new ArrayList<>(columns));result.put("rows",display.subList(from,Math.min(from+limit,display.size())));
            result.put("total",display.size());result.put("note",note);
            return result;
        } catch(IOException e) { throw new IllegalStateException("原始采集记录读取失败",e); }
    }

    /**
     * 供 time-points 接口使用：校验指标归属后返回当日时间点与可选日期。
     */
    public Map<String,Object> timePointsFor(String category, long metricId, String date) {
        var metric = catalog(category).stream().filter(m -> ((Number)m.get("id")).longValue()==metricId)
            .findFirst().orElseThrow(() -> new IllegalArgumentException("指标不属于当前大类或未启用"));
        if (date!=null && !date.isBlank()) java.time.LocalDate.parse(date);
        String code = metric.get("code") == null ? "" : metric.get("code").toString();
        List<String> times = timePoints(code, Objects.toString(metric.get("name"),""), date, 500);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("metricId", metricId);
        result.put("metricCode", code);
        result.put("date", date);
        result.put("times", times);
        result.put("count", times.size());
        result.put("dates", availableDates(code, 90));
        return result;
    }

    /**
     * 某个指标在某一天（date 为空则不限日期）有哪些观测时间点，倒序。
     *
     * <p>必须按指标编码过滤：{@code data_time} 在 JSON 里，没有生成列索引，
     * 对整大类做 DISTINCT 会全表扫 20 万行；按 metric_code_canonical_digest 走等值索引后是毫秒级。</p>
     */
    public List<String> timePoints(String metricCode, String metricName, String date, int limit) {
        String code = metricCode == null ? "" : metricCode;
        // 时间点下拉只在"某一天"范围内才有意义：跨日期取 DISTINCT 会把不同天的同一时刻混在一起，
        // 下拉里出现多个同名 08:00:00 却无法区分。未指定日期时用该指标最新数据的日期，
        // 这样进入页面（默认最新一批）也能直接切到当天其他批次。
        String day = date;
        if (day == null || day.isBlank()) {
            List<String> latest = jdbc.queryForList("""
                SELECT DATE(%s) AS d FROM collection_row r
                WHERE r.metric_code_canonical_digest=UNHEX(SHA2(CONVERT(? USING utf8mb4),256))
                ORDER BY %s DESC LIMIT 1
                """.formatted(DATA_TIME, DATA_TIME), String.class, code);
            if (latest.isEmpty() || latest.get(0) == null) return List.of();
            day = latest.get(0);
        }
        String sql = """
            SELECT DISTINCT %s AS t FROM collection_row r
            WHERE r.metric_code_canonical_digest=UNHEX(SHA2(CONVERT(? USING utf8mb4),256))
              AND DATE(%s)=?
            ORDER BY t DESC LIMIT %d
            """.formatted(DATA_TIME, DATA_TIME, Math.max(1, Math.min(limit, 500)));
        return jdbc.queryForList(sql, String.class, code, day);
    }

    /** 某指标有采集数据的日期（升序取最后 N 天再倒序返回），供日期选择器限定可选范围。 */
    public List<String> availableDates(String metricCode, int limit) {
        String sql = """
            SELECT DISTINCT DATE(%s) AS d FROM collection_row r
            WHERE r.metric_code_canonical_digest=UNHEX(SHA2(CONVERT(? USING utf8mb4),256))
            ORDER BY d DESC LIMIT %d
            """.formatted(DATA_TIME, Math.max(1, Math.min(limit, 400)));
        return jdbc.queryForList(sql, String.class, metricCode == null ? "" : metricCode);
    }

    /** data_time 在采集 JSON 里的统一读取表达式；只用于 SQL 拼接，不拼请求值。 */
    private static final String DATA_TIME =
        "REPLACE(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.data_time')),'T',' ')";

    static java.time.LocalDateTime parseDataTime(String value) {
        String text = value.trim().replace('T', ' ');
        try {
            return java.time.LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("时间点格式无效，请使用 YYYY-MM-DD HH:mm:ss");
        }
    }

    static boolean sameObservationTime(Object sourceTime, String observationTime) {
        if (sourceTime == null || observationTime == null) return false;
        try {
            return java.time.LocalDateTime.parse(sourceTime.toString().replace(' ', 'T'))
                .equals(java.time.LocalDateTime.parse(observationTime.replace(' ', 'T')));
        } catch (java.time.format.DateTimeParseException e) { return false; }
    }

    // Replay the parser's declared row selection only; do not recalculate metric values.
    static boolean matchesSelection(Map<String,Object> row, com.fasterxml.jackson.databind.JsonNode rule) {
        if(rule.has("businessContains")) {
            boolean contains=Objects.toString(row.get("业务名称"),"").toLowerCase(Locale.ROOT).contains(rule.path("businessContains").asText());
            if(contains!=rule.path("businessMatches").asBoolean()) return false;
        }
        if(rule.has("stepType")) {
            String step=Objects.toString(row.get("步骤类型"),"");
            if(step.isBlank()) step=Objects.toString(row.getOrDefault("步骤名称",row.get("业务步骤")),"").contains("页面")?"页面加载":"步骤时长";
            if(!step.equals(rule.path("stepType").asText())) return false;
        }
        return true;
    }

    public Map<String, Object> preview(String categoryName, String sourceCode, String batchId, int page, int size) {
        String source = sourceCode == null || sourceCode.isBlank() ? resolveSource(categoryName) : sourceCode;
        if (source == null) return Map.of("source", "", "batches", List.of(), "columns", List.of(), "rows", List.of(),
            "total", 0, "note", "该大类还没有关联的采集任务");
        List<Map<String, Object>> batches = jdbc.queryForList("""
            SELECT id,source_file AS sourceFile,archive_file AS archiveFile,business_date AS businessDate,
              collected_at AS collectedAt,row_count AS rowCount,archived
            FROM collection_file WHERE source_code=? ORDER BY business_date DESC,collected_at DESC,id DESC LIMIT 60
            """, source);
        if (batches.isEmpty()) return Map.of("source", source, "batches", List.of(), "columns", List.of(),
            "rows", List.of(), "total", 0, "note", "该大类还没有采集到批次文件");
        String selected = batchId == null || batchId.isBlank() ? String.valueOf(batches.get(0).get("id")) : batchId;
        var batch = batches.stream().filter(b -> selected.equals(String.valueOf(b.get("id")))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        String archiveFile = Objects.toString(batch.get("archiveFile"), "");

        // Full archived input is authoritative; embedded details are bounded samples.
        Path file = archivedFile(source, archiveFile);
        if (file != null) {
            CsvTable table = readCsv(file);
            return page(source, batches, selected, "file", table.header(), table.rows(), page, size,
                file.getFileName().toString());
        }
        List<Map<String, Object>> detailed = sourceDetails(selected);
        if (!detailed.isEmpty()) {
            var columns = new LinkedHashSet<String>();
            detailed.forEach(row -> columns.addAll(row.keySet()));
            return page(source, batches, selected, "source_rows", new ArrayList<>(columns), detailed, page, size,
                archiveFile);
        }
        // 3) 兜底：回放入库的采集记录本身（指标值行，不含计算）
        List<Map<String, Object>> records = jdbc.queryForList("""
            SELECT data_json FROM collection_row WHERE batch_id=? ORDER BY data_row_number
            """, selected).stream().map(r -> {
            try {
                    return mapper.readValue(String.valueOf(r.get("data_json")),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
            } catch (Exception e) {
                return Map.<String, Object>of();
            }
        }).collect(Collectors.toList());
        var columns = new LinkedHashSet<String>();
        records.forEach(row -> columns.addAll(row.keySet()));
        return page(source, batches, selected, "records", new ArrayList<>(columns), records, page, size, archiveFile);
    }

    private Map<String, Object> page(String source, List<Map<String, Object>> batches, String selected, String mode,
                                     List<String> columns, List<Map<String, Object>> rows, int page, int size,
                                     String file) {
        int safeSize = Math.max(1, Math.min(size <= 0 ? 50 : size, 500));
        int safePage = Math.max(1, page);
        int from = (int) Math.min((long) (safePage - 1) * safeSize, rows.size());
        int to = from + Math.min(safeSize, rows.size() - from);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("batchId", selected);
        result.put("batches", batches);
        result.put("mode", mode);
        result.put("file", file);
        result.put("columns", columns);
        result.put("rows", rows.subList(from, to));
        result.put("total", rows.size());
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("note", switch (mode) {
            case "source_rows" -> "归档文件不可用，当前显示入库时保留的明细样本（可能不完整）";
            case "file" -> "原始数据取自本批次归档的采集文件原文";
            default -> "本批次只归档了采集记录，未保留来源明细行";
        });
        return result;
    }

    private String resolveSource(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) return null;
        var codes = jdbc.queryForList("SELECT category_code FROM metric_category WHERE category_name=?",
            String.class, categoryName);
        if (codes.isEmpty()) return null;
        String categoryCode = codes.get(0);
        var sources = jdbc.queryForList("""
            SELECT code FROM collection_source
            WHERE code=? OR JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.categoryCode'))=?
            ORDER BY enabled DESC,code LIMIT 1
            """, String.class, categoryCode, categoryCode);
        return sources.isEmpty() ? null : sources.get(0);
    }

    /** 优先使用随指标记录入库的 source_details（每行是一条来源明细）。 */
    private List<Map<String, Object>> sourceDetails(String batchId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> record : jdbc.queryForList(
            "SELECT data_json FROM collection_row WHERE batch_id=? ORDER BY data_row_number", batchId)) {
            Object details = record.get("data_json");
            try {
                var node = mapper.readTree(String.valueOf(details));
                String dataset = node.path("dataset").asText("");
                var array = node.path("source_details");
                if (!array.isArray()) array = node.path("details");
                if (!array.isArray()) continue;
                for (var item : array) {
                    if (!item.isObject()) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    if (!dataset.isBlank()) row.put("数据集", dataset);
                    item.fields().forEachRemaining(f -> row.put(f.getKey(), f.getValue().isValueNode()
                        ? f.getValue().asText() : f.getValue().toString()));
                    rows.add(row);
                }
            } catch (Exception ignored) {
                // 记录不是 JSON 或没有明细时跳过
            }
        }
        return rows;
    }

    private Path archivedFile(String source, String archiveFile) {
        if (archiveFile == null || archiveFile.isBlank()) return null;
        var configs = jdbc.queryForList("SELECT config_json FROM collection_source WHERE code=?", String.class, source);
        if (configs.isEmpty()) return null;
        try {
            var config = mapper.readTree(configs.get(0));
            Path root = Path.of(roots.forSource(source)).toRealPath();
            Path dir = root.resolve(config.path("archivePath").asText()).normalize();
            Path file = dir.resolve(archiveFile).normalize();
            if (!dir.startsWith(root) || !file.startsWith(dir) || !Files.isRegularFile(file)
                    || !file.toRealPath().startsWith(root)) return null;
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            return name.endsWith(".csv") || name.endsWith(".tsv") ? file : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 读取 CSV/TSV：首行为列名，其余为数据行；自动识别分隔符与 BOM。 */
    private CsvTable readCsv(Path file) throws IllegalStateException {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            List<String> lines = csvRecords(content);
            if (lines.isEmpty()) return new CsvTable(List.of(), List.of());
            char delimiter = lines.get(0).contains("\t") ? '\t' : ',';
            List<String> header = splitCsv(lines.get(0), delimiter);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                List<String> cells = splitCsv(lines.get(i), delimiter);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) row.put(header.get(c), c < cells.size() ? cells.get(c) : "");
                if (header.contains("dataset") && header.contains("payload")) {
                    String payload = Objects.toString(row.remove("payload"), "");
                    if (!payload.isBlank()) {
                        var node = mapper.readTree(payload);
                        if (node.isObject()) node.fields().forEachRemaining(f -> row.put(f.getKey(),
                            f.getValue().isNull() ? null : f.getValue().isValueNode() ? f.getValue().asText() : f.getValue().toString()));
                    }
                }
                rows.add(row);
            }
            var columns = new LinkedHashSet<String>();
            rows.forEach(row -> columns.addAll(row.keySet()));
            return new CsvTable(rows.isEmpty() ? header : new ArrayList<>(columns), rows);
        } catch (IOException e) {
            throw new IllegalStateException("读取原始文件失败：" + file.getFileName(), e);
        }
    }

    private record CsvTable(List<String> header, List<Map<String, Object>> rows) {
    }

    static List<String> csvRecords(String content) {
        List<String> records=new ArrayList<>();StringBuilder row=new StringBuilder();boolean quoted=false;
        for(int i=0;i<content.length();i++) {
            char c=content.charAt(i);
            if(c=='"') {
                row.append(c);
                if(quoted && i+1<content.length() && content.charAt(i+1)=='"') row.append(content.charAt(++i));
                else quoted=!quoted;
            } else if(!quoted && (c=='\r' || c=='\n')) {
                if(!row.toString().isBlank()) records.add(row.toString());row.setLength(0);
                if(c=='\r' && i+1<content.length() && content.charAt(i+1)=='\n') i++;
            } else row.append(c);
        }
        if(quoted) throw new IllegalArgumentException("采集CSV引号未闭合");
        if(!row.toString().isBlank()) records.add(row.toString());
        return records;
    }

    private List<String> splitCsv(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == delimiter && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return cells;
    }
}
