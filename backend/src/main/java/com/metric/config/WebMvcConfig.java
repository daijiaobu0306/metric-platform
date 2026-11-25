package com.metric.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 额外允许的来源（逗号分隔），对应环境变量 APP_CORS_ALLOWED_ORIGINS。
     *
     * 为什么需要它：同源判断依赖请求的 Host 与端口。一旦前面加了反向代理
     * （尤其是多层代理），Origin 里的对外地址与后端看到的 Host/端口可能不一致，
     * Spring 会判定为跨域，POST 直接被拒并返回 403 "Invalid CORS request"，
     * 表现为「登录页能打开，但登录提示用户名或密码错误」。
     *
     * 配法（app.env）：APP_CORS_ALLOWED_ORIGINS=http://192.168.1.11,https://metric.xxx.com
     */
    @Value("${app.cors.allowed-origins:}")
    private String extraOrigins;

    /**
     * CORS 配置（P2 起）
     *   - dev: vite dev server (28080) + 本机后端直连 (18080)
     *   - prod: 同源部署不触发 CORS；经代理或跨域时用 APP_CORS_ALLOWED_ORIGINS 追加
     *
     * 注意：allowedOrigins 不能是 "*"，因为要 allowCredentials(true) 支持
     *      OIDC cookie (JSESSIONID) 和前端 withCredentials 的 fetch。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = new ArrayList<>(List.of(
                "http://localhost:28080",
                "http://127.0.0.1:28080",
                "http://localhost:18080",
                "http://127.0.0.1:18080",
                "http://8.148.195.209:28081"
        ));
        if (extraOrigins != null) {
            for (String origin : extraOrigins.split(",")) {
                String value = origin.trim();
                // 去掉误写的结尾斜杠，Spring 要求精确匹配 origin（无路径）
                while (value.endsWith("/")) {
                    value = value.substring(0, value.length() - 1);
                }
                if (!value.isEmpty() && !origins.contains(value)) {
                    origins.add(value);
                }
            }
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
