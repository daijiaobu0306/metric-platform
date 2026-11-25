package com.metric.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;


public class StarAgentCommonTokenGenerator {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String AUDIENCE = "staragent";
    private static final long MAX_TTL_SECONDS = 3600L;


    private StarAgentCommonTokenGenerator() {
    }

    /**
     * 生成 StarAgent Common 换票 token。
     *
     * @param secret   StarAgent 与第三方约定的 HMAC 密钥
     * @param issuer   第三方系统标识，例如 crm-system
     * @param userId   第三方系统内稳定、唯一的用户 ID
     * @param username StarAgent 登录账号
     * @param nickname 用户显示名，可空
     * @param email    用户邮箱，可空
     * @param phone    用户手机号，可空
     * @return Common token
     */
    public static String generate(
            String secret,
            String issuer,
            String userId,
            String username,
            String nickname,
            String email,
            String phone
    ) throws Exception {
        requireNotBlank(secret, "secret");
        requireNotBlank(issuer, "issuer");
        requireNotBlank(userId, "userId");
        requireNotBlank(username, "username");

        long issuedAt = Instant.now().getEpochSecond();

        // LinkedHashMap 保证输出顺序稳定，便于联调和排查。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", userId);
        payload.put("iat", issuedAt);
        payload.put("exp", issuedAt + MAX_TTL_SECONDS);
        payload.put("iss", issuer);
        payload.put("aud", AUDIENCE);
        payload.put("userName", username);

        putIfNotBlank(payload, "nickname", nickname);
        putIfNotBlank(payload, "email", email);
        putIfNotBlank(payload, "phone", phone);

        // 签名的是实际 JSON Base64URL 编码后的字符串。
        byte[] jsonBytes = OBJECT_MAPPER
                .writeValueAsString(payload)
                .getBytes(StandardCharsets.UTF_8);

        String payloadBase64Url = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(jsonBytes);

        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                HMAC_ALGORITHM
        ));

        byte[] signatureBytes = mac.doFinal(
                payloadBase64Url.getBytes(StandardCharsets.US_ASCII)
        );

        return payloadBase64Url + "." + toLowerHex(signatureBytes);
    }

    private static String toLowerHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void putIfNotBlank(
            Map<String, Object> payload,
            String key,
            String value
    ) {
        if (value != null && !value.trim().isEmpty()) {
            payload.put(key, value.trim());
        }
    }

    private static void requireNotBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

}
