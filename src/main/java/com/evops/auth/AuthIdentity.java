package com.evops.auth;

import java.util.List;

/**
 * 已通过鉴权的登录身份（访问令牌签名 + 会话/账号回库均通过后建立）。
 */
public class AuthIdentity {

    private final long userId;
    private final String username;
    private final String displayName;
    private final List<String> roles;
    private final long sessionId;
    private final String accessJti;

    public AuthIdentity(long userId, String username, String displayName, List<String> roles,
                        long sessionId, String accessJti) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.roles = roles;
        this.sessionId = sessionId;
        this.accessJti = accessJti;
    }

    public long getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public List<String> getRoles() { return roles; }
    public long getSessionId() { return sessionId; }
    public String getAccessJti() { return accessJti; }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
