package com.nailglow.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 静态资源映射（上传文件对外访问）。
 *
 * <p>CORS 已统一由若依的 {@code ResourcesConfig.corsFilter()} 处理（注册在
 * Spring Security 过滤器链中，覆盖 {@code /**}，允许全部来源/方法/请求头）。
 * 重构前这里另有一份 MVC {@code CorsRegistry} 配置（{@code /api/**}），
 * 两层 CORS 会同时写入响应头，导致浏览器收到重复的
 * {@code Access-Control-Allow-Origin} 而拒绝跨域请求，因此移除。
 *
 * <p>{@code /uploads/**} 的资源映射保留，因为业务代码把用户上传的图片
 * 写在 {@code ./uploads} 下，而若依的 {@code /profile/**} 指向
 * {@code ./uploads/ruoyi}，两者路径不同，互不覆盖。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final TrafficInterceptor trafficInterceptor;

    public CorsConfig(TrafficInterceptor trafficInterceptor) {
        this.trafficInterceptor = trafficInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadPath = Path.of("uploads").toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**").addResourceLocations(uploadPath);
    }

    /** 接口流量统计（Redis 计数），仅统计 /api/**。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(trafficInterceptor).addPathPatterns("/api/**");
    }
}
