package com.evops.auth;

import com.evops.common.UnauthorizedException;

/**
 * 当前请求登录身份持有器：AuthInterceptor 校验通过后写入，请求结束清理。
 * 与业务 RequestContext 分工：这里放鉴权要素（id/角色/会话），RequestContext 放业务三要素。
 */
public final class AuthContext {

    private static final ThreadLocal<AuthIdentity> HOLDER = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(AuthIdentity identity) { HOLDER.set(identity); }

    public static AuthIdentity get() { return HOLDER.get(); }

    /** 必须已登录场景取值；未登录直接编程错误（拦截器未放行）时抛异常 */
    public static AuthIdentity require() {
        AuthIdentity identity = HOLDER.get();
        if (identity == null) {
            throw new UnauthorizedException("未登录或登录态已失效");
        }
        return identity;
    }

    public static void clear() { HOLDER.remove(); }
}
