package com.evops.auth;

import com.evops.common.ForbiddenException;
import com.evops.common.UnauthorizedException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录态拦截（auth-1）：
 * - 除白名单（健康检查、登录、刷新）外，所有 /api/** 请求须携带 Bearer 访问令牌；
 * - 令牌通过签名校验后回库确认会话存活、账号启用、令牌版本一致（登出/改密/停用立即生效）；
 * - 校验通过后建立 AuthContext；业务操作人由 RequestContextInterceptor 以登录身份写入
 *   （只认令牌账号，忽略客户端自报 X-Operator）；
 * - VIEWER 只读角色发起写操作一律 403。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String AUTH_HEADER = "Authorization";
    public static final String BEARER = "Bearer ";

    private final AuthService authService;
    private final AuthProperties properties;

    public AuthInterceptor(AuthService authService, AuthProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (isAnonymous(path)) {
            return true;
        }

        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER)) {
            throw new UnauthorizedException("缺少访问令牌（Authorization: Bearer <token>）");
        }
        AuthIdentity identity = authService.authenticate(header.substring(BEARER.length()).trim());

        boolean writeRequest = !"GET".equalsIgnoreCase(request.getMethod());
        if (writeRequest && identity.getRoles().size() == 1
                && identity.hasRole(AuthService.ROLE_VIEWER)) {
            throw new ForbiddenException("只读角色无权执行写操作");
        }

        AuthContext.set(identity);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }

    private boolean isAnonymous(String path) {
        return "/api/health".equals(path)
                || "/api/auth/login".equals(path)
                || "/api/auth/refresh".equals(path);
    }
}
