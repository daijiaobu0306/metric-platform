package com.metric.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

/** Fast reachability check used only before redirecting a browser to the SSO provider. */
@Service
public class SsoProviderAvailabilityService {
    private static final Logger log = LoggerFactory.getLogger(SsoProviderAvailabilityService.class);

    private final URI authorizationUri;
    private final String clientId;
    private final ConnectionProbe probe;

    @Autowired
    public SsoProviderAvailabilityService(
            @Value("${spring.security.oauth2.client.provider.sso.authorization-uri}") String authorizationUri,
            @Value("${spring.security.oauth2.client.registration.sso.client-id}") String clientId) {
        this.authorizationUri = URI.create(authorizationUri);
        this.clientId = clientId;
        this.probe = SsoProviderAvailabilityService::connect;
    }

    SsoProviderAvailabilityService(String authorizationUri, String clientId, ConnectionProbe probe) {
        this.authorizationUri = URI.create(authorizationUri);
        this.clientId = clientId;
        this.probe = probe;
    }

    public boolean isAvailable() {
        // 默认占位值只用于让 OAuth2 客户端完成启动，不能据此跳转统一认证。
        if (clientId == null || clientId.isBlank() || clientId.startsWith("placeholder-")) {
            log.debug("SSO 不可用：client-id 为占位/空（clientId={}）", clientId);
            return false;
        }
        boolean result = false;
        int port = authorizationUri.getPort() > 0
                ? authorizationUri.getPort()
                : "https".equalsIgnoreCase(authorizationUri.getScheme()) ? 443 : 80;
        try { result = probe.connect(authorizationUri.getHost(), port); }
        catch (Exception ignored) { /* Keep local login available. */ }
        log.debug("SSO 可用性探测：clientId={} uri={} port={} result={}", clientId, authorizationUri, port, result);
        return result;
    }

    private static boolean connect(String host, int port) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        }
    }

    @FunctionalInterface
    interface ConnectionProbe {
        boolean connect(String host, int port) throws Exception;
    }
}
