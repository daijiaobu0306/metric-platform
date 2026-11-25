package com.metric.collect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Restore only archives owned by the versioned collection-data migration. */
@Service
public class CollectionReleaseFiles {
    private static final Logger log = LoggerFactory.getLogger(CollectionReleaseFiles.class);
    private static final String RESOURCE_ROOT = "local/collection_20261020/";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BasePathProvider paths;

    public CollectionReleaseFiles(JdbcTemplate jdbc, ObjectMapper mapper, BasePathProvider paths) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.paths = paths;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        try (var input = new ClassPathResource(RESOURCE_ROOT + "manifest.json").getInputStream()) {
            JsonNode manifest = mapper.readTree(input);
            Path root = Path.of(paths.getBasePath()).toAbsolutePath().normalize();
            CollectionDirectoryPreparationService.ensureDirectory(root);
            int restored = 0;
            for (JsonNode source : manifest.path("sources")) {
                var configs = jdbc.queryForList("SELECT config_json FROM collection_source WHERE code=?", source.asText());
                if (configs.isEmpty()) continue; // A task deliberately deleted later stays deleted.
                CollectionSource config = mapper.readValue(configs.get(0).get("config_json").toString(), CollectionSource.class);
                for (String value : new String[] {config.importPath(), config.archivePath()}) {
                    CollectionDirectoryPreparationService.ensureDirectory(
                            CollectionDirectoryPreparationService.resolveSource(root.toString(), value));
                }
                Path archive = CollectionDirectoryPreparationService.resolveSource(root.toString(), config.archivePath());
                for (JsonNode batch : manifest.path("batches")) {
                    if (!source.asText().equals(batch.path("sourceCode").asText())) continue;
                    Integer owned = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM collection_file f WHERE f.id=? AND f.source_code=?
                            AND f.file_hash=? AND f.archive_file=?
                            AND EXISTS (SELECT 1 FROM collection_row r WHERE r.batch_id=f.id)
                            """, Integer.class, batch.path("batchId").asText(), source.asText(),
                            batch.path("sha256").asText(), batch.path("archiveFile").asText());
                    if (owned == null || owned == 0) continue;
                    Path target = archive.resolve(batch.path("archiveFile").asText()).normalize();
                    if (!target.getParent().equals(archive)) throw new IOException("增量归档文件名越界");
                    if (copy(batch.path("asset").asText(), batch.path("sha256").asText(), target)) restored++;
                    if (batch.hasNonNull("metadataAsset")) {
                        copy(batch.path("metadataAsset").asText(), batch.path("metadataSha256").asText(),
                                target.resolveSibling(target.getFileName() + ".json"));
                    }
                }
            }
            log.info("Collection release {}: restored {} missing archive files; existing data and settings preserved",
                    manifest.path("release").asText(), restored);
        } catch (IOException e) {
            throw new IllegalStateException("恢复采集增量归档失败，请检查文件根目录、目录权限和冲突文件", e);
        }
    }

    private boolean copy(String resource, String expectedHash, Path target) throws IOException {
        if (resource.isBlank() || resource.contains("..") || resource.startsWith("/") || resource.contains("\\")) {
            throw new IOException("无效的增量资源路径");
        }
        byte[] bytes;
        try (var input = new ClassPathResource(RESOURCE_ROOT + resource).getInputStream()) {
            bytes = input.readAllBytes();
        }
        if (!sha256(bytes).equals(expectedHash)) throw new IOException("增量资源校验不一致：" + resource);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            checkExisting(target, expectedHash);
            return false;
        }
        Path temporary = Files.createTempFile(target.getParent(), ".collection-release-", ".part");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target); // Never replace an existing file.
                return true;
            } catch (FileAlreadyExistsException e) {
                checkExisting(target, expectedHash);
                return false;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void checkExisting(Path target, String expectedHash) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !sha256(Files.readAllBytes(target)).equals(expectedHash)) {
            throw new IOException("保留已有归档文件，内容冲突需人工处理：" + target);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
