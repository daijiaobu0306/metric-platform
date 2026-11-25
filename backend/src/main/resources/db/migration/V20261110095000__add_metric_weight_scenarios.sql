CREATE TABLE metric_weight_scenario (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    revision INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL,
    priority INT NOT NULL,
    schedule_type ENUM('date_range', 'annual_range', 'month_edges', 'month_middle') NOT NULL,
    starts_on DATE,
    ends_on DATE,
    first_days INT,
    last_days INT,
    metric_ids JSON NOT NULL,
    multiplier DECIMAL(5,2) NOT NULL,
    CONSTRAINT chk_weight_scenario_multiplier CHECK (multiplier BETWEEN 0.01 AND 10.00)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE metric_weight_scenario_revision (
    scenario_id BIGINT NOT NULL,
    revision INT NOT NULL,
    operator_id BIGINT NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    snapshot JSON NOT NULL,
    PRIMARY KEY (scenario_id, revision),
    CONSTRAINT fk_weight_scenario_revision FOREIGN KEY (scenario_id) REFERENCES metric_weight_scenario(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE category_health_history ADD COLUMN weight_trace JSON;
ALTER TABLE sub_category_health_history ADD COLUMN weight_trace JSON;
