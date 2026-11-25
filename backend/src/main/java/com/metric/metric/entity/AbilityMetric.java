package com.metric.metric.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ability_metric", indexes = {
    @Index(name = "idx_data_date", columnList = "data_date"),
    @Index(name = "idx_unified_capability_code", columnList = "unified_capability_code"),
    @Index(name = "idx_unified_service_code", columnList = "unified_service_code"),
    @Index(name = "idx_ability_user_code", columnList = "ability_user_code"),
    @Index(name = "idx_unified_scene_code", columnList = "unified_scene_code")
})
public class AbilityMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "region_code", length = 20)
    private String regionCode;

    @Column(name = "function_code", length = 20)
    private String functionCode;

    @Column(name = "unified_capability_code", length = 20)
    private String unifiedCapabilityCode;

    @Column(name = "service_org_code", length = 20)
    private String serviceOrgCode;

    @Column(name = "data_date", length = 20)
    private String dataDate;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(name = "unified_scene_code", length = 50)
    private String unifiedSceneCode;

    @Column(name = "unified_service_code", length = 50)
    private String unifiedServiceCode;

    @Column(name = "ability_user_name", length = 200)
    private String abilityUserName;

    @Column(name = "ability_user_code", length = 50)
    private String abilityUserCode;

    @Column(name = "ability_user_app_name", length = 200)
    private String abilityUserAppName;

    @Column(name = "ability_user_app_code", length = 50)
    private String abilityUserAppCode;

    @Column(name = "ability_user_org_name", length = 200)
    private String abilityUserOrgName;

    @Column(name = "ability_user_org_code", length = 50)
    private String abilityUserOrgCode;

    @Column(name = "portal_order_no", length = 100)
    private String portalOrderNo;

    @Column(name = "portal_sub_order_no", length = 100)
    private String portalSubOrderNo;

    @Column(name = "invoke_volume", columnDefinition = "TEXT")
    private String invokeVolume;

    @Column(name = "invoke_failure", columnDefinition = "TEXT")
    private String invokeFailure;

    @Column(name = "unique_key", length = 255, unique = true)
    private String uniqueKey;

    @Column(name = "insert_time")
    private LocalDateTime insertTime = LocalDateTime.now();
}
