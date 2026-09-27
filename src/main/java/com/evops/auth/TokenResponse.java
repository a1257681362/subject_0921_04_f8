package com.evops.auth;

import java.util.List;

/**
 * 登录/刷新成功返回：双令牌 + 过期时刻 + 登录身份。
 * 客户端持有访问令牌访问业务接口；访问令牌过期后用刷新令牌静默换新。
 */
public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    /** 访问令牌过期时间（epoch 秒） */
    private long accessExpiresAt;
    /** 刷新令牌过期时间（epoch 秒） */
    private long refreshExpiresAt;
    private long userId;
    private String username;
    private String displayName;
    private List<String> roles;
    private String deviceType;

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }
    public long getAccessExpiresAt() { return accessExpiresAt; }
    public void setAccessExpiresAt(long accessExpiresAt) { this.accessExpiresAt = accessExpiresAt; }
    public long getRefreshExpiresAt() { return refreshExpiresAt; }
    public void setRefreshExpiresAt(long refreshExpiresAt) { this.refreshExpiresAt = refreshExpiresAt; }
    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
}
