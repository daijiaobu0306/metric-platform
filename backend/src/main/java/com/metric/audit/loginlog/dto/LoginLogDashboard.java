package com.metric.audit.loginlog.dto;

import java.util.List;

/**
 * 登录看板响应（2026-07-10）。
 *
 * <p>含 9 个 KPI + 2 个趋势数组。{@code trend30d} / {@code trend24h} 分别覆盖过去 30 天 / 24 小时，
 * 前端 radio 切换显示。
 */
public record LoginLogDashboard(
        long todayLoginCount,                 // 今日登录次数
        long todayLoginUserCount,             // 今日登录人数（distinct）
        long onlineCount,                     // 当前在线人数
        long last24hActiveUserCount,          // 24h 活跃用户数
        long todayTotalDurationMinutes,       // 今日总时长（分钟）
        long weekLoginUserCount,              // 本周登录人数
        long weekTotalDurationMinutes,        // 本周总时长
        long monthLoginUserCount,             // 本月登录人数
        long monthTotalDurationMinutes,       // 本月总时长
        List<TrendPoint> trend30d,            // 过去 30 天每日 (label=yyyy-MM-dd, count)
        List<TrendPoint> trend24h             // 过去 24 小时每小时 (label=HH, count)
) {
    /** 趋势点：label=日期或小时字符串，count=登录次数 */
    public record TrendPoint(String label, long count) {}
}