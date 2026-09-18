package com.nailglow.backend.config;

import com.nailglow.backend.service.RealtimeTrafficService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口流量统计拦截器：把 {@code /api/**} 的每次请求记入 Redis 计数器。
 *
 * <p>只计数、不改写请求或响应，统计失败也不影响主流程
 * （见 {@link RealtimeTrafficService} 内部的降级逻辑）。
 */
@Component
public class TrafficInterceptor implements HandlerInterceptor {

    private final RealtimeTrafficService trafficService;

    public TrafficInterceptor(RealtimeTrafficService trafficService) {
        this.trafficService = trafficService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        trafficService.recordRequest(request.getRequestURI());
        return true;
    }
}
