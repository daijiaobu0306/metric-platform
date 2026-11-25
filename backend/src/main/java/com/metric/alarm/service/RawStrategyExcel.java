package com.metric.alarm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metric.alarm.entity.AlarmStrategy;
import com.metric.alarm.entity.StrategyRule;
import com.metric.alarm.repository.AlarmStrategyRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/** 原始数据独立工作表；与六个指标/健康度sheet共用预览、整批事务和版本检查。 */
@Service
public class RawStrategyExcel {
    public static final String SHEET="原始数据策略";
    private static final String[] HEADERS={"配置标识","策略ID","策略更新时间","策略名称","业务分类","数据来源编码","检查字段","适用字段","适用匹配方式","适用内容","个性化序号(0为默认)","个性化字段","个性化匹配方式","个性化内容","生成类型","级别","比较条件","阈值(ms)","连续异常记录条数","告警开关","工单开关","策略状态"};
    public record Change(AlarmStrategy strategy,ObjectNode definition,String before) {}
    public record Plan(List<Change> changes,List<StrategyExcelService.ImportError> errors,List<StrategyExcelService.RowChange> rows,int skipped,int configured) {
        public static Plan empty() { return new Plan(List.of(),List.of(),List.of(),0,0); }
        public int created() { return (int)changes.stream().filter(c -> c.strategy().getId()==null).count(); }
    }
    private final RawStrategyService raw;
    private final AlarmStrategyRepository strategies;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    public RawStrategyExcel(RawStrategyService raw,AlarmStrategyRepository strategies,ObjectMapper mapper,JdbcTemplate jdbc) {
        this.raw=raw;this.strategies=strategies;this.mapper=mapper;this.jdbc=jdbc;
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("原始数据模板序列化失败",e); } }
    private AlarmStrategy copy(AlarmStrategy s) { return mapper.convertValue(mapper.valueToTree(s),AlarmStrategy.class); }
    private String snapshot(AlarmStrategy strategy,ObjectNode definition) {
        return normalized(mapper.valueToTree(List.of(Objects.toString(strategy.getUpdateTime(),""),strategy.getStrategyName(),strategy.getStatus(),strategy.getAlarmEnabled(),strategy.getGongdanEnabled(),
            strategy.getAlarmRules()==null ? List.of() : strategy.getAlarmRules(),strategy.getGongdanRules()==null ? List.of() : strategy.getGongdanRules(),definition))).toString();
    }
    private com.fasterxml.jackson.databind.JsonNode normalized(com.fasterxml.jackson.databind.JsonNode node) {
        if(node.isNumber()) return mapper.getNodeFactory().numberNode(new BigDecimal(node.decimalValue().stripTrailingZeros().toPlainString()));
        if(node.isArray()) { var out=mapper.createArrayNode();for(var child:node)out.add(normalized(child));return out; }
        if(node.isObject()) { var out=mapper.createObjectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);for(String key:keys)out.set(key,normalized(node.get(key)));return out; }
        return node;
    }
    public void append(Workbook workbook) {
        Sheet sheet=workbook.createSheet(SHEET);Row header=sheet.createRow(0);header.setHeightInPoints(36);
        CellStyle heading=workbook.createCellStyle();heading.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);heading.setWrapText(true);
        for(int i=0;i<HEADERS.length;i++) { Cell cell=header.createCell(i);cell.setCellValue(HEADERS[i]);cell.setCellStyle(heading);sheet.setColumnWidth(i,(i==3 || i==9 || i==13 ? 28 : 19)*256); }
        CellStyle input=workbook.createCellStyle();input.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());input.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Set<String> configured=new HashSet<>();
        for(AlarmStrategy strategy:strategies.findAll()) if("raw".equals(strategy.getStrategyType())) {
            ObjectNode definition=(ObjectNode)raw.definition(strategy.getId());configured.add(definition.path("source").asText()+":"+definition.path("valueField").asText());
            writeScope(sheet,input,strategy,definition,0,mapper.createObjectNode(),strategy.getAlarmRules(),strategy.getGongdanRules());
            int index=0;
            for(var scope:definition.path("overrides")) writeScope(sheet,input,strategy,definition,++index,(ObjectNode)scope,
                mapper.convertValue(scope.path("alarmRules"),new com.fasterxml.jackson.core.type.TypeReference<List<StrategyRule>>(){}),
                mapper.convertValue(scope.path("gongdanRules"),new com.fasterxml.jackson.core.type.TypeReference<List<StrategyRule>>(){}));
        }
        for(var source:raw.catalog()) for(String field:source.values()) if(!configured.contains(source.key()+":"+field)) {
            AlarmStrategy s=new AlarmStrategy();s.setStrategyName(source.name()+"-"+field);s.setAlarmEnabled(1);s.setGongdanEnabled(0);s.setStatus(1);
            ObjectNode d=mapper.createObjectNode();d.put("source",source.key());d.put("valueField",field);d.putObject("filter").put("mode","exact");
            writeScope(sheet,input,s,d,0,mapper.createObjectNode(),List.of(),List.of());
        }
        sheet.createFreezePane(4,1);sheet.setAutoFilter(new CellRangeAddress(0,sheet.getLastRowNum(),0,HEADERS.length-1));
        sheet.setColumnHidden(1,true);sheet.setColumnHidden(2,true);
        for(int col:new int[]{8,12}) validation(sheet,col,new String[]{"精确匹配","包含","正则表达式"});
        validation(sheet,14,new String[]{"告警","工单"});validation(sheet,15,new String[]{"一般","重要","严重"});
        validation(sheet,16,new String[]{">",">=","<","<=","="});for(int col:new int[]{19,20,21}) validation(sheet,col,new String[]{"启用","停用"});
        sheet.setZoom(85);
    }
    private void validation(Sheet sheet,int col,String[] values) {
        var h=sheet.getDataValidationHelper();var v=h.createValidation(h.createExplicitListConstraint(values),new CellRangeAddressList(1,1048575,col,col));
        v.setShowErrorBox(true);v.createErrorBox("请选择列表值",String.join("、",values));sheet.addValidationData(v);
    }
    private void writeScope(Sheet sheet,CellStyle input,AlarmStrategy s,ObjectNode d,int index,ObjectNode custom,List<StrategyRule> alarm,List<StrategyRule> ticket) {
        String group=raw.catalog().stream().filter(source -> source.key().equals(d.path("source").asText())).findFirst().orElseThrow().group();
        String key=s.getId()==null ? d.path("source").asText()+":"+d.path("valueField").asText() : "strategy:"+s.getId();
        for(boolean gongdan:List.of(false,true)) {
            List<StrategyRule> rules=gongdan ? ticket : alarm;
            if(rules==null || rules.isEmpty()) { StrategyRule blank=new StrategyRule();blank.setLevel(2);blank.setConsecutiveTimes(1);rules=List.of(blank); }
            for(StrategyRule r:rules) {
                Object[] values={key,s.getId(),s.getUpdateTime(),s.getStrategyName(),group,d.path("source").asText(),d.path("valueField").asText(),d.path("filter").path("field").asText(),mode(d.path("filter").path("mode").asText()),d.path("filter").path("value").asText(),index,custom.path("filter").path("field").asText(),index==0 ? "" : mode(custom.path("filter").path("mode").asText()),custom.path("filter").path("value").asText(),gongdan ? "工单" : "告警",level(r.getLevel()),r.getCondition(),r.getThreshold(),r.getConsecutiveTimes(),flag(s.getAlarmEnabled()),flag(s.getGongdanEnabled()),flag(s.getStatus())};
                Row row=sheet.createRow(sheet.getLastRowNum()+1);
                for(int i=0;i<values.length;i++) { Cell c=row.createCell(i);if(i!=1 && i!=2 && i!=4)c.setCellStyle(input);if(values[i]!=null)c.setCellValue(values[i].toString()); }
            }
        }
    }
    private String flag(Integer value) { return Integer.valueOf(1).equals(value) ? "启用" : "停用"; }
    private int flag(String value) { return switch(value) { case "启用" -> 1;case "停用" -> 0;default -> throw new IllegalArgumentException("开关请选择启用或停用"); }; }
    private String mode(String value) { return switch(value) { case "contains" -> "包含";case "regex" -> "正则表达式";default -> "精确匹配"; }; }
    private String parseMode(String value) { return switch(value) { case "精确匹配","" -> "exact";case "包含" -> "contains";case "正则表达式" -> "regex";default -> throw new IllegalArgumentException("匹配方式无效"); }; }
    private String level(Integer value) { return switch(value) { case 2 -> "一般";case 3 -> "重要";case 4 -> "严重";default -> throw new IllegalArgumentException("级别无效"); }; }
    private int level(String value) { return switch(value) { case "一般" -> 2;case "重要" -> 3;case "严重" -> 4;default -> throw new IllegalArgumentException("级别无效"); }; }
    private String text(Row row,int col,FormulaEvaluator evaluator) {
        Cell c=row==null ? null : row.getCell(col);if(c==null)return "";
        CellValue v=evaluator.evaluate(c);if(v==null)return "";
        return switch(v.getCellType()) { case STRING -> v.getStringValue().trim();case NUMERIC -> org.apache.poi.ss.util.NumberToTextConverter.toText(v.getNumberValue());case BLANK -> "";default -> throw new IllegalArgumentException("单元格须为文字或有效数值，不能为错误公式"); };
    }
    public Plan preview(Workbook workbook) {
        Sheet sheet=workbook.getSheet(SHEET);if(sheet==null)return Plan.empty();
        FormulaEvaluator evaluator=workbook.getCreationHelper().createFormulaEvaluator();
        for(int i=0;i<HEADERS.length;i++) if(!HEADERS[i].equals(text(sheet.getRow(0),i,evaluator))) throw new IllegalArgumentException(SHEET+"表头不匹配："+HEADERS[i]);
        if(sheet.getLastRowNum()>100000) throw new IllegalArgumentException("原始数据sheet最多100000行");
        Map<String,Change> changes=new LinkedHashMap<>();Map<String,String> shared=new HashMap<>();Set<String> slots=new HashSet<>();Map<Long,String> ids=new HashMap<>();
        List<StrategyExcelService.ImportError> errors=new ArrayList<>();List<StrategyExcelService.RowChange> rows=new ArrayList<>();int skipped=0,configured=0;
        for(int number=1;number<=sheet.getLastRowNum();number++) {
            try {
                Row row=sheet.getRow(number);if(text(row,17,evaluator).isBlank()) { skipped++;continue; }configured++;
                String[] v=new String[HEADERS.length];for(int i=0;i<v.length;i++)v[i]=text(row,i,evaluator);
                String key=v[0];if(key.isBlank())throw new IllegalArgumentException("配置标识不能为空；同一策略各行使用相同标识，新建另一条策略请使用不同标识");
                String common=json(List.of(Arrays.copyOfRange(v,1,10),Arrays.copyOfRange(v,19,22)));
                if(shared.putIfAbsent(key,common)!=null && !shared.get(key).equals(common))throw new IllegalArgumentException("同一配置标识的策略身份、范围、名称或开关不一致");
                Change change=changes.get(key);
                if(change==null) {
                    AlarmStrategy s;ObjectNode d;String before="";
                    if(!v[1].isBlank()) {
                        Long id=Long.valueOf(v[1]);if(ids.putIfAbsent(id,key)!=null && !ids.get(id).equals(key))throw new IllegalArgumentException("同一策略ID不能使用多个配置标识");
                        AlarmStrategy existing=strategies.findById(id).orElseThrow(() -> new IllegalArgumentException("策略ID不存在"));
                        if(!"raw".equals(existing.getStrategyType()))throw new IllegalArgumentException("策略ID不属于原始数据策略");
                        if(!Objects.toString(existing.getUpdateTime(),"").equals(v[2]))throw new IllegalArgumentException("策略在导出后已变更，请重新导出");
                        s=copy(existing);d=(ObjectNode)raw.definition(id).deepCopy();before=snapshot(s,d);
                    } else {
                        s=new AlarmStrategy();s.setAlarmRules(new ArrayList<>());s.setGongdanRules(new ArrayList<>());
                        d=mapper.createObjectNode();d.putArray("overrides");
                    }
                    s.setStrategyName(v[3]);s.setAlarmEnabled(flag(v[19]));s.setGongdanEnabled(flag(v[20]));s.setStatus(flag(v[21]));
                    d.put("source",v[5]);d.put("valueField",v[6]);d.set("filter",filter(v[7],v[8],v[9]));d.put("version",2);
                    String expected=raw.catalog().stream().filter(source -> source.key().equals(v[5])).findFirst().orElseThrow(() -> new IllegalArgumentException("数据来源无效")).group();
                    if(!expected.equals(v[4]))throw new IllegalArgumentException("业务分类与来源不一致");
                    change=new Change(s,d,before);changes.put(key,change);
                }
                int index=new BigDecimal(v[10]).intValueExact();if(index<0 || index>30)throw new IllegalArgumentException("个性化序号须为0～30");
                boolean ticket=switch(v[14]) {case "告警" -> false;case "工单" -> true;default -> throw new IllegalArgumentException("生成类型请选择告警或工单");};
                int grade=level(v[15]);if(!slots.add(key+":"+index+":"+ticket+":"+grade))throw new IllegalArgumentException("同策略同范围同类型同级别重复");
                if(!Set.of(">",">=","<","<=","=").contains(v[16]))throw new IllegalArgumentException("比较条件无效");
                StrategyRule rule=new StrategyRule();rule.setLevel(grade);rule.setCondition(v[16]);rule.setThreshold(new BigDecimal(v[17]).stripTrailingZeros());rule.setConsecutiveTimes(new BigDecimal(v[18]).intValueExact());
                List<StrategyRule> list;
                if(index==0) { if(!v[11].isBlank() || !v[13].isBlank())throw new IllegalArgumentException("默认规则不要填写个性化范围");list=ticket ? change.strategy().getGongdanRules() : change.strategy().getAlarmRules(); }
                else {
                    var overrides=(com.fasterxml.jackson.databind.node.ArrayNode)change.definition().withArray("overrides");
                    if(index>overrides.size()+1)throw new IllegalArgumentException("新增个性化序号须连续排列，先填写较小序号");
                    if(index==overrides.size()+1) { ObjectNode custom=overrides.addObject();custom.putArray("alarmRules");custom.putArray("gongdanRules");custom.set("filter",filter(v[11],v[12],v[13])); }
                    ObjectNode custom=(ObjectNode)overrides.get(index-1);
                    ObjectNode f=filter(v[11],v[12],v[13]);String scopeKey=key+":scope:"+index;
                    String prior=shared.putIfAbsent(scopeKey,json(f));if(prior!=null && !prior.equals(json(f)))throw new IllegalArgumentException("同一个性化序号的匹配范围不一致");
                    custom.set("filter",f);list=mapper.convertValue(custom.path(ticket ? "gongdanRules" : "alarmRules"),new com.fasterxml.jackson.core.type.TypeReference<ArrayList<StrategyRule>>(){});
                }
                list.removeIf(r -> Objects.equals(r.getLevel(),grade));list.add(rule);
                if(index>0)((ObjectNode)change.definition().path("overrides").get(index-1)).set(ticket ? "gongdanRules" : "alarmRules",mapper.valueToTree(list));
                rows.add(new StrategyExcelService.RowChange(SHEET,number+1,v[4]+" / "+v[5],v[3],"设置"+(index==0 ? "默认" : "个性化"+index)+v[14]+v[15],"按导出版本合并",v[16]+v[17]+"ms，连续"+v[18]+"条"));
            } catch(RuntimeException error) { errors.add(new StrategyExcelService.ImportError(SHEET,number+1,error.getMessage())); }
        }
        List<Change> effective=new ArrayList<>();
        for(Change c:changes.values()) try { raw.validate(c.strategy(),c.definition());if(!snapshot(c.strategy(),c.definition()).equals(c.before()))effective.add(c); }
        catch(RuntimeException e) { errors.add(new StrategyExcelService.ImportError(SHEET,0,c.strategy().getStrategyName()+"："+e.getMessage())); }
        return new Plan(effective,errors,rows,skipped,configured);
    }
    private ObjectNode filter(String field,String mode,String value) { ObjectNode f=mapper.createObjectNode();f.put("field",field);f.put("mode",parseMode(mode));f.put("value",value);return f; }
    public void check(Plan plan) {
        for(Change c:plan.changes().stream().filter(c -> c.strategy().getId()!=null).sorted(Comparator.comparing(c -> c.strategy().getId())).toList()) {
            Long id=c.strategy().getId();jdbc.queryForList("SELECT id FROM alarm_strategy WHERE id=? FOR UPDATE",id);
            AlarmStrategy current=strategies.findById(id).orElseThrow(() -> new IllegalArgumentException("原始数据策略已删除，请重新校验"));
            if(!snapshot(current,(ObjectNode)raw.definition(id)).equals(c.before()))throw new IllegalArgumentException("原始数据策略在预览后已变更，请重新校验");
        }
    }
    public List<AlarmStrategy> apply(Plan plan) {
        List<AlarmStrategy> saved=new ArrayList<>();
        for(Change c:plan.changes()) saved.add(raw.save(copy(c.strategy()),c.definition().deepCopy()));
        return saved;
    }
}
