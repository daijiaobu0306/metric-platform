package com.metric.shared.dto;

import lombok.Data;

@Data
public class SyncResult {
    private boolean success;
    private String message;
    private Integer syncedCount;
    private Long metricId;
    private Long strategyId;

    public SyncResult() {
    }

    public static SyncResult success() {
        SyncResult r = new SyncResult();
        r.success = true;
        r.message = "同步成功";
        return r;
    }

    public static SyncResult success(String message) {
        SyncResult r = new SyncResult();
        r.success = true;
        r.message = message;
        return r;
    }

    public static SyncResult success(String message, int count) {
        SyncResult r = new SyncResult();
        r.success = true;
        r.message = message;
        r.syncedCount = count;
        return r;
    }

    public static SyncResult success(Long metricId, Long strategyId) {
        SyncResult r = new SyncResult();
        r.success = true;
        r.message = "同步成功";
        r.metricId = metricId;
        r.strategyId = strategyId;
        return r;
    }

    public static SyncResult fail(String message) {
        SyncResult r = new SyncResult();
        r.success = false;
        r.message = message;
        return r;
    }

    public static SyncResult fail(String message, Long metricId) {
        SyncResult r = new SyncResult();
        r.success = false;
        r.message = message;
        r.metricId = metricId;
        return r;
    }
}
