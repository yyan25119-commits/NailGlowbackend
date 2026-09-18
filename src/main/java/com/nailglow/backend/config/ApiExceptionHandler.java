package com.nailglow.backend.config;

import com.nailglow.backend.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * NailGlow 异常响应契约。
 *
 * <p>重构引入若依后，{@code com.ruoyi.framework.web.exception.GlobalExceptionHandler}
 * 也带 {@code @RestControllerAdvice}，且它会兜底捕获 {@code RuntimeException} /
 * {@code Exception}，返回若依的 {@code {"code":500,"msg":"..."}} 结构。
 * 前端只认 {@code {code, message, data}}（{@code code:0} 为成功），
 * 两者同时存在时若依的处理器可能抢先命中，导致前端拿不到 {@code message} 字段。
 *
 * <p>因此这里用 {@link Order} 把 NailGlow 的处理器提到最高优先级，
 * 并显式覆盖最常触发的几类异常，保证响应体始终是 NailGlow 的结构。
 *
 * <p>注意：{@link ResponseStatusException} 保持「HTTP 200 + code:1」的原有行为，
 * 因为重构前 {@code ApiExceptionHandler} 就是这样返回的，前端据此展示错误提示。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 业务主动抛出的状态异常：保持重构前的 HTTP 200 + {"code":1} 契约。 */
    @ExceptionHandler(ResponseStatusException.class)
    public Map<String, Object> responseStatus(ResponseStatusException error) {
        return ApiResponse.fail(error.getReason() == null ? "请求失败" : error.getReason());
    }

    /** 静态资源/未知路径 404：返回 NailGlow 结构而非若依的 {"msg":...}。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("接口不存在：" + error.getResourcePath()));
    }

    /**
     * 方法级安全（{@code @PreAuthorize}）抛出的拒绝访问异常。
     * 必须显式处理，否则会被下面的 {@code Exception} 兜底捕获而降级为 500，
     * 破坏前端依赖的 403 契约。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> accessDenied(
            org.springframework.security.access.AccessDeniedException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(accessDeniedBody());
    }

    private Map<String, Object> accessDeniedBody() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", 403);
        body.put("message", "没有访问权限");
        body.put("data", null);
        return body;
    }

    /** 兜底：任何未处理异常都返回 NailGlow 结构，避免泄漏若依响应格式。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception error) {
        log.error("未处理异常", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(error.getMessage() == null ? "服务器内部错误" : error.getMessage()));
    }
}
