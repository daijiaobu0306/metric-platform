package com.metric.security.service;

import com.metric.security.entity.SysUser;
import com.metric.security.repository.SysUserRepository;
import com.metric.config.repository.SystemConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * OAuth2 userinfo 端点的扩展点（P3 引入，2026-07 切换自 OIDC）。
 *
 * 切换原因：dev 环境的 usermanager 对 OIDC scope 处理有 bug（任何非空 scope 都返 500），
 * 所以现在只走 OAuth2 authorization code 流程（没有 id_token，userinfo 端点返回 attributes）。
 *
 * 2026-07-08 关键修改：BaoLand UserManager 的 `/usermanager/user/me` 端点返回 500（IdP 实现 bug），
 * 但 access_token 本身就是 JWT，载荷里已经包含 sub / user_name / user_id / email / role_infos
 * 等所有需要的字段。本类改为：跳过 super.loadUser()（不再调 /user/me），直接从 access_token
 * 的 JWT 载荷解码出 attributes，然后走原有的 upsert 逻辑。
 *
 *   - 从 access_token JWT 解析 claims（不验签，因为 IdP 的 JWKS 端点也 403 + 我们信任 localhost
 *     / 后续生产 HTTPS；如果需要验签，可注入 NimbusJwtDecoder bean）
 *   - 读 sub（userNameAttribute，与 application.yml user-name-attribute: sub 对齐）
 *   - 读 user_name / preferred_username / email / role_infos
 *   - 按 external_id = sub 查本地 sys_user：
 *       · 命中：刷新 displayName/email/lastLoginTime，保存
 *       · 未命中：看 sso.auto_create_user 开关（默认开启）；开启则自动建账号
 *                  并分配"普通用户"角色；关闭抛 OAuth2AuthenticationException
 *   - 返回带 authorities 的 OAuth2User
 *
 * 注意：本类不写 user_session —— 由 Oauth2LoginSuccessHandler 在 OAuth2 完成后建会话。
 */
@Service
public class OidcSsoUserService extends DefaultOAuth2UserService {

    private static final Logger log = LoggerFactory.getLogger(OidcSsoUserService.class);

    private static final String SSO_AUTO_CREATE_USER_KEY = "sso.auto_create_user";

    @Autowired
    private SysUserRepository sysUserRepository;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private SessionService sessionService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        // dev IdP 的 /user/me 端点 500（implementation bug），不能调 super.loadUser()。
        // 直接从 access_token JWT 解析 user claims。
        Map<String, Object> attributes = decodeJwtPayload(userRequest.getAccessToken().getTokenValue());
        if (attributes == null) {
            // 兜底：access_token 不是 JWT 时再尝试 userinfo（IdP 修好 user/me 后也能用）
            log.warn("access_token 不是标准 JWT 格式，回退到 /user/me 调 userinfo");
            OAuth2User oauth2User = super.loadUser(userRequest);
            attributes = oauth2User.getAttributes();
        }
        log.info("[SSO] JWT claims: {}", attributes);

        String sub = stringAttr(attributes, "sub");
        if (sub == null || sub.isBlank()) {
            sub = stringAttr(attributes, "user_id");
        }
        if (sub == null || sub.isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("sso_missing_sub",
                    "统一认证返回的 access_token 中缺少 sub/user_id，无法识别用户", null));
        }
        String userName = stringAttr(attributes, "user_name");
        if (userName == null || userName.isBlank()) {
            userName = stringAttr(attributes, "preferred_username");
            if (userName == null || userName.isBlank()) {
                userName = stringAttr(attributes, "givenname");
            }
        }
        String email = stringAttr(attributes, "email");

        Optional<SysUser> existing = sysUserRepository.findByExternalId(sub);
        // 首次接入：本地可能有手工建的同名老用户（external_id=NULL）。
        // 若按 sub 找不到，再按 username 找一次，命中则回填 external_id 而非新建，
        // 避免撞 sys_user.username 唯一约束。
        if (existing.isEmpty() && (userName != null && !userName.isBlank())) {
            existing = sysUserRepository.findByUsername(userName);
            if (existing.isPresent()) {
                SysUser legacy = existing.get();
                if (legacy.getExternalId() != null && !sub.equals(legacy.getExternalId()))
                    throw new OAuth2AuthenticationException(new OAuth2Error("sso_username_conflict",
                            "统一认证账号已绑定其他本地身份，请联系管理员核对账号", null));
                log.info("[SSO] 本地用户 {} 之前未绑 SSO（external_id=NULL），现回填 external_id={}",
                        legacy.getUsername(), sub);
                legacy.setExternalId(sub);
            }
        }
        SysUser user;
        if (existing.isPresent()) {
            user = existing.get();
            if (userName != null && !userName.isBlank()) {
                user.setDisplayName(userName);
            }
            if (email != null && !email.isBlank()) {
                user.setEmail(email);
            }
            user.setLastLoginTime(LocalDateTime.now());
            sysUserRepository.save(user);
        } else {
            String autoCreateFlag = systemConfigRepository.findByConfigKey(SSO_AUTO_CREATE_USER_KEY)
                    .map(c -> c.getConfigValue())
                    .orElse("true");
            if (!"true".equalsIgnoreCase(autoCreateFlag)) {
                throw new OAuth2AuthenticationException(new OAuth2Error("sso_auto_create_disabled",
                        "首次通过统一认证登录，但本平台未开启自动建号，请联系管理员开通账号", null));
            }
            String username = (userName != null && !userName.isBlank()) ? userName : "sso_" + Integer.toHexString(sub.hashCode()) + "_" + sub.substring(0, Math.min(20, sub.length()));
            if (username.length() > 50)
                throw new OAuth2AuthenticationException(new OAuth2Error("sso_username_too_long",
                        "统一认证账号名超过本平台长度限制，请联系管理员处理", null));
            String displayName = (userName != null && !userName.isBlank()) ? userName : sub;
            String unusablePwd = passwordEncoder.encode(UUID.randomUUID().toString());
            user = sessionService.createLocalUser(
                    username,
                    displayName,
                    unusablePwd,
                    SessionService.SOURCE_OIDC);
            user.setExternalId(sub);
            user.setEmail(email);
            user.setLastLoginTime(LocalDateTime.now());
            sysUserRepository.save(user);
        }

        if (!Integer.valueOf(1).equals(user.getStatus()))
            throw new OAuth2AuthenticationException(new OAuth2Error("sso_user_disabled",
                "本平台账号已停用，请联系管理员", null));

        // 从 role_infos 或 authorities claim 构造 Spring authorities
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        Object roleInfosObj = attributes.get("role_infos");
        if (roleInfosObj instanceof List<?> roleInfos) {
            for (Object ri : roleInfos) {
                if (ri instanceof Map<?, ?> m) {
                    Object roleId = m.get("role_id");
                    if (roleId != null) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + roleId));
                    }
                }
            }
        }
        // 没有 role_infos 时给个最小权限，保证鉴权不挂；具体业务权限仍走 SessionKeyAuthenticationFilter
        if (authorities.isEmpty()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        }

        // 返回带 authorities 的 OAuth2User（attributes 留给 Oauth2LoginSuccessHandler 用）
        // nameAttributeKey 硬编码 "sub"，与 application.yml user-name-attribute: sub 对齐
        return new DefaultOAuth2User(
                authorities,
                attributes,
                "sub");
    }

    /**
     * 解 JWT 载荷（不验签）。token 格式：header.payload.signature，载荷是 base64url(JSON)。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeJwtPayload(String jwt) {
        if (jwt == null || jwt.isBlank()) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return null;
        try {
            String payload = parts[1];
            // base64url → base64
            String padded = payload + "=".repeat((4 - payload.length() % 4) % 4);
            byte[] decoded = Base64.getUrlDecoder().decode(padded);
            String json = new String(decoded, StandardCharsets.UTF_8);
            // 用 Jackson 解析
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("JWT 载荷解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static String stringAttr(Map<String, Object> attrs, String key) {
        Object v = attrs.get(key);
        return v != null ? v.toString() : null;
    }
}
