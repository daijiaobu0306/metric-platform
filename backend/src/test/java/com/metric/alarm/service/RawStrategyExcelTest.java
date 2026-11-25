package com.metric.alarm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metric.alarm.entity.*;
import com.metric.alarm.repository.*;
import com.metric.gongdan.service.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离测试，仅使用内存工作簿与mock仓库，不连接数据库或外部工单。 */
class RawStrategyExcelTest {
    private ObjectMapper mapper;
    private AlarmStrategyRepository strategies;
    private RawDataAlarmConfigRepository configs;
    private RawStrategyService raw;
    private RawStrategyExcel excel;
    private JdbcTemplate jdbc;
    private AlarmService alarms;
    private GongdanService tickets;
    private AlarmStrategy strategy;
    private ObjectNode definition;
    @BeforeEach void setup() throws Exception {
        mapper=new ObjectMapper().findAndRegisterModules();strategies=mock(AlarmStrategyRepository.class);configs=mock(RawDataAlarmConfigRepository.class);
        jdbc=mock(JdbcTemplate.class);alarms=mock(AlarmService.class);tickets=mock(GongdanService.class);
        raw=new RawStrategyService(jdbc,mapper,strategies,configs,new StrategyRuleService(),alarms,tickets,mock(GongdanIntegrationService.class),mock(PlatformTransactionManager.class));
        excel=new RawStrategyExcel(raw,strategies,mapper,mock(JdbcTemplate.class));
        strategy=new AlarmStrategy();strategy.setId(7L);strategy.setStrategyType("raw");strategy.setTargetType("raw");strategy.setTargetId(9L);strategy.setTargetName("查询记录");strategy.setStrategyName("查询耗时");strategy.setStatus(1);strategy.setAlarmEnabled(1);strategy.setGongdanEnabled(1);strategy.setUpdateTime(LocalDateTime.of(2026,11,6,9,0));
        strategy.setAlarmRules(new ArrayList<>(List.of(rule(2,"100",1))));strategy.setGongdanRules(new ArrayList<>(List.of(rule(3,"200",5))));
        definition=(ObjectNode)mapper.readTree("{\"source\":\"inner_query\",\"valueField\":\"avg_duration_sec\",\"filter\":{\"field\":\"\",\"mode\":\"exact\",\"value\":\"\"},\"overrides\":[{\"filter\":{\"field\":\"business_name\",\"mode\":\"regex\",\"value\":\"打印\"},\"alarmRules\":[{\"level\":2,\"condition\":\">\",\"threshold\":300,\"consecutiveTimes\":2}],\"gongdanRules\":[{\"level\":3,\"condition\":\">\",\"threshold\":500,\"consecutiveTimes\":6}]}],\"version\":2}");
        RawDataAlarmConfig config=new RawDataAlarmConfig();config.setDimensionThresholds(mapper.writeValueAsString(definition));
        when(strategies.findAll()).thenReturn(List.of(strategy));when(strategies.findById(7L)).thenReturn(Optional.of(strategy));when(configs.findByStrategyId(7L)).thenReturn(List.of(config));
    }
    private StrategyRule rule(int level,String threshold,int count) { StrategyRule r=new StrategyRule();r.setLevel(level);r.setCondition(">");r.setThreshold(new BigDecimal(threshold));r.setConsecutiveTimes(count);return r; }
    @Test void unchangedWorkbookIsNoOpAndContainsBothTypesAndOverrides() throws Exception {
        try(Workbook book=new XSSFWorkbook()) {
            excel.append(book);Sheet sheet=book.getSheet(RawStrategyExcel.SHEET);
            assertEquals("告警",sheet.getRow(1).getCell(14).getStringCellValue());assertEquals("工单",sheet.getRow(2).getCell(14).getStringCellValue());
            assertEquals("6",sheet.getRow(4).getCell(18).getStringCellValue());
            RawStrategyExcel.Plan plan=excel.preview(book);
            assertTrue(plan.errors().isEmpty(),plan.errors().toString());assertTrue(plan.changes().isEmpty(),"原样导入不能重置计数或生成新策略");
            verify(configs,never()).save(any());
        }
    }
    @Test void editsPreserveOtherRulesAndOverrideTicketCounts() throws Exception {
        try(Workbook book=new XSSFWorkbook()) {
            excel.append(book);book.getSheet(RawStrategyExcel.SHEET).getRow(1).getCell(17).setCellValue("150");
            var plan=excel.preview(book);assertTrue(plan.errors().isEmpty(),plan.errors().toString());assertEquals(1,plan.changes().size());
            var change=plan.changes().get(0);assertEquals(0,new BigDecimal("150").compareTo(change.strategy().getAlarmRules().get(0).getThreshold()));
            assertEquals(5,change.strategy().getGongdanRules().get(0).getConsecutiveTimes());assertEquals(6,change.definition().path("overrides").get(0).path("gongdanRules").get(0).path("consecutiveTimes").asInt());
            assertEquals(new BigDecimal("100"),strategy.getAlarmRules().get(0).getThreshold(),"预览不能修改仓库实体");
        }
    }
    @Test void staleVersionAndZeroCountsAreRejected() throws Exception {
        try(Workbook book=new XSSFWorkbook()) {
            excel.append(book);book.getSheet(RawStrategyExcel.SHEET).getRow(1).getCell(2).setCellValue("过期版本");assertFalse(excel.preview(book).errors().isEmpty());
        }
        try(Workbook book=new XSSFWorkbook()) {
            excel.append(book);book.getSheet(RawStrategyExcel.SHEET).getRow(1).getCell(18).setCellValue("0");assertFalse(excel.preview(book).errors().isEmpty());
        }
    }
    @Test void invalidRegexAndDuplicateGradeAreRejected() throws Exception {
        try(Workbook book=new XSSFWorkbook()) {
            excel.append(book);Sheet sheet=book.getSheet(RawStrategyExcel.SHEET);sheet.getRow(3).getCell(13).setCellValue("[");sheet.getRow(4).getCell(13).setCellValue("[");
            assertFalse(excel.preview(book).errors().isEmpty());
        }
        try(Workbook book=new XSSFWorkbook()) {
            excel.append(book);Sheet sheet=book.getSheet(RawStrategyExcel.SHEET);Row row=sheet.createRow(sheet.getLastRowNum()+1);
            for(int i=0;i<22;i++)row.createCell(i).setCellValue(sheet.getRow(1).getCell(i).getStringCellValue());
            assertFalse(excel.preview(book).errors().isEmpty());
        }
    }
    @Test void legacyWorkbookWithoutRawSheetRemainsSupported() throws Exception {
        try(Workbook book=new XSSFWorkbook()) { assertTrue(excel.preview(book).changes().isEmpty()); }
    }
    @Test void previewDetectsConcurrentConfigurationChangeBeforeApply() throws Exception {
        try(Workbook book=new XSSFWorkbook()) {
            excel.append(book);book.getSheet(RawStrategyExcel.SHEET).getRow(1).getCell(17).setCellValue("151");var plan=excel.preview(book);
            strategy.setStrategyName("他人已修改");assertThrows(IllegalArgumentException.class,() -> excel.check(plan));
        }
    }
    @Test void newSourceCanBeConfiguredWithoutLookingUpDatabaseIds() throws Exception {
        when(strategies.findAll()).thenReturn(List.of());
        try(Workbook book=new XSSFWorkbook()) {
            excel.append(book);Sheet sheet=book.getSheet(RawStrategyExcel.SHEET);
            sheet.getRow(1).getCell(16).setCellValue(">");sheet.getRow(1).getCell(17).setCellValue("300");sheet.getRow(1).getCell(18).setCellValue("2");
            var plan=excel.preview(book);assertTrue(plan.errors().isEmpty(),plan.errors().toString());assertEquals(1,plan.created());
            assertEquals("inner_query",plan.changes().get(0).definition().path("source").asText());
        }
    }
    @Test void completeTemplateHasSevenSheetsAndOldSixSheetTemplateStillPreviews() throws Exception {
        var service=new StrategyExcelService();var metrics=mock(com.metric.metric.service.MetricService.class);var systems=mock(com.metric.metric.service.SystemService.class);
        when(metrics.findAllCategories()).thenReturn(List.of());when(metrics.findAllItems(any(org.springframework.data.domain.Pageable.class))).thenReturn(org.springframework.data.domain.Page.empty());when(systems.findAll()).thenReturn(List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"strategies",strategies);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"ruleService",new StrategyRuleService());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"metrics",metrics);org.springframework.test.util.ReflectionTestUtils.setField(service,"systems",systems);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"mapper",mapper);org.springframework.test.util.ReflectionTestUtils.setField(service,"rawExcel",excel);
        byte[] bytes=service.exportWorkbook();assertEquals(0,service.preview(bytes,"tester").get("errorCount"));
        try(Workbook book=new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));var out=new java.io.ByteArrayOutputStream()) {
            assertEquals(7,book.getNumberOfSheets());book.removeSheetAt(6);book.write(out);
            assertEquals(0,service.preview(out.toByteArray(),"tester").get("errorCount"));
        }
    }
    @Test void scanCountsNewRowsIndependentlyResetsOnNormalAndDoesNotRecountOnRescan() throws Exception {
        Map<String,String> state=new HashMap<>();List<Map<String,Object>> input=new ArrayList<>();
        when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(7L))).thenAnswer(call -> state.get("raw-cursor"));
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(7L),anyString())).thenAnswer(call -> state.get(call.getArgument(3)));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(7L),anyString())).thenReturn(0);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(7L),anyString(),anyInt())).thenReturn(0);
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(call -> {
            String sql=call.getArgument(0);
            if(sql.startsWith("INSERT IGNORE")) {
                if(sql.contains("'raw-cursor'"))state.putIfAbsent("raw-cursor",call.getArgument(2));
                else state.putIfAbsent(call.getArgument(2),"{}");
            } else if(sql.startsWith("UPDATE alarm_observation_state")) {
                String scope=sql.contains("scope_key=?") ? call.getArgument(3) : "raw-cursor";state.put(scope,call.getArgument(1));
            }
            return 1;
        });
        when(jdbc.queryForList(startsWith("SELECT *"),any(Object[].class))).thenAnswer(call -> {
            long cursor=mapper.readTree(state.get("raw-cursor")).path("id").asLong();
            return input.stream().filter(row -> ((Number)row.get("id")).longValue()>cursor).toList();
        });
        strategy.setGongdanRules(new ArrayList<>(List.of(rule(3,"200",3))));
        for(int i=1;i<=3;i++)input.add(new HashMap<>(Map.of("id",(long)i,"business_name","A","avg_duration_sec",250)));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(4,raw.scan(7L),"3条告警和第3条时的1张工单");assertEquals(0,raw.scan(7L),"重复扫描不增加次数或事件");
            input.add(new HashMap<>(Map.of("id",4L,"business_name","B","avg_duration_sec",250)));
            assertEquals(1,raw.scan(7L),"另一对象不能借用A的连续次数");
            input.add(new HashMap<>(Map.of("id",5L,"business_name","A","avg_duration_sec",50)));
            input.add(new HashMap<>(Map.of("id",6L,"business_name","A","avg_duration_sec",250)));
            assertEquals(1,raw.scan(7L),"正常记录打断A的连续次数，此时不生成工单");
            verify(tickets,times(1)).save(any());
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
    }
}
