package com.metric.mcp.support;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行映射与类型归一。
 *
 * 目的有二：
 *   1. 字段白名单——只把 COLS 里列出的列写进结果，不整表透出；
 *   2. 类型归一——原生查询返回的 Timestamp/BigDecimal/LocalDateTime 统一成
 *      agent 友好的字符串或数字，避免客户端 SDK 反序列化差异。
 */
public final class McpRowMapper {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private McpRowMapper() {
    }

    public static Map<String, Object> map(String[] aliases, Object[] row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < aliases.length && i < row.length; i++) {
            result.put(aliases[i], normalize(row[i]));
        }
        return result;
    }

    public static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(DATE_TIME);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(DATE_TIME);
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof byte[] bytes) {
            return bytes.length + " bytes";
        }
        return value;
    }

    /** 数据库里的 0/1 状态位转成可读文本，减少模型误读。 */
    public static String yesNo(Integer value) {
        if (value == null) {
            return null;
        }
        return value == 1 ? "是" : "否";
    }

    /**
     * 按字典取可读名，key 为 null 时返回兜底值。
     *
     * 不能直接用 Map.of(...).getOrDefault(null, x)：Map.of 生成的不可变集合
     * 在 get 时会调用 key.hashCode()，key 为 null 直接 NPE。
     * 历史数据里 alarm_level / process_status 存在 NULL，必须走这里。
     */
    public static String nameOf(Map<Integer, String> mapping, Integer key, String fallback) {
        if (key == null) {
            return fallback;
        }
        return mapping.getOrDefault(key, fallback);
    }
}
