package com.nailglow.backend.service;

import com.ruoyi.common.core.redis.RedisCache;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时流量统计（Redis 承载）。
 *
 * <p>重构前没有任何流量统计能力。这里用 Redis 计数器实现跨实例可见的实时指标：
 * <ul>
 *   <li>当前分钟 / 当前小时 / 当天的接口请求数（{@code INCR} + {@code EXPIRE} 自动过期）</li>
 *   <li>管理端 WebSocket 在线连接数（连接 +1、断开 -1）</li>
 *   <li>累计请求总数（持久计数，不设过期）</li>
 * </ul>
 *
 * <p>选用 Redis 而非 JVM 内 {@code AtomicLong}：多实例部署时指标需聚合，
 * 且进程重启不应丢失当天统计。Redis 不可用时退化为本地计数，不影响请求处理。
 *
 * <p>直接用若依 {@code RedisCache} 里已配置好的 {@code redisTemplate}
 * （{@code RedisTemplate<Object,Object>} + fastjson2 序列化），
 * 避免再声明一个与之冲突的 {@code RedisTemplate<String,Object>}。
 */
@Service
public class RealtimeTrafficService {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String PREFIX = "nailglow:traffic:";
    private static final String ONLINE_WS = "nailglow:ws:online";

    private final RedisCache redisCache;

    /** Redis 不可用时的本地兜底计数。 */
    private final AtomicLong localTotal = new AtomicLong();
    private final AtomicLong localMinute = new AtomicLong();
    private final AtomicLong localOnline = new AtomicLong();

    public RealtimeTrafficService(RedisCache redisCache) {
        this.redisCache = redisCache;
    }

    /** 记录一次接口请求。 */
    public void recordRequest(String path) {
        LocalDateTime now = LocalDateTime.now();
        try {
            increment(PREFIX + "min:" + MINUTE.format(now), 3, TimeUnit.MINUTES);
            increment(PREFIX + "hour:" + HOUR.format(now), 2, TimeUnit.HOURS);
            increment(PREFIX + "day:" + DAY.format(now), 2, TimeUnit.DAYS);
            increment(PREFIX + "total", 0, null);
            if (path != null && !path.isEmpty()) {
                increment(PREFIX + "path:" + DAY.format(now) + ":" + path, 2, TimeUnit.DAYS);
            }
        } catch (Exception ignored) {
            // 降级为本地计数
            localTotal.incrementAndGet();
            localMinute.incrementAndGet();
        }
    }

    /** WebSocket 连接建立。 */
    public void sessionOpened() {
        try {
            increment(ONLINE_WS, 0, null);
        } catch (Exception ignored) {
            localOnline.incrementAndGet();
        }
    }

    /** WebSocket 连接关闭。 */
    public void sessionClosed() {
        try {
            Long value = incrementBy(ONLINE_WS, -1L);
            if (value != null && value < 0) {
                set(ONLINE_WS, 0L);
            }
        } catch (Exception ignored) {
            localOnline.decrementAndGet();
        }
    }

    /** 实时快照，供管理端展示。 */
    public Map<String, Object> snapshot() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestsThisMinute", read(PREFIX + "min:" + MINUTE.format(now), localMinute.get()));
        data.put("requestsThisHour", read(PREFIX + "hour:" + HOUR.format(now), 0L));
        data.put("requestsToday", read(PREFIX + "day:" + DAY.format(now), 0L));
        data.put("requestsTotal", read(PREFIX + "total", localTotal.get()));
        data.put("onlineAdminSessions", read(ONLINE_WS, localOnline.get()));
        data.put("date", LocalDate.now().toString());
        data.put("time", now.toString());
        data.put("storage", "redis");
        return data;
    }

    private void increment(String key, long timeout, TimeUnit unit) {
        Long value = incrementBy(key, 1L);
        // 仅在计数器刚创建时设置过期，避免每次请求都刷新 TTL
        if (unit != null && value != null && value == 1L) {
            redisCache.expire(key, timeout, unit);
        }
    }

    @SuppressWarnings("unchecked")
    private Long incrementBy(String key, long delta) {
        Object result = redisCache.redisTemplate.opsForValue().increment(key, delta);
        return result instanceof Number number ? number.longValue() : null;
    }

    private void set(String key, Object value) {
        redisCache.redisTemplate.opsForValue().set(key, value);
    }

    private long read(String key, long fallback) {
        try {
            Object value = redisCache.redisTemplate.opsForValue().get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            return fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
