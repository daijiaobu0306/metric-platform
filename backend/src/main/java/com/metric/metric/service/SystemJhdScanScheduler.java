package com.metric.metric.service;

import com.metric.metric.entity.MetricImportConfig;
import com.metric.metric.repository.MetricImportConfigRepository;
import com.metric.collect.service.OriginalDataImportLogService;
import com.metric.shared.util.BasePathProvider;
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
public class SystemJhdScanScheduler {

    @Autowired
    private MetricImportConfigRepository importConfigRepository;

    @Autowired
    private LegacyCollectionGuard collectionGuard;

    @Autowired
    private OriginalDataImportLogService originalDataImportLogService;

    // 用配置的 BasePathProvider 而不是 user.dir，避免 CWD 依赖；运行期可改无需重启
    @Autowired
    private BasePathProvider basePathProvider;

    @Autowired
    private SystemJhdImportService systemJhdImportService;

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void scanAndImport() {
        List<MetricImportConfig> configs = importConfigRepository.findByEnabled(1);
        for (MetricImportConfig config : configs) {
            if ("jihe".equals(config.getSubCategoryCode())) {
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

        // 用配置的 BasePathProvider 而不是 System.getProperty("user.dir")
        File inboxDir = new File(basePathProvider.getBasePath(), importPath);
        System.out.println("[SystemJhdScan] Trying import path: " + inboxDir.getAbsolutePath());

        if (!inboxDir.exists() || !inboxDir.isDirectory()) {
            System.out.println("[SystemJhdScan] Import path not found: " + inboxDir.getAbsolutePath());
            return;
        }

        File[] files = inboxDir.listFiles((dir, name) -> name.endsWith(".txt"));

        if (files == null || files.length == 0) {
            System.out.println("[SystemJhdScan] No .txt files found in: " + inboxDir.getAbsolutePath());
            return;
        }
        System.out.println("[SystemJhdScan] Found " + files.length + " file(s) in: " + inboxDir.getAbsolutePath());

        for (File file : files) {
            try {
                System.out.println("[SystemJhdScan] About to import file: " + file.getAbsolutePath());
                int recordCount = importFile(file);
                System.out.println("[SystemJhdScan] Import returned recordCount: " + recordCount);
                System.out.println("[SystemJhdScan] About to move file to archive...");
                moveToArchive(file, archivePath);
                System.out.println("[SystemJhdScan] About to log import...");
                originalDataImportLogService.logImport("jihe", "system_jhd", file.getName(), recordCount);
                System.out.println("[SystemJhdScan] Successfully imported: " + file.getName() + ", records: " + recordCount);
            } catch (Exception e) {
                System.err.println("[SystemJhdScan] Failed to import: " + file.getName() + ", error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private int importFile(File file) throws IOException {
        try (InputStream inputStream = new FileInputStream(file)) {
            byte[] bytes = inputStream.readAllBytes();
            MultipartFile multipartFile = new ByteArrayMultipartFile(
                file.getName(),
                "text/plain",
                bytes
            );

            return systemJhdImportService.importTxt(multipartFile, file.getName());
        }
    }

    private void moveToArchive(File file, String archiveBasePath) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // 用配置的 BasePathProvider 而不是 System.getProperty("user.dir")
        String archiveDirPath = basePathProvider.getBasePath() + "/" + archiveBasePath + "/" + dateStr;
        File archiveDir = new File(archiveDirPath);

        if (!archiveDir.exists()) {
            archiveDir.mkdirs();
        }

        String timestamp = now.format(DateTimeFormatter.ofPattern("HHmmss"));
        String archiveFileName = file.getName().replace(".txt", "_" + timestamp + ".txt");

        Path source = file.toPath();
        Path target = new File(archiveDir, archiveFileName).toPath();
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);

        System.out.println("[SystemJhdScan] Moved to archive: " + target);
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
