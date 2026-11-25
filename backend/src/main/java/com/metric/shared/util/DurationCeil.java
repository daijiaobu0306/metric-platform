package com.metric.shared.util;

import java.time.Duration;

/**
 * 时长向上取整工具
 *
 * 用于登录会话时长计算 — 50 秒和 1 分钟都展示为"1 分钟"。
 *
 * 公式：(秒数 + 59) / 60，等价于 CEIL(秒 / 60)：
 *   0 秒   → 0
 *   1 秒   → 1
 *   45 秒  → 1
 *   59 秒  → 1
 *   60 秒  → 1
 *   61 秒  → 2
 *   119 秒 → 2
 *   120 秒 → 2
 *
 * 之前用 `Duration.toMinutes()` 是截断，45 秒会变成 0 分钟。
 *
 * 注：0 秒及负数（无时长、未开始）返回 0，不强制展示为 1 分钟，
 *     避免一个刚创建未使用的会话被显示"在线 1 分钟"。
 */
public final class DurationCeil {

    private DurationCeil() {}

    public static int ceilMinutes(Duration duration) {
        if (duration == null) return 0;
        long sec = duration.getSeconds();
        if (sec <= 0) return 0;
        return (int) ((sec + 59) / 60);
    }
}
