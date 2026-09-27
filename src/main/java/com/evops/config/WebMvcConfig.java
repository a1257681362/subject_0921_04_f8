package com.evops.config;

import com.evops.auth.AuthInterceptor;
import com.evops.idempotent.IdempotentInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestContextInterceptor requestContextInterceptor;
    private final IdempotentInterceptor idempotentInterceptor;
    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(RequestContextInterceptor requestContextInterceptor,
                        IdempotentInterceptor idempotentInterceptor,
                        AuthInterceptor authInterceptor) {
        this.requestContextInterceptor = requestContextInterceptor;
        this.idempotentInterceptor = idempotentInterceptor;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 0 先鉴权建立登录身份；1 再以登录身份写业务上下文；2 写请求入口幂等
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .order(0);
        registry.addInterceptor(requestContextInterceptor)
                .addPathPatterns("/api/**")
                .order(1);
        registry.addInterceptor(idempotentInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health")
                .excludePathPatterns("/api/auth/login")
                .excludePathPatterns("/api/auth/refresh")
                .order(2);
    }
}
