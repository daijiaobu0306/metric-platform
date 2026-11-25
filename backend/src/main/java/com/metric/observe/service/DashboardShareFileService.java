package com.metric.observe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.observe.dto.DashboardPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 专屏分享链接鉴权文件维护（appuser 侧读取的 screenUrl.txt）。
 *
 * <p>文件由平台独占写入，格式固定：表头 {@code view,role,user,screenUrl}，一条分享链接一行。
 * 生成链接时写入/覆盖该行；撤销或过期后删除该行；每分钟按最新授权刷新每行的
 * view/role/user 三列（角色成员变动一分钟内生效）。
 *
 * <p>role 列只作说明（角色中文名），真正的鉴权名单在 user 列：角色下的全部有效成员 + 直接授权用户。
 *
 * <p>注意：分享密钥只以哈希形式入库（{@code dashboard_share_link.secret_hash}），
 * 因此只能按 publicId 增量维护行，无法在文件丢失后从数据库重建历史链接的完整 URL。
 */
@Service
public class DashboardShareFileService {
    private static final Logger log = LoggerFactory.getLogger(DashboardShareFileService.class);

    /** 是否启用写文件（1/0、true/false） */
    static final String KEY_ENABLED = "screen.share.file.enabled";
    /** 鉴权文件绝对路径，例如 /home/appuser/4Alogin/screenUrl.txt */
    static final String KEY_PATH = "screen.share.file.path";
    /** 分享链接的对外访问前缀，例如 https://211.17.117.144:18080 */
    static final String KEY_BASE_URL = "screen.share.file.base-url";

    private static final String HEADER = "view,role,user,screenUrl";
    private static final int PREVIEW_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final DashboardAccessService access;
    private final ObjectMapper json;
    private final ReentrantLock lock = new ReentrantLock();

    /** Docker 部署可用环境变量覆盖：SCREEN_SHARE_FILE_PATH / SCREEN_SHARE_FILE_BASE_URL / SCREEN_SHARE_FILE_ENABLED */
    @Value("${screen.share.file.path:}")
    private String envPath;
    @Value("${screen.share.file.base-url:}")
    private String envBaseUrl;
    @Value("${screen.share.file.enabled:}")
    private String envEnabled;

    public DashboardShareFileService(JdbcTemplate jdbc, DashboardAccessService access, ObjectMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.json = json;
    }

    // ---------------------------------------------------------------- 配置读写

    public Map<String, Object> config() {
        access.requireAdmin();
        Map<String, Object> out = new LinkedHashMap<>();
        Path path = path();
        out.put("enabled", enabled());
        out.put("path", path == null ? "" : path.toString());
        out.put("pathSource", source(envPath, KEY_PATH));
        out.put("baseUrl", baseUrlOrEmpty());
        out.put("baseUrlSource", source(envBaseUrl, KEY_BASE_URL));
        out.put("envPath", blank(envPath) ? "" : envPath.trim());
        out.put("envBaseUrl", blank(envBaseUrl) ? "" : envBaseUrl.trim());
        out.put("exists", path != null && Files.exists(path));
        out.put("header", HEADER);
        return out;
    }

    /** 支持只提交部分字段（例如只改对外地址时不必回传路径）。 */
    public Map<String, Object> saveConfig(Map<String, Object> body) {
        access.requireAdmin();
        String pathValue = body.containsKey("path") ? text(body.get("path")) : text(cfg(KEY_PATH));
        String baseUrl = body.containsKey("baseUrl") ? normalizeBase(text(body.get("baseUrl"))) : baseUrlOrEmpty();
        boolean enabled = body.containsKey("enabled") ? truthy(String.valueOf(body.get("enabled"))) : enabled();

        if (!pathValue.isEmpty()) {
            validatePath(pathValue);
            testWritable(Path.of(pathValue.trim()).toAbsolutePath().normalize());
        } else if (enabled && blank(envPath)) {
            throw bad("启用鉴权文件时必须填写文件绝对路径");
        }
        if (!baseUrl.isEmpty() && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw bad("对外访问地址需以 http:// 或 https:// 开头");
        }
        if (body.containsKey("path")) save(KEY_PATH, pathValue);
        if (body.containsKey("baseUrl")) {
            save(KEY_BASE_URL, baseUrl);
            rebase(baseUrl);
        }
        if (body.containsKey("enabled")) save(KEY_ENABLED, enabled ? "1" : "0");
        return preview();
    }

    /** 对外地址变更后，把文件里已有链接的域名前缀一起换掉（密钥保留在 URL 尾部，无需重新生成链接）。 */
    private void rebase(String base) {
        Path target = path();
        if (target == null || base.isEmpty() || !Files.exists(target)) return;
        try {
            lock.lock();
            try {
                List<String> lines = read(target);
                List<String> next = new ArrayList<>(lines.size());
                boolean changed = false;
                for (String line : lines) {
                    if (isHeader(line)) {
                        next.add(HEADER);
                        continue;
                    }
                    String url = urlOf(line);
                    String publicId = publicIdOf(line);
                    if (url.isEmpty() || publicId == null) {
                        next.add(line);
                        continue;
                    }
                    String suffix = url.contains("#k=") ? url.substring(url.indexOf("#k=")) : "";
                    String rebuilt = base + "/metric-code/screen/s/" + publicId + suffix;
                    if (!rebuilt.equals(url)) changed = true;
                    next.add(line.substring(0, line.lastIndexOf(url)) + rebuilt);
                }
                if (changed) write(target, next);
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            log.warn("按新的对外地址刷新鉴权文件失败：{}", e.toString());
        }
    }

    // ---------------------------------------------------------------- 链接维护

    /**
     * 写入或覆盖一条分享链接对应的文件行。
     *
     * @param linkId   dashboard_share_link.id
     * @param secret   明文密钥（仅生成时可得，不落库）
     * @param fallback 页面当前访问地址，用于首次自动确定对外前缀
     * @return 写入失败时的提示信息；成功或未启用返回 null
     */
    public String record(Long linkId, String secret, String fallback) {
        if (!enabled()) return null;
        Path target = path();
        if (target == null) return null;
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT l.public_id,v.id AS view_id,v.view_name,v.layout_config FROM dashboard_share_link l "
                            + "JOIN dashboard_view_config v ON v.id=l.view_id WHERE l.id=?", linkId);
            String publicId = text(row.get("public_id"));
            String viewName = text(row.get("view_name"));
            String[] subjects = subjects(id(row.get("view_id")), text(row.get("layout_config")), null);
            if (subjects == null) subjects = new String[]{"", ""};
            String base = baseUrl(fallback);
            String url = (base.isEmpty() ? "" : base) + "/metric-code/screen/s/" + publicId + "#k=" + secret;
            String line = String.join(",", csv(viewName), csv(subjects[0]), csv(subjects[1]), url);

            lock.lock();
            try {
                List<String> lines = read(target);
                ensureHeader(lines);
                lines.removeIf(item -> item.contains(token(publicId)));
                lines.add(line);
                write(target, lines);
            } finally {
                lock.unlock();
            }
            return null;
        } catch (Exception e) {
            log.warn("写入专屏鉴权文件失败（linkId={}）：{}", linkId, e.toString());
            return "分享链接已生成，但写入鉴权文件失败：" + trimMessage(e);
        }
    }

    /** 链接撤销后删除对应行。 */
    public void remove(String publicId) {
        if (!enabled() || blank(publicId)) return;
        Path target = path();
        if (target == null) return;
        try {
            lock.lock();
            try {
                List<String> lines = read(target);
                if (lines.removeIf(item -> item.contains(token(publicId)))) write(target, lines);
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("删除专屏鉴权文件行失败（publicId={}）：{}", publicId, e.toString());
        }
    }

    /** 手动同步：刷新行内容、清理失效行并返回文件内容预览。 */
    public Map<String, Object> sync() {
        access.requireAdmin();
        int removed = sweepInternal();
        Map<String, Object> out = preview();
        out.put("removed", removed);
        return out;
    }

    /**
     * 每分钟刷新一次：按最新授权重写每行的 view/role/user 三列（角色成员变动即时生效），
     * 并删除已撤销/已过期链接对应的行；永久有效链接不受影响（到期时间为 9999-12-31）。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void sweep() {
        try {
            if (!enabled() || path() == null) return;
            sweepInternal();
        } catch (Exception e) {
            log.warn("专屏鉴权文件定时刷新失败：{}", e.toString());
        }
    }

    /** @return 本次删除的失效行数 */
    private int sweepInternal() {
        Path target = path();
        if (target == null || !Files.exists(target)) return 0;
        List<Map<String, Object>> links = jdbc.queryForList(
                "SELECT l.public_id,l.view_id,l.status,l.expires_at,v.view_name,v.layout_config "
                        + "FROM dashboard_share_link l JOIN dashboard_view_config v ON v.id=l.view_id");
        Map<String, Map<String, Object>> byPublicId = new HashMap<>();
        links.forEach(link -> byPublicId.put(text(link.get("public_id")), link));
        Map<Long, String[]> subjectCache = new HashMap<>();
        try {
            lock.lock();
            try {
                List<String> lines = read(target);
                List<String> next = new ArrayList<>(lines.size());
                int removed = 0;
                for (String line : lines) {
                    if (isHeader(line)) {
                        next.add(HEADER);
                        continue;
                    }
                    String publicId = publicIdOf(line);
                    Map<String, Object> link = publicId == null ? null : byPublicId.get(publicId);
                    if (link == null) {
                        // 无法识别或库中已不存在的行：原样保留，避免误删
                        next.add(line);
                        continue;
                    }
                    if (!valid(link)) {
                        removed++;
                        continue;
                    }
                    String[] columns = subjects(id(link.get("view_id")), text(link.get("layout_config")), subjectCache);
                    if (columns == null) {
                        // 授权配置解析失败时保留原行，避免把可访问名单清空
                        next.add(line);
                        continue;
                    }
                    String url = urlOf(line);
                    next.add(String.join(",", csv(text(link.get("view_name"))), csv(columns[0]), csv(columns[1]),
                            url.isEmpty() ? line : url));
                }
                if (!next.equals(lines)) write(target, next);
                return removed;
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            log.warn("刷新专屏鉴权文件失败：{}", e.toString());
            return 0;
        }
    }

    // ---------------------------------------------------------------- 行内容

    /**
     * role 列写入视图授权的角色名（中文，仅供人阅读）；
     * user 列写入真正的鉴权名单：角色下的全部有效成员 + 直接授权的用户，多个用 | 分隔。
     */
    private String[] subjects(Long viewId, String layoutConfig, Map<Long, String[]> cache) {
        if (viewId != null && cache != null && cache.containsKey(viewId)) return cache.get(viewId);
        Set<String> roles = new LinkedHashSet<>();
        Set<String> users = new LinkedHashSet<>();
        try {
            DashboardPolicy policy = json.readValue(layoutConfig, DashboardPolicy.class);
            for (DashboardPolicy.Grant grant : policy.grants()) {
                if ("role".equals(grant.subjectType())) {
                    add(roles, name("SELECT role_name FROM sys_role WHERE id=?", grant.subjectId()));
                    users.addAll(members(grant.subjectId()));
                } else if ("user".equals(grant.subjectType())) {
                    add(users, name("SELECT username FROM sys_user WHERE id=? AND status=1", grant.subjectId()));
                }
            }
        } catch (Exception e) {
            log.warn("解析专屏授权配置失败，鉴权文件将写入空角色/用户列：{}", e.toString());
            return null;
        }
        String[] result = {String.join("|", roles), String.join("|", users)};
        if (viewId != null && cache != null) cache.put(viewId, result);
        return result;
    }

    /** 角色成员：取该角色下所有启用用户（鉴权以 user 为准，角色只作说明）。 */
    private List<String> members(Long roleId) {
        if (roleId == null) return List.of();
        return jdbc.queryForList("SELECT u.username FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id "
                + "JOIN sys_role r ON r.id=ur.role_id AND r.status=1 "
                + "WHERE ur.role_id=? AND u.status=1 ORDER BY u.username", String.class, roleId);
    }

    private boolean valid(Map<String, Object> link) {
        if (!Integer.valueOf(1).equals(((Number) link.get("status")).intValue())) return false;
        LocalDateTime expires = dateTime(link.get("expires_at"));
        return expires == null || !expires.isBefore(LocalDateTime.now());
    }

    private LocalDateTime dateTime(Object value) {
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof java.sql.Timestamp time) return time.toLocalDateTime();
        return null;
    }

    private String name(String sql, Long id) {
        if (id == null) return null;
        List<String> rows = jdbc.queryForList(sql, String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void add(Collection<String> target, String value) {
        if (!blank(value)) target.add(value.trim());
    }

    private boolean isHeader(String line) {
        return line.trim().toLowerCase(Locale.ROOT).startsWith("view,role,user");
    }

    /** 从文件行中解析 publicId；解析不出返回 null（该行原样保留）。 */
    private String publicIdOf(String line) {
        int start = line.indexOf("/screen/s/");
        if (start < 0) return null;
        int end = line.indexOf("#k=", start);
        return end < 0 ? null : line.substring(start + "/screen/s/".length(), end);
    }

    /** 从文件行中取出 URL（最后一个 http 开始的部分），用于刷新前两列时保持链接不变。 */
    private String urlOf(String line) {
        int pointer = line.indexOf("/screen/s/");
        if (pointer < 0) return "";
        int start = line.lastIndexOf("http", pointer);
        return start < 0 ? "" : line.substring(start).trim();
    }

    private Long id(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    // ---------------------------------------------------------------- 文件读写

    private List<String> read(Path target) throws IOException {
        if (!Files.exists(target)) return new ArrayList<>();
        List<String> lines = new ArrayList<>(Files.readAllLines(target, StandardCharsets.UTF_8));
        lines.removeIf(String::isBlank);
        return lines;
    }

    private void ensureHeader(List<String> lines) {
        if (lines.isEmpty()) {
            lines.add(HEADER);
            return;
        }
        if (!isHeader(lines.get(0))) lines.add(0, HEADER);
    }

    /** 先写临时文件再原子替换，避免 appuser 读到半个文件。 */
    private void write(Path target, List<String> lines) throws IOException {
        Path dir = target.getParent();
        if (dir != null) Files.createDirectories(dir);
        String content = String.join("\n", lines) + "\n";
        Path tmp = dir == null ? Files.createTempFile(target.getFileName().toString(), ".tmp")
                : Files.createTempFile(dir, target.getFileName().toString() + ".", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // Windows 本地或无权限时忽略，不影响写入结果
        }
    }

    private Map<String, Object> preview() {
        Map<String, Object> out = new LinkedHashMap<>(config());
        Path target = path();
        if (target == null) {
            out.put("content", "");
            out.put("lineCount", 0);
            return out;
        }
        try {
            List<String> lines = read(target);
            out.put("lineCount", Math.max(0, lines.size() - 1));
            out.put("content", String.join("\n", lines.subList(0, Math.min(lines.size(), PREVIEW_LIMIT))));
        } catch (IOException e) {
            out.put("content", "");
            out.put("lineCount", 0);
            out.put("readError", trimMessage(e));
        }
        return out;
    }

    // ---------------------------------------------------------------- 配置项

    private boolean enabled() {
        String env = normalize(envEnabled);
        if (env != null) return truthy(env);
        String cfg = normalize(cfg(KEY_ENABLED));
        if (cfg != null) return truthy(cfg);
        return path() != null;
    }

    private Path path() {
        String raw = firstNonBlank(envPath, cfg(KEY_PATH));
        if (raw == null) return null;
        try {
            return Path.of(raw.trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            log.warn("专屏鉴权文件路径不合法：{}", raw);
            return null;
        }
    }

    private String baseUrlOrEmpty() {
        String configured = firstNonBlank(envBaseUrl, cfg(KEY_BASE_URL));
        return configured == null ? "" : normalizeBase(configured);
    }

    /** 已配置的对外访问地址；为空表示尚未设置（历史列表此时只回传相对路径）。 */
    public String externalBaseUrl() {
        return baseUrlOrEmpty();
    }

    /** 对外访问地址：环境变量 > 配置项 > 当前页面地址（首次自动写入配置，便于后续修改）。 */
    private String baseUrl(String fallback) {
        String configured = baseUrlOrEmpty();
        if (!configured.isEmpty()) return configured;
        String auto = normalizeBase(fallback);
        if (!auto.isEmpty()) save(KEY_BASE_URL, auto);
        return auto;
    }

    private String cfg(String key) {
        List<String> rows = jdbc.queryForList("SELECT config_value FROM system_config WHERE config_key=?", String.class, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void save(String key, String value) {
        jdbc.update("INSERT INTO system_config(config_key,config_value,config_type,description,status) "
                        + "VALUES(?,?,?,?,1) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),update_time=NOW()",
                key, value, "screen-share", "专屏分享鉴权文件配置");
    }

    private String source(String envValue, String key) {
        return normalize(envValue) != null ? "env" : normalize(cfg(key)) != null ? "db" : "none";
    }

    private void validatePath(String raw) {
        if (raw.contains("..")) throw bad("路径不能包含 ..");
        try {
            if (!Path.of(raw.trim()).isAbsolute()) throw bad("请填写绝对路径，例如 /home/appuser/4Alogin/screenUrl.txt");
        } catch (java.nio.file.InvalidPathException e) {
            throw bad("路径格式不合法：" + e.getMessage());
        }
    }

    private void testWritable(Path target) {
        Path dir = target.getParent();
        try {
            if (dir != null) Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, "screen-url-probe.", ".tmp");
            Files.deleteIfExists(probe);
        } catch (Exception e) {
            throw bad("目录不可写：" + trimMessage(e));
        }
    }

    private String token(String publicId) {
        return "/screen/s/" + publicId + "#k=";
    }

    private String csv(String value) {
        String text = value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
        return text.contains(",") || text.contains("\"") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    private String normalizeBase(String value) {
        if (blank(value)) return "";
        String text = value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private boolean truthy(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        return Set.of("1", "true", "yes", "on").contains(text);
    }

    private String firstNonBlank(String first, String second) {
        String a = normalize(first);
        return a != null ? a : normalize(second);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String trimMessage(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
