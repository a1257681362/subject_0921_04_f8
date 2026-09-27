package com.evops.config;

import com.evops.auth.AuthContext;
import com.evops.auth.AuthIdentity;
import com.evops.common.RequestContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * 入口拦截（在 AuthInterceptor 之后执行）：
 * - 已登录：操作人只取 JWT 登录身份，忽略客户端自报 X-Operator（防冒充）；
 *   写请求未带 X-Request-No 时由服务端补唯一请求号，带了仍按客户端请求号做幂等；
 * - 未登录（鉴权关闭或匿名接口）：保持历史行为，写请求必须带 X-Operator、X-Request-No。
 * X-Biz-Time 业务时间始终可选（yyyy-MM-dd'T'HH:mm:ss，缺省取服务端当前时间）。
 */
@Component
public class RequestContextInterceptor implements HandlerInterceptor {

    public static final String H_OPERATOR = "X-Operator";
    public static final String H_REQUEST_NO = "X-Request-No";
    public static final String H_BIZ_TIME = "X-Biz-Time";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        // 登录/刷新在鉴权前可用，没有登录身份也不走写请求三件套
        if ("/api/auth/login".equals(path) || "/api/auth/refresh".equals(path)) {
            return true;
        }
        String method = request.getMethod();
        boolean writeRequest = !"GET".equalsIgnoreCase(method) && !"OPTIONS".equalsIgnoreCase(method);

        AuthIdentity identity = AuthContext.get();
        String operator;
        String requestNo;
        if (identity != null) {
            operator = identity.getUsername();
            requestNo = request.getHeader(H_REQUEST_NO);
            if (!StringUtils.hasText(requestNo) && writeRequest) {
                requestNo = "srv-" + UUID.randomUUID().toString().replace("-", "");
            }
        } else {
            operator = request.getHeader(H_OPERATOR);
            requestNo = request.getHeader(H_REQUEST_NO);
            if (writeRequest) {
                if (!StringUtils.hasText(operator)) {
                    throw new IllegalArgumentException("缺少请求头 " + H_OPERATOR + "（操作人）");
                }
                if (!StringUtils.hasText(requestNo)) {
                    throw new IllegalArgumentException("缺少请求头 " + H_REQUEST_NO + "（请求号）");
                }
            }
        }

        LocalDateTime bizTime = null;
        String bizTimeRaw = request.getHeader(H_BIZ_TIME);
        if (StringUtils.hasText(bizTimeRaw)) {
            try {
                bizTime = LocalDateTime.parse(bizTimeRaw);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(H_BIZ_TIME + " 格式应为 yyyy-MM-dd'T'HH:mm:ss");
            }
        }
        if (StringUtils.hasText(operator)) {
            RequestContext.set(operator.trim(), bizTime,
                    StringUtils.hasText(requestNo) ? requestNo.trim() : null);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        RequestContext.clear();
    }
}
