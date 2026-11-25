package com.metric.shared.util;

/**
 * 文件根目录提供器接口
 *
 * 业务模块（collect/metric/issue 等）只依赖此接口，具体实现（SystemConfig 或 Env）
 * 由 config 模块注入。允许运行时热更新，无需重启后端。
 *
 * Why 接口放在 shared：PathResolver、FileService 等通用工具在 shared 模块，
 * 不能反向依赖具体业务模块（config）。
 */
public interface BasePathProvider {

    /**
     * 当前生效的文件根目录绝对路径（已 normalize）。
     */
    String getBasePath();

    /**
     * 路径变更后强制失效缓存。save upload.base_path 后调用。
     */
    void invalidate();
}
