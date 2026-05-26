package com.nailglow.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class RouteAgentService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SystemSettingService systemSettingService;

    @Value("${nailglow.python.bin:${PYTHON_BIN:python}}")
    private String pythonBin;

    @Value("${nailglow.doubao.api-key:}")
    private String apiKey;

    @Value("${nailglow.route-agent.ai-base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String aiBaseUrl;

    @Value("${nailglow.route-agent.ai-model:doubao-seed-2-0-pro-260215}")
    private String aiModel;

    @Value("${nailglow.route-agent.timeout-seconds:35}")
    private long timeoutSeconds;

    public RouteAgentService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    public Map<String, Object> planRoute(Map<String, Object> payload) {
        Path scriptPath = Path.of("src", "main", "python", "route_agent.py").toAbsolutePath().normalize();
        if (!Files.exists(scriptPath)) {
            return Map.of(
                    "ok", false,
                    "message", "未找到 Python Route Agent 脚本：" + scriptPath,
                    "agentSource", "spring_route_agent_missing_script"
            );
        }

        try {
            String effectiveApiKey = systemSettingService.effectiveAiApiKey("route_agent_api_key", apiKey, "ROUTE_AGENT_API_KEY");
            String effectiveAmapKey = systemSettingService.effectiveAmapWebServiceKey();
            String effectiveBaseUrl = systemSettingService.effectiveAiBaseUrl("route_agent_base_url", aiBaseUrl);
            String effectiveModel = systemSettingService.getText("route_agent_model", aiModel);
            ProcessBuilder builder = new ProcessBuilder(pythonBin, scriptPath.toString());
            builder.directory(Path.of(".").toAbsolutePath().normalize().toFile());
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONUTF8", "1");
            builder.environment().put("ARK_BASE_URL", effectiveBaseUrl);
            builder.environment().put("AI_BASE_URL", effectiveBaseUrl);
            builder.environment().put("ROUTE_AGENT_MODEL", effectiveModel);
            builder.environment().put("ROUTE_AGENT_TIMEOUT_SECONDS", String.valueOf(timeoutSeconds));
            if (effectiveApiKey != null && !effectiveApiKey.isBlank()) {
                builder.environment().put("ARK_API_KEY", effectiveApiKey);
                builder.environment().put("AI_API_KEY", effectiveApiKey);
                builder.environment().put("DEEPSEEK_API_KEY", effectiveApiKey);
                builder.environment().put("OPENAI_API_KEY", effectiveApiKey);
            }
            if (effectiveAmapKey != null && !effectiveAmapKey.isBlank()) {
                builder.environment().put("AMAP_WEB_SERVICE_KEY", effectiveAmapKey);
                builder.environment().put("AMAP_KEY", effectiveAmapKey);
            }
            Process process = builder.start();

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(mapper.writeValueAsString(payload));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Map.of(
                        "ok", false,
                        "message", "路线规划 Agent 执行超时：" + Duration.ofSeconds(timeoutSeconds),
                        "agentSource", "spring_route_agent_timeout"
                );
            }

            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            if (process.exitValue() != 0 && stdout.isBlank()) {
                return Map.of(
                        "ok", false,
                        "message", stderr.isBlank() ? "路线规划 Agent 执行失败" : stderr,
                        "agentSource", "spring_route_agent_error"
                );
            }

            Map<String, Object> result = mapper.readValue(stdout.isBlank() ? "{}" : stdout, Map.class);
            result.putIfAbsent("springWeb", true);
            return result;
        } catch (Exception ex) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("message", "Spring Web 调用 Python Route Agent 失败：" + ex.getMessage());
            result.put("agentSource", "spring_route_agent_exception");
            return result;
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
}
