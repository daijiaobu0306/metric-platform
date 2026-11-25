package com.metric.metric.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.metric.entity.AbilityMetric;
import com.metric.metric.repository.AbilityMetricRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AbilityMetricStatisticsService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AbilityMetricRepository abilityMetricRepository;

    /**
     * 获取同比环比数据
     */
    public Map<String, Object> getYoYMoMAnalysis(String capabilityCode, String userCode,
                                                  String currentDate, String period) {
        Map<String, Object> result = new HashMap<>();

        LocalDate current = LocalDate.parse(currentDate, DATE_FORMAT);

        String compareDateStr = null;
        String lastYearDateStr = null;

        switch (period) {
            case "day":
                compareDateStr = current.minusDays(1).format(DATE_FORMAT);
                lastYearDateStr = current.minusYears(1).format(DATE_FORMAT);
                break;
            case "week":
                compareDateStr = current.minusWeeks(1).format(DATE_FORMAT);
                lastYearDateStr = current.minusYears(1).format(DATE_FORMAT);
                break;
            case "month":
                compareDateStr = current.minusMonths(1).format(DATE_FORMAT);
                lastYearDateStr = current.minusYears(1).format(DATE_FORMAT);
                break;
            default:
                compareDateStr = current.minusDays(1).format(DATE_FORMAT);
                lastYearDateStr = current.minusYears(1).format(DATE_FORMAT);
        }

        Long currentValue = getTotalInvokeVolume(capabilityCode, userCode, currentDate);
        Long compareValue = getTotalInvokeVolume(capabilityCode, userCode, compareDateStr);
        Long lastYearValue = getTotalInvokeVolume(capabilityCode, userCode, lastYearDateStr);

        result.put("currentDate", currentDate);
        result.put("currentValue", currentValue);
        result.put("compareDate", compareDateStr);
        result.put("compareValue", compareValue);
        result.put("lastYearDate", lastYearDateStr);
        result.put("lastYearValue", lastYearValue);

        // 计算环比
        if (compareValue != null && compareValue > 0 && currentValue != null) {
            BigDecimal mom = BigDecimal.valueOf((currentValue - compareValue) * 100.0 / compareValue);
            result.put("momPercent", mom.setScale(2, RoundingMode.HALF_UP).doubleValue());
            result.put("momTrend", mom.compareTo(BigDecimal.ZERO) > 0 ? "up" : (mom.compareTo(BigDecimal.ZERO) < 0 ? "down" : "flat"));
        } else {
            result.put("momPercent", null);
            result.put("momTrend", "N/A");
        }

        // 计算同比
        if (lastYearValue != null && lastYearValue > 0 && currentValue != null) {
            BigDecimal yoy = BigDecimal.valueOf((currentValue - lastYearValue) * 100.0 / lastYearValue);
            result.put("yoyPercent", yoy.setScale(2, RoundingMode.HALF_UP).doubleValue());
            result.put("yoyTrend", yoy.compareTo(BigDecimal.ZERO) > 0 ? "up" : (yoy.compareTo(BigDecimal.ZERO) < 0 ? "down" : "flat"));
        } else {
            result.put("yoyPercent", null);
            result.put("yoyTrend", "N/A");
        }

        return result;
    }

    /**
     * 按维度汇总统计
     */
    public List<Map<String, Object>> getStatisticsByDimension(String dimension,
        String startDate, String endDate, String capabilityCode, String userCode,
        String regionCode, String functionCode, String serviceOrgCode, String sceneCode) {

        List<AbilityMetric> data;
        if ((startDate == null || startDate.isEmpty()) && (endDate == null || endDate.isEmpty())) {
            data = abilityMetricRepository.findAll();
        } else {
            data = abilityMetricRepository.findByDateRange(startDate, endDate);
        }

        // 应用额外过滤条件
        if (capabilityCode != null && !capabilityCode.isEmpty()) {
            data = data.stream().filter(d -> capabilityCode.equals(d.getUnifiedCapabilityCode())).toList();
        }
        if (userCode != null && !userCode.isEmpty()) {
            data = data.stream().filter(d -> userCode.equals(d.getAbilityUserCode())).toList();
        }
        if (regionCode != null && !regionCode.isEmpty()) {
            data = data.stream().filter(d -> regionCode.equals(d.getRegionCode())).toList();
        }
        if (functionCode != null && !functionCode.isEmpty()) {
            data = data.stream().filter(d -> functionCode.equals(d.getFunctionCode())).toList();
        }
        if (serviceOrgCode != null && !serviceOrgCode.isEmpty()) {
            data = data.stream().filter(d -> serviceOrgCode.equals(d.getServiceOrgCode())).toList();
        }
        if (sceneCode != null && !sceneCode.isEmpty()) {
            data = data.stream().filter(d -> sceneCode.equals(d.getUnifiedSceneCode())).toList();
        }

        List<Map<String, Object>> stats = new ArrayList<>();

        if ("capability".equals(dimension)) {
            Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
            for (AbilityMetric item : data) {
                String key = item.getUnifiedCapabilityCode();
                grouped.computeIfAbsent(key, k -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("capabilityCode", key);
                    m.put("totalInvoke", 0L);
                    m.put("totalFailure", 0L);
                    m.put("count", 0);
                    return m;
                });
                Map<String, Object> m = grouped.get(key);
                m.put("totalInvoke", ((Long) m.get("totalInvoke")) + parseInvokeVolume(item.getInvokeVolume()));
                m.put("totalFailure", ((Long) m.get("totalFailure")) + parseInvokeFailure(item.getInvokeFailure()));
                m.put("count", ((Integer) m.get("count")) + 1);
            }
            stats.addAll(grouped.values());
        } else if ("user".equals(dimension)) {
            Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
            for (AbilityMetric item : data) {
                if (userCode != null && !userCode.equals(item.getAbilityUserCode())) continue;
                String key = item.getAbilityUserCode();
                grouped.computeIfAbsent(key, k -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("userCode", key);
                    m.put("userName", item.getAbilityUserName());
                    m.put("totalInvoke", 0L);
                    m.put("totalFailure", 0L);
                    m.put("count", 0);
                    return m;
                });
                Map<String, Object> m = grouped.get(key);
                m.put("totalInvoke", ((Long) m.get("totalInvoke")) + parseInvokeVolume(item.getInvokeVolume()));
                m.put("totalFailure", ((Long) m.get("totalFailure")) + parseInvokeFailure(item.getInvokeFailure()));
                m.put("count", ((Integer) m.get("count")) + 1);
            }
            stats.addAll(grouped.values());
        } else if ("date".equals(dimension)) {
            Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
            for (AbilityMetric item : data) {
                String key = item.getDataDate();
                grouped.computeIfAbsent(key, k -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("dataDate", key);
                    m.put("totalInvoke", 0L);
                    m.put("totalFailure", 0L);
                    m.put("count", 0);
                    return m;
                });
                Map<String, Object> m = grouped.get(key);
                m.put("totalInvoke", ((Long) m.get("totalInvoke")) + parseInvokeVolume(item.getInvokeVolume()));
                m.put("totalFailure", ((Long) m.get("totalFailure")) + parseInvokeFailure(item.getInvokeFailure()));
                m.put("count", ((Integer) m.get("count")) + 1);
            }
            stats.addAll(grouped.values());
        } else if ("org".equals(dimension)) {
            Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
            for (AbilityMetric item : data) {
                String key = item.getAbilityUserOrgCode();
                grouped.computeIfAbsent(key, k -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("orgCode", key);
                    m.put("orgName", item.getAbilityUserOrgName());
                    m.put("totalInvoke", 0L);
                    m.put("totalFailure", 0L);
                    m.put("count", 0);
                    return m;
                });
                Map<String, Object> m = grouped.get(key);
                m.put("totalInvoke", ((Long) m.get("totalInvoke")) + parseInvokeVolume(item.getInvokeVolume()));
                m.put("totalFailure", ((Long) m.get("totalFailure")) + parseInvokeFailure(item.getInvokeFailure()));
                m.put("count", ((Integer) m.get("count")) + 1);
            }
            stats.addAll(grouped.values());
        } else if ("service".equals(dimension)) {
            Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
            for (AbilityMetric item : data) {
                String key = item.getUnifiedServiceCode();
                grouped.computeIfAbsent(key, k -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("serviceCode", key);
                    m.put("totalInvoke", 0L);
                    m.put("totalFailure", 0L);
                    m.put("count", 0);
                    return m;
                });
                Map<String, Object> m = grouped.get(key);
                m.put("totalInvoke", ((Long) m.get("totalInvoke")) + parseInvokeVolume(item.getInvokeVolume()));
                m.put("totalFailure", ((Long) m.get("totalFailure")) + parseInvokeFailure(item.getInvokeFailure()));
                m.put("count", ((Integer) m.get("count")) + 1);
            }
            stats.addAll(grouped.values());
        } else if ("region".equals(dimension)) {
            Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
            for (AbilityMetric item : data) {
                String key = item.getRegionCode();
                grouped.computeIfAbsent(key, k -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("regionCode", key);
                    m.put("totalInvoke", 0L);
                    m.put("totalFailure", 0L);
                    m.put("count", 0);
                    return m;
                });
                Map<String, Object> m = grouped.get(key);
                m.put("totalInvoke", ((Long) m.get("totalInvoke")) + parseInvokeVolume(item.getInvokeVolume()));
                m.put("totalFailure", ((Long) m.get("totalFailure")) + parseInvokeFailure(item.getInvokeFailure()));
                m.put("count", ((Integer) m.get("count")) + 1);
            }
            stats.addAll(grouped.values());
        }

        return stats;
    }

    private Long getTotalInvokeVolume(String capabilityCode, String userCode, String date) {
        List<AbilityMetric> data = abilityMetricRepository.findByDataDate(date);
        long total = 0;
        for (AbilityMetric item : data) {
            if (capabilityCode != null && !capabilityCode.equals(item.getUnifiedCapabilityCode())) continue;
            if (userCode != null && !userCode.equals(item.getAbilityUserCode())) continue;
            total += parseInvokeVolume(item.getInvokeVolume());
        }
        return total;
    }

    private long parseInvokeVolume(String json) {
        if (json == null || json.isEmpty()) return 0;
        try {
            JsonNode root = objectMapper.readTree(json);
            long total = 0;

            // 解析主量纲 ZLG
            JsonNode zlg = root.get("ZLG");
            if (zlg != null && zlg.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = zlg.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (entry.getValue().isNumber()) {
                        total += entry.getValue().asLong();
                    }
                }
            }

            // 解析副量纲 FLG
            JsonNode flg = root.get("FLG");
            if (flg != null && flg.isArray()) {
                for (JsonNode node : flg) {
                    if (node.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> entry = fields.next();
                            if (entry.getValue().isNumber()) {
                                total += entry.getValue().asLong();
                            }
                        }
                    }
                }
            }

            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseInvokeFailure(String json) {
        if (json == null || json.isEmpty()) return 0;
        try {
            JsonNode root = objectMapper.readTree(json);
            long total = 0;

            // 解析主量纲 ZLG
            JsonNode zlg = root.get("ZLG");
            if (zlg != null && zlg.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = zlg.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (entry.getValue().isObject()) {
                        JsonNode sub = entry.getValue();
                        Iterator<Map.Entry<String, JsonNode>> subFields = sub.fields();
                        while (subFields.hasNext()) {
                            Map.Entry<String, JsonNode> subEntry = subFields.next();
                            if (subEntry.getValue().isNumber()) {
                                total += subEntry.getValue().asLong();
                            }
                        }
                    }
                }
            }

            // 解析副量纲 FLG
            JsonNode flg = root.get("FLG");
            if (flg != null && flg.isArray()) {
                for (JsonNode node : flg) {
                    if (node.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> entry = fields.next();
                            if (entry.getValue().isObject()) {
                                JsonNode sub = entry.getValue();
                                Iterator<Map.Entry<String, JsonNode>> subFields = sub.fields();
                                while (subFields.hasNext()) {
                                    Map.Entry<String, JsonNode> subEntry = subFields.next();
                                    if (subEntry.getValue().isNumber()) {
                                        total += subEntry.getValue().asLong();
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return total;
        } catch (Exception e) {
            return 0;
        }
    }
}
