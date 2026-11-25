package com.metric.shared.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件路径解析工具类
 * 统一处理 files/ 目录下的相对路径和绝对路径转换
 *
 * basePath 由 BasePathProvider 接口注入；默认实现 SystemConfigBasePathProvider
 * 优先读 system_config['upload.base_path']，回退到 application.yml。
 */
@Component
public class PathResolver {

    @Autowired
    private BasePathProvider basePathProvider;

    /**
     * 获取文件存储的根目录
     */
    public String getBasePath() {
        return basePathProvider.getBasePath();
    }

    /**
     * 将相对路径解析为完整路径
     * @param relativePath 相对路径，如 "alarm_output/alarm.txt"
     *                      或者已存在的绝对路径，如 "/Users/xxx/alarm.txt"
     * @return 完整路径
     */
    public String resolve(String path) {
        if (path == null || path.isEmpty()) {
            return getBasePath();
        }
        // 如果已经是绝对路径，直接返回（保留历史行为；绝对路径不参与 basePath 安全检查）
        if (Paths.get(path).isAbsolute()) {
            return path;
        }
        // 清理路径前面的 /
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        Path resolved = Paths.get(getBasePath(), cleanPath).toAbsolutePath().normalize();
        return resolved.toString();
    }

    /**
     * 安全版路径解析：拒绝 '..' 跳出 basePath，用于用户可控输入（导入配置子目录等）。
     *
     * @param path 相对或绝对路径
     * @return 完整路径
     * @throws IllegalArgumentException 当 path 含 '..' 试图跳出 basePath 时
     */
    public String resolveSafe(String path) {
        if (path == null || path.isEmpty()) {
            return getBasePath();
        }
        Path resolved = Paths.get(path).isAbsolute()
                ? Paths.get(path).toAbsolutePath().normalize()
                : Paths.get(getBasePath(), path.startsWith("/") ? path.substring(1) : path)
                        .toAbsolutePath().normalize();
        Path base = Paths.get(getBasePath()).toAbsolutePath().normalize();
        if (!resolved.startsWith(base) && !resolved.startsWith(base + "/")) {
            throw new IllegalArgumentException(
                    "路径 '" + path + "' 解析后 '" + resolved + "' 超出 basePath '" + base + "'，已拒绝");
        }
        return resolved.toString();
    }

    /**
     * 将绝对路径转为相对路径（基于 basePath）
     * @param absolutePath 绝对路径
     * @return 相对路径，如果不在 basePath 下则返回原值
     */
    public String relativize(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) {
            return "";
        }
        Path absolute = Paths.get(absolutePath).toAbsolutePath().normalize();
        Path base = Paths.get(getBasePath()).toAbsolutePath().normalize();

        if (absolute.startsWith(base)) {
            return base.relativize(absolute).toString();
        }
        return absolutePath;
    }

    /**
     * 判断路径是否为绝对路径
     */
    public boolean isAbsolute(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return Paths.get(path).isAbsolute();
    }

    /**
     * 获取带完整路径前缀的展示文本
     * 如果是绝对路径直接返回，否则拼接 basePath
     */
    public String getDisplayPath(String path) {
        if (path == null || path.isEmpty()) {
            return getBasePath();
        }
        if (Paths.get(path).isAbsolute()) {
            return path;
        }
        return resolve(path);
    }
}
