package com.metric.collect.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "plugin_execution_log", indexes = {
    @Index(name = "idx_plugin_execution_log_plugin_id", columnList = "plugin_id"),
    @Index(name = "idx_plugin_execution_log_business", columnList = "business_type, business_id")
})
public class PluginExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_id")
    private Long pluginId;

    @Column(name = "business_type", length = 50)
    private String businessType;

    @Column(name = "business_id")
    private Long businessId;

    @Column(name = "request_data", columnDefinition = "TEXT")
    private String requestData;

    @Column(name = "response_data", columnDefinition = "TEXT")
    private String responseData;

    @Column(name = "exec_status", length = 20)
    private String execStatus;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "exec_time")
    private LocalDateTime execTime;

    @PrePersist
    protected void onCreate() {
        if (execTime == null) {
            execTime = LocalDateTime.now();
        }
    }
}
