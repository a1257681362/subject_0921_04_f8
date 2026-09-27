package com.evops.common;

/**
 * 未认证 / 登录态失效：未携带令牌、令牌签名非法或过期、会话已登出/被吊销、
 * 账号停用或改密后旧令牌版本失配。HTTP 401。
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
