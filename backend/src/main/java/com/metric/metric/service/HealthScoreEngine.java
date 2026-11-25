package com.metric.metric.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.config.service.ConfigService;
import com.metric.metric.entity.MetricHealthRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 健康度算分引擎（纯算法，不碰数据库写入）。
 *
 * <p>三态互斥，由规则自动推导：
 * <ol>
 *   <li><b>不计分（白名单）</b>：{@code exempt=1}，不出分、不进聚合，页面显示“不计分/仅观察”；</li>
 *   <li><b>人工基线</b>：{@code baseline_status='confirmed'} 且有基线值，按偏离基线的程度扣分；</li>
 *   <li><b>动态基线</b>：其余指标，按近 window_days 天历史值的稳健偏离度（中位数 + MAD）打分。</li>
 * </ol>
 *
 * <p><b>为什么用中位数 + MAD 而不是均值 + 标准差</b>：指标值普遍是重尾分布（少量超长耗时、
 * 偶发大批异常），均值和标准差会被极端值带偏，导致“平时 90 分、一出事故后基线被抬高，
 * 第二天反而满分”。中位数和 MAD 对极端值不敏感，更接近“业务心里的正常水平”。
 *
 * <p><b>为什么冷启动不出分</b>：样本不足时任何结果都是噪声。这里返回“基线学习中”而不是给 0 分，
 * 避免把“还没数据”误判成“不健康”，聚合时排除并计入覆盖率。
 *
 * <p><b>为什么建议基线不参与算分</b>：历史阈值没有逐条业务确认，直接当正式基线会违反
 * “未确认的阈值不得当规则”的约束。建议值只在页面展示，人工「采纳」后置 confirmed 才生效。
 */
@Service
public class HealthScoreEngine {

    /** 规则引擎版本号，写入 metric_health_history.algorithm_version，用于新旧对比与回滚 */
    public static final String ALGORITHM_VERSION = "rule-v1";

    public static final String MODE_MANUAL = "manual";
    public static final String MODE_DYNAMIC = "dynamic";
    public static final String MODE_EXEMPT = "exempt";

    public static final String STATE_SCORED = "scored";
    public static final String STATE_LEARNING = "learning";
    public static final String STATE_NO_DATA = "no_data";
    public static final String STATE_EXEMPT = "exempt";

    public static final String DIRECTION_LOWER = "lower_better";
    public static final String DIRECTION_HIGHER = "higher_better";
    public static final String DIRECTION_RANGE = "range";
    public static final String DIRECTION_ZERO = "zero_tolerance";

    /** 动态基线打分曲线：z ≤ 2 满分；2~6 线性降到 0；≥ 6 为 0 */
    private static final double Z_FULL_SCORE = 2.0;
    private static final double Z_ZERO_SCORE = 6.0;

    /** 稳健标准差的换算系数：正态分布下 σ ≈ 1.4826 × MAD */
    private static final double MAD_TO_SIGMA = 1.4826;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfigService configService;

    /** 一次历史样本 */
    public record Sample(LocalDateTime recordTime, BigDecimal value, String origin) {}

    /** 全局算分口径（全部来自 system_config，前后端共用，禁止前端另写 70/90） */
    public record Settings(int alarmThreshold, int warningThreshold, int staleDays, int minDays,
                           int minSamples, boolean mockFallback, String sampleMode,
                           double alarmRatio, double warningRatio) {

        public static Settings load(ConfigService config) {
            return new Settings(
                    config.getConfigLong("metric.health.alarm-threshold", 60L).intValue(),
                    config.getConfigLong("metric.health.warning-threshold", 80L).intValue(),
                    config.getConfigLong("metric.health.stale-days", 7L).intValue(),
                    config.getConfigLong("metric.health.dynamic.min-days", 3L).intValue(),
                    config.getConfigLong("metric.health.dynamic.min-samples", 30L).intValue(),
                    Boolean.parseBoolean(config.getConfigValue("metric.health.dynamic.mock-fallback", "true")),
                    config.getConfigValue("metric.health.baseline.sample-mode", "collected"),
                    parseDouble(config.getConfigValue("metric.health.aggregation.alarm-ratio", "0.20"), 0.20),
                    parseDouble(config.getConfigValue("metric.health.aggregation.warning-ratio", "0.30"), 0.30));
        }

        private static double parseDouble(String raw, double fallback) {
            try {
                return Double.parseDouble(raw.trim());
            } catch (RuntimeException e) {
                return fallback;
            }
        }
    }

    /**
     * 一次算分结果。
     *
     * @param mode         manual/dynamic/exempt；不出分时也带上推导出的模式，便于页面解释
     * @param score        分数；null 表示不出分（学习中/缺数/不计分）
     * @param learnState   scored/learning/no_data/exempt
     * @param traceJson    算分过程 JSON，解释弹窗直接读取，前端不再复刻公式
     */
    public record Outcome(String mode, Integer score, String status, BigDecimal baselineUsed,
                          BigDecimal deviation, Integer sampleCount, String dataOrigin,
                          BigDecimal currentValue, String learnState, String message, String traceJson) {

        public boolean scored() {
            return score != null;
        }
    }

    public Settings settings() {
        return Settings.load(configService);
    }

    public String statusOf(Integer score, Settings settings) {
        if (score == null) return null;
        if (score < settings.alarmThreshold()) return "alarm";
        if (score < settings.warningThreshold()) return "warning";
        return "normal";
    }

    /** 同上，但入参是未取整的分数，避免先四舍五入到整数造成 59.6 被判成 60 分档。 */
    public String statusOf(BigDecimal score, Settings settings) {
        if (score == null) return null;
        if (score.compareTo(BigDecimal.valueOf(settings.alarmThreshold())) < 0) return "alarm";
        if (score.compareTo(BigDecimal.valueOf(settings.warningThreshold())) < 0) return "warning";
        return "normal";
    }

    // ==================================================================
    // 主入口
    // ==================================================================

    /**
     * 对单个指标算分。
     *
     * @param rule          规则（可为 null，视为默认动态基线 + 越小越好）
     * @param currentValue  最新指标值；null 表示缺数
     * @param currentOrigin 最新值的数据来源 collected/mock/import/unknown
     * @param samples       历史样本（调用方按 window_days 预筛，方法内再按 origin 细分）
     * @param now           计算时刻
     */
    public Outcome evaluate(MetricHealthRule rule, BigDecimal currentValue, String currentOrigin,
                            List<Sample> samples, LocalDateTime now, Settings settings) {
        MetricHealthRule effective = rule != null ? rule : defaultRule();

        if (effective.isExempt()) {
            return new Outcome(MODE_EXEMPT, null, null, null, null, 0, currentOrigin, currentValue,
                    STATE_EXEMPT, "该指标在健康度规则里被标记为「不计分」，只观察不参与聚合",
                    trace(effective, Map.of("algorithmVersion", ALGORITHM_VERSION, "mode", MODE_EXEMPT,
                            "exemptReason", Objects.toString(effective.getExemptReason(), "未填写原因"),
                            "note", "不计分 ≠ 不监控：指标值照常展示，指标值告警照常生效")));
        }

        if (currentValue == null) {
            return new Outcome(MODE_DYNAMIC, null, null, null, null,
                    samples == null ? 0 : samples.size(), currentOrigin, null,
                    STATE_NO_DATA, "没有可用指标值（未采集或已停更），不出分",
                    trace(effective, Map.of("algorithmVersion", ALGORITHM_VERSION, "mode", MODE_DYNAMIC,
                            "learnState", STATE_NO_DATA, "note", "缺数指标计入覆盖率分母，不计入分子")));
        }

        if (effective.hasConfirmedBaseline()) {
            return manual(effective, currentValue, currentOrigin, samples, settings);
        }
        return dynamic(effective, currentValue, currentOrigin, samples, now, settings);
    }

    private MetricHealthRule defaultRule() {
        MetricHealthRule rule = new MetricHealthRule();
        rule.setMetricId(0L);
        rule.setExempt(0);
        rule.setDirection(DIRECTION_LOWER);
        rule.setDirectionSource("auto");
        rule.setTolerance(BigDecimal.ZERO);
        rule.setWindowDays(90);
        rule.setMinSamples(30);
        rule.setSeasonality("none");
        rule.setBaselineStatus("none");
        rule.setStatus(1);
        rule.setVersion(1);
        return rule;
    }

    // ==================================================================
    // 人工基线
    // ==================================================================

    private Outcome manual(MetricHealthRule rule, BigDecimal value, String origin,
                           List<Sample> samples, Settings settings) {
        BigDecimal baseline = rule.getBaselineValue();
        BigDecimal tolerance = rule.getTolerance() != null ? rule.getTolerance() : BigDecimal.ZERO;
        BigDecimal penalty = rule.getPenaltyValue();
        String direction = normalizeDirection(rule.getDirection());

        List<String> steps = new ArrayList<>();
        steps.add("模式：人工基线（基线值 " + num(baseline) + (tolerance.signum() > 0 ? "，宽容区 ±" + num(tolerance) : "，无宽容区") + "）");
        steps.add("当前值：" + num(value));

        double score;
        BigDecimal deviation;
        String formula;

        switch (direction) {
            case DIRECTION_HIGHER -> {
                // 越大越好：值 ≥ 基线-宽容区 得 100，跌到 penalty_value 得 0
                BigDecimal good = baseline.subtract(tolerance);
                BigDecimal worst = penalty != null ? penalty : good.subtract(BigDecimal.TEN);
                if (worst.compareTo(good) >= 0) worst = good.subtract(BigDecimal.ONE);
                double ratio = clamp01(good.subtract(value).divide(good.subtract(worst), MathContext.DECIMAL64).doubleValue());
                score = 100.0 * (1.0 - ratio);
                deviation = good.subtract(value).max(BigDecimal.ZERO);
                formula = "分数 = 100 × (当前值 − 扣到0的值) ÷ (基线 − 扣到0的值)，夹在 0~100；值 ≥ 基线不扣分";
                steps.add("方向：越大越好；满分线 " + num(good) + "，扣到 0 分的值 " + num(worst));
                steps.add("偏离：" + (deviation.signum() <= 0 ? "未低于满分线，不扣分" : "低于满分线 " + num(deviation)));
            }
            case DIRECTION_RANGE -> {
                BigDecimal min = rule.getRangeMin();
                BigDecimal max = rule.getRangeMax();
                if (min == null || max == null || min.compareTo(max) >= 0) {
                    // 区间没配全就退回“越小越好”，并在 trace 里说明，避免静默给出误导分数
                    return manualLower(rule, value, origin, settings, steps, formulaNote());
                }
                if (value.compareTo(min) >= 0 && value.compareTo(max) <= 0) {
                    score = 100.0;
                    deviation = BigDecimal.ZERO;
                } else {
                    BigDecimal width = max.subtract(min);
                    BigDecimal out = value.compareTo(max) > 0 ? value.subtract(max) : min.subtract(value);
                    deviation = out;
                    score = 100.0 * (1.0 - clamp01(out.divide(width, MathContext.DECIMAL64).doubleValue()));
                }
                formula = "分数 = 100 × (1 − 出界量 ÷ 区间宽度)，落在 [区间下限, 区间上限] 内为满分";
                steps.add("方向：区间型；目标区间 [" + num(min) + ", " + num(max) + "]");
                steps.add(deviation.signum() == 0 ? "当前值落在目标区间内 → 100 分" : "当前值出界 " + num(deviation));
            }
            case DIRECTION_ZERO -> {
                List<Tier> tiers = parseTiers(rule.getTiers());
                if (tiers.isEmpty()) {
                    tiers = defaultZeroTiers();
                    steps.add("档位表未配置，使用系统默认档位（页面可改）");
                }
                Tier hit = null;
                for (Tier tier : tiers) {
                    if (value.compareTo(tier.max()) <= 0) {
                        hit = tier;
                        break;
                    }
                }
                score = hit == null ? 0.0 : hit.score();
                deviation = value;
                formula = "零容忍：按人工配置的档位表命中分数（0 → 100 分，异常越多分越低）";
                steps.add("方向：零容忍（必须为 0）；命中的档位：" + (hit == null ? "超出最高档 → 0 分" : "≤ " + num(hit.max()) + " → " + (int) score + " 分"));
            }
            default -> {
                return manualLower(rule, value, origin, settings, steps, formulaNote());
            }
        }

        int finalScore = (int) Math.round(Math.max(0, Math.min(100, score)));
        Map<String, Object> trace = baseTrace(rule, MODE_MANUAL);
        trace.put("direction", direction);
        trace.put("baseline", baseline);
        trace.put("tolerance", tolerance);
        trace.put("penalty", penalty);
        trace.put("inputValue", value);
        trace.put("deviation", deviation);
        trace.put("formula", formula);
        trace.put("steps", steps);
        trace.put("note", "人工基线来自人工配置（baseline_status=confirmed），不经历史统计");
        trace.put("sampleCount", samples == null ? 0 : samples.size());

        return new Outcome(MODE_MANUAL, finalScore, statusOf(finalScore, settings), baseline,
                deviation.setScale(4, RoundingMode.HALF_UP), samples == null ? 0 : samples.size(),
                origin, value, STATE_SCORED, "按人工基线扣分", trace(rule, trace));
    }

    private String formulaNote() {
        return "区间参数未配全，已按「越小越好」计算，请在规则配置页补齐区间";
    }

    private Outcome manualLower(MetricHealthRule rule, BigDecimal value, String origin, Settings settings,
                                List<String> steps, String note) {
        BigDecimal baseline = rule.getBaselineValue();
        BigDecimal tolerance = rule.getTolerance() != null ? rule.getTolerance() : BigDecimal.ZERO;
        BigDecimal penalty = rule.getPenaltyValue();

        BigDecimal good = baseline.add(tolerance);
        BigDecimal worst = penalty != null ? penalty : good.multiply(BigDecimal.valueOf(2));
        if (worst.compareTo(good) <= 0) worst = good.add(BigDecimal.ONE);

        double ratio = clamp01(value.subtract(good).divide(worst.subtract(good), MathContext.DECIMAL64).doubleValue());
        int score = (int) Math.round(100.0 * (1.0 - ratio));
        score = Math.max(0, Math.min(100, score));

        steps.add("方向：越小越好；满分线 " + num(good) + "（基线 " + num(baseline) + " + 宽容区 " + num(tolerance) + "），扣到 0 分的值 " + num(worst));
        steps.add(value.compareTo(good) <= 0
                ? "当前值未超过满分线 → 不扣分"
                : "当前值超出满分线 " + num(value.subtract(good)) + " → 按比例扣分");

        Map<String, Object> trace = baseTrace(rule, MODE_MANUAL);
        trace.put("direction", DIRECTION_LOWER);
        trace.put("baseline", baseline);
        trace.put("tolerance", tolerance);
        trace.put("penalty", worst);
        trace.put("inputValue", value);
        trace.put("deviation", value.subtract(good).max(BigDecimal.ZERO));
        trace.put("formula", "分数 = 100 × (扣到0的值 − 当前值) ÷ (扣到0的值 − 基线)，夹在 0~100；当前值 ≤ 基线+宽容区时不扣分");
        trace.put("steps", steps);
        if (note != null) trace.put("warning", note);
        trace.put("note", "人工基线来自人工配置（baseline_status=confirmed），不经历史统计");

        return new Outcome(MODE_MANUAL, score, statusOf(score, settings), baseline,
                value.subtract(good).max(BigDecimal.ZERO), null, origin, value, STATE_SCORED, "按人工基线扣分", trace(rule, trace));
    }

    // ==================================================================
    // 动态基线
    // ==================================================================

    private Outcome dynamic(MetricHealthRule rule, BigDecimal value, String origin,
                            List<Sample> samples, LocalDateTime now, Settings settings) {
        int windowDays = rule.getWindowDays() != null && rule.getWindowDays() > 0 ? rule.getWindowDays() : 90;
        int minSamples = rule.getMinSamples() != null && rule.getMinSamples() > 0
                ? rule.getMinSamples() : settings.minSamples();
        LocalDateTime from = now.minusDays(windowDays);

        List<Sample> inWindow = samples == null ? List.of() : samples.stream()
                .filter(s -> s.recordTime() != null && !s.recordTime().isBefore(from) && s.value() != null)
                .toList();

        // 动态基线优先只用真实采集数据；真实数据不够时才按配置兜底样例数据，
        // 并在 trace / 页面明确标注“含样例数据”，不让样例数据冒充真实基线。
        List<Sample> collected = inWindow.stream().filter(s -> "collected".equals(s.origin())).toList();
        boolean includeMock = settings.sampleMode() != null && settings.sampleMode().contains("mock");
        List<Sample> usable;
        String sampleFrom;
        if (includeMock) {
            // 样例/演练库专用口径：真实采集样本太少（例如只有 7 天且大量 0 值），
            // 明确切换成“真实采集 + 样例数据”，trace 里始终标注，生产保持默认 collected。
            usable = inWindow.stream().filter(s -> !"unknown".equals(s.origin())).toList();
            sampleFrom = collected.size() == usable.size() ? "collected" : "collected+mock";
        } else {
            usable = collected;
            sampleFrom = "collected";
        }
        if (!includeMock && collected.size() < minSamples && settings.mockFallback()) {
            List<Sample> nonUnknown = inWindow.stream()
                    .filter(s -> !"unknown".equals(s.origin()))
                    .toList();
            if (nonUnknown.size() > collected.size()) {
                usable = nonUnknown;
                sampleFrom = "collected+mock";
            }
        }

        // 季节性：按工作日/周末分组，组内样本够用时只用同组样本
        String seasonality = rule.getSeasonality() == null ? "none" : rule.getSeasonality();
        boolean weekend = isWeekend(now);
        List<Sample> seasonal = usable;
        if ("workday_weekend".equals(seasonality)) {
            List<Sample> same = usable.stream().filter(s -> isWeekend(s.recordTime()) == weekend).toList();
            if (same.size() >= minSamples) seasonal = same;
        } else if ("weekday".equals(seasonality)) {
            List<Sample> same = usable.stream().filter(s -> s.recordTime().getDayOfWeek() == now.getDayOfWeek()).toList();
            if (same.size() >= minSamples) seasonal = same;
        }

        List<String> steps = new ArrayList<>();
        steps.add("模式：动态基线（近 " + windowDays + " 天历史值，样本来源：" + ("collected".equals(sampleFrom) ? "真实采集" : "真实采集 + 样例数据") + "）");
        steps.add("当前值：" + num(value));

        if (seasonal.size() < minSamples) {
            Map<String, Object> trace = baseTrace(rule, MODE_DYNAMIC);
            trace.put("learnState", STATE_LEARNING);
            trace.put("sampleCount", seasonal.size());
            trace.put("requiredSamples", minSamples);
            trace.put("requiredDays", settings.minDays());
            trace.put("sampleFrom", sampleFrom);
            trace.put("inputValue", value);
            trace.put("steps", List.of("样本数 " + seasonal.size() + " < 冷启动门槛 " + minSamples + "，暂不出分"));
            trace.put("note", "基线学习中：样本不足时不出分，聚合时排除并计入覆盖率，不拿 0 分冒充异常");
            return new Outcome(MODE_DYNAMIC, null, null, null, null, seasonal.size(), origin, value,
                    STATE_LEARNING, "基线学习中（样本 " + seasonal.size() + "/" + minSamples + "）", trace(rule, trace));
        }

        long spanDays = Duration.between(
                seasonal.stream().map(Sample::recordTime).min(Comparator.naturalOrder()).orElse(now),
                seasonal.stream().map(Sample::recordTime).max(Comparator.naturalOrder()).orElse(now)).toDays();
        if (spanDays < settings.minDays()) {
            Map<String, Object> trace = baseTrace(rule, MODE_DYNAMIC);
            trace.put("learnState", STATE_LEARNING);
            trace.put("sampleCount", seasonal.size());
            trace.put("spanDays", spanDays);
            trace.put("requiredDays", settings.minDays());
            trace.put("sampleFrom", sampleFrom);
            trace.put("steps", List.of("样本时间跨度 " + spanDays + " 天 < 冷启动门槛 " + settings.minDays() + " 天，暂不出分"));
            trace.put("note", "基线学习中：跨度太短无法代表正常波动");
            return new Outcome(MODE_DYNAMIC, null, null, null, null, seasonal.size(), origin, value,
                    STATE_LEARNING, "基线学习中（跨度 " + spanDays + "/" + settings.minDays() + " 天）", trace(rule, trace));
        }

        List<Double> values = seasonal.stream().map(s -> s.value().doubleValue()).sorted().toList();
        double median = median(values);
        double mad = median(values.stream().map(v -> Math.abs(v - median)).sorted().toList());
        double sigma = MAD_TO_SIGMA * mad;
        boolean lowVariance = sigma < 1e-9;
        if (lowVariance) {
            // 历史几乎恒定（例如异常次数一直是 0）时 MAD=0，直接除会得到无穷大。
            // 用「中位数量级的 5%，且不小于 0.5」兜底，让“从 0 变成 3”这类变化仍可解释地扣分。
            sigma = Math.max(Math.abs(median) * 0.05, 0.5);
        }

        String direction = normalizeDirection(rule.getDirection());
        double z;
        if (DIRECTION_HIGHER.equals(direction)) {
            z = (median - value.doubleValue()) / sigma;
        } else if (DIRECTION_RANGE.equals(direction)) {
            z = Math.abs(value.doubleValue() - median) / sigma;
        } else {
            z = (value.doubleValue() - median) / sigma;
        }

        double rawScore = z <= Z_FULL_SCORE ? 100.0
                : z >= Z_ZERO_SCORE ? 0.0
                : 100.0 * (Z_ZERO_SCORE - z) / (Z_ZERO_SCORE - Z_FULL_SCORE);
        int score = (int) Math.round(Math.max(0, Math.min(100, rawScore)));

        steps.add("中位数 " + num(median) + "，MAD " + num(mad) + "，稳健标准差 σ̂ " + num(sigma));
        steps.add("偏离度 z = " + num(z) + "（" + (z <= 0 ? "处于或优于正常水平" : "偏离正常水平 " + num(z) + " 个 σ̂") + "）");
        steps.add("折算：z ≤ 2 → 100；2 < z < 6 线性降到 0；z ≥ 6 → 0");

        Map<String, Object> trace = baseTrace(rule, MODE_DYNAMIC);
        trace.put("direction", direction);
        trace.put("learnState", STATE_SCORED);
        trace.put("inputValue", value);
        trace.put("median", round(median, 4));
        trace.put("mad", round(mad, 4));
        trace.put("sigma", round(sigma, 4));
        trace.put("z", round(z, 4));
        trace.put("lowVariance", lowVariance);
        trace.put("sampleCount", seasonal.size());
        trace.put("spanDays", spanDays);
        trace.put("windowDays", windowDays);
        trace.put("sampleFrom", sampleFrom);
        trace.put("seasonality", seasonality);
        trace.put("formula", "中位数 + MAD 稳健基线：z = (值 − 中位数) ÷ σ̂，σ̂ = 1.4826 × MAD；z ≤ 2 → 100，2~6 线性降到 0，≥ 6 → 0");
        trace.put("steps", steps);
        trace.put("note", "动态基线只用真实采集数据；" + ("collected".equals(sampleFrom) ? "当前样本全部为真实采集" : "真实样本不足，已按配置兜底样例数据并标注"));

        return new Outcome(MODE_DYNAMIC, score, statusOf(score, settings),
                BigDecimal.valueOf(median).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(z).setScale(4, RoundingMode.HALF_UP), seasonal.size(), origin, value,
                STATE_SCORED, "按近 " + windowDays + " 天稳健基线打分", trace(rule, trace));
    }

    // ==================================================================
    // 工具
    // ==================================================================

    public record Tier(BigDecimal max, double score) {}

    /** 解析人工配置的档位表 JSON；格式非法时返回空表（调用方退回默认档位或人工基线） */
    public List<Tier> parseTiers(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            List<Tier> tiers = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object max = row.get("max");
                Object score = row.get("score");
                if (max == null || score == null) continue;
                tiers.add(new Tier(new BigDecimal(max.toString()), Double.parseDouble(score.toString())));
            }
            tiers.sort(Comparator.comparing(Tier::max));
            return tiers;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Tier> defaultZeroTiers() {
        return List.of(
                new Tier(BigDecimal.ZERO, 100),
                new Tier(BigDecimal.ONE, 80),
                new Tier(BigDecimal.valueOf(5), 60),
                new Tier(BigDecimal.valueOf(20), 30),
                new Tier(BigDecimal.valueOf(100), 10));
    }

    private static String normalizeDirection(String direction) {
        if (direction == null) return DIRECTION_LOWER;
        return switch (direction) {
            case DIRECTION_HIGHER, DIRECTION_RANGE, DIRECTION_ZERO, DIRECTION_LOWER -> direction;
            default -> DIRECTION_LOWER;
        };
    }

    private Map<String, Object> baseTrace(MetricHealthRule rule, String mode) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("algorithmVersion", ALGORITHM_VERSION);
        trace.put("mode", mode);
        trace.put("metricId", rule.getMetricId());
        trace.put("ruleVersion", rule.getVersion() == null ? 1 : rule.getVersion());
        return trace;
    }

    private String trace(MetricHealthRule rule, Map<String, Object> trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException e) {
            // trace 只是解释性数据，序列化失败不能影响算分主流程
            return "{\"algorithmVersion\":\"" + ALGORITHM_VERSION + "\",\"traceError\":\"序列化失败\"}";
        }
    }

    private static double median(List<Double> sorted) {
        if (sorted.isEmpty()) return 0;
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static boolean isWeekend(LocalDateTime time) {
        DayOfWeek day = time.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static BigDecimal round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private static String num(BigDecimal value) {
        if (value == null) return "-";
        return num(value.doubleValue());
    }

    private static String num(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
