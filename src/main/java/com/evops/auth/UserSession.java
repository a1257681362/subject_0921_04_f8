package com.evops.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 登录会话：一个端登录一行。刷新令牌在会话内一次性轮换；
 * status 非 ACTIVE（LOGGED_OUT/REVOKED）或刷新令牌过期后，该端访问令牌立即失效。
 */
@TableName("t_user_session")
public class UserSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** WEB / APP / UNKNOWN */
    private String deviceType;
    /** ACTIVE / LOGGED_OUT / REVOKED */
    private String status;
    private String accessJti;
    private String refreshJti;
    private String refreshTokenHash;
    private LocalDateTime loginAt;
    private LocalDateTime lastRefreshAt;
    private LocalDateTime refreshExpireAt;
    private String revokeReason;
    private String clientIp;
    private String userAgent;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAccessJti() { return accessJti; }
    public void setAccessJti(String accessJti) { this.accessJti = accessJti; }
    public String getRefreshJti() { return refreshJti; }
    public void setRefreshJti(String refreshJti) { this.refreshJti = refreshJti; }
    public String getRefreshTokenHash() { return refreshTokenHash; }
    public void setRefreshTokenHash(String refreshTokenHash) { this.refreshTokenHash = refreshTokenHash; }
    public LocalDateTime getLoginAt() { return loginAt; }
    public void setLoginAt(LocalDateTime loginAt) { this.loginAt = loginAt; }
    public LocalDateTime getLastRefreshAt() { return lastRefreshAt; }
    public void setLastRefreshAt(LocalDateTime lastRefreshAt) { this.lastRefreshAt = lastRefreshAt; }
    public LocalDateTime getRefreshExpireAt() { return refreshExpireAt; }
    public void setRefreshExpireAt(LocalDateTime refreshExpireAt) { this.refreshExpireAt = refreshExpireAt; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
