package com.metric.security.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.security.entity.SysUser;
import com.metric.security.entity.UserSession;
import com.metric.security.repository.*;
import com.metric.security.service.SessionService;
import com.metric.audit.loginlog.service.LoginLogService;
import com.metric.shared.util.DurationCeil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/session")
public class SessionController {

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private SysUserRepository sysUserRepository;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private LoginLogService loginLogService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 用户登录（用户名 + 密码校验）
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ApiResponse.error("用户名和密码不能为空");
        }

        Optional<SysUser> userOpt = sysUserRepository.findByUsernameAndStatus(username.trim(), 1);
        if (userOpt.isEmpty()) {
            loginLogService.recordLocalLoginNotFound(username, request);
            return ApiResponse.error("用户名或密码错误");
        }

        SysUser user = userOpt.get();
        if (!passwordEncoder.matches(password, user.getPassword())) {
            loginLogService.recordLocalLoginBadPassword(user, request);
            return ApiResponse.error("用户名或密码错误");
        }

        // P1 重构：会话创建和响应组装统一委托 SessionService
        String sessionKey = sessionService.createSession(user, SessionService.SOURCE_LOCAL, request);
        loginLogService.recordLocalLoginSuccess(user, request);
        return ApiResponse.success(sessionService.buildLoginResponse(user, sessionKey));
    }

    /**
     * 获取会话配置（空闲超时时间）
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getSessionConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("idleTimeout", sessionService.getIdleTimeout());
        return ApiResponse.success(result);
    }

    /**
     * 校验并补全已登录会话。不存在或已失效的会话必须重新登录。
     *
     * 活跃会话会返回完整 profile
     *   (displayName / username / idleTimeout / isAdmin / permissions)
     * 供 SSO 首次进入时一次性补齐 sessionStorage，避免依赖 URL 参数。
     * Layout 调 init 时这些字段会被忽略（已有 sessionStorage 缓存）。
     */
    @PostMapping("/init")
    public ApiResponse<Map<String, Object>> initSession(@RequestBody Map<String, Object> body,
                                                         HttpServletRequest request) {
        String sessionKey = (String) body.get("sessionKey");
        if (sessionKey == null || sessionKey.isBlank()) return ApiResponse.error("sessionKey 不能为空");
        Optional<UserSession> existing = sessionRepository.findBySessionKey(sessionKey);
        if (existing.isEmpty()) return ApiResponse.error("会话不存在，请重新登录");
        UserSession s = existing.get();
        int idle = sessionService.getIdleTimeout();
        if (!Integer.valueOf(1).equals(s.getIsActive()) || s.getUserId() == null
                || s.getLastHeartbeat() == null || s.getLastHeartbeat().isBefore(LocalDateTime.now().minusMinutes(idle)))
            return ApiResponse.error("会话已失效，请重新登录");
        Optional<SysUser> user = sysUserRepository.findById(s.getUserId());
        if (user.isEmpty() || !Integer.valueOf(1).equals(user.get().getStatus()))
            return ApiResponse.error("用户已停用，请重新登录");
        s.setUserAgent(getString(body, "userAgent", s.getUserAgent()));
        s.setBrowserName(getString(body, "browserName", s.getBrowserName()));
        s.setBrowserVersion(getString(body, "browserVersion", s.getBrowserVersion()));
        s.setOsName(getString(body, "osName", s.getOsName()));
        s.setDeviceType(getString(body, "deviceType", s.getDeviceType()));
        Object sw = body.get("screenWidth"), sh = body.get("screenHeight");
        if (sw instanceof Number && ((Number) sw).intValue() > 0) s.setScreenWidth(((Number) sw).intValue());
        if (sh instanceof Number && ((Number) sh).intValue() > 0) s.setScreenHeight(((Number) sh).intValue());
        s.setLastHeartbeat(LocalDateTime.now());
        s.setDurationMinutes(DurationCeil.ceilMinutes(Duration.between(s.getLoginTime(), LocalDateTime.now())));
        sessionRepository.save(s);
        return ApiResponse.success(buildFullSessionResponse(s));
    }

    /** 心跳上报 — 前端每分钟调用一次，更新持续时长。 */
    @PostMapping("/heartbeat")
    public ApiResponse<Map<String, Object>> heartbeat(@RequestBody Map<String, String> body) {
        String sessionKey = body.get("sessionKey");
        if (sessionKey == null || sessionKey.isBlank()) {
            return ApiResponse.error("sessionKey 不能为空");
        }

        Optional<UserSession> opt = sessionRepository.findBySessionKey(sessionKey);
        if (opt.isEmpty() || opt.get().getIsActive() != 1 || opt.get().getUserId() == null
                || opt.get().getLastHeartbeat() == null
                || opt.get().getLastHeartbeat().isBefore(LocalDateTime.now().minusMinutes(sessionService.getIdleTimeout()))
                || sysUserRepository.findById(opt.get().getUserId())
                    .map(u -> !Integer.valueOf(1).equals(u.getStatus())).orElse(true)) {
            // 会话不存在或已结束，让前端重新 init
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("valid", false);
            return ApiResponse.success(r);
        }

        UserSession s = opt.get();
        LocalDateTime now = LocalDateTime.now();
        s.setLastHeartbeat(now);
        s.setDurationMinutes(DurationCeil.ceilMinutes(Duration.between(s.getLoginTime(), now)));
        sessionRepository.save(s);

        return ApiResponse.success(buildSessionResponse(s));
    }

    /**
     * 结束会话（页面关闭 / 用户主动退出）
     */
    @PostMapping("/end")
    public ApiResponse<Void> endSession(@RequestBody Map<String, String> body,
                                         HttpServletRequest request) {
        String sessionKey = body.get("sessionKey");
        if (sessionKey == null) return ApiResponse.success();

        Optional<UserSession> opt = sessionRepository.findBySessionKey(sessionKey);
        opt.ifPresent(s -> {
            s.setIsActive(0);
            s.setLogoutTime(LocalDateTime.now());
            s.setDurationMinutes(DurationCeil.ceilMinutes(Duration.between(s.getLoginTime(), LocalDateTime.now())));
            sessionRepository.save(s);
            // LoginLog: 同步写一条 OFFLINE 审计行（与 user_session 状态表并行；UserSession 已标 inactive）
            loginLogService.recordLogout(s.getUsername(), request);
        });
        return ApiResponse.success();
    }

    /**
     * 获取当前活跃会话列表（管理用）
     */
    @GetMapping("/active")
    public ApiResponse<List<UserSession>> getActiveSessions() {
        return ApiResponse.success(sessionRepository.findByIsActiveOrderByLoginTimeDesc(1));
    }

    /**
     * 分页查询登录日志（支持多维筛选）
     */
    @GetMapping("/logs")
    public ApiResponse<Page<UserSession>> getSessionLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String browserName,
            @RequestParam(required = false) String osName,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) Integer isActive,
            @RequestParam(required = false) String clientIp,
            @RequestParam(required = false) String loginTimeStart,
            @RequestParam(required = false) String loginTimeEnd) {

        Specification<UserSession> spec = Specification.where(null);

        if (username != null && !username.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.like(root.get("username"), "%" + username + "%"));
        }
        if (browserName != null && !browserName.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("browserName"), browserName));
        }
        if (osName != null && !osName.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("osName"), osName));
        }
        if (deviceType != null && !deviceType.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("deviceType"), deviceType));
        }
        if (isActive != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("isActive"), isActive));
        }
        if (clientIp != null && !clientIp.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.like(root.get("clientIp"), "%" + clientIp + "%"));
        }
        if (loginTimeStart != null && !loginTimeStart.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("loginTime"),
                    LocalDateTime.parse(loginTimeStart)));
        }
        if (loginTimeEnd != null && !loginTimeEnd.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("loginTime"),
                    LocalDateTime.parse(loginTimeEnd + "T23:59:59")));
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "loginTime"));
        Page<UserSession> result = sessionRepository.findAll(spec, pageable);
        return ApiResponse.success(result);
    }

    /**
     * 登录日志统计看板（Issue 1B）
     *
     * - timeRange: today / week / month / year / all，决定时间窗
     * - 其余 filter：username / browserName / osName / deviceType / clientIp 与 /logs 一致
     * - 返回：{ summary: {totalCount, totalDurationMinutes, distinctUserCount}, daily: [{date, count, durationMinutes, userCount}] }
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getSessionLogStats(
            @RequestParam(required = false, defaultValue = "week") String timeRange,
            @RequestParam(required = false) String loginTimeStart,
            @RequestParam(required = false) String loginTimeEnd,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String browserName,
            @RequestParam(required = false) String osName,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String clientIp) {

        // 1. 解析时间窗
        LocalDateTime[] window = computeStatsWindow(timeRange, loginTimeStart, loginTimeEnd);
        LocalDateTime startTime = window[0];
        LocalDateTime endTime = window[1];

        // 2. 概要聚合（native 查询即使单行也返回 List<Object[]>，外层=行 / 内层=列）
        List<Object[]> summaryRows = sessionRepository.getSessionLogSummary(
                startTime, endTime, blankToNull(username), blankToNull(browserName),
                blankToNull(osName), blankToNull(deviceType), blankToNull(clientIp));
        Map<String, Object> summary = new LinkedHashMap<>();
        Object[] summaryRow = (summaryRows != null && !summaryRows.isEmpty()) ? summaryRows.get(0) : null;
        long totalCount = (summaryRow != null && summaryRow[0] != null) ? ((Number) summaryRow[0]).longValue() : 0;
        long totalDuration = (summaryRow != null && summaryRow[1] != null) ? ((Number) summaryRow[1]).longValue() : 0;
        long distinctUsers = (summaryRow != null && summaryRow[2] != null) ? ((Number) summaryRow[2]).longValue() : 0;
        summary.put("totalCount", totalCount);
        summary.put("totalDurationMinutes", totalDuration);
        summary.put("distinctUserCount", distinctUsers);

        // 3. 按天聚合
        List<Object[]> dailyRows = sessionRepository.getSessionLogDaily(
                startTime, endTime, blankToNull(username), blankToNull(browserName),
                blankToNull(osName), blankToNull(deviceType), blankToNull(clientIp));
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Object[] r : dailyRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", r[0] == null ? null : r[0].toString());
            row.put("count", ((Number) r[1]).longValue());
            row.put("durationMinutes", ((Number) r[2]).longValue());
            row.put("userCount", ((Number) r[3]).longValue());
            daily.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summary);
        data.put("daily", daily);
        data.put("timeRange", timeRange);
        data.put("startTime", startTime != null ? startTime.toString() : null);
        data.put("endTime", endTime != null ? endTime.toString() : null);
        return ApiResponse.success(data);
    }

    /**
     * timeRange → [startTime, endTime] 计算（与 GranularityUtil 思路类似，但单独维护避免依赖扩散）
     * 优先级：自定义 loginTimeStart/loginTimeEnd > timeRange > 默认 week
     */
    private static LocalDateTime[] computeStatsWindow(String timeRange, String start, String end) {
        LocalDateTime endTime;
        LocalDateTime startTime;
        if (end != null && !end.isBlank()) {
            endTime = LocalDateTime.parse(end + "T23:59:59");
        } else {
            endTime = LocalDateTime.now();
        }
        if (start != null && !start.isBlank()) {
            startTime = LocalDateTime.parse(start);
        } else {
            switch (timeRange == null ? "week" : timeRange) {
                case "today": startTime = endTime.toLocalDate().atStartOfDay(); break;
                case "month": startTime = endTime.minusDays(30); break;
                case "year":  startTime = endTime.minusDays(365); break;
                case "all":   startTime = null; break;
                case "week":
                default:      startTime = endTime.minusDays(7); break;
            }
        }
        return new LocalDateTime[]{startTime, endTime};
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 定时任务：每 5 分钟清理超过 session_idle_timeout 分钟无心跳的会话
     *
     * 阈值读 sessionService.getIdleTimeout()（与 SessionKeyAuthenticationFilter 一致），
     * 避免 system_config 调高 idleTimeout 后清理任务仍按 30 分钟硬编码提前杀掉。
     */
    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void cleanupStaleSessions() {
        int idleMinutes = sessionService.getIdleTimeout();
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(idleMinutes);
        int count = sessionRepository.markStaleInactive(threshold, LocalDateTime.now());
        if (count > 0) {
            System.out.println("[SessionCleanup] Marked " + count + " stale sessions as inactive (idle > " + idleMinutes + " min)");
        }
    }

    @PostMapping("/batch-delete")
    public ApiResponse<Void> batchDeleteSessions(@RequestBody List<Long> ids) {
        sessionRepository.deleteAllById(ids);
        return ApiResponse.success();
    }

    // ===== 工具方法 =====

    private Map<String, Object> buildSessionResponse(UserSession s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionKey", s.getSessionKey());
        r.put("loginTime", s.getLoginTime());
        r.put("durationMinutes", s.getDurationMinutes());
        r.put("isActive", s.getIsActive());
        return r;
    }

    /**
     * 扩展版 session response —— 在 4 项基础上补齐 profile 信息
     * (displayName / username / idleTimeout / isAdmin / permissions)，
     * 通过 session.user_id 查询 sys_user。
     *
     * 用于 init 已存在会话分支（SSO 首次进入需要一次性补齐 6 个 sessionStorage key）。
     * sys_user 找不到时降级返回基础 4 项，前端会兜底。
     */
    private Map<String, Object> buildFullSessionResponse(UserSession s) {
        Map<String, Object> r = buildSessionResponse(s);
        Optional<SysUser> userOpt = s.getUserId() == null ? Optional.empty() : sysUserRepository.findById(s.getUserId());
        if (userOpt.isPresent()) {
            SysUser user = userOpt.get();
            r.put("username", user.getUsername());
            r.put("displayName", user.getDisplayName() != null && !user.getDisplayName().isBlank()
                    ? user.getDisplayName() : user.getUsername());
            r.put("idleTimeout", sessionService.getIdleTimeout());
            r.put("isAdmin", sessionService.isAdmin(user));
            r.put("permissions", sessionService.computePermissions(user));
        }
        return r;
    }

    private String getString(Map<String, Object> body, String key, String def) {
        Object v = body.get(key);
        return v != null ? v.toString() : def;
    }
}
