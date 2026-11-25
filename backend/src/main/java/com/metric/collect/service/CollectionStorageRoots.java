package com.metric.collect.service;

import com.metric.shared.util.BasePathProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

/** Operator-controlled secondary storage for explicitly named sources; never a caller-supplied path. */
@Service
public class CollectionStorageRoots {
    private final BasePathProvider paths;
    private final String external;
    private final Set<String> externalCodes;
    public CollectionStorageRoots(BasePathProvider paths,
            @Value("${COLLECTION_EXTERNAL_FILES_ROOT:}") String external,
            @Value("${COLLECTION_EXTERNAL_SOURCE_CODES:}") String codes) {
        this.paths=paths;this.external=external;
        this.externalCodes=new HashSet<>();
        for(String code:codes.split(",")) if(!code.isBlank()) {
            if(!code.trim().matches("[a-z][a-z0-9_]{0,49}")) throw new IllegalArgumentException("外部采集任务编码无效");
            externalCodes.add(code.trim());
        }
        if(!externalCodes.isEmpty() && (external.isBlank() || !Path.of(external).isAbsolute()))
            throw new IllegalArgumentException("外部采集目录必须显式配置为绝对路径");
    }
    public boolean isExternal(String code) { return externalCodes.contains(code); }
    public String forSource(String code) {
        if(!isExternal(code)) return paths.getBasePath();
        try {
            Path root=Path.of(external).toRealPath(),normal=Path.of(paths.getBasePath()).toRealPath();
            if(root.startsWith(normal) || normal.startsWith(root)) throw new IllegalArgumentException("外部采集根目录不得与原文件根目录重叠");
            return root.toString();
        } catch(java.io.IOException e) { throw new IllegalArgumentException("外部采集目录不可访问",e); }
    }
}
