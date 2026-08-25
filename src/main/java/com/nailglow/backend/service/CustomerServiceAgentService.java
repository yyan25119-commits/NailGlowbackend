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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class CustomerServiceAgentService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SystemSettingService systemSettingService;

    @Value("${nailglow.python.bin:${PYTHON_BIN:python}}")
    private String pythonBin;

    @Value("${nailglow.doubao.api-key:}")
    private String apiKey;

    @Value("${nailglow.customer-agent.ai-base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String aiBaseUrl;

    @Value("${nailglow.customer-agent.ai-model:doubao-seed-2-0-pro-260215}")
    private String aiModel;

    @Value("${nailglow.customer-agent.timeout-seconds:35}")
    private long timeoutSeconds;

    @Value("${nailglow.agent-graph.enabled:${AGENT_GRAPH_ENABLED:true}}")
    private boolean agentGraphEnabled;

    public CustomerServiceAgentService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    public Map<String, Object> chat(Map<String, Object> payload) {
        return chat(payload, toolCalls -> List.of());
    }

    public Map<String, Object> chat(Map<String, Object> payload, ToolExecutor executor) {
        Map<String, Object> basePayload = new LinkedHashMap<>(payload);
        Map<String, Object> currentPayload = new LinkedHashMap<>(basePayload);
        for (int index = 0; index < 6; index++) {
            Map<String, Object> result = runOnce(currentPayload);
            List<Map<String, Object>> toolCalls = normalizeToolCalls(result.get("toolCalls"));
            if (!"tool_calls".equals(String.valueOf(result.getOrDefault("status", ""))) || toolCalls.isEmpty()) {
                return result;
            }
            List<Map<String, Object>> toolResults = executor == null ? List.of() : executor.execute(toolCalls);
            Map<String, Object> localFinal = buildLocalToolFinalResult(payload, result, toolResults);
            if (!localFinal.isEmpty()) {
                return localFinal;
            }
            Map<String, Object> agentState = mapValue(result.get("agentState"));
            currentPayload = new LinkedHashMap<>(basePayload);
            Map<String, Object> agentLoop = new LinkedHashMap<>();
            agentLoop.put("messages", agentState.getOrDefault("messages", List.of()));
            agentLoop.put("currentStep", intValue(agentState.get("currentStep")));
            agentLoop.put("toolResults", toolResults);
            currentPayload.put("agentLoop", agentLoop);
        }
        return fallback(payload, "客服 Agent Tool Loop 超过最大轮次");
    }

    private Map<String, Object> buildLocalToolFinalResult(Map<String, Object> payload,
                                                          Map<String, Object> agentResult,
                                                          List<Map<String, Object>> toolResults) {
        if (toolResults.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> finalResult = new LinkedHashMap<>(agentResult);
        finalResult.remove("status");
        finalResult.put("toolCalls", List.of());
        finalResult.put("agentSource", String.valueOf(agentResult.getOrDefault("agentSource", "python_customer_action_request")) + "_local_final");
        finalResult.put("toolResults", toolResults);

        Map<String, Object> appointment = findToolResult(toolResults, "create_or_reschedule_appointment");
        Map<String, Object> handoff = findToolResult(toolResults, "request_human_handoff");
        Map<String, Object> support = findToolResult(toolResults, "update_support_case");
        if (!handoff.isEmpty() && bool(handoff.get("ok"))) {
            String message = stringValue(handoff.getOrDefault("handoffMessage", "已为你转接人工客服，请在当前对话等待回复。"));
            finalResult.put("answer", message);
            finalResult.put("intent", "support");
            finalResult.put("handoffRequested", true);
            finalResult.put("handoffMessage", message);
            finalResult.put("handoffReason", handoff.getOrDefault("handoffReason", ""));
            return finalResult;
        }
        if (!appointment.isEmpty() && "single_active_appointment".equals(stringValue(appointment.get("conflictType")))) {
            String currentSlot = stringValue(appointment.getOrDefault("currentSlot", "当前预约"));
            String requestedSlot = stringValue(appointment.getOrDefault("requestedSlot", "新的预约时间"));
            finalResult.put("answer", "当前账号只能保留一个有效预约，不能同时保留 " + currentSlot + " 和 " + requestedSlot + " 两条预约。若继续处理，我只能把当前预约改到 " + requestedSlot + "。确认的话请直接回复我。");
            finalResult.put("intent", "appointment");
            Map<String, Object> pendingAction = mapValue(appointment.get("pendingAction"));
            if (!pendingAction.isEmpty()) {
                finalResult.put("pendingAction", pendingAction);
            }
            return finalResult;
        }
        if (!appointment.isEmpty() && bool(appointment.get("ok"))) {
            String slot = stringValue(appointment.getOrDefault("slotTimeAdmin", appointment.getOrDefault("slotTimeUser", appointment.get("slotTime"))));
            String service = stringValue(appointment.getOrDefault("serviceName", contextValue(payload, "serviceName", "AI 试穿复刻")));
            String amount = stringValue(appointment.getOrDefault("amount", contextValue(payload, "amount", 268)));
            String duration = stringValue(appointment.getOrDefault("durationMinutes", contextValue(payload, "serviceDurationMinutes", 110)));
            String effectiveAction = stringValue(appointment.getOrDefault("effectiveAction", appointment.getOrDefault("requestedAction", "create")));
            if ("reschedule".equals(effectiveAction)) {
                finalResult.put("answer", "已将你当前的有效预约更新为 " + slot + "，服务项目是" + service + "，金额约 ¥" + amount + "，服务约 " + duration + " 分钟。");
            } else {
                finalResult.put("answer", "已帮你预约 " + slot + "，服务项目是" + service + "，金额约 ¥" + amount + "，服务约 " + duration + " 分钟。");
            }
            finalResult.put("intent", "appointment");
            return finalResult;
        }
        if (!support.isEmpty() && bool(support.get("ok"))) {
            String category = stringValue(support.getOrDefault("category", "客服事项"));
            finalResult.put("answer", "我已经记录你的" + category + "问题，并同步给门店后台处理。你可以继续在这里补充细节。");
            finalResult.put("intent", "support");
            return finalResult;
        }
        return Map.of();
    }

    private Map<String, Object> findToolResult(List<Map<String, Object>> toolResults, String toolName) {
        for (Map<String, Object> item : toolResults) {
            if (!toolName.equals(String.valueOf(item.getOrDefault("toolName", "")))) {
                continue;
            }
            Map<String, Object> result = mapValue(item.get("result"));
            if (!result.isEmpty()) {
                return result;
            }
        }
        return Map.of();
    }

    private Object contextValue(Map<String, Object> payload, String key, Object fallback) {
        Map<String, Object> context = mapValue(payload.get("context"));
        return context.getOrDefault(key, fallback);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public interface ToolExecutor {
        List<Map<String, Object>> execute(List<Map<String, Object>> toolCalls);
    }

    private Map<String, Object> runOnce(Map<String, Object> payload) {
        String scriptName = agentGraphEnabled ? "agent_graph.py" : "customer_service_agent.py";
        Path scriptPath = Path.of("src", "main", "python", scriptName).toAbsolutePath().normalize();
        if (!Files.exists(scriptPath)) {
            return fallback(payload, "未找到 Python 客服 Agent 脚本：" + scriptPath);
        }

        try {
            String effectiveApiKey = systemSettingService.effectiveAiApiKey("customer_agent_api_key", apiKey, "CUSTOMER_AGENT_API_KEY");
            String effectiveBaseUrl = systemSettingService.effectiveAiBaseUrl("customer_agent_base_url", aiBaseUrl);
            String effectiveModel = systemSettingService.getText("customer_agent_model", aiModel);
            ProcessBuilder builder = new ProcessBuilder(pythonBin, scriptPath.toString());
            builder.directory(Path.of(".").toAbsolutePath().normalize().toFile());
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONUTF8", "1");
            builder.environment().put("ARK_BASE_URL", effectiveBaseUrl);
            builder.environment().put("AI_BASE_URL", effectiveBaseUrl);
            builder.environment().put("CUSTOMER_AGENT_MODEL", effectiveModel);
            builder.environment().put("CUSTOMER_AGENT_TIMEOUT_SECONDS", String.valueOf(timeoutSeconds));
            if (effectiveApiKey != null && !effectiveApiKey.isBlank()) {
                builder.environment().put("ARK_API_KEY", effectiveApiKey);
                builder.environment().put("AI_API_KEY", effectiveApiKey);
                builder.environment().put("DEEPSEEK_API_KEY", effectiveApiKey);
                builder.environment().put("OPENAI_API_KEY", effectiveApiKey);
            }
            Process process = builder.start();

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(mapper.writeValueAsString(payload));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return fallback(payload, "客服 Agent 执行超时：" + Duration.ofSeconds(timeoutSeconds));
            }

            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            if (process.exitValue() != 0 && stdout.isBlank()) {
                return fallback(payload, stderr.isBlank() ? "客服 Agent 执行失败" : stderr);
            }

            Map<String, Object> result = mapper.readValue(stdout.isBlank() ? "{}" : stdout, Map.class);
            result.putIfAbsent("agentSource", "spring_customer_agent");
            return result;
        } catch (Exception ex) {
            return fallback(payload, "Spring Web 调用客服 Agent 失败：" + ex.getMessage());
        }
    }

    private List<Map<String, Object>> normalizeToolCalls(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                calls.add(normalized);
            }
        }
        return calls;
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private Map<String, Object> fallback(Map<String, Object> payload, String reason) {
        String mode = String.valueOf(payload.getOrDefault("mode", "general"));
        Map<?, ?> context = payload.get("context") instanceof Map<?, ?> map ? map : Map.of();
        Object queue = context.containsKey("queueAhead") ? context.get("queueAhead") : 0;
        Object wait = context.containsKey("estimatedWaitMinutes") ? context.get("estimatedWaitMinutes") : 0;
        Object slot = context.containsKey("recommendedSlot") ? context.get("recommendedSlot") : "今天 18:00";
        Object amount = context.containsKey("amount") ? context.get("amount") : 268;
        Map<?, ?> route = context.get("route") instanceof Map<?, ?> map ? map : Map.of();
        Object routeSummary = route.containsKey("summary") ? route.get("summary") : "请告诉我你的出发地，我会结合门店地址帮你规划到店路线。";
        String answer = switch (mode) {
            case "route" -> routeSummary + " 我也可以根据你选的预约时间，提醒你提前多久出门。";
            case "presale" -> "售前可以先确认手型、甲型长度、颜色偏好、是否需要钻饰，以及是否要按 AI 试穿图复刻。";
            case "aftersale" -> "售后可咨询补甲、翘边、饰品松动、卸甲护理和保养建议。完成后 7 天内有明显问题可以联系门店复查。";
            case "queue" -> "当前前方约 " + queue + " 名顾客，预计等待 " + wait + " 分钟，推荐时间为 " + slot + "。";
            default -> "你好，我是 NailGlow 智能客服。你可以问我款式、价格、预约、排队、路线、售前和售后问题。当前推荐预约 " + slot + "，项目金额约 ¥" + amount + "。";
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("intent", mode);
        result.put("quickReplies", java.util.List.of("售前服务", "售后服务", "查看排队", "采纳并预约"));
        result.put("handoffRequested", false);
        result.put("handoffReason", "");
        result.put("handoffMessage", "");
        result.put("agentSource", "spring_customer_agent_fallback");
        result.put("agentReason", reason);
        return result;
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
