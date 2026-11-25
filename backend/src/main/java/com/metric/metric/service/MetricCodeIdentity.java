package com.metric.metric.service;

/** Explicit user-confirmed rename; source files and stored raw records remain unchanged. */
final class MetricCodeIdentity {
    private MetricCodeIdentity() {}

    static String canonical(String category,String code) {
        return "businessValue".equals(category) && "importantAlarmRate".equals(code)?"importantAlarmRatio":code;
    }

    static String historicalCode(String category,String code) {
        return "businessValue".equals(category) && "importantAlarmRatio".equals(code)?"importantAlarmRate":code;
    }

    // 观察表读取用：collection_row.metric_code_canonical_digest 生成列已把历史别名
    // importantAlarmRate 归一到 importantAlarmRatio，故单一等值即可同时覆盖新旧编码并走索引。
    // 参数均为内部 SQL 表达式，不拼接请求值。
    static String canonicalDigestMatchSql(String metricCode, String rowDigestColumn) {
        return rowDigestColumn+"=UNHEX(SHA2(CONVERT("+metricCode+" USING utf8mb4),256))";
    }

    // Arguments are internal SQL expressions, never request values.
    static String matchesSql(String metricCode,String sourceCode,String category) {
        return "(BINARY "+metricCode+"=BINARY "+sourceCode+" OR (BINARY "+category+"=BINARY 'businessValue'"
            +" AND BINARY "+metricCode+"=BINARY 'importantAlarmRatio' AND BINARY "+sourceCode+"=BINARY 'importantAlarmRate'))";
    }
}
