package com.nailglow.backend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Service
public class SystemSettingService {
    /** Redis 业务缓存前缀：系统设置项。 */
    private static final String SETTING_CACHE_PREFIX = "nailglow:setting:";
    /** 缓存时长（分钟）：设置项变更频率低，用较长 TTL，写入时主动失效。 */
    private static final long SETTING_CACHE_MINUTES = 360L;

    private final JdbcTemplate jdbc;
    private final com.ruoyi.common.core.redis.RedisCache redisCache;

    public SystemSettingService(JdbcTemplate jdbc, com.ruoyi.common.core.redis.RedisCache redisCache) {
        this.jdbc = jdbc;
        this.redisCache = redisCache;
    }

    /**
     * 读取设置项。
     *
     * <p>重构后走 Redis 业务缓存（若依 {@code RedisCache}）：命中则不再查库。
     * AI Key / 端点等设置在每次智能体调用时都会被读取，缓存可显著减少
     * {@code system_settings} 的重复查询。Redis 不可用时自动回退到直查数据库，
     * 不影响功能。
     */
    public String getText(String key, String fallback) {
        String cacheKey = SETTING_CACHE_PREFIX + key;
        try {
            Object cached = redisCache.getCacheObject(cacheKey);
            if (cached instanceof String text) {
                return text.isEmpty() ? fallback : text;
            }
        } catch (Exception ignored) {
            // Redis 异常时降级查库
        }

        List<String> rows = jdbc.query("""
                select value_text
                from system_settings
                where key_name = ?
                limit 1
                """, (rs, rowNum) -> rs.getString("value_text"), key);

        String value = "";
        if (!rows.isEmpty() && StringUtils.hasText(rows.get(0))) {
            value = rows.get(0).trim();
        }

        try {
            // 缓存空串以表示「已确认无此配置」，避免每次穿透到数据库
            redisCache.setCacheObject(cacheKey, value, (int) SETTING_CACHE_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
        } catch (Exception ignored) {
            // 缓存写入失败不影响返回值
        }

        return value.isEmpty() ? fallback : value;
    }

    /** 设置项变更后失效缓存（写入方需调用）。 */
    public void evict(String key) {
        try {
            redisCache.deleteObject(SETTING_CACHE_PREFIX + key);
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /** 清空全部设置项缓存。 */
    public void evictAll() {
        try {
            java.util.Collection<String> keys = redisCache.keys(SETTING_CACHE_PREFIX + "*");
            for (String key : keys) {
                redisCache.deleteObject(key);
            }
        } catch (Exception ignored) {
            // 忽略
        }
    }

    public String effectiveSharedAiApiKey(String propertyValue) {
        return firstNonBlank(
                getText("shared_ai_api_key", ""),
                propertyValue,
                System.getenv("DOUBAO_API_KEY"),
                System.getenv("DEEPSEEK_API_KEY"),
                System.getenv("AI_API_KEY"),
                System.getenv("OPENAI_API_KEY")
        );
    }

    public String sharedAiApiKeySource(String propertyValue) {
        if (StringUtils.hasText(getText("shared_ai_api_key", ""))) {
            return "系统设置";
        }
        if (StringUtils.hasText(propertyValue)) {
            if (StringUtils.hasText(System.getenv("DOUBAO_API_KEY"))) return "DOUBAO_API_KEY";
            if (StringUtils.hasText(System.getenv("DEEPSEEK_API_KEY"))) return "DEEPSEEK_API_KEY";
            if (StringUtils.hasText(System.getenv("AI_API_KEY"))) return "AI_API_KEY";
            if (StringUtils.hasText(System.getenv("OPENAI_API_KEY"))) return "OPENAI_API_KEY";
            return "应用配置";
        }
        return "未配置";
    }

    public String effectiveAiApiKey(String settingKey, String propertyValue, String... envNames) {
        return firstNonBlank(
                getText(settingKey, ""),
                getText("shared_ai_api_key", ""),
                firstEnv(envNames),
                propertyValue,
                System.getenv("DOUBAO_API_KEY"),
                System.getenv("DEEPSEEK_API_KEY"),
                System.getenv("AI_API_KEY"),
                System.getenv("OPENAI_API_KEY")
        );
    }

    public String effectiveImageGenerationEndpoint(String propertyValue) {
        return normalizeImageGenerationEndpoint(firstNonBlank(
                getText("doubao_endpoint", ""),
                propertyValue
        ));
    }

    public String effectiveImageGenerationSize(String propertyValue) {
        return normalizeImageGenerationSize(firstNonBlank(
                getText("doubao_size", ""),
                propertyValue
        ));
    }

    public String effectiveAiBaseUrl(String settingKey, String propertyValue) {
        return normalizeBaseUrl(firstNonBlank(
                getText(settingKey, ""),
                propertyValue
        ));
    }

    public String aiApiKeySource(String settingKey, String propertyValue, String... envNames) {
        if (StringUtils.hasText(getText(settingKey, ""))) {
            return "系统设置";
        }
        if (StringUtils.hasText(getText("shared_ai_api_key", ""))) {
            return "系统设置(共享)";
        }
        String envValue = firstEnv(envNames);
        if (StringUtils.hasText(envValue)) {
            for (String envName : envNames) {
                if (StringUtils.hasText(System.getenv(envName))) {
                    return envName;
                }
            }
        }
        if (StringUtils.hasText(propertyValue)) {
            if (StringUtils.hasText(System.getenv("DOUBAO_API_KEY"))) return "DOUBAO_API_KEY";
            if (StringUtils.hasText(System.getenv("DEEPSEEK_API_KEY"))) return "DEEPSEEK_API_KEY";
            if (StringUtils.hasText(System.getenv("AI_API_KEY"))) return "AI_API_KEY";
            if (StringUtils.hasText(System.getenv("OPENAI_API_KEY"))) return "OPENAI_API_KEY";
            return "应用配置";
        }
        return "未配置";
    }

    public String effectiveAmapWebServiceKey() {
        return firstNonBlank(
                getText("amap_web_service_key", ""),
                System.getenv("AMAP_WEB_SERVICE_KEY"),
                System.getenv("AMAP_KEY")
        );
    }

    public String amapKeySource() {
        if (StringUtils.hasText(getText("amap_web_service_key", ""))) {
            return "系统设置";
        }
        if (StringUtils.hasText(System.getenv("AMAP_WEB_SERVICE_KEY"))) {
            return "AMAP_WEB_SERVICE_KEY";
        }
        if (StringUtils.hasText(System.getenv("AMAP_KEY"))) {
            return "AMAP_KEY";
        }
        return "未配置";
    }

    public String aiBaseUrlSource(String settingKey, String propertyValue) {
        if (StringUtils.hasText(getText(settingKey, ""))) {
            return "系统设置";
        }
        if (StringUtils.hasText(propertyValue)) {
            return "应用配置";
        }
        return "默认值";
    }

    public String masked(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.trim();
        if (text.length() <= 8) {
            return "*".repeat(text.length());
        }
        return text.substring(0, 4) + "****" + text.substring(text.length() - 4);
    }

    public Map<String, Object> aiKeyStatus(String propertyValue) {
        String key = effectiveSharedAiApiKey(propertyValue);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("configured", StringUtils.hasText(key));
        row.put("source", sharedAiApiKeySource(propertyValue));
        row.put("masked", masked(key));
        return row;
    }

    public Map<String, Object> aiKeyStatus(String settingKey, String propertyValue, String... envNames) {
        String key = effectiveAiApiKey(settingKey, propertyValue, envNames);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("configured", StringUtils.hasText(key));
        row.put("source", aiApiKeySource(settingKey, propertyValue, envNames));
        row.put("masked", masked(key));
        return row;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String firstEnv(String... envNames) {
        if (envNames == null) {
            return "";
        }
        for (String envName : envNames) {
            if (!StringUtils.hasText(envName)) {
                continue;
            }
            String value = System.getenv(envName);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalizeImageGenerationEndpoint(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().replaceAll("/+$", "");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("/v1/images/generations") || lower.contains("/api/v3/images/generations")) {
            return normalized;
        }
        if (lower.endsWith("/api/v3")) {
            return normalized + "/images/generations";
        }
        return normalized + "/v1/images/generations";
    }

    private String normalizeImageGenerationSize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("*", "x");
        return switch (normalized) {
            case "1k", "1024", "1024x1024" -> "1024x1024";
            case "2k", "2048", "2048x2048" -> "2048x2048";
            case "4k", "4096", "4096x4096" -> "4096x4096";
            default -> normalized.matches("\\d{3,4}x\\d{3,4}") ? normalized : value.trim();
        };
    }

    private String normalizeBaseUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().replaceAll("/+$", "");
    }
}
