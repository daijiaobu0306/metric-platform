package com.metric.shared.dto;

import lombok.Data;

@Data
public class PluginParamConfig {
    private String name;
    private String type = "string";
    private String position = "query";
    private Boolean required = false;
    private String defaultValue;
    private String source;
    private String description;
}
