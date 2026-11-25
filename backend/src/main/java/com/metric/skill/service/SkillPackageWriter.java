package com.metric.skill.service;

import com.metric.skill.service.BuiltinSkillCatalog.BuiltinSkill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 内置技能打包器：单个技能一个包，或全部技能合成一个包。
 *
 * <p>压缩包顶层目录使用中文展示名（用户解压后一眼能认出来），
 * 条目内容全部来自 classpath 里的 ASCII 目录。</p>
 */
public final class SkillPackageWriter {

    private SkillPackageWriter() {
    }

    public static byte[] zipSingle(BuiltinSkill skill) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            write(zip, skill);
        }
        return bytes.toByteArray();
    }

    public static byte[] zipAll() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (BuiltinSkill skill : BuiltinSkillCatalog.all()) {
                write(zip, skill);
            }
        }
        return bytes.toByteArray();
    }

    private static void write(ZipOutputStream zip, BuiltinSkill skill) throws IOException {
        String prefix = skill.displayName() + "/";
        for (String entry : skill.entries()) {
            zip.putNextEntry(new ZipEntry(prefix + skill.downloadEntryName(entry)));
            zip.write(BuiltinSkillCatalog.read(skill, entry));
            zip.closeEntry();
        }
    }
}
