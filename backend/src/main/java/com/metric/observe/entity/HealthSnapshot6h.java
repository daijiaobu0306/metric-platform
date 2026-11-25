package com.metric.observe.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 健康度 6 小时粒度快照表
 * 字段定义见 {@link HealthSnapshotBase}
 */
@Entity
@Table(name = "health_snapshot_6h")
public class HealthSnapshot6h extends HealthSnapshotBase {
}
