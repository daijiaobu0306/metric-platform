package com.metric.skill.service;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置技能目录。
 *
 * <p>技能正文放在 {@code backend/src/main/resources/weight-skills/} 下，目录名必须是 ASCII：
 * Spring Boot 3.2.0 的 fat jar 加载器遇到 jar 内非 ASCII 条目名会陷入死循环
 * （启动卡在 ZipString.readInBuffer），中文只作为展示名和下载包内顶层目录名使用。</p>
 *
 * <p>这里是「权重技能」的唯一来源：指标体系配置页的「下载 Skills」（全量包）与
 * 技能管理页的技能仓库（单个包）都读这份目录，避免两处各写一套清单后漂移。</p>
 */
public final class BuiltinSkillCatalog {

    /** directory = jar 内 ASCII 目录，displayName = 页面展示名与下载包内顶层目录名 */
    public record BuiltinSkill(String directory, String displayName, List<String> references) {

        /** 技能内的相对文件名，顺序稳定，便于展示文件数 */
        public List<String> entries() {
            List<String> entries = new ArrayList<>();
            entries.add("SKILL.md");
            entries.add("usage.txt");
            for (String reference : references) {
                entries.add("references/" + reference);
            }
            entries.add("references/write-contract.md");
            return entries;
        }

        /** 下载包内中文文件名（只在压缩包条目里出现，不参与资源路径） */
        public String downloadEntryName(String entry) {
            return "usage.txt".equals(entry) ? "使用说明.txt" : entry;
        }
    }

    public static final List<BuiltinSkill> BUILTIN_SKILLS = List.of(
            new BuiltinSkill("expert-basic-weight", "指标平台-专家基础权重", List.of("catalog.md")),
            new BuiltinSkill("monthly-dynamic-weight", "指标平台-月度动态权重",
                    List.of("monthly-data.md", "weight-rules.md")));

    private BuiltinSkillCatalog() {
    }

    public static List<BuiltinSkill> all() {
        return BUILTIN_SKILLS;
    }

    /**
     * 读取技能内的一个文件。
     *
     * <p>写入契约放在技能目录之外（供两个技能共用），这里做一次路径映射，
     * 保证对调用方来说「每个技能包都是自包含的」。</p>
     */
    public static byte[] read(BuiltinSkill skill, String entry) throws IOException {
        String resourcePath = "references/write-contract.md".equals(entry)
                ? "write-contract.md"
                : skill.directory() + "/" + entry;
        try (InputStream in = new ClassPathResource("weight-skills/" + resourcePath).getInputStream()) {
            return in.readAllBytes();
        }
    }

    public static String readText(BuiltinSkill skill, String entry) throws IOException {
        return new String(read(skill, entry), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 技能包解压后的字节数，用于列表展示大小 */
    public static long packageSize(BuiltinSkill skill) {
        long size = 0;
        for (String entry : skill.entries()) {
            try {
                size += read(skill, entry).length;
            } catch (IOException ignored) {
                // 单个引用文件缺失不影响列表展示
            }
        }
        return size;
    }
}
