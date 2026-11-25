package com.metric.observe.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 健康度 30 天粒度快照表
 * 字段定义见 {@link HealthSnapshotBase}
 */
@Entity
@Table(name = "health_snapshot_30d")
public class HealthSnapshot30d extends HealthSnapshotBase {
}
