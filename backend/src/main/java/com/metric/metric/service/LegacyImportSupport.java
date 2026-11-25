package com.metric.metric.service;

import java.util.Set;

/** Only collectors with a real file parser may consume files. */
public final class LegacyImportSupport {
    private LegacyImportSupport() {}

    public static boolean available(String code) { return false; }

    public static String unavailableMessage(String code) {
        return "采集通道 " + code + " 已下线；请使用采集任务中的Python脚本加配置方式";
    }

    public static void requireAvailable(String code) {
        if (!available(code)) throw new IllegalArgumentException(unavailableMessage(code));
    }
}
