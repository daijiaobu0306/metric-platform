package com.metric.observe.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 健康度 7 天粒度快照表
 * 字段定义见 {@link HealthSnapshotBase}
 */
@Entity
@Table(name = "health_snapshot_7d")
public class HealthSnapshot7d extends HealthSnapshotBase {
}
