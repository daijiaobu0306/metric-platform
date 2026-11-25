package com.metric.observe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * 分享链接密钥的对称加密存取。
 *
 * <p>历史设计只保存 SHA-256 哈希，管理员一旦关掉“生成链接”弹窗就再也取不回完整 URL。
 * 新增密文列后，数据库里依旧是密文，只有管理员接口才解密回显，访客校验仍走 {@code secret_hash}。
 *
 * <p>密钥来源优先级：环境变量 {@code SCREEN_SHARE_SECRET_KEY}（Base64，32 字节）
 * &gt; system_config {@code screen.share.secret.key}（首次使用自动生成并持久化）。
 * 更换密钥后旧密文无法解密，列表会把对应链接标记为“不可恢复”，但已发出的链接仍可正常访问
 * （访客校验只比对哈希）。
 */
@Component
public class DashboardShareSecretCipher {
    private static final Logger log = LoggerFactory.getLogger(DashboardShareSecretCipher.class);
    private static final String CONFIG_KEY = "screen.share.secret.key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final JdbcTemplate jdbc;
    private final String envKey;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKeySpec key;

    public DashboardShareSecretCipher(JdbcTemplate jdbc, @Value("${screen.share.secret-key:}") String envKey) {
        this.jdbc = jdbc;
        this.envKey = envKey == null ? "" : envKey.trim();
    }

    /** 加密；失败返回 null（创建链接不能因为加密失败而中断）。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            log.warn("分享链接密钥加密失败，将只保存哈希：{}", e.toString());
            return null;
        }
    }

    /** 解密；密文缺失、密钥变更或数据损坏时返回 null，由上层提示“不可恢复”。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(stored.trim());
            if (raw.length <= IV_BYTES) return null;
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            return new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("分享链接密钥解密失败（可能是密钥已更换）：{}", e.toString());
            return null;
        }
    }

    private SecretKeySpec secretKey() {
        SecretKeySpec current = key;
        if (current != null) return current;
        synchronized (this) {
            if (key == null) key = new SecretKeySpec(resolveKeyBytes(), "AES");
            return key;
        }
    }

    private byte[] resolveKeyBytes() {
        if (!envKey.isEmpty()) {
            byte[] fromEnv = decodeKey(envKey);
            if (fromEnv != null) return fromEnv;
            log.warn("环境变量 SCREEN_SHARE_SECRET_KEY 不是合法的 32 字节 Base64，改用数据库配置项");
        }
        String stored = cfg(CONFIG_KEY);
        byte[] fromDb = stored == null ? null : decodeKey(stored);
        if (fromDb != null) return fromDb;
        byte[] generated = new byte[KEY_BYTES];
        random.nextBytes(generated);
        String encoded = Base64.getEncoder().encodeToString(generated);
        save(encoded);
        log.info("已生成专屏分享密钥（system_config:{}），更换或删除会导致历史链接无法回显", CONFIG_KEY);
        return generated;
    }

    private byte[] decodeKey(String value) {
        try {
            byte[] raw = Base64.getDecoder().decode(value.trim());
            return raw.length == KEY_BYTES ? raw : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String cfg(String name) {
        List<String> rows = jdbc.queryForList("SELECT config_value FROM system_config WHERE config_key=?", String.class, name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void save(String value) {
        jdbc.update("INSERT INTO system_config(config_key,config_value,config_type,description,status) "
                        + "VALUES(?,?,?,?,1) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),update_time=NOW()",
                CONFIG_KEY, value, "screen-share", "专屏分享链接密钥加密密钥（勿删改，删除后历史链接无法回显）");
    }
}
