package com.metric.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.config.entity.SystemConfig;
import com.metric.config.repository.SystemConfigRepository;
import com.metric.security.entity.SysUser;
import com.metric.security.service.CurrentUserProvider;
import com.metric.security.service.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

@Service
public class ZhiyunService {
    public static final String KEY = "integration.zhiyun.v1";
    public record Settings(boolean enabled, String callbackUrl, String redirect, String issuer,
                           String username, String nickname, String email, String phone) {}
    public record Update(String version, Settings config) {}
    private final SystemConfigRepository repository;
    private final CurrentUserProvider current;
    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public ZhiyunService(SystemConfigRepository repository, CurrentUserProvider current,
                         SessionService sessions, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.repository = repository;
        this.current = current;
        this.sessions = sessions;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    private SysUser user() {
        return current.currentUser().orElseThrow(() -> new IllegalArgumentException("请先登录"));
    }

    private void admin() {
        if (!Integer.valueOf(1).equals(user().getIsAdmin())) throw new IllegalArgumentException("仅管理员可维护智云配置");
    }

    private Settings defaults() {
        return new Settings(true, "http://192.168.1.13:8050/staragent/auth/callback/common",
                "/share/a/RvmA4wxW2hCdznPKG02XbqQHHPtv_u55-zCMyIi-E8ngEAZdYT3iVgGcHnR3IEM63BmeS7ykvX4VQhLiJKcJ0xWNsOtD6aQt1_oPQwAAAADBDdyLDJQ4C7xkN_j3oHHC76kz14vsHPXp_hyI5wrNCbafinVCUTDj8oEeUMsI8hdhFDACQeAiMU1WvmpTvcbMzpVoHpsC683i_WH5CImA2z1dweXE_bJ5c8R1zhrej33R3YgIHdEsd1rsMSVlOfDxeAtxeKaBK-hZKvrL_kTiEYg26UEo0QH1rVDr2CivHmuJ77X7Ox_sadIVFteP4p0iCVokyB76YsbMcVouX0DXbWcUZSFF6Iba_7u1Dn7Vwpy3-OMfYI93JUP_Mlr79kz-KzY6leelAPF999YXjj7XhYbhaevo742yELjvwSWOUmw5krSyrZJm0HaXUhDLMN-7VA-p5phe0xPnLoRiUTbhiMFH",
                "MOP-system", "wbyupeng1", "", "", "");
    }

    private Settings read() {
        return repository.findByConfigKey(KEY).map(c -> {
            try { return mapper.readValue(c.getConfigValue(), Settings.class); }
            catch (Exception e) { throw new IllegalArgumentException("智云配置读取失败"); }
        }).orElseGet(this::defaults);
    }

    private String version() {
        return repository.findByConfigKey(KEY).map(c -> DigestUtils.md5DigestAsHex(
                c.getConfigValue().getBytes(StandardCharsets.UTF_8))).orElse("new");
    }

    public Map<String, Object> management() {
        admin();
        String secret = System.getenv("STARAGENT_COMMON_AUTH_SECRET");
        return Map.of("config", read(), "version", version(), "secretConfigured", secret != null && !secret.isBlank());
    }

    @Transactional
    public Map<String, String> save(Update update) {
        admin();
        jdbc.queryForList("SELECT id FROM system_config WHERE config_key=? FOR UPDATE", KEY);
        if (!Objects.equals(version(), update.version())) throw new IllegalArgumentException("配置已变更，请刷新后重试");
        Settings s = update.config();
        if (s == null) throw new IllegalArgumentException("配置不能为空");
        URI url;
        try { url = URI.create(s.callbackUrl()); }
        catch (Exception e) { throw new IllegalArgumentException("请填写有效回调地址"); }
        if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null
                || url.getUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null
                || !url.getPath().endsWith("/auth/callback/common"))
            throw new IllegalArgumentException("回调地址须为http/https的/auth/callback/common地址，不含查询参数或账号密码");
        if (s.redirect() == null || !s.redirect().startsWith("/share/a/") || s.redirect().length() > 4096
                || !s.redirect().matches("/share/a/[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("分享目标须为/share/a/开头的站内分享路径，请勿填写完整URL或编码后的路径");
        required(s.issuer(), "系统标识", 100);
        required(s.username(), "智云登录账号", 100);
        for (String v : new String[]{s.nickname(), s.email(), s.phone()})
            if (v != null && v.length() > 200) throw new IllegalArgumentException("昵称、邮箱或手机号不能超过200字");
        SystemConfig c = repository.findByConfigKey(KEY).orElseGet(SystemConfig::new);
        c.setConfigKey(KEY); c.setConfigType("integration"); c.setStatus(1);
        c.setDescription("智云页面接入配置，不含共享密钥");
        try { c.setConfigValue(mapper.writeValueAsString(s)); }
        catch (Exception e) { throw new IllegalArgumentException("配置格式错误"); }
        repository.saveAndFlush(c);
        return Map.of("version", version());
    }

    private void required(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip()))
            throw new IllegalArgumentException(name + "必填，不含首尾空格，最多" + max + "字");
    }

    public Map<String, String> launch() {
        SysUser user = user();
        if (!Integer.valueOf(1).equals(user.getIsAdmin()) && !sessions.computePermissions(user).contains("/script"))
            throw new IllegalArgumentException("无技能管理访问权限");
        Settings s = read();
        if (!s.enabled()) throw new IllegalArgumentException("智云接入已停用，请联系管理员");
        String secret = System.getenv("STARAGENT_COMMON_AUTH_SECRET");
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("后端未配置智云共享密钥，请联系管理员");
        try {
            String token = StarAgentCommonTokenGenerator.generate(secret, s.issuer(), String.valueOf(user.getId()),
                    s.username(), s.nickname(), s.email(), s.phone());
            return Map.of("url", s.callbackUrl() + "?token=" + encode(token) + "&redirect=" + encode(s.redirect()));
        } catch (Exception e) { throw new IllegalArgumentException("智云登录凭证生成失败，请联系管理员"); }
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
