package com.metric.config.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SystemConfigBasePathProvider 静态校验逻辑单测
 *
 * 只覆盖静态方法 validate()，不需要 Spring 上下文。
 * DB 驱动的 getBasePath() 行为靠 mvn compile + 集成验证保证。
 */
class SystemConfigBasePathProviderTest {

    @Test
    void validateRejectsNullAndEmpty() {
        assertNotNull(SystemConfigBasePathProvider.validate(null));
        assertNotNull(SystemConfigBasePathProvider.validate(""));
        assertNotNull(SystemConfigBasePathProvider.validate("   "));
    }

    @Test
    void validateRejectsDoubleDotEscape() {
        String err = SystemConfigBasePathProvider.validate("/tmp/../etc/passwd");
        assertNotNull(err);
        assertTrue(err.contains(".."));
    }

    @Test
    void validateRejectsRelativePathWithDoubleDot() {
        String err = SystemConfigBasePathProvider.validate("files/../../../etc");
        assertNotNull(err);
        assertTrue(err.contains(".."));
    }

    @Test
    void validateAcceptsExistingWritableDirectory() throws Exception {
        // 临时目录，Files.createTempDirectory 保证存在
        Path tmp = Files.createTempDirectory("basepath-test-");
        try {
            String err = SystemConfigBasePathProvider.validate(tmp.toString());
            assertNull(err, "期望合法但实际报错: " + err);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void validateCreatesAndAcceptsNonExistentDirectory() throws Exception {
        Path tmpParent = Files.createTempDirectory("basepath-test-parent-");
        Path newDir = tmpParent.resolve("new-subdir");
        try {
            assertFalse(Files.exists(newDir));
            String err = SystemConfigBasePathProvider.validate(newDir.toString());
            assertNull(err, "期望自动创建成功但报错: " + err);
            assertTrue(Files.exists(newDir));
        } finally {
            Files.deleteIfExists(newDir);
            Files.deleteIfExists(tmpParent);
        }
    }

    @Test
    void validateRejectsFileNotDirectory() throws Exception {
        Path tmpFile = Files.createTempFile("basepath-test-file-", ".txt");
        try {
            String err = SystemConfigBasePathProvider.validate(tmpFile.toString());
            assertNotNull(err);
            assertTrue(err.contains("不是目录"));
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    void validateNormalizesAbsolutePath() {
        // 含 ./ 的路径应当被 normalize 成绝对路径，校验流程不报错
        Path existing = Paths.get(System.getProperty("java.io.tmpdir"));
        String err = SystemConfigBasePathProvider.validate(existing + "/.");
        assertNull(err, "期望合法但报错: " + err);
    }

    @Test
    void configKeyMatchesExpectedName() {
        // 防止重命名时漏改 ConfigService / Frontend 调用方
        assertEquals("upload.base_path", SystemConfigBasePathProvider.CONFIG_KEY);
    }
}
