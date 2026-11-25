package com.metric.shared.dto;

import lombok.Data;
import java.util.Map;

@Data
public class PluginExecutionResult {
    private Boolean success;
    private String message;
    private Map<String, Object> responseData;
    private String rawResponse;

    public PluginExecutionResult() {
    }

    public PluginExecutionResult(Boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public PluginExecutionResult(Boolean success, Map<String, Object> responseData, String rawResponse) {
        this.success = success;
        this.responseData = responseData;
        this.rawResponse = rawResponse;
    }

    public static PluginExecutionResult success(Map<String, Object> responseData, String rawResponse) {
        return new PluginExecutionResult(true, responseData, rawResponse);
    }

    public static PluginExecutionResult failure(String message) {
        return new PluginExecutionResult(false, message);
    }
}
