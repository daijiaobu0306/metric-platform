package com.metric.observe.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 健康度 1 小时粒度快照表
 * 字段定义见 {@link HealthSnapshotBase}
 */
@Entity
@Table(name = "health_snapshot_1h")
public class HealthSnapshot1h extends HealthSnapshotBase {
}
