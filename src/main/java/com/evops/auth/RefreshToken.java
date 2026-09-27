package com.evops.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 刷新令牌台账：登录/每次轮换各一行。
 * ACTIVE 才能换新；轮换后旧令牌置 ROTATED 并记后继 jti；
 * 一旦 ROTATED/REVOKED 令牌再次被提交使用，判定为被盗用，吊销其会话全部令牌。
 */
@TableName("t_refresh_token")
public class RefreshToken {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String jti;
    private Long sessionId;
    private Long userId;
    private String tokenHash;
    /** ACTIVE / ROTATED / REVOKED */
    private String status;
    private String rotatedJti;
    private LocalDateTime issuedAt;
    private LocalDateTime expireAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRotatedJti() { return rotatedJti; }
    public void setRotatedJti(String rotatedJti) { this.rotatedJti = rotatedJti; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
