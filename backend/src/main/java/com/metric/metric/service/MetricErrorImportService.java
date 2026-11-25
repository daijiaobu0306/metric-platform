package com.metric.metric.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Compatibility boundary: all acquisition and parsing belongs to Python tasks. */
@Service
public class MetricErrorImportService {
    public int importExcel(MultipartFile file, String sourceFileName) throws java.io.IOException {
        throw new IllegalArgumentException("旧专用导入已下线，请使用对应大类采集任务的生产CSV脚本");
    }
    public String generateArchiveFileName(String name) { return name+"_imported_"+java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")); }
}
