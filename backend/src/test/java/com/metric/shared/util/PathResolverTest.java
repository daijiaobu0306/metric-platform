package com.metric.shared.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PathResolver 安全路径解析单测
 *
 * 覆盖：resolveSafe 拒绝 '..' 跳出 basePath；resolve 保持原行为（向后兼容）。
 */
class PathResolverTest {

    /** 桩 BasePathProvider，固定返回给定 basePath。 */
    private static class StubProvider implements BasePathProvider {
        private final String path;
        StubProvider(String path) { this.path = path; }
        @Override public String getBasePath() { return path; }
        @Override public void invalidate() { /* noop */ }
    }

    private PathResolver resolver(String base) {
        PathResolver r = new PathResolver();
        // 反射注入 stub provider（@Autowired 只在 Spring 上下文里有效）
        try {
            var f = PathResolver.class.getDeclaredField("basePathProvider");
            f.setAccessible(true);
            f.set(r, new StubProvider(base));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return r;
    }

    @Test
    void resolveRelativePathJoinsBase() {
        PathResolver r = resolver("/tmp/base");
        String resolved = r.resolve("sub/file.txt");
        assertEquals(Paths.get("/tmp/base").resolve("sub/file.txt").toAbsolutePath().normalize(), Paths.get(resolved));
    }

    @Test
    void resolveAbsolutePathShortCircuits() {
        // 绝对路径不走 basePath 拼接（保留历史行为，避免破坏历史数据）
        PathResolver r = resolver("/tmp/base");
        String abs = Paths.get(System.getProperty("java.io.tmpdir"), "hosts").toAbsolutePath().normalize().toString();
        assertEquals(abs, r.resolve(abs));
    }

    @Test
    void resolveSafeBlocksDoubleDotEscape() {
        PathResolver r = resolver("/tmp/base");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> r.resolveSafe("../etc/passwd"));
        assertTrue(ex.getMessage().contains("超出 basePath"));
    }

    @Test
    void resolveSafeBlocksDeepDoubleDotEscape() {
        PathResolver r = resolver("/tmp/base");
        assertThrows(IllegalArgumentException.class,
                () -> r.resolveSafe("sub/../../../etc"));
    }

    @Test
    void resolveSafeAllowsValidRelativePath() {
        PathResolver r = resolver(Paths.get(System.getProperty("java.io.tmpdir")).toString());
        String resolved = r.resolveSafe("logs/app.log");
        assertTrue(Paths.get(resolved).endsWith(Paths.get("logs", "app.log")));
        assertNotEquals("logs/app.log", resolved);
    }

    @Test
    void resolveSafeAllowsEmptyPath() {
        PathResolver r = resolver("/tmp/base");
        assertEquals("/tmp/base", r.resolveSafe(""));
        assertEquals("/tmp/base", r.resolveSafe(null));
    }
}
