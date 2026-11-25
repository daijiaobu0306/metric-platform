package com.metric.mcp.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP 只读查询的统一入口。
 *
 * 只允许 SELECT：调用方传入的 SQL 由各工具类静态拼装（条件为常量片段，值一律走参数绑定），
 * 不接受任何来自外部的 SQL 片段，避免注入；分页 limit/offset 已在
 * {@link McpToolSupport#window} 中校验为整数，直接拼接不进参数绑定，
 * 规避部分驱动对原生 LIMIT 参数绑定的兼容问题。
 */
@Component
public class McpReadOnlyQuery {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 执行查询并返回行数组。列顺序由调用方的 COLS 常量决定，映射时按索引取值。
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> rows(String sql, Map<String, Object> params) {
        assertSelect(sql);
        Query query = entityManager.createNativeQuery(sql);
        bind(query, params);
        return query.getResultList();
    }

    /**
     * 单值查询（count、avg 等）。
     *
     * 不能复用 rows()：单列原生查询 Hibernate 返回的是 List<Long>，
     * 强转 Object[] 会抛 ClassCastException。
     */
    public long scalar(String sql, Map<String, Object> params) {
        assertSelect(sql);
        Query query = entityManager.createNativeQuery(sql);
        bind(query, params);
        Object value = query.getSingleResult();
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 取单个可空数值（如 AVG），无值返回 null。 */
    public Double nullableScalar(String sql, Map<String, Object> params) {
        assertSelect(sql);
        Query query = entityManager.createNativeQuery(sql);
        bind(query, params);
        Object value = query.getSingleResult();
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private void bind(Query query, Map<String, Object> params) {
        if (params == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }
    }

    private void assertSelect(String sql) {
        String normalized = sql.stripLeading().toUpperCase();
        if (!(normalized.startsWith("SELECT") || normalized.startsWith("WITH"))) {
            throw new IllegalArgumentException("MCP 只允许只读查询");
        }
    }
}
