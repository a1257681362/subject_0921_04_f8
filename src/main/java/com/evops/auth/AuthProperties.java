package com.evops.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 登录态配置（auth-1）。访问令牌短期、刷新令牌长期；刷新令牌只在有效期内可换新。
 */
@Component
@ConfigurationProperties(prefix = "evops.auth")
public class AuthProperties {

    /** JWT HS256 签名密钥（至少 32 字节）。生产必须通过配置覆盖默认开发值。 */
    private String jwtSecret = "evops-dev-only-secret-key-change-me-please-0123456789";

    /** 访问令牌有效期（分钟），默认 30 分钟：短期，过期走无感刷新 */
    private long accessTtlMinutes = 30;

    /** 访问令牌有效期秒级覆盖项（非空时优先于分钟配置；仅供测试/特殊场景使用） */
    private Long accessTtlSeconds;

    /** 刷新令牌有效期（天），默认 7 天 */
    private long refreshTtlDays = 7;

    /** 是否强制登录态。关闭时回到无鉴权模式（仅应急/本地调试，不建议生产关闭） */
    private boolean enabled = true;

    @PostConstruct
    public void validate() {
        if (jwtSecret == null || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("evops.auth.jwt-secret 至少需要 32 字节，访问令牌签名无法保证强度");
        }
        if (accessTtlMinutes <= 0 || refreshTtlDays <= 0) {
            throw new IllegalStateException("evops.auth 令牌有效期必须为正数");
        }
        if (accessTtlSeconds != null && accessTtlSeconds <= 0) {
            throw new IllegalStateException("evops.auth.access-ttl-seconds 必须为正数");
        }
    }

    /** 访问令牌实际有效期（秒）：配置了秒级覆盖项时以其为准 */
    public long effectiveAccessTtlSeconds() {
        return accessTtlSeconds != null ? accessTtlSeconds : accessTtlMinutes * 60;
    }

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public long getAccessTtlMinutes() { return accessTtlMinutes; }
    public void setAccessTtlMinutes(long accessTtlMinutes) { this.accessTtlMinutes = accessTtlMinutes; }
    public Long getAccessTtlSeconds() { return accessTtlSeconds; }
    public void setAccessTtlSeconds(Long accessTtlSeconds) { this.accessTtlSeconds = accessTtlSeconds; }
    public long getRefreshTtlDays() { return refreshTtlDays; }
    public void setRefreshTtlDays(long refreshTtlDays) { this.refreshTtlDays = refreshTtlDays; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** 是否仍为内置开发密钥（启动时据此告警） */
    public boolean isDevDefaultSecret() {
        return "evops-dev-only-secret-key-change-me-please-0123456789".equals(jwtSecret);
    }
}
