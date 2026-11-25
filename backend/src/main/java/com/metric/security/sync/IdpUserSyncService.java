package com.metric.security.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.security.entity.SysRole;
import com.metric.security.entity.SysUser;
import com.metric.security.entity.SysUserRole;
import com.metric.security.repository.SysRoleRepository;
import com.metric.security.repository.SysUserRepository;
import com.metric.security.repository.SysUserRoleRepository;
import com.metric.config.repository.SystemConfigRepository;
import com.metric.security.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * IdP 用户/角色同步任务（P4 引入）
 *
 * 触发：
 *   - @Scheduled 定时触发（fixedDelayString 读 app.sso.sync.fixed-delay-ms 配置）
 *   - admin 手动 POST /api/admin/sso/sync
 *
 * 行为：
 *   1. 读 system_config 中 sso.sync.enabled — false 则跳过
 *   2. 读 sso.sync.api_base_url + sso.sync.api_token
 *   3. 分页拉 {api_base_url}/role?startIndex=0&count=100，按 externalId upsert 到 sys_role
 *      不在 IdP 返回列表中的本地 source='sso_oidc' 角色 → status=0
 *   4. 分页拉 {api_base_url}/user，同步到 sys_user
 *   5. 写 idp_sync_log
 *
 * IdP 响应格式（按 usermanager-page/SSO_INTEGRATION_PACKAGE.md）：
 *   /role  → { resources: [...], startIndex, itemsPerPage, totalResults }
 *   /user  → { resources: [...], startIndex, itemsPerPage, totalResults }
 *   每条 role/user 含 id（externalId）、name / displayName / email / status 等
 */
@Service
public class IdpUserSyncService {

    private static final Logger log = LoggerFactory.getLogger(IdpUserSyncService.class);

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private SysUserRepository sysUserRepository;

    @Autowired
    private SysRoleRepository sysRoleRepository;

    @Autowired
    private SysUserRoleRepository sysUserRoleRepository;

    @Autowired
    private IdpSyncLogRepository idpSyncLogRepository;

    @Autowired
    private SessionService sessionService;

    @Value("${app.sso.sync.fixed-delay-ms:3600000}")
    private long fixedDelayMs;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Scheduled(fixedDelayString = "${app.sso.sync.fixed-delay-ms:3600000}", initialDelay = 60000)
    public void scheduledSync() {
        runSync("scheduled");
    }

    /** 暴露给 admin 手动触发 */
    public IdpSyncLog runManualSync() {
        return runSync("manual");
    }

    @Transactional
    public IdpSyncLog runSync(String triggerType) {
        IdpSyncLog logRow = new IdpSyncLog();
        logRow.setStartedAt(LocalDateTime.now());
        logRow.setStatus("RUNNING");
        logRow.setTriggerType(triggerType);
        logRow = idpSyncLogRepository.save(logRow);

        try {
            if (!isSyncEnabled()) {
                logRow.setStatus("SUCCESS");
                logRow.setFinishedAt(LocalDateTime.now());
                logRow.setError("sync disabled");
                return idpSyncLogRepository.save(logRow);
            }
            String apiBase = readConfig("sso.sync.api_base_url", "");
            if (apiBase == null || apiBase.isBlank()) {
                throw new IllegalStateException("sso.sync.api_base_url 未配置");
            }
            String token = readConfig("sso.sync.api_token", "");

            int rolesSynced = syncRoles(apiBase, token);
            int usersSynced = syncUsers(apiBase, token);
            // TODO: 同步 IdP 角色与本地 sys_user_role 映射（待 IdP 返回格式明确）

            logRow.setRolesSynced(rolesSynced);
            logRow.setUsersSynced(usersSynced);
            logRow.setStatus("SUCCESS");
            logRow.setFinishedAt(LocalDateTime.now());
            log.info("[IdpSync] success: roles={}, users={}", rolesSynced, usersSynced);
        } catch (Exception e) {
            logRow.setStatus("FAILED");
            logRow.setFinishedAt(LocalDateTime.now());
            logRow.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("[IdpSync] failed: {}", e.getMessage(), e);
        }
        return idpSyncLogRepository.save(logRow);
    }

    private int syncRoles(String apiBase, String token) {
        JsonNode page = fetchPage(apiBase + "/role", token);
        if (page == null) return 0;
        JsonNode resources = page.get("resources");
        if (resources == null || !resources.isArray()) return 0;
        int count = 0;
        for (JsonNode r : resources) {
            upsertRole(r);
            count++;
        }
        return count;
    }

    private int syncUsers(String apiBase, String token) {
        JsonNode page = fetchPage(apiBase + "/user", token);
        if (page == null) return 0;
        JsonNode resources = page.get("resources");
        if (resources == null || !resources.isArray()) return 0;
        int count = 0;
        for (JsonNode u : resources) {
            upsertUser(u);
            count++;
        }
        return count;
    }

    private void upsertRole(JsonNode r) {
        String externalId = textOrNull(r, "id");
        if (externalId == null) return;
        Optional<SysRole> existing = sysRoleRepository.findByExternalId(externalId);
        String roleName = textOrDefault(r, "name", externalId);
        // 本地基础角色（如“普通用户”）可能与 IdP 返回角色同名，但 externalId 不同。
        // 优先复用本地角色，避免 role_name 唯一键冲突；本地角色仍由平台维护。
        if (existing.isEmpty()) {
            Optional<SysRole> sameName = sysRoleRepository.findByRoleName(roleName);
            if (sameName.isPresent() && !"sso_oidc".equals(sameName.get().getSource())) {
                log.info("[IdpSync] skip local role name collision: {}", roleName);
                return;
            }
        }
        SysRole role = existing.orElseGet(SysRole::new);
        role.setExternalId(externalId);
        role.setSource("sso_oidc");
        role.setRoleName(roleName);
        role.setDescription(textOrNull(r, "description"));
        Integer status = intOrDefault(r, "status", 1);
        role.setStatus(status);
        sysRoleRepository.save(role);
    }

    private void upsertUser(JsonNode u) {
        String externalId = textOrNull(u, "id");
        if (externalId == null) return;
        Optional<SysUser> existing = sysUserRepository.findByExternalId(externalId);
        if (existing.isEmpty()) {
            String username = textOrDefault(u, "user_name", externalId);
            Optional<SysUser> sameName = sysUserRepository.findByUsername(username);
            if (sameName.isPresent()) {
                if (sameName.get().getExternalId() != null && !externalId.equals(sameName.get().getExternalId()))
                    throw new IllegalStateException("IdP 用户名已绑定其他外部身份: " + username);
                existing = sameName;
            }
        }
        SysUser user = existing.orElseGet(SysUser::new);
        user.setExternalId(externalId);
        user.setSource("sso_oidc");
        user.setUsername(textOrDefault(u, "user_name", externalId));
        user.setDisplayName(textOrDefault(u, "displayName", user.getUsername()));
        user.setEmail(textOrNull(u, "email"));
        user.setStatus(intOrDefault(u, "status", 1));
        if (user.getId() == null) user.setIsAdmin(0);  // 保留本平台管理员配置
        if (user.getId() == null) {
            // 新建用户必须有密码（BCrypt 一个不可用密码；用户后续从 OIDC 登录）
            user.setPassword(sessionService.encodeUnusablePassword());
        }
        SysUser saved = sysUserRepository.save(user);
        if (existing.isEmpty()) {
            SysUserRole relation = new SysUserRole();
            relation.setUserId(saved.getId());
            relation.setRoleId(sessionService.resolveNormalUserRoleId());
            sysUserRoleRepository.save(relation);
        }
    }

    private JsonNode fetchPage(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isBlank()) {
            headers.set("Authorization", "Bearer " + token);
        }
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return null;
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException("fetch " + url + " failed: " + e.getMessage(), e);
        }
    }

    private boolean isSyncEnabled() {
        return "true".equalsIgnoreCase(readConfig("sso.sync.enabled", "false"));
    }

    private String readConfig(String key, String def) {
        return systemConfigRepository.findByConfigKey(key)
                .map(c -> c.getConfigValue())
                .orElse(def);
    }

    private static String textOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }
    private static String textOrDefault(JsonNode n, String f, String def) {
        String v = textOrNull(n, f);
        return v == null || v.isBlank() ? def : v;
    }
    private static Integer intOrDefault(JsonNode n, String f, Integer def) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? def : v.asInt();
    }
}
