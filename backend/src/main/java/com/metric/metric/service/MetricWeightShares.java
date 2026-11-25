package com.metric.metric.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

final class MetricWeightShares {
    private MetricWeightShares() {}

    // Largest remainder in percentage points. Ties are resolved by metric ID.
    static Map<Long, Integer> distribute(Map<Long, BigDecimal> weights, int points) {
        Map<Long, Integer> shares = new LinkedHashMap<>();
        if (weights.isEmpty()) {
            if (points != 0) throw new IllegalArgumentException("本组批准后的权重总和必须为 100%");
            return shares;
        }

        BigDecimal total = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean equal = total.signum() == 0;
        BigDecimal denominator = equal ? BigDecimal.valueOf(weights.size()) : total;
        Map<Long, BigDecimal> remainders = new LinkedHashMap<>();
        weights.keySet().stream().sorted().forEach(id -> {
            BigDecimal numerator = (equal ? BigDecimal.ONE : weights.get(id)).multiply(BigDecimal.valueOf(points));
            BigDecimal[] quotient = numerator.divideAndRemainder(denominator);
            shares.put(id, quotient[0].intValueExact());
            remainders.put(id, quotient[1]);
        });
        int remaining = points - shares.values().stream().mapToInt(Integer::intValue).sum();
        remainders.keySet().stream()
                .sorted(Comparator.<Long, BigDecimal>comparing(remainders::get).reversed().thenComparingLong(id -> id))
                .limit(remaining).forEach(id -> shares.compute(id, (key, value) -> value + 1));
        return shares;
    }
}
