package com.metric.skill.dto;

/**
 * 技能仓库列表项。
 *
 * @param source BUILTIN = 随平台发布的内置技能；UPLOADED = 团队上传
 */
public record SkillSummary(
        String id,
        String name,
        String description,
        String version,
        int fileCount,
        long size,
        String source,
        String uploader,
        String uploadedAt,
        String fileName,
        boolean deletable) {
}
