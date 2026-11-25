package com.metric.metric.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MetricWeightScenarioService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public record Settings(String name, boolean enabled, int priority, String scheduleType,
                           LocalDate startsOn, LocalDate endsOn, Integer firstDays, Integer lastDays,
                           List<Long> metricIds, BigDecimal multiplier) {}
    public record Scenario(long id, int revision, Settings settings, boolean active, LocalDate nextStart) {}
    public record Adjustment(long scenarioId, int revision, BigDecimal multiplier) {}
    public record Save(Integer revision, Settings settings) {}

    public List<Scenario> list() {
        return jdbc.query("SELECT * FROM metric_weight_scenario ORDER BY priority DESC, id", scenarioMapper());
    }

    @Transactional
    public long save(Long id, Save request, long reviewerId) {
        if (request == null || request.settings() == null) throw invalid("请填写场景配置");
        Settings settings = request.settings();
        validate(settings);
        int revision = 1;
        if (id == null) {
            var keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO metric_weight_scenario(revision, name, enabled, priority, schedule_type,
                            starts_on, ends_on, first_days, last_days, metric_ids, multiplier)
                        VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                Object[] fields = fields(settings);
                for (int i = 0; i < fields.length; i++) statement.setObject(i + 1, fields[i]);
                return statement;
            }, keys);
            id = Objects.requireNonNull(keys.getKey()).longValue();
        } else {
            if (request.revision() == null) throw invalid("请刷新场景版本");
            var arguments = new ArrayList<>(Arrays.asList(fields(settings)));
            arguments.add(id);
            arguments.add(request.revision());
            int updated = jdbc.update("""
                    UPDATE metric_weight_scenario SET revision = revision + 1, name = ?, enabled = ?, priority = ?,
                        schedule_type = ?, starts_on = ?, ends_on = ?, first_days = ?, last_days = ?, metric_ids = ?,
                        multiplier = ? WHERE id = ? AND revision = ?
                    """, arguments.toArray());
            if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "场景已变更，请刷新后编辑");
            revision = request.revision() + 1;
        }
        jdbc.update("""
                INSERT INTO metric_weight_scenario_revision(scenario_id, revision, operator_id, changed_at, snapshot)
                VALUES (?, ?, ?, ?, ?)
                """, id, revision, reviewerId, LocalDateTime.now(ZONE), serialize(settings));
        return id;
    }

    // For each metric, the highest priority active rule wins. Equal priorities prefer the older ID.
    public Map<Long, Adjustment> adjustments(LocalDate date) {
        var result = new HashMap<Long, Adjustment>();
        for (Scenario scenario : jdbc.query(
                "SELECT * FROM metric_weight_scenario WHERE enabled = TRUE ORDER BY priority DESC, id", scenarioMapper())) {
            var settings = scenario.settings();
            if (!matches(settings, date)) continue;
            var adjustment = new Adjustment(scenario.id(), scenario.revision(), settings.multiplier());
            for (long metricId : settings.metricIds()) result.putIfAbsent(metricId, adjustment);
        }
        return result;
    }

    static boolean matches(Settings s, LocalDate date) {
        return switch (s.scheduleType()) {
            case "date_range" -> !date.isBefore(s.startsOn()) && !date.isAfter(s.endsOn());
            case "annual_range" -> {
                MonthDay start = MonthDay.from(s.startsOn());
                MonthDay end = MonthDay.from(s.endsOn());
                MonthDay day = MonthDay.from(date);
                boolean afterStart = day.compareTo(start) >= 0;
                boolean beforeEnd = day.compareTo(end) <= 0;
                yield start.compareTo(end) <= 0 ? afterStart && beforeEnd : afterStart || beforeEnd;
            }
            case "month_edges", "month_middle" -> {
                boolean edge = date.getDayOfMonth() <= s.firstDays()
                        || date.getDayOfMonth() > date.lengthOfMonth() - s.lastDays();
                yield s.scheduleType().equals("month_edges") == edge;
            }
            default -> false;
        };
    }

    static LocalDate nextStart(Settings s, LocalDate today) {
        if (!s.enabled()) return null;
        if ("date_range".equals(s.scheduleType())) return s.startsOn().isAfter(today) ? s.startsOn() : null;
        // A recurring February 29 start may be eight years away across a non-leap century year.
        boolean yesterday = matches(s, today);
        for (LocalDate day = today.plusDays(1); !day.isAfter(today.plusYears(8)); day = day.plusDays(1)) {
            boolean todayMatches = matches(s, day);
            if (todayMatches && !yesterday) return day;
            yesterday = todayMatches;
        }
        return null;
    }

    private RowMapper<Scenario> scenarioMapper() {
        return (rs, row) -> {
            try {
                Settings settings = new Settings(rs.getString("name"), rs.getBoolean("enabled"), rs.getInt("priority"),
                        rs.getString("schedule_type"), rs.getObject("starts_on", LocalDate.class),
                        rs.getObject("ends_on", LocalDate.class), rs.getObject("first_days", Integer.class),
                        rs.getObject("last_days", Integer.class), json.readValue(rs.getString("metric_ids"), new TypeReference<List<Long>>() {}),
                        rs.getBigDecimal("multiplier"));
                LocalDate today = LocalDate.now(ZONE);
                return new Scenario(rs.getLong("id"), rs.getInt("revision"), settings,
                        settings.enabled() && matches(settings, today), nextStart(settings, today));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("场景指标列表无效", e);
            }
        };
    }

    private Object[] fields(Settings s) {
        return new Object[]{s.name().trim(), s.enabled(), s.priority(), s.scheduleType(),
                s.startsOn() == null ? null : Date.valueOf(s.startsOn()),
                s.endsOn() == null ? null : Date.valueOf(s.endsOn()),
                s.firstDays(), s.lastDays(), serialize(s.metricIds()), s.multiplier()};
    }

    private void validate(Settings s) {
        if (s.name() == null || s.name().isBlank() || s.name().trim().length() > 100
                || s.priority() < 0 || s.priority() > 9999) throw invalid("请填写场景名称和 0 至 9999 的优先级");
        if (s.multiplier() == null || s.multiplier().stripTrailingZeros().scale() > 2
                || s.multiplier().compareTo(new BigDecimal("0.01")) < 0 || s.multiplier().compareTo(BigDecimal.TEN) > 0) {
            throw invalid("系数须在 0.01 至 10.00 之间，最多两位小数");
        }
        if (s.scheduleType() == null) throw invalid("请选择周期");
        switch (s.scheduleType()) {
            case "date_range", "annual_range" -> {
                if (s.startsOn() == null || s.endsOn() == null || s.firstDays() != null || s.lastDays() != null
                        || ("date_range".equals(s.scheduleType()) && s.startsOn().isAfter(s.endsOn()))) {
                    throw invalid("请核对场景起止日期");
                }
            }
            case "month_edges", "month_middle" -> {
                if (s.startsOn() != null || s.endsOn() != null || s.firstDays() == null || s.lastDays() == null
                        || s.firstDays() < 0 || s.lastDays() < 0 || s.firstDays() + (long) s.lastDays() < 1
                        || s.firstDays() + (long) s.lastDays() > 28) throw invalid("月初与月末天数合计须在 1 至 28 天之间");
            }
            default -> throw invalid("不支持的场景周期");
        }
        if (s.metricIds() == null || s.metricIds().isEmpty() || s.metricIds().size() > 1000
                || s.metricIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(s.metricIds()).size() != s.metricIds().size()) throw invalid("请选择 1 至 1000 个不同的指标");
        if (s.enabled()) {
            int count = Objects.requireNonNull(new NamedParameterJdbcTemplate(jdbc).queryForObject(
                    "SELECT COUNT(*) FROM metric_item WHERE status = 1 AND id IN (:ids)",
                    Map.of("ids", s.metricIds()), Integer.class));
            if (count != s.metricIds().size()) throw invalid("启用场景前请移除已停用或删除的指标");
        }
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存场景版本", e);
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
