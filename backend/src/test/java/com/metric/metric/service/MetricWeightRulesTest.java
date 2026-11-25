package com.metric.metric.service;

import com.metric.metric.entity.MetricHealthHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricWeightRulesTest {
    @Test
    void distributesRemaindersDeterministicallyAndPreservesTheRequestedTotal() {
        var equal = Map.of(1L, BigDecimal.ONE, 2L, BigDecimal.ONE, 3L, BigDecimal.ONE);
        assertEquals(Map.of(1L, 34, 2L, 33, 3L, 33), MetricWeightShares.distribute(equal, 100));

        var original = Map.of(1L, new BigDecimal("0.1"), 2L, new BigDecimal("0.3"));
        assertEquals(Map.of(1L, 13, 2L, 37), MetricWeightShares.distribute(original, 50));
        assertEquals(Map.of(1L, 2, 2L, 1), MetricWeightShares.distribute(Map.of(1L, BigDecimal.ZERO, 2L, BigDecimal.ZERO), 3));
        assertThrows(IllegalArgumentException.class, () -> MetricWeightShares.distribute(Map.of(), 1));
    }

    @Test
    void monthEdgesFollowMonthLengthAndMiddleIsTheComplement() {
        var busy = settings("month_edges", null, null, 5, 5);
        var quiet = settings("month_middle", null, null, 5, 5);
        assertTrue(MetricWeightScenarioService.matches(busy, date("2026-02-05")));
        assertFalse(MetricWeightScenarioService.matches(busy, date("2026-02-06")));
        assertTrue(MetricWeightScenarioService.matches(quiet, date("2026-02-23")));
        assertFalse(MetricWeightScenarioService.matches(quiet, date("2026-02-24")));
        assertEquals(date("2026-02-24"), MetricWeightScenarioService.nextStart(busy, date("2026-02-04")));
    }

    @Test
    void supportsInclusiveDateRangesAndAnnualWindowsAcrossNewYear() {
        var annual = settings("annual_range", date("2000-12-29"), date("2000-01-03"), null, null);
        assertTrue(MetricWeightScenarioService.matches(annual, date("2027-01-03")));
        assertFalse(MetricWeightScenarioService.matches(annual, date("2027-01-04")));
        assertEquals(date("2027-12-29"), MetricWeightScenarioService.nextStart(annual, date("2027-01-02")));

        var nationalDay = settings("date_range", date("2026-10-01"), date("2026-10-07"), null, null);
        assertTrue(MetricWeightScenarioService.matches(nationalDay, date("2026-10-07")));
        assertFalse(MetricWeightScenarioService.matches(nationalDay, date("2026-10-08")));
        assertNull(MetricWeightScenarioService.nextStart(nationalDay, date("2026-10-08")));
    }

    @Test
    void normalizesScenariosWithOnlyTheMetricsThatHaveScores() {
        var first = observation(11L, 40);
        var second = observation(12L, 100);
        var weights = Map.of(11L, new BigDecimal("0.50"), 12L, new BigDecimal("0.50"), 13L, BigDecimal.ONE);
        var adjustment = new MetricWeightScenarioService.Adjustment(7L, 3, new BigDecimal("2.00"));
        var result = MetricValueCalcService.weightedGroup("小类", BigDecimal.ONE, List.of(first, second),
                weights, Map.of(11L, adjustment));

        assertEquals(0, new BigDecimal("60").compareTo(result.score()));
        assertEquals(0, new BigDecimal("1.50").compareTo(result.adjustedTotal()));
        assertEquals(adjustment, result.metrics().get(0).scenario());
        assertEquals(new BigDecimal("0.50"), result.metrics().get(0).baseWeight());
        assertEquals(2, result.metrics().size());
    }

    private static MetricHealthHistory observation(long id, int score) {
        var observation = new MetricHealthHistory();
        observation.setMetricId(id);
        observation.setHealthScore(score);
        return observation;
    }

    private static MetricWeightScenarioService.Settings settings(String type, LocalDate start, LocalDate end,
                                                                 Integer first, Integer last) {
        return new MetricWeightScenarioService.Settings("场景", true, 0, type, start, end, first, last,
                List.of(11L), BigDecimal.ONE);
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(value);
    }
}
