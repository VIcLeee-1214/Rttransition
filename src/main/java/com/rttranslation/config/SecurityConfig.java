package com.rttranslation.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    @Value("${app.auth.token:}")
    private String authToken;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (authToken == null || authToken.isBlank()) return; // 未配置则跳过认证

        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                // 静态资源和健康检查不拦截
                String path = request.getRequestURI();
                if (path.startsWith("/css/") || path.startsWith("/js/") || path.equals("/health") || path.equals("/")) {
                    return true;
                }
                String token = request.getHeader("X-Auth-Token");
                if (token == null) token = request.getParameter("token");
                if (authToken.equals(token)) return true;
                response.setStatus(401);
                response.getWriter().write("{\"error\":\"未授权\"}");
                return false;
            }
        }).addPathPatterns("/api/**", "/ws/**");
    }
}
