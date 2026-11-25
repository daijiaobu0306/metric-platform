package com.metric.alarm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.alarm.entity.AlarmStrategy;
import com.metric.alarm.entity.StrategyRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** 持久化每个策略/对象的有效观测；重复扫描、同数据时点修订和回补不推进计数。 */
@Service
public class EffectiveObservationService {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private static final int SCAN_GRACE_MINUTES = 5;
    private static final Pattern FREQUENCY = Pattern.compile("(?:每)?(\\d+)\\s*(分钟|分|小时|时|天|日|周|星期|个月|月|年)(?:一次|/次|/周期)?");

    public record Source(LocalDateTime time, String frequency, boolean valid) {}
    public record Decision(StrategyRule alarm, StrategyRule gongdan, Map<String, Integer> counts,
                           LocalDateTime dataTime, String status) {
        public String note(boolean ticket) {
            StrategyRule rule = ticket ? gongdan : alarm;
            int count = rule == null ? 0 : counts.getOrDefault((ticket ? "g:" : "a:") + rule.getLevel(), 0);
            return "；判定口径=连续有效数据更新；连续异常=" + count + "次（要求" + (rule == null ? 0 : rule.getConsecutiveTimes())
                    + "次）；数据时间=" + dataTime + "；重复扫描不计次";
        }
    }
    public static class Seen {
        public String dataTime;
        public String receivedAt;
    }
    public static class State {
        public String configuration;
        public Map<String, Seen> sources = new TreeMap<>();
        public Map<String, Integer> counts = new HashMap<>();
        public boolean broken;
        public String status;
    }

    @Transactional(rollbackFor = Exception.class)
    public int inTransaction(Supplier<Integer> work) { return work.get(); }

    @Transactional(rollbackFor = Exception.class)
    public int evaluate(AlarmStrategy strategy, String scope, BigDecimal value, Map<String, Source> sources,
                        List<StrategyRule> alarmRules, List<StrategyRule> ticketRules, Function<Decision, Integer> action) {
        jdbc.update("INSERT IGNORE INTO alarm_observation_state(strategy_id,scope_key,state_json,updated_at) VALUES (?,?,'{}',NOW(6))", strategy.getId(), scope);
        String stored = jdbc.queryForObject("SELECT state_json FROM alarm_observation_state WHERE strategy_id=? AND scope_key=? FOR UPDATE", String.class, strategy.getId(), scope);
        State state = read(stored);
        boolean firstRead = state.configuration == null;
        Map<String, String> sourceConfiguration = new TreeMap<>();
        sources.forEach((key, source) -> sourceConfiguration.put(key, source.frequency()));
        String configuration = write(List.of(Objects.toString(strategy.getUpdateTime(), ""), alarmRules, ticketRules, sourceConfiguration));
        if (!configuration.equals(state.configuration)) { state.counts.clear(); state.configuration = configuration; }
        LocalDateTime now = LocalDateTime.now();
        boolean fresh = false, missing = sources.isEmpty() || value == null, blocked = missing;
        boolean unknownFrequency = false;
        LocalDateTime dataTime = null;
        Map<String, Seen> pendingSources = new TreeMap<>();
        for (var entry : sources.entrySet()) {
            Source source = entry.getValue();
            Seen previous = state.sources.get(entry.getKey());
            boolean valid = source.valid() && source.time() != null && !source.time().isAfter(now);
            boolean newer = valid && (previous == null || source.time().isAfter(LocalDateTime.parse(previous.dataTime)));
            LocalDateTime previousTime = previous == null ? null : LocalDateTime.parse(previous.dataTime);
            if (previous != null) {
                LocalDateTime due = next(LocalDateTime.parse(previous.receivedAt), source.frequency());
                LocalDateTime expectedData = next(previousTime, source.frequency());
                if (due != null && now.isAfter(due.plusMinutes(SCAN_GRACE_MINUTES))) { missing = true; if (!newer) blocked = true; }
                // 数据时点容差不能长到吞掉一个5分钟采样；接收时间仍允许完整5分钟扫描延迟。
                long dataGraceSeconds = expectedData == null ? 0 : Math.min(300, Math.max(1, java.time.Duration.between(previousTime, expectedData).getSeconds() / 2));
                if (newer && expectedData != null && source.time().isAfter(expectedData.plusSeconds(dataGraceSeconds))) missing = true;
            } else if (!valid) { missing = true; blocked = true; }
            if (next(now, source.frequency()) == null) unknownFrequency = true;
            if (newer) {
                fresh = true;
                Seen seen = new Seen(); seen.dataTime = source.time().toString(); seen.receivedAt = now.toString();
                pendingSources.put(entry.getKey(), seen);
                if (dataTime == null || source.time().isAfter(dataTime)) dataTime = source.time();
            }
        }
        if (missing) { state.counts.clear(); state.broken = true; }
        boolean sameDataPoint = !sources.isEmpty() && sources.entrySet().stream().allMatch(entry -> {
            Seen seen = state.sources.get(entry.getKey());
            return seen != null && entry.getValue().valid() && entry.getValue().time() != null
                    && entry.getValue().time().toString().equals(seen.dataTime);
        });
        if (!fresh && sameDataPoint && value != null) {
            // 同时点修订不能加一次；修订为正常值仍须打断连续异常。
            for (StrategyRule rule : alarmRules) if (!matches(value, rule)) state.counts.put("a:" + rule.getLevel(), 0);
            for (StrategyRule rule : ticketRules) if (!matches(value, rule)) state.counts.put("g:" + rule.getLevel(), 0);
        }
        state.status = missing ? "数据缺失或超过预期更新时间，连续计数已中断"
                : unknownFrequency ? "采集频率未确认：仅按新数据计次，不推断超时缺失"
                : fresh ? "已收到新的有效数据" : "等待新数据，重复扫描不计次";
        if (!missing && sources.values().stream().anyMatch(s -> !s.valid() || s.time() == null || s.time().isAfter(now)))
            state.status = "等待有效数据时间或完整健康度输入，未增加次数";
        Decision decision = null;
        if (fresh && value != null) {
            // 失联后的第一份有效数据从1开始，不能把旧计数接续回来。
            if (state.broken) state.counts.clear();
            StrategyRule alarm = advance("a:", alarmRules, value, state.counts);
            StrategyRule ticket = advance("g:", ticketRules, value, state.counts);
            // 聚合对象若仍有其它输入缺失，不用不完整输入凑连续次数。
            boolean allPresent = !blocked && !sources.isEmpty() && sources.values().stream().allMatch(s -> s.valid() && s.time() != null && !s.time().isAfter(now));
            if (allPresent) {
                state.sources.putAll(pendingSources);
                state.broken = false;
                if (missing) state.status = "检测到数据更新间断，已从本次有效数据重新计数";
                if (firstRead) {
                    state.counts.clear();
                    state.status = "已建立当前数据基线，等待下一份新数据；历史扫描不补算";
                } else decision = new Decision(alarm, ticket, new HashMap<>(state.counts), dataTime, state.status);
            } else state.counts.clear();
        }
        jdbc.update("UPDATE alarm_observation_state SET state_json=?,updated_at=NOW(6) WHERE strategy_id=? AND scope_key=?", write(state), strategy.getId(), scope);
        return decision == null ? 0 : action.apply(decision);
    }

    private StrategyRule advance(String prefix, List<StrategyRule> rules, BigDecimal value, Map<String, Integer> counts) {
        StrategyRule highest = null;
        for (StrategyRule rule : rules) {
            String key = prefix + rule.getLevel();
            int previous = counts.getOrDefault(key, 0);
            int count = matches(value, rule) ? (previous == Integer.MAX_VALUE ? previous : previous + 1) : 0;
            counts.put(key, count);
            if (count >= Math.max(1, Objects.requireNonNullElse(rule.getConsecutiveTimes(), 1))
                    && (highest == null || rule.getLevel() > highest.getLevel())) highest = rule;
        }
        return highest;
    }
    private boolean matches(BigDecimal value, StrategyRule rule) {
        if (rule.getLevel() == null || rule.getThreshold() == null || rule.getCondition() == null) return false;
        int compared = value.compareTo(rule.getThreshold());
        return switch (rule.getCondition()) { case ">" -> compared > 0; case ">=" -> compared >= 0;
            case "<" -> compared < 0; case "<=" -> compared <= 0; case "=" -> compared == 0; default -> false; };
    }
    /** 只解析明确频率，不把未知值默认成5分钟；日/月使用日历运算。 */
    private LocalDateTime next(LocalDateTime start, String frequency) {
        if (frequency == null) return null;
        String text = frequency.trim().replace(" ", "").replace("每隔", "每");
        if (text.endsWith("一次")) text = text.substring(0, text.length() - 2);
        if (text.startsWith("1次/")) text = "1" + text.substring(3);
        text = switch (text) { case "每日", "每天", "日", "daily" -> "1天"; case "每小时", "小时", "hourly" -> "1小时";
            case "每周", "每星期", "周", "weekly" -> "1周"; case "每月", "月", "monthly" -> "1月";
            case "每年", "年", "yearly" -> "1年"; default -> text; };
        var match = FREQUENCY.matcher(text);
        if (!match.matches()) return null;
        try {
            long amount = Long.parseLong(match.group(1)); if (amount < 1) return null;
            return switch (match.group(2)) { case "分钟", "分" -> start.plusMinutes(amount); case "小时", "时" -> start.plusHours(amount);
                case "天", "日" -> start.plusDays(amount); case "周", "星期" -> start.plusWeeks(amount);
                case "个月", "月" -> start.plusMonths(amount); case "年" -> start.plusYears(amount); default -> null; };
        } catch (RuntimeException invalid) { return null; }
    }
    private State read(String text) { try { return json.readValue(text, State.class); } catch (Exception error) { throw new IllegalStateException("告警观测状态损坏，请检查后重试", error); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException("告警观测状态保存失败", error); } }

    public List<Map<String, Object>> status(Long strategyId) {
        return jdbc.queryForList("SELECT scope_key AS scopeKey,state_json AS observationState,updated_at AS updatedAt FROM alarm_observation_state WHERE strategy_id=? ORDER BY scope_key", strategyId);
    }
}
