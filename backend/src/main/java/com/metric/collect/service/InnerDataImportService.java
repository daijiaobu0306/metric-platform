package com.metric.collect.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Compatibility boundary: all acquisition and parsing belongs to Python tasks. */
@Service
public class InnerDataImportService {
    public int importExcel(MultipartFile file) throws java.io.IOException {
        throw new IllegalArgumentException("旧专用导入已下线，请使用对应大类采集任务的生产CSV脚本");
    }
    public int importBusinessDurationExcel(MultipartFile file) throws java.io.IOException {
        throw new IllegalArgumentException("旧专用导入已下线，请使用对应大类采集任务的生产CSV脚本");
    }
}
