package com.metric.config.service;

import com.metric.metric.entity.MetricImportConfig;
import com.metric.metric.service.LegacyCollectionGuard;
import com.metric.metric.repository.MetricImportConfigRepository;
import com.metric.shared.util.PathResolver;
import com.metric.collect.service.OriginalDataImportLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ErrorFrontendScanScheduler {

    @Autowired
    private MetricImportConfigRepository importConfigRepository;

    @Autowired
    private LegacyCollectionGuard collectionGuard;

    @Autowired
    private ErrorFrontendImportService errorFrontendImportService;

    @Autowired
    private OriginalDataImportLogService originalDataImportLogService;

    // 用 PathResolver 把 DB 中的相对路径解析为 baseUploadPath + path（支持绝对路径短路）
    @Autowired
    private PathResolver pathResolver;

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void scanAndImport() {
        List<MetricImportConfig> configs = importConfigRepository.findByEnabled(1);
        for (MetricImportConfig config : configs) {
            if ("error_frontend".equals(config.getSubCategoryCode())) {
                collectionGuard.scheduled(config, () -> scanConfig(config));
            }
        }
    }

    public void scanAndImportByConfig(MetricImportConfig config) {
        collectionGuard.manual(config, () -> scanConfig(config));
    }

    private void scanConfig(MetricImportConfig config) {
        String importPath = config.getImportPath();
        String archivePath = config.getArchivePath();

        // 用 PathResolver 解析为完整路径，避免裸相对路径落到 CWD
        String resolvedImportPath = pathResolver.resolve(importPath);
        File inboxDir = new File(resolvedImportPath);
        System.out.println("[ErrorFrontendScan] Trying import path: " + resolvedImportPath);

        if (!inboxDir.exists() || !inboxDir.isDirectory()) {
            System.out.println("[ErrorFrontendScan] Import path not found: " + resolvedImportPath);
            return;
        }

        File[] files = inboxDir.listFiles((dir, name) ->
            name.endsWith(".csv") || name.endsWith(".xlsx"));

        if (files == null || files.length == 0) {
            return;
        }

        for (File file : files) {
            try {
                int recordCount = importFile(file);
                moveToArchive(file, archivePath);
                // 根据实际 sheet 类型动态写正确的表名
                // 业务返回码 → metric_error_return_code
                // 业务返回异常 → metric_error_biz_return
                String tableName = file.getName().contains("返回码")
                    ? "metric_error_return_code"
                    : "metric_error_biz_return";
                originalDataImportLogService.logImport("error_frontend", tableName, file.getName(), recordCount);
                System.out.println("[ErrorFrontendScan] Successfully imported: " + file.getName()
                    + " → " + tableName + ", records: " + recordCount);
            } catch (Exception e) {
                System.err.println("[ErrorFrontendScan] Failed to import: " + file.getName() + ", error: " + e.getMessage());
            }
        }
    }

    private int importFile(File file) throws IOException {
        try (InputStream inputStream = new FileInputStream(file)) {
            byte[] bytes = inputStream.readAllBytes();
            MultipartFile multipartFile = new ByteArrayMultipartFile(
                file.getName(),
                getContentType(file.getName()),
                bytes
            );

            if (file.getName().endsWith(".xlsx")) {
                return errorFrontendImportService.importExcel(multipartFile, file.getName());
            }
            return 0;
        }
    }

    private String getContentType(String fileName) {
        if (fileName.endsWith(".csv")) {
            return "text/csv";
        } else if (fileName.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/octet-stream";
    }

    private void moveToArchive(File file, String archiveBasePath) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        // 用 PathResolver 解析归档根目录（DB 里的相对路径），再拼接日期子目录
        String archiveDirPath = pathResolver.resolve(archiveBasePath) + "/" + dateStr;
        File archiveDir = new File(archiveDirPath);

        if (!archiveDir.exists()) {
            archiveDir.mkdirs();
        }

        String timestamp = now.format(DateTimeFormatter.ofPattern("HHmmss"));
        String archiveFileName = file.getName().replace(".csv", "_" + timestamp + ".csv")
                                       .replace(".xlsx", "_" + timestamp + ".xlsx");

        Path source = file.toPath();
        Path target = new File(archiveDir, archiveFileName).toPath();
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);

        System.out.println("[ErrorFrontendScan] Moved to archive: " + target);
    }

    private static class ByteArrayMultipartFile implements MultipartFile {
        private final String name;
        private final String contentType;
        private final byte[] bytes;

        public ByteArrayMultipartFile(String name, String contentType, byte[] bytes) {
            this.name = name;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getOriginalFilename() { return name; }

        @Override
        public String getContentType() { return contentType; }

        @Override
        public boolean isEmpty() { return bytes.length == 0; }

        @Override
        public long getSize() { return bytes.length; }

        @Override
        public byte[] getBytes() { return bytes; }

        @Override
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
