package com.metric.alarm.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class StrategyRulesConverter implements AttributeConverter<List<StrategyRule>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<StrategyRule> rules) {
        if (rules == null) return null;
        try { return MAPPER.writeValueAsString(rules); }
        catch (Exception e) { throw new IllegalArgumentException("无法保存策略规则", e); }
    }

    @Override
    public List<StrategyRule> convertToEntityAttribute(String value) {
        if (value == null) return null;
        try { return MAPPER.readValue(value, new TypeReference<List<StrategyRule>>() {}); }
        catch (Exception e) { throw new IllegalArgumentException("策略规则格式损坏，停止读取", e); }
    }
}
