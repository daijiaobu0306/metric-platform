package com.metric.observe.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 健康度 1 天粒度快照表
 * 字段定义见 {@link HealthSnapshotBase}
 */
@Entity
@Table(name = "health_snapshot_1d")
public class HealthSnapshot1d extends HealthSnapshotBase {
}
