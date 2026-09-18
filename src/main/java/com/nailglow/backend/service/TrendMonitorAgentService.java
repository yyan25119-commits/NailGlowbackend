package com.nailglow.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class TrendMonitorAgentService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SystemSettingService systemSettingService;

    @Value("${nailglow.python.bin:${PYTHON_BIN:python}}")
    private String pythonBin;

    @Value("${nailglow.doubao.api-key:}")
    private String apiKey;

    @Value("${nailglow.trend-agent.ai-base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String aiBaseUrl;

    @Value("${nailglow.trend-agent.ai-model:doubao-seed-2-0-pro-260215}")
    private String aiModel;

    @Value("${nailglow.trend-agent.timeout-seconds:28}")
    private long timeoutSeconds;

    public TrendMonitorAgentService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    public Map<String, Object> analyze(Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>(payload);
        request.put("mode", "analyze");
        Map<String, Object> result = runAgent(request);
        if (!result.containsKey("summary")) {
            return fallbackAnalyze(payload, "趋势分析 Agent 未返回有效 summary");
        }
        result.putIfAbsent("signals", List.of());
        result.putIfAbsent("actions", List.of());
        result.putIfAbsent("operationScript", "");
        result.putIfAbsent("agentSource", "python_trend_monitor_agent");
        return result;
    }

    public List<String> planQueries(Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>(payload);
        request.put("mode", "keyword_plan");
        Map<String, Object> result = runAgent(request);
        List<String> queries = stringList(result.get("queries"));
        if (!queries.isEmpty()) {
            return queries;
        }
        return fallbackQueries(payload);
    }

    private Map<String, Object> runAgent(Map<String, Object> payload) {
        Path scriptPath = Path.of("src", "main", "python", "trend_monitor_agent.py").toAbsolutePath().normalize();
        if (!Files.exists(scriptPath)) {
            return Map.of("agentReason", "未找到趋势分析 Agent 脚本");
        }
        try {
            String effectiveApiKey = systemSettingService.effectiveAiApiKey("trend_agent_api_key", apiKey, "TREND_AGENT_API_KEY");
            String effectiveBaseUrl = systemSettingService.effectiveAiBaseUrl("trend_agent_base_url", aiBaseUrl);
            String effectiveModel = systemSettingService.getText("trend_agent_model", aiModel);
            ProcessBuilder builder = new ProcessBuilder(pythonBin, scriptPath.toString());
            builder.directory(Path.of(".").toAbsolutePath().normalize().toFile());
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONUTF8", "1");
            builder.environment().put("ARK_BASE_URL", effectiveBaseUrl);
            builder.environment().put("AI_BASE_URL", effectiveBaseUrl);
            builder.environment().put("TREND_AGENT_TIMEOUT_SECONDS", String.valueOf(timeoutSeconds));
            if (effectiveApiKey != null && !effectiveApiKey.isBlank()) {
                builder.environment().put("ARK_API_KEY", effectiveApiKey);
                builder.environment().put("AI_API_KEY", effectiveApiKey);
                builder.environment().put("DEEPSEEK_API_KEY", effectiveApiKey);
                builder.environment().put("OPENAI_API_KEY", effectiveApiKey);
            }
            builder.environment().put("TREND_AGENT_MODEL", effectiveModel);
            Process process = builder.start();

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(mapper.writeValueAsString(payload));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Map.of("agentReason", "趋势分析 Agent 执行超时");
            }

            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            if (process.exitValue() != 0 && stdout.isBlank()) {
                return Map.of("agentReason", stderr.isBlank() ? "趋势分析 Agent 执行失败" : stderr);
            }

            return mapper.readValue(stdout.isBlank() ? "{}" : stdout, Map.class);
        } catch (Exception ex) {
            return Map.of("agentReason", "趋势分析 Agent 调用失败：" + ex.getMessage());
        }
    }

    private String readAll(java.io.InputStream stream) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private Map<String, Object> fallbackAnalyze(Map<String, Object> payload, String reason) {
        List<Map<String, Object>> items = listValue(payload.get("items"));
        Map<String, Integer> keywordCounts = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            Object keywords = item.get("keywords");
            if (!(keywords instanceof List<?> raw)) continue;
            for (Object keyword : raw) {
                String value = String.valueOf(keyword);
                if (!value.isBlank()) keywordCounts.merge(value, 1, Integer::sum);
            }
        }
        List<String> topKeywords = keywordCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
        List<Map<String, Object>> signals = new ArrayList<>();
        for (int index = 0; index < Math.min(4, items.size()); index++) {
            Map<String, Object> item = items.get(index);
            signals.add(Map.of(
                    "label", String.valueOf(item.getOrDefault("styleName", "热门款式")),
                    "platform", String.valueOf(item.getOrDefault("platformLabel", "站外平台")),
                    "value", "点赞 " + String.valueOf(item.getOrDefault("likeText", item.getOrDefault("likeCount", 0)))
            ));
        }
        List<String> actions = new ArrayList<>();
        if (!topKeywords.isEmpty()) {
            actions.add("优先上架“" + topKeywords.get(0) + "”方向的高点赞款式，并把同风格款前置到试穿推荐位。");
        }
        if (items.size() >= 3) {
            actions.add("把点赞最高的 3 个站外热门款式做成专题推荐，结合返图和预约入口承接转化。");
        }
        if (actions.isEmpty()) {
            actions.add("先完成一次真实抓取，再继续分析站外趋势。");
        }
        String summary = topKeywords.isEmpty()
                ? "当前样本较少，建议先刷新站外热门后再进行运营判断。"
                : "站外热门风格集中在“" + String.join(" / ", topKeywords) + "”，建议优先承接高点赞的清透、法式和显白方向。";
        return Map.of(
                "summary", summary,
                "signals", signals,
                "actions", actions,
                "operationScript", topKeywords.isEmpty()
                        ? "当前站外样本还不够稳定，建议先完成一次真实抓取，再把高点赞款式整理成首页推荐、客服推荐和预约转化的统一承接话术。"
                        : "建议围绕“" + String.join("、", topKeywords) + "”做首页上新专题，把高点赞款式放进首屏试穿推荐，并在客服推荐中统一强调显白、通勤和拍照出片的优势，带动用户从站外种草直接进入站内试穿和预约转化。",
                "agentSource", "spring_trend_monitor_fallback",
                "agentReason", reason
        );
    }

    private List<String> fallbackQueries(Map<String, Object> payload) {
        List<Map<String, Object>> styles = listValue(payload.get("styles"));
        List<String> queries = new ArrayList<>();
        for (Map<String, Object> style : styles) {
            String name = String.valueOf(style.getOrDefault("name", "")).trim();
            if (!name.isBlank()) {
                queries.add(name);
            }
            Object tags = style.get("tags");
            if (tags instanceof List<?> raw) {
                for (Object tag : raw) {
                    String value = String.valueOf(tag).trim();
                    if (!value.isBlank()) {
                        queries.add(value + "美甲");
                    }
                }
            }
            if (queries.size() >= 8) break;
        }
        if (queries.isEmpty()) {
            return List.of("显白美甲", "法式美甲", "猫眼美甲", "夏日美甲", "短甲美甲", "裸粉美甲");
        }
        return queries.stream().distinct().limit(8).toList();
    }

    private List<Map<String, Object>> listValue(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            rows.add(normalized);
        }
        return rows;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        for (Object item : raw) {
            String text = String.valueOf(item).trim();
            if (!text.isBlank()) {
                rows.add(text);
            }
        }
        return rows;
    }
}
