package com.nailglow.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证失败 / 权限不足的响应处理。
 *
 * <p>必须保持与重构前 {@code AuthRequiredFilter} 完全一致的响应契约，否则前端会失效：
 * <pre>
 * 401 {"code":401,"message":"请先登录","data":null}
 * 403 {"code":403,"message":"没有访问权限","data":null}
 * </pre>
 * 前端 {@code AdminDashboard.vue} / {@code UserMobile.vue} 的 api() 会读取
 * HTTP 状态码 401/403 触发跳登录，并读取 {@code payload.message} 展示错误。
 *
 * <p>注意：若依自带的 {@code AuthenticationEntryPointImpl} 返回的是 {@code {"code":401,"msg":...}}
 * （键名是 msg），与本项目前端约定不符，因此这里单独实现。
 */
@Component
public class RestAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        write(response, HttpStatus.UNAUTHORIZED.value(), "请先登录");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN.value(), "没有访问权限");
    }

    private void write(HttpServletResponse response, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("message", message);
        body.put("data", null);

        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
