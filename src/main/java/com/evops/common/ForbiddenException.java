package com.evops.common;

/**
 * 已认证但权限不足：如只读角色发起写操作、非管理员调用账号管理。HTTP 403。
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
