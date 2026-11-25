package com.metric.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import com.metric.skill.dto.SkillSummary;
import com.metric.skill.service.BuiltinSkillCatalog.BuiltinSkill;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能仓库：内置技能（随平台发布）+ 团队上传技能（落在 upload.base-path/skills）。
 *
 * <p>上传的技能包不自带数据库记录，元信息与压缩包同目录成对存放：
 * {@code files/skills/<id>.zip} + {@code files/skills/<id>.json}。
 * 好处是仓库可以直接用文件系统备份/迁移，不牵动 Flyway 迁移；
 * 技能名与简介优先从包内 {@code SKILL.md} 的 front-matter 解析，保持「包即事实」。</p>
 */
@Service
@RequiredArgsConstructor
public class SkillRepositoryService {

    private static final String BUILTIN_PREFIX = "builtin-";
    private static final String UPLOADED_PREFIX = "up-";
    private static final long MAX_PACKAGE_SIZE = 20L * 1024 * 1024;
    private static final int MAX_SKILL_MD_SIZE = 256 * 1024;
    private static final int MAX_ENTRIES = 2000;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BasePathProvider basePathProvider;
    private final ObjectMapper objectMapper;

    /** 上传技能的元信息，与压缩包成对存放 */
    @Data
    public static class StoredSkill {
        private String id;
        private String name;
        private String description;
        private String version = "1.0";
        private int fileCount;
        private long size;
        private String uploader;
        private String uploadedAt;
        private String originalName;
    }

    public List<SkillSummary> list(String operator, boolean admin) {
        List<SkillSummary> skills = new ArrayList<>();
        for (BuiltinSkill skill : BuiltinSkillCatalog.all()) {
            Map<String, String> front = readBuiltinFrontMatter(skill);
            String name = firstNonBlank(front.get("name"), skill.displayName());
            skills.add(new SkillSummary(
                    BUILTIN_PREFIX + skill.directory(),
                    name,
                    firstNonBlank(front.get("description"), "平台内置技能"),
                    firstNonBlank(front.get("version"), "1.0"),
                    skill.entries().size(),
                    BuiltinSkillCatalog.packageSize(skill),
                    "BUILTIN",
                    "平台内置",
                    null,
                    name + ".zip",
                    false));
        }
        skills.addAll(listUploaded(operator, admin));
        return skills;
    }

    /** 下载：内置技能现打包，上传技能直接读文件 */
    public Download download(String id) throws IOException {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("技能不存在");
        }
        if (id.startsWith(BUILTIN_PREFIX)) {
            String directory = id.substring(BUILTIN_PREFIX.length());
            BuiltinSkill skill = BuiltinSkillCatalog.all().stream()
                    .filter(item -> item.directory().equals(directory))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("技能不存在"));
            Map<String, String> front = readBuiltinFrontMatter(skill);
            String name = firstNonBlank(front.get("name"), skill.displayName());
            return new Download(name + ".zip", SkillPackageWriter.zipSingle(skill));
        }
        StoredSkill meta = readMeta(id);
        Path packagePath = packagePath(id);
        if (meta == null || !Files.isRegularFile(packagePath)) {
            throw new IllegalArgumentException("技能不存在或安装包已丢失");
        }
        return new Download(firstNonBlank(meta.getName(), id) + ".zip", Files.readAllBytes(packagePath));
    }

    public SkillSummary upload(MultipartFile file, String operator) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的技能包");
        }
        if (file.getSize() > MAX_PACKAGE_SIZE) {
            throw new IllegalArgumentException("技能包不能超过 20MB");
        }
        byte[] content = file.getBytes();
        SkillScan scan = scan(content);
        if (scan.skillMarkdown() == null) {
            throw new IllegalArgumentException("技能包内未找到 SKILL.md，请按技能规范（SKILL.md + references）打包后再上传");
        }
        Map<String, String> front = parseFrontMatter(scan.skillMarkdown());
        String fallbackName = stripExtension(file.getOriginalFilename());
        String name = firstNonBlank(front.get("name"), fallbackName);
        if (name == null) {
            throw new IllegalArgumentException("无法识别技能名称，请在 SKILL.md 的 name 字段填写");
        }

        String id = nextId();
        Path dir = skillsDir();
        Files.createDirectories(dir);
        Files.write(packagePath(id), content);

        StoredSkill meta = new StoredSkill();
        meta.setId(id);
        meta.setName(name);
        meta.setDescription(firstNonBlank(front.get("description"), "团队上传技能"));
        meta.setVersion(firstNonBlank(front.get("version"), "1.0"));
        meta.setFileCount(scan.entryCount());
        meta.setSize(content.length);
        meta.setUploader(operator);
        meta.setUploadedAt(LocalDateTime.now().format(DISPLAY_TIME));
        meta.setOriginalName(file.getOriginalFilename());
        objectMapper.writeValue(metaPath(id).toFile(), meta);

        return toSummary(meta, operator, true);
    }

    public void delete(String id, String operator, boolean admin) throws IOException {
        if (id == null || !SAFE_ID.matcher(id).matches() || id.startsWith(BUILTIN_PREFIX)) {
            throw new IllegalArgumentException("内置技能不可删除");
        }
        StoredSkill meta = readMeta(id);
        if (meta == null) {
            throw new IllegalArgumentException("技能不存在");
        }
        if (!admin && !operator.equals(meta.getUploader())) {
            throw new IllegalArgumentException("只能删除自己上传的技能");
        }
        Files.deleteIfExists(packagePath(id));
        Files.deleteIfExists(metaPath(id));
    }

    // ---------------------------------------------------------------- 内部实现

    private List<SkillSummary> listUploaded(String operator, boolean admin) {
        Path dir = skillsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<StoredSkill> metas = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            metas.add(objectMapper.readValue(path.toFile(), StoredSkill.class));
                        } catch (IOException ignored) {
                            // 元信息损坏的技能包不展示，但文件保留，便于人工排查
                        }
                    });
        } catch (IOException ignored) {
            return List.of();
        }
        metas.sort(Comparator.comparing(StoredSkill::getUploadedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return metas.stream().map(meta -> toSummary(meta, operator, admin)).toList();
    }

    private SkillSummary toSummary(StoredSkill meta, String operator, boolean admin) {
        boolean deletable = admin || (operator != null && operator.equals(meta.getUploader()));
        return new SkillSummary(
                meta.getId(),
                meta.getName(),
                meta.getDescription(),
                meta.getVersion(),
                meta.getFileCount(),
                meta.getSize(),
                "UPLOADED",
                meta.getUploader(),
                meta.getUploadedAt(),
                firstNonBlank(meta.getName(), meta.getId()) + ".zip",
                deletable);
    }

    private StoredSkill readMeta(String id) {
        Path path = metaPath(id);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return objectMapper.readValue(path.toFile(), StoredSkill.class);
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, String> readBuiltinFrontMatter(BuiltinSkill skill) {
        try {
            return parseFrontMatter(BuiltinSkillCatalog.readText(skill, "SKILL.md"));
        } catch (IOException e) {
            return Map.of();
        }
    }

    private Path skillsDir() {
        return Paths.get(basePathProvider.getBasePath()).toAbsolutePath().normalize().resolve("skills");
    }

    private Path packagePath(String id) {
        return skillsDir().resolve(id + ".zip");
    }

    private Path metaPath(String id) {
        return skillsDir().resolve(id + ".json");
    }

    private String nextId() {
        return UPLOADED_PREFIX + LocalDateTime.now().format(ID_TIME)
                + "-" + String.format("%04x", RANDOM.nextInt(0x10000));
    }

    /** 扫描压缩包：取 SKILL.md 正文，同时统计条目数，避免解压整包 */
    private SkillScan scan(byte[] content) throws IOException {
        String skillMarkdown = null;
        int entryCount = 0;
        String bestEntry = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entryCount > MAX_ENTRIES) {
                    throw new IllegalArgumentException("技能包条目过多，请拆分后再上传");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                boolean isSkillMarkdown = "SKILL.md".equalsIgnoreCase(name)
                        || name.toLowerCase(Locale.ROOT).endsWith("/skill.md");
                if (isSkillMarkdown && (bestEntry == null || name.length() < bestEntry.length())) {
                    bestEntry = name;
                    skillMarkdown = new String(zip.readNBytes(MAX_SKILL_MD_SIZE), StandardCharsets.UTF_8);
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无法解析技能包，请使用 UTF-8 文件名的 ZIP");
        }
        return new SkillScan(skillMarkdown, entryCount);
    }

    /** 解析 SKILL.md 顶部的 YAML front-matter，只取 name / description / version 三个标量 */
    static Map<String, String> parseFrontMatter(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.stripLeading();
        if (!normalized.startsWith("---")) {
            return Map.of();
        }
        int firstLineEnd = normalized.indexOf('\n');
        if (firstLineEnd < 0) {
            return Map.of();
        }
        int close = normalized.indexOf("\n---", firstLineEnd);
        if (close < 0) {
            return Map.of();
        }
        String block = normalized.substring(firstLineEnd + 1, close);
        Map<String, String> values = new java.util.HashMap<>();
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0 || line.startsWith(" ") || line.startsWith("\t")) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            if (!key.isEmpty() && !value.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String stripExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private record SkillScan(String skillMarkdown, int entryCount) {
    }

    /** 下载内容：文件名 + 字节 */
    public record Download(String fileName, byte[] content) {
    }
}
