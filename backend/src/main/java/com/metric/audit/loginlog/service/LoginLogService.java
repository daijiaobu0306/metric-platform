package com.metric.audit.loginlog.service;

import com.metric.audit.loginlog.dto.LoginLogDashboard;
import com.metric.audit.loginlog.dto.LoginLogDashboard.TrendPoint;
import com.metric.audit.loginlog.dto.LoginLogQueryRequest;
import com.metric.audit.loginlog.entity.LoginLog;
import com.metric.audit.loginlog.repository.LoginLogRepository;
import com.metric.security.entity.SysUser;
import com.metric.security.service.SessionService;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 登录日志业务层（V50/2026-07-10，2026-10-12 移植到 main）。
 *
 * <p>职责：
 * <ol>
 *   <li>写入 3 类事件：本地登录、SSO 登录、显式退出</li>
 *   <li>定时扫描超时会话（每 5min），自动标 TIMEOUT</li>
 *   <li>多维分页查询</li>
 *   <li>看板聚合（9 KPI + 2 趋势）</li>
 * </ol>
 *
 * <p>写入失败一律不抛（log.warn），保证不影响登录主流程。
 *
 * <p>TTL 来源：main 用 SessionService.getIdleTimeout()（从 system_config.session_idle_timeout 读，
 * 单位分钟，默认 20）。与分支原 24h 不同——20 分钟更严格。
 */
@Service
public class LoginLogService {

    private static final Logger log = LoggerFactory.getLogger(LoginLogService.class);

    // ===== 字段常量 =====
    public static final String SOURCE_LOCAL = "LOCAL";
    public static final String SOURCE_SSO = "SSO";
    public static final String SOURCE_LOGOUT = "LOGOUT";
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILED = "FAILED";
    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_OFFLINE = "OFFLINE";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    // ===== 失败原因常量 =====
    private static final String FAIL_USER_NOT_FOUND = "用户不存在";
    private static final String FAIL_BAD_PASSWORD = "密码错误";
    private static final String FAIL_USER_DISABLED = "账号已停用";
    private static final String FAIL_STATE_MISMATCH = "State 不匹配";
    private static final String FAIL_TOKEN_EXCHANGE = "Token 交换失败";
    private static final String FAIL_USERINFO = "用户信息解析失败";
    private static final String FAIL_UPSERT = "用户同步失败";

    private final LoginLogRepository repo;
    private final SessionService sessionService;

    public LoginLogService(LoginLogRepository repo, SessionService sessionService) {
        this.repo = repo;
        this.sessionService = sessionService;
    }

    // =================================================================
    // 写：本地登录
    // =================================================================

    /**
     * 记录本地 BCrypt 登录成功事件。{@code status=ONLINE}，后续 logout/timeout 扫描会 flip 到 OFFLINE/TIMEOUT。
     */
    public void recordLocalLoginSuccess(SysUser user, HttpServletRequest req) {
        try {
            LoginLog row = baseRow(req);
            row.setSource(SOURCE_LOCAL);
            row.setResult(RESULT_SUCCESS);
            row.setStatus(STATUS_ONLINE);
            row.setUsername(user.getUsername());
            row.setUserId(user.getId());
            repo.save(row);
        } catch (Exception e) {
            log.warn("[LoginLog] 记录本地登录成功日志失败: username={}", user.getUsername(), e);
        }
    }

    /**
     * 记录本地 BCrypt 登录用户名查不到事件（用户不存在）。
     * 写 {@code status=OFFLINE} 且 {@code logoutTime=loginTime}（无真实会话，不计入 onlineCount KPI）。
     */
    public void recordLocalLoginNotFound(String requestedUsername, HttpServletRequest req) {
        try {
            LoginLog row = baseRow(req);
            row.setSource(SOURCE_LOCAL);
            row.setResult(RESULT_FAILED);
            row.setStatus(STATUS_OFFLINE);
            row.setLogoutTime(row.getLoginTime());  // 等于 login_time → duration_minutes 保持 NULL
            row.setUsername(requestedUsername != null ? requestedUsername : "");
            row.setFailReason(FAIL_USER_NOT_FOUND);
            repo.save(row);
        } catch (Exception e) {
            log.warn("[LoginLog] 记录本地登录用户名不存在日志失败: username={}", requestedUsername, e);
        }
    }

    /** 本地登录密码错（用户已查到） */
    public void recordLocalLoginBadPassword(SysUser user, HttpServletRequest req) {
        try {
            LoginLog row = baseRow(req);
            row.setSource(SOURCE_LOCAL);
            row.setResult(RESULT_FAILED);
            row.setStatus(STATUS_ONLINE);
            row.setUsername(user.getUsername());
            row.setUserId(user.getId());
            row.setFailReason(FAIL_BAD_PASSWORD);
            repo.save(row);
        } catch (Exception e) {
            log.warn("[LoginLog] 记录本地登录失败日志失败: username={}", user.getUsername(), e);
        }
    }

    /** 本地登录账号停用 */
    public void recordLocalLoginDisabled(SysUser user, HttpServletRequest req) {
        try {
            LoginLog row = baseRow(req);
            row.setSource(SOURCE_LOCAL);
            row.setResult(RESULT_FAILED);
            row.setStatus(STATUS_ONLINE);
            row.setUsername(user.getUsername());
            row.setUserId(user.getId());
            row.setFailReason(FAIL_USER_DISABLED);
            repo.save(row);
        } catch (Exception e) {
            log.warn("[LoginLog] 记录本地登录失败日志失败: username={}", user.getUsername(), e);
        }
    }

    // =================================================================
    // 写：SSO 登录
    // =================================================================

    /**
     * SSO 成功（main 上无 JWT iat，跳过 jwt_iat 字段；sub 用 SysUser.externalId）。
     */
    public void recordSsoLoginSuccess(SysUser user, HttpServletRequest req) {
        try {
            LoginLog row = baseRow(req);
            row.setSource(SOURCE_SSO);
            row.setResult(RESULT_SUCCESS);
            row.setStatus(STATUS_ONLINE);
            row.setUsername(user.getUsername());
            row.setUserId(user.getId());
            row.setJwtSub(user.getExternalId());  // main 的 OIDC sub 存到 externalId
            repo.save(row);
        } catch (Exception e) {
            log.warn("[LoginLog] 记录 SSO 成功日志失败: username={}", user.getUsername(), e);
        }
    }

    /**
     * SSO 失败（reasonKey 取 FAIL_* 常量；subOrNull 已知则填）。
     * 写 {@code status=OFFLINE}（无真实会话），{@code logoutTime=loginTime} → duration_minutes 保持 NULL，
     * 避免污染 onlineCount KPI（详见 review I-1 / M-6）。
     */
    public void recordSsoLoginFailure(String reasonKey, String subOrNull, HttpServletRequest req) {
        try {
            LoginLog row = baseRow(req);
            row.setSource(SOURCE_SSO);
            row.setResult(RESULT_FAILED);
            row.setStatus(STATUS_OFFLINE);
            row.setLogoutTime(row.getLoginTime());  // 等于 login_time → duration_minutes 保持 NULL
            row.setUsername("");  // SSO 失败时通常 username 未知，留空
            row.setJwtSub(subOrNull);
            row.setFailReason(reasonKey);
            repo.save(row);
        } catch (Exception e) {
            log.warn("[LoginLog] 记录 SSO 失败日志失败: sub={}", subOrNull, e);
        }
    }

    // =================================================================
    // 写：显式退出
    // =================================================================

    /**
     * 把某 username 当前 ONLINE 的最新一行改为 OFFLINE。没找到就不动（兼容多设备/多窗口场景）。
     */
    public void recordLogout(String username, HttpServletRequest req) {
        if (username == null || username.isBlank()) return;
        try {
            repo.findFirstByUsernameAndStatusOrderByLoginTimeDesc(username, STATUS_ONLINE)
                .ifPresent(row -> {
                    row.setStatus(STATUS_OFFLINE);
                    row.setLogoutTime(LocalDateTime.now());
                    row.setDurationMinutes((int) Duration.between(
                            row.getLoginTime(), LocalDateTime.now()).toMinutes());
                    repo.save(row);
                });
        } catch (Exception e) {
            log.warn("[LoginLog] 记录退出日志失败: username={}", username, e);
        }
    }

    // =================================================================
    // 超时扫描
    // =================================================================

    /**
     * 每 5 分钟扫一次：status=ONLINE 且 login_time 早于 (now - TTL) 的行 → 标 TIMEOUT。
     * TTL 取自 SessionService.getIdleTimeout()（system_config.session_idle_timeout，单位分钟，默认 20）。
     */
    @Scheduled(fixedDelay = 300_000)
    public void markTimedOutSessions() {
        try {
            int ttlMinutes = Math.max(1, sessionService.getIdleTimeout());
            LocalDateTime threshold = LocalDateTime.now().minusMinutes(ttlMinutes);

            List<LoginLog> stale = repo.findByStatusAndLoginTimeBefore(STATUS_ONLINE, threshold);
            if (stale.isEmpty()) return;

            LocalDateTime now = LocalDateTime.now();
            for (LoginLog row : stale) {
                row.setStatus(STATUS_TIMEOUT);
                row.setLogoutTime(now);
                row.setDurationMinutes((int) Duration.between(row.getLoginTime(), now).toMinutes());
            }
            repo.saveAll(stale);
            log.info("[LoginLog] Marked {} sessions TIMEOUT", stale.size());
        } catch (Exception e) {
            log.warn("[LoginLog] 超时扫描失败", e);
        }
    }

    // =================================================================
    // 查询
    // =================================================================

    public Page<LoginLog> query(LoginLogQueryRequest q) {
        Specification<LoginLog> spec = (root, query1, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (q.getUsername() != null && !q.getUsername().isBlank()) {
                ps.add(cb.like(root.get("username"), "%" + q.getUsername() + "%"));
            }
            if (q.getSource() != null && !q.getSource().isBlank()) {
                ps.add(cb.equal(root.get("source"), q.getSource()));
            }
            if (q.getResult() != null && !q.getResult().isBlank()) {
                ps.add(cb.equal(root.get("result"), q.getResult()));
            }
            if (q.getStatus() != null && !q.getStatus().isBlank()) {
                ps.add(cb.equal(root.get("status"), q.getStatus()));
            }
            if (q.getClientIp() != null && !q.getClientIp().isBlank()) {
                ps.add(cb.like(root.get("clientIp"), "%" + q.getClientIp() + "%"));
            }
            if (q.getBrowserName() != null && !q.getBrowserName().isBlank()) {
                ps.add(cb.equal(root.get("browserName"), q.getBrowserName()));
            }
            if (q.getOsName() != null && !q.getOsName().isBlank()) {
                ps.add(cb.equal(root.get("osName"), q.getOsName()));
            }
            if (q.getDeviceType() != null && !q.getDeviceType().isBlank()) {
                ps.add(cb.equal(root.get("deviceType"), q.getDeviceType()));
            }
            if (q.getLoginTimeStart() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("loginTime"), q.getLoginTimeStart()));
            }
            if (q.getLoginTimeEnd() != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("loginTime"), q.getLoginTimeEnd()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return repo.findAll(spec,
                PageRequest.of(q.getPage(), q.getSize(), Sort.by(Sort.Direction.DESC, "loginTime")));
    }

    // =================================================================
    // 看板聚合
    // =================================================================

    public LoginLogDashboard getDashboard() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekStart = LocalDate.now().with(java.time.DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime last24hStart = LocalDateTime.now().minusHours(24);
        LocalDateTime last30dStart = LocalDateTime.now().minusDays(30);

        return new LoginLogDashboard(
                repo.countLoginsSince(todayStart),
                repo.countDistinctUsersSince(todayStart),
                repo.countByStatus(STATUS_ONLINE),
                repo.countDistinctUsersSince(last24hStart),
                repo.sumDurationSince(todayStart),
                repo.countDistinctUsersSince(weekStart),
                repo.sumDurationSince(weekStart),
                repo.countDistinctUsersSince(monthStart),
                repo.sumDurationSince(monthStart),
                toTrendPoints(repo.dailyTrendSince(last30dStart), 30, "d"),
                toTrendPoints(repo.hourlyTrendSince(last24hStart), 24, "h")
        );
    }

    /** 当前在线人数（单独端点用） */
    public long getOnlineCount() {
        return repo.countByStatus(STATUS_ONLINE);
    }

    // =================================================================
    // 内部工具
    // =================================================================

    /** 构建一行基础字段（IP/UA/浏览器/OS/设备/登录时间） */
    private LoginLog baseRow(HttpServletRequest req) {
        LoginLog row = new LoginLog();
        row.setLoginTime(LocalDateTime.now());
        if (req != null) {
            String ip = extractClientIp(req);
            row.setClientIp(ip);
            String ua = req.getHeader("User-Agent");
            if (ua != null) {
                row.setUserAgent(ua.length() > 500 ? ua.substring(0, 500) : ua);
                ParsedUa parsed = parseUserAgent(ua);
                row.setBrowserName(parsed.browserName());
                row.setBrowserVersion(parsed.browserVersion());
                row.setOsName(parsed.osName());
                row.setDeviceType(parsed.deviceType());
            }
        }
        return row;
    }

    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) return ip;
        return request.getRemoteAddr();
    }

    /**
     * 简单 UA 解析（不引第三方库）。解析失败返回 (空, 空, 空, desktop)。
     */
    ParsedUa parseUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return new ParsedUa("", "", "", "desktop");
        String lower = ua.toLowerCase();
        String browser = "Other", os = "Other", device = "desktop", version = "";

        // Browser（注意 Edge 含 "Chrome"，先判 Edge）
        if (lower.contains("edg/")) {
            browser = "Edge";
            version = extractAfter(lower, "edg/");
        } else if (lower.contains("chrome/") || lower.contains("crios/")) {
            browser = "Chrome";
            version = extractAfter(lower, "chrome/");
        } else if (lower.contains("firefox/") || lower.contains("fxios/")) {
            browser = "Firefox";
            version = extractAfter(lower, "firefox/");
        } else if (lower.contains("safari/") && !lower.contains("chrome/")) {
            browser = "Safari";
            version = extractAfter(lower, "version/");
        } else if (lower.contains("opr/") || lower.contains("opera/")) {
            browser = "Opera";
            version = extractAfter(lower, "opr/");
        }

        // OS
        if (lower.contains("windows")) os = "Windows";
        else if (lower.contains("mac os") || lower.contains("macintosh")) os = "macOS";
        else if (lower.contains("android")) os = "Android";
        else if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ipod")) os = "iOS";
        else if (lower.contains("linux")) os = "Linux";

        // Device
        if (lower.contains("ipad") || lower.contains("tablet")) device = "tablet";
        else if (lower.contains("mobi") || lower.contains("iphone") || lower.contains("android")) device = "mobile";

        return new ParsedUa(browser, version, os, device);
    }

    /** 提取 token 后第一个数字段（如 "edg/120.0.0.0" → "120.0.0.0"） */
    private String extractAfter(String lower, String token) {
        int idx = lower.indexOf(token);
        if (idx < 0) return "";
        int start = idx + token.length();
        int end = start;
        while (end < lower.length() && "0123456789.".indexOf(lower.charAt(end)) >= 0) end++;
        String v = lower.substring(start, end);
        return v.length() > 20 ? v.substring(0, 20) : v;
    }

    record ParsedUa(String browserName, String browserVersion, String osName, String deviceType) {}

    /**
     * 把 native query 返回的 {@code List<Object[]>} 转 {@code List<TrendPoint>}。
     * 缺失日期/小时补 0，保证图表 X 轴连续。
     */
    private List<TrendPoint> toTrendPoints(List<Object[]> raw, int slots, String mode) {
        long[] counts = new long[slots];
        boolean[] filled = new boolean[slots];
        LocalDateTime now = LocalDateTime.now();
        for (Object[] row : raw) {
            int idx;
            if ("d".equals(mode)) {
                // row[0] 是 java.sql.Date
                java.sql.Date d = (java.sql.Date) row[0];
                long daysAgo = java.time.temporal.ChronoUnit.DAYS.between(
                        d.toLocalDate(), now.toLocalDate());
                idx = (int) (slots - 1 - daysAgo);
            } else {
                // row[0] 是 Integer (HOUR)
                Integer hour = (Integer) row[0];
                long hoursAgo = java.time.temporal.ChronoUnit.HOURS.between(
                        now.toLocalDate().atTime(hour, 0), now);
                idx = (int) (slots - 1 - hoursAgo);
            }
            if (idx >= 0 && idx < slots) {
                counts[idx] = ((Number) row[1]).longValue();
                filled[idx] = true;
            }
        }
        List<TrendPoint> out = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            String label;
            if ("d".equals(mode)) {
                label = now.minusDays(slots - 1 - i).toLocalDate().toString();
            } else {
                label = String.format("%02d", now.minusHours(slots - 1 - i).getHour());
            }
            out.add(new TrendPoint(label, filled[i] ? counts[i] : 0));
        }
        return out;
    }
}