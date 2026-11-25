package com.metric.metric.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.entity.SystemInfo;
import com.metric.metric.entity.WeightConfig;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.MetricItemRepository;
import com.metric.metric.repository.SystemInfoRepository;
import com.metric.metric.repository.WeightConfigRepository;
import com.metric.security.service.CurrentUserProvider;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class WeightConfigService {

    @Autowired
    private WeightConfigRepository weightConfigRepository;

    @Autowired
    private MetricCategoryRepository categoryRepository;

    @Autowired
    private MetricItemRepository itemRepository;

    @Autowired
    private SystemInfoRepository systemInfoRepository;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CurrentUserProvider users;
    @Autowired private WeightResolver weightResolver;

    // 获取系统下所有权重配置
    public Map<String, Object> getWeightConfig(Long systemId) {
        Map<String, Object> result = new HashMap<>();

        // 获取系统下所有指标大类
        List<MetricCategory> categories = categoryRepository.findBySystemId(systemId);
        result.put("categories", categories);

        // 2026-11-17 P3：页面展示的权重与算分读取的权重必须是同一份（WeightResolver），
        // 否则会出现“页面上改了权重、算分不生效”的历史问题。
        WeightResolver.WeightSnapshot snapshot = weightResolver.snapshot();

        // 大类权重（按系统）
        Map<Long, BigDecimal> categoryWeightMap = new HashMap<>(
                snapshot.categoryWeightsBySystem().getOrDefault(systemId, Map.of()));

        // 小类权重（按大类）
        Map<Long, Map<String, BigDecimal>> subcategoryWeightsMap = new HashMap<>();
        Map<Long, Map<Long, BigDecimal>> metricWeightsMap = new HashMap<>();
        for (MetricCategory category : categories) {
            subcategoryWeightsMap.put(category.getId(),
                    new LinkedHashMap<>(snapshot.subcategoryWeightsByCategory()
                            .getOrDefault(category.getId(), Map.of())));
            metricWeightsMap.put(category.getId(),
                    new LinkedHashMap<>(snapshot.metricWeightsByCategory()
                            .getOrDefault(category.getId(), Map.of())));
        }

        result.put("categoryWeights", categoryWeightMap);
        result.put("subcategoryWeights", subcategoryWeightsMap);
        result.put("metricWeights", metricWeightsMap);
        result.put("weightSource", "WeightResolver（与健康度算分同一口径；未配置时取同级平均值）");
        return result;
    }

    // 更新大类权重
    @Transactional
    public void updateCategoryWeights(Long systemId, List<Map<String, Object>> weights) {
        jdbc.queryForList("SELECT id FROM system_info WHERE id = ? FOR UPDATE", Long.class, systemId);
        var before = weightConfigRepository.findBySystemIdAndWeightType(systemId, "category");
        // 删除旧的
        weightConfigRepository.deleteBySystemIdAndWeightType(systemId, "category");

        // 保存新的
        for (Map<String, Object> w : weights) {
            WeightConfig config = new WeightConfig();
            config.setSystemId(systemId);
            config.setCategoryId(((Number) w.get("categoryId")).longValue());
            config.setWeight(new BigDecimal(w.get("weight").toString()));
            config.setWeightType("category");
            weightConfigRepository.save(config);
        }
        recordManualVersion("category", systemId, before, weightConfigRepository.findBySystemIdAndWeightType(systemId, "category"));
    }

    // 更新小类权重
    @Transactional
    public void updateSubcategoryWeights(Long categoryId, List<Map<String, Object>> weights) {
        lockCategory(categoryId);
        var before = weightConfigRepository.findByCategoryIdAndWeightType(categoryId, "subcategory");
        // 删除旧的
        weightConfigRepository.deleteByCategoryIdAndWeightType(categoryId, "subcategory");

        // 保存新的
        for (Map<String, Object> w : weights) {
            WeightConfig config = new WeightConfig();
            config.setCategoryId(categoryId);
            config.setSubCategoryName((String) w.get("subCategoryName"));
            config.setWeight(new BigDecimal(w.get("weight").toString()));
            config.setWeightType("subcategory");
            weightConfigRepository.save(config);
        }
        recordManualVersion("subcategory", categoryId, before, weightConfigRepository.findByCategoryIdAndWeightType(categoryId, "subcategory"));
    }

    // 更新指标权重
    @Transactional
    public void updateMetricWeights(Long categoryId, List<Map<String, Object>> weights) {
        lockCategory(categoryId);
        var metricIds = itemRepository.findByCategoryId(categoryId).stream()
                .map(MetricItem::getId).collect(Collectors.toSet());
        var patch = new LinkedHashMap<Long, BigDecimal>();
        for (var row : weights) {
            try {
                long id = Long.parseLong(row.get("metricId").toString());
                BigDecimal value = new BigDecimal(row.get("weight").toString()).setScale(2);
                if (!metricIds.contains(id) || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0
                        || patch.putIfAbsent(id, value) != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException | ArithmeticException | NullPointerException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指标不存在、重复或权重无效");
            }
        }
        if (patch.isEmpty()) return;

        var existing = weightConfigRepository.findByCategoryIdAndWeightType(categoryId, "metric");
        // 编辑器提交当前小类，保留同一大类中的其他指标。
        weightConfigRepository.deleteAll(existing.stream().filter(row -> patch.containsKey(row.getMetricId())).toList());
        for (var entry : patch.entrySet()) {
            var config = new WeightConfig();
            config.setCategoryId(categoryId);
            config.setMetricId(entry.getKey());
            config.setWeight(entry.getValue());
            config.setWeightType("metric");
            weightConfigRepository.save(config);
        }
        recordManualVersion("metric", categoryId, existing, weightConfigRepository.findByCategoryIdAndWeightType(categoryId, "metric"));
    }

    private void lockCategory(Long categoryId) {
        if (jdbc.queryForList("SELECT id FROM metric_category WHERE id = ? FOR UPDATE", Long.class, categoryId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "指标大类不存在");
        }
        jdbc.queryForList("SELECT id FROM metric_item WHERE category_id = ? FOR UPDATE", Long.class, categoryId);
    }

    private void recordManualVersion(String scope, Long id, List<WeightConfig> before, List<WeightConfig> after) {
        try {
            jdbc.update("""
                    INSERT INTO metric_weight_version(scope_type, scope_id, source, operator_id, operator_name, weights_before, weights_after)
                    VALUES (?, ?, 'manual', ?, ?, ?, ?)
                    """, scope, id, users.currentUser().map(user -> user.getId()).orElse(null),
                    users.operatorName(), objectMapper.writeValueAsString(before), objectMapper.writeValueAsString(after));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存权重变更历史", e);
        }
    }

    // 获取大类权重（与算分同一口径）
    public Map<Long, BigDecimal> getCategoryWeights(Long systemId) {
        return new HashMap<>(weightResolver.snapshot()
                .categoryWeightsBySystem().getOrDefault(systemId, Map.of()));
    }

    // 获取小类权重（键为小类中文名，与算分同一口径）
    public Map<String, BigDecimal> getSubcategoryWeights(Long categoryId) {
        return new HashMap<>(weightResolver.snapshot()
                .subcategoryWeightsByCategory().getOrDefault(categoryId, Map.of()));
    }

    // 获取指标权重（与算分同一口径）
    public Map<Long, BigDecimal> getMetricWeights(Long categoryId) {
        return new HashMap<>(weightResolver.snapshot()
                .metricWeightsByCategory().getOrDefault(categoryId, Map.of()));
    }

    /**
     * 导出权重配置到Excel
     */
    public byte[] exportWeightConfig(Long systemId) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            // 获取系统信息
            SystemInfo system = systemInfoRepository.findById(systemId).orElse(null);

            // 创建大类权重Sheet
            Sheet categorySheet = workbook.createSheet("大类权重");
            createCategoryWeightSheet(categorySheet, systemId);

            // 创建小类权重Sheet
            Sheet subcategorySheet = workbook.createSheet("小类权重");
            createSubcategoryWeightSheet(subcategorySheet, systemId);

            // 创建指标权重Sheet
            Sheet metricSheet = workbook.createSheet("指标权重");
            createMetricWeightSheet(metricSheet, systemId);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private void createCategoryWeightSheet(Sheet sheet, Long systemId) {
        CellStyle headerStyle = createHeaderStyle(sheet.getWorkbook());

        String[] headers = {"系统名称", "大类编码", "大类名称", "权重"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        List<MetricCategory> categories = categoryRepository.findBySystemId(systemId);
        Map<Long, BigDecimal> categoryWeights = getCategoryWeights(systemId);

        int rowNum = 1;
        for (MetricCategory category : categories) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(category.getSystemId() != null ? category.getSystemId().toString() : "");
            row.createCell(1).setCellValue(category.getCategoryCode() != null ? category.getCategoryCode() : "");
            row.createCell(2).setCellValue(category.getCategoryName() != null ? category.getCategoryName() : "");
            row.createCell(3).setCellValue(categoryWeights.getOrDefault(category.getId(), BigDecimal.ZERO).doubleValue());
        }

        setColumnWidth(sheet, headers.length);
    }

    private void createSubcategoryWeightSheet(Sheet sheet, Long systemId) {
        CellStyle headerStyle = createHeaderStyle(sheet.getWorkbook());

        String[] headers = {"大类名称", "小类编码", "小类名称", "权重"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        List<MetricCategory> categories = categoryRepository.findBySystemId(systemId);
        int rowNum = 1;
        for (MetricCategory category : categories) {
            Map<String, BigDecimal> subWeights = getSubcategoryWeights(category.getId());
            for (Map.Entry<String, BigDecimal> entry : subWeights.entrySet()) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(category.getCategoryName() != null ? category.getCategoryName() : "");
                row.createCell(1).setCellValue(entry.getKey());
                row.createCell(2).setCellValue(entry.getKey());
                row.createCell(3).setCellValue(entry.getValue().doubleValue());
            }
        }

        setColumnWidth(sheet, headers.length);
    }

    private void createMetricWeightSheet(Sheet sheet, Long systemId) {
        CellStyle headerStyle = createHeaderStyle(sheet.getWorkbook());

        String[] headers = {"大类名称", "小类名称", "指标编码", "指标名称", "权重"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        List<MetricCategory> categories = categoryRepository.findBySystemId(systemId);
        int rowNum = 1;
        for (MetricCategory category : categories) {
            List<MetricItem> items = itemRepository.findByCategoryId(category.getId());
            Map<Long, BigDecimal> metricWeights = getMetricWeights(category.getId());
            for (MetricItem item : items) {
                BigDecimal weight = metricWeights.get(item.getId());
                if (weight == null) {
                    weight = BigDecimal.ZERO;
                }
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(category.getCategoryName() != null ? category.getCategoryName() : "");
                row.createCell(1).setCellValue(item.getSubCategoryName() != null ? item.getSubCategoryName() : "");
                row.createCell(2).setCellValue(item.getMetricCode() != null ? item.getMetricCode() : "");
                row.createCell(3).setCellValue(item.getMetricName() != null ? item.getMetricName() : "");
                row.createCell(4).setCellValue(weight != null ? weight.doubleValue() : 0.0);
            }
        }

        setColumnWidth(sheet, headers.length);
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private void setColumnWidth(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.setColumnWidth(i, 4500);
        }
    }
}
