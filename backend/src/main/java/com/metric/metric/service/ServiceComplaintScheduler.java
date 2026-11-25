package com.metric.metric.service;

import com.metric.metric.entity.MetricImportConfig;
import com.metric.metric.repository.MetricImportConfigRepository;
import com.metric.metric.service.ServiceComplaintImportService;
import com.metric.shared.util.PathResolver;
import com.metric.collect.service.OriginalDataImportLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * 阶段 3 (客服投诉) 文件采集调度器。
 *
 * <p>对应 sub_category_code: serviceComplaint</p>
 *
 * <p>每 5 分钟扫描一次 metric_import_config 中匹配的 sub_category_code，
 * 调用 ServiceComplaintImportService 解析文件后归档。
 *
 * <p>骨架版本：仅完成扫描+归档+日志链路，文件内容解析（按真实文件格式迭代）
 * 在 ServiceComplaintImportService.importFile() 中实现。</p>
 */
@Component
public class ServiceComplaintScheduler {

    /** 该 scheduler 负责的 sub_category_code 列表（用于配置匹配） */
    private static final java.util.Set<String> OWNED_SUB_CODES =
            java.util.Set.of("serviceComplaint");

    @Autowired
    private MetricImportConfigRepository importConfigRepository;

    @Autowired
    private LegacyCollectionGuard collectionGuard;

    @Autowired
    private ServiceComplaintImportService serviceComplaintImportService;

    @Autowired
    private OriginalDataImportLogService originalDataImportLogService;

    @Autowired
    private PathResolver pathResolver;

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void scanAndImport() {
        List<MetricImportConfig> configs = importConfigRepository.findByEnabled(1);
        for (MetricImportConfig config : configs) {
            if (OWNED_SUB_CODES.contains(config.getSubCategoryCode())) {
                collectionGuard.scheduled(config, () -> scanConfig(config));
            }
        }
    }

    public void scanAndImportByConfig(MetricImportConfig config) {
        collectionGuard.manual(config, () -> scanConfig(config));
    }

    private void scanConfig(MetricImportConfig config) {
        String importPath = pathResolver.resolve(config.getImportPath());
        File inboxDir = new File(importPath);
        System.out.println("[ServiceComplaintScheduler] Trying import path: " + importPath);

        if (!inboxDir.exists() || !inboxDir.isDirectory()) {
            System.out.println("[ServiceComplaintScheduler] Import path not found: " + importPath);
            return;
        }

        File[] files = inboxDir.listFiles((dir, name) ->
                name.endsWith(".csv") || name.endsWith(".xlsx"));
        if (files == null || files.length == 0) {
            return;
        }

        for (File file : files) {
            try {
                int recordCount = serviceComplaintImportService.importFile(file, config);
                moveToArchive(file, config.getArchivePath());
                originalDataImportLogService.logImport(
                        config.getSubCategoryCode(), "service_complaint", file.getName(), recordCount);
                System.out.println("[ServiceComplaintScheduler] Imported: " + file.getName() + ", records: " + recordCount);
            } catch (Exception e) {
                System.err.println("[ServiceComplaintScheduler] Failed: " + file.getName() + ", " + e.getMessage());
            }
        }
    }

    private void moveToArchive(File file, String archivePath) throws java.io.IOException {
        if (archivePath == null || archivePath.isEmpty()) {
            return;
        }
        File archiveDir = new File(pathResolver.resolve(archivePath));
        if (!archiveDir.exists()) {
            archiveDir.mkdirs();
        }
        java.nio.file.Path target = archiveDir.toPath().resolve(file.getName());
        java.nio.file.Files.move(file.toPath(), target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
