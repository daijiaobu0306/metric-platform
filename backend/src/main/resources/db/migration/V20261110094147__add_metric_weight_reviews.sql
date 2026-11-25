CREATE TABLE metric_weight_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT NOT NULL,
    sub_category_name VARCHAR(100),
    source VARCHAR(24) NOT NULL,
    run_id BIGINT,
    operator_id BIGINT,
    operator_name VARCHAR(128) NOT NULL,
    weights_before JSON NOT NULL,
    weights_after JSON NOT NULL,
    applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_weight_version_scope (scope_type, scope_id, applied_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE metric_weight_recommendation_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recommendation_type ENUM('expert_base', 'monthly_dynamic') NOT NULL,
    category_id BIGINT NOT NULL,
    sub_category_name VARCHAR(100),
    skill_name VARCHAR(100) NOT NULL,
    skill_version VARCHAR(50) NOT NULL,
    agent_model VARCHAR(160),
    generated_at DATETIME(6) NOT NULL,
    period_start DATETIME,
    period_end DATETIME,
    expires_at DATETIME NOT NULL,
    run_status ENUM('ready', 'failed') NOT NULL,
    baseline JSON NOT NULL COMMENT 'Active metric ID to baseline token for the entire group',
    summary TEXT NOT NULL,
    raw_analysis MEDIUMTEXT,
    error_message TEXT,
    UNIQUE KEY uk_weight_run_key (run_key),
    INDEX idx_weight_run_filter (recommendation_type, category_id, id),
    CONSTRAINT chk_weight_run_dates CHECK (
        expires_at > generated_at AND
        (recommendation_type <> 'monthly_dynamic' OR
         (period_start IS NOT NULL AND period_end IS NOT NULL AND period_start < period_end AND period_end <= generated_at))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE metric_weight_recommendation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    metric_id BIGINT NOT NULL,
    metric_name VARCHAR(100) NOT NULL,
    definition_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_share DECIMAL(5,2) NOT NULL COMMENT 'Share at generation time in 0..1',
    suggested_share DECIMAL(5,2) NOT NULL COMMENT 'Target share in 0..1',
    assessment ENUM('business_importance', 'current_risk') NOT NULL,
    reason TEXT NOT NULL,
    evidence JSON NOT NULL,
    quality VARCHAR(50),
    review_status ENUM('pending', 'applied', 'rejected') NOT NULL DEFAULT 'pending',
    reviewed_by BIGINT,
    reviewer_name VARCHAR(128),
    reviewed_at DATETIME(6),
    approved_share DECIMAL(5,2),
    review_note VARCHAR(500),
    weight_version_id BIGINT,
    UNIQUE KEY uk_weight_run_metric (run_id, metric_id),
    INDEX idx_weight_metric_history (metric_id, review_status, id),
    INDEX idx_weight_review_status (review_status, run_id),
    CONSTRAINT fk_weight_review_run FOREIGN KEY (run_id) REFERENCES metric_weight_recommendation_run(id),
    CONSTRAINT fk_weight_review_version FOREIGN KEY (weight_version_id) REFERENCES metric_weight_version(id),
    CONSTRAINT chk_weight_review_range CHECK (
        current_share BETWEEN 0 AND 1 AND suggested_share BETWEEN 0 AND 1
        AND (approved_share IS NULL OR approved_share BETWEEN 0 AND 1)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE SQL SECURITY INVOKER VIEW metric_weight_review_baseline AS
SELECT m.id AS metric_id, c.system_id, m.category_id, m.sub_category_name, m.sub_category_code,
       m.metric_code, m.metric_name, m.metric_definition, m.unit, m.statistics_caliber,
       COALESCE(MAX(w.weight), 1.00) AS base_weight,
       COUNT(w.id) AS configured_count,
       SHA2(JSON_ARRAY(m.id, m.category_id, m.metric_code, m.metric_name, m.metric_definition,
                       m.unit, m.statistics_caliber, m.sub_category_code, m.sub_category_name), 256) AS definition_token,
       SHA2(JSON_ARRAY(m.id, m.metric_code, m.metric_name, m.metric_definition,
                       m.unit, m.statistics_caliber, m.sub_category_code, m.sub_category_name,
                       COUNT(w.id), MAX(w.weight)), 256) AS baseline_token
FROM metric_item m
JOIN metric_category c ON c.id = m.category_id
LEFT JOIN weight_config w ON w.category_id = m.category_id AND w.metric_id = m.id AND w.weight_type = 'metric'
WHERE m.status = 1
GROUP BY m.id, c.system_id, m.category_id, m.sub_category_name, m.sub_category_code,
         m.metric_code, m.metric_name, m.metric_definition, m.unit, m.statistics_caliber;
