package com.metric.shared.dto;

import lombok.Data;

@Data
public class PluginResponseParamConfig {
    private String name;
    private String type = "string";
    private String path;
    private String description;
}
