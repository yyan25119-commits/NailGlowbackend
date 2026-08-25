package com.nailglow.backend.controller;

import com.nailglow.backend.ApiResponse;
import com.nailglow.backend.service.AdminRealtimeService;
import com.nailglow.backend.service.AuthService;
import com.nailglow.backend.service.CustomerServiceAgentService;
import com.nailglow.backend.service.RouteAgentService;
import com.nailglow.backend.service.SupportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private static final DateTimeFormatter SERVER_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final RouteAgentService routeAgentService;
    private final CustomerServiceAgentService customerServiceAgentService;
    private final JdbcTemplate jdbc;
    private final AuthService authService;
    private final SupportService supportService;
    private final AdminRealtimeService realtime;

    public AgentController(RouteAgentService routeAgentService, CustomerServiceAgentService customerServiceAgentService, JdbcTemplate jdbc, AuthService authService, SupportService supportService, AdminRealtimeService realtime) {
        this.routeAgentService = routeAgentService;
        this.customerServiceAgentService = customerServiceAgentService;
        this.jdbc = jdbc;
        this.authService = authService;
        this.supportService = supportService;
        this.realtime = realtime;
    }

    @PostMapping("/route-plan")
    public Map<String, Object> planRoute(HttpServletRequest request, @RequestBody Map<String, Object> payload) {
        authService.require(request, "user");
        String origin = String.valueOf(payload.getOrDefault("origin", payload.getOrDefault("start", ""))).trim();
        String destination = String.valueOf(payload.getOrDefault("destination", payload.getOrDefault("storeAddress", payload.getOrDefault("end", "")))).trim();
        if (!StringUtils.hasText(origin) || !StringUtils.hasText(destination)) {
            return ApiResponse.fail("origin 和 destination/storeAddress 均为必填项");
        }
        return ApiResponse.ok(routeAgentService.planRoute(payload));
    }

    @PostMapping("/customer-service")
    public Map<String, Object> customerService(HttpServletRequest request, @RequestBody Map<String, Object> payload) {
        long userId = authService.require(request, "user").id();
        return ApiResponse.ok(customerChatData(userId, payload));
    }

    @PostMapping("/customer-chat")
    public Map<String, Object> customerChat(HttpServletRequest request, @RequestBody Map<String, Object> payload) {
        long userId = authService.require(request, "user").id();
        return ApiResponse.ok(customerChatData(userId, payload));
    }

    @PostMapping(value = "/customer-chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter customerChatStream(HttpServletRequest request, @RequestBody Map<String, Object> payload) {
        long userId = authService.require(request, "user").id();
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> data = customerChatData(userId, payload);
                String answer = String.valueOf(data.getOrDefault("answer", ""));
                for (String chunk : splitAnswerForStreaming(answer)) {
                    emitter.send(SseEmitter.event().name("delta").data(Map.of("content", chunk), MediaType.APPLICATION_JSON));
                    try {
                        Thread.sleep(16L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                emitter.send(SseEmitter.event().name("done").data(data, MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message", ex.getMessage() == null ? "智能客服暂时不可用，请稍后再试。" : ex.getMessage()), MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    private Map<String, Object> customerChatData(long userId, Map<String, Object> payload) {
        long requestedConversationId = longValue(payload.get("conversationId"));
        String requestedMode = String.valueOf(payload.getOrDefault("mode", "general"));
        String userText = String.valueOf(payload.getOrDefault("message", ""));
        long styleId = longValue(payload.get("styleId"));
        String styleName = resolveStyleName(styleId, String.valueOf(payload.getOrDefault("styleName", "显白通勤款")));
        boolean autoBook = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("autoBook", "false")));

        Map<String, Object> activeHandoff = supportService.activeHandoffConversation(userId, requestedConversationId);
        if (!activeHandoff.isEmpty()) {
            long conversationId = supportService.recordManualUserMessage(userId, requestedConversationId, userText, requestedMode);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conversationId", conversationId);
            data.put("answer", "");
            data.put("managedBy", "human");
            data.put("handoffRequested", true);
            data.put("handoffStatus", activeHandoff.getOrDefault("handoffStatus", "manual"));
            data.put("handoffReason", activeHandoff.getOrDefault("handoffReason", ""));
            data.put("quickReplies", List.of("售前服务", "售后服务", "查看排队", "采纳并预约"));
            return data;
        }

        String serviceName = String.valueOf(payload.getOrDefault("serviceName", "AI 试穿复刻"));
        String preferredSlot = String.valueOf(payload.getOrDefault("preferredSlot", payload.getOrDefault("slotTime", ""))).trim();
        String recommendedSlot = StringUtils.hasText(preferredSlot) ? preferredSlot : recommendSlot();
        LocalDateTime scheduledAt = UserController.parseSlotTime(recommendedSlot);
        int duration = UserController.serviceDuration(serviceName);
        int amount = UserController.servicePrice(serviceName);
        String destination = String.valueOf(payload.getOrDefault("destination", payload.getOrDefault("storeAddress", "NailGlow 市中心旗舰店")));
        String origin = String.valueOf(payload.getOrDefault("origin", payload.getOrDefault("start", ""))).trim();
        String mode = requestedMode;

        Map<String, Object> route = Map.of(
                "ok", false,
                "summary", "",
                "needsOrigin", false,
                "navigationUrl", "",
                "routeSteps", List.of()
        );

        Map<String, Object> appointment = findLatestAppointment(userId);
        Map<String, Object> previousAgentState = supportService.latestAssistantAgentState(userId, requestedConversationId);
        int queueAhead = appointment.isEmpty()
                ? queueAhead(scheduledAt)
                : Math.max(0, intValue(appointment.get("queueNo")) - 1);
        int estimatedWait = queueAhead * 35;

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("currentServerTime", LocalDateTime.now().format(SERVER_TIME_FORMAT));
        context.put("quickSlots", List.of("今天 15:30", "今天 18:00", "明天 10:30", "明天 14:00"));
        context.put("supportsCustomSlot", true);
        context.put("recommendedSlot", recommendedSlot);
        context.put("queueAhead", queueAhead);
        context.put("estimatedWaitMinutes", estimatedWait);
        context.put("serviceName", serviceName);
        context.put("serviceDurationMinutes", duration);
        context.put("amount", amount);
        context.put("route", route);
        context.put("appointment", appointment);
        Map<String, Object> pendingAction = mapValue(previousAgentState.get("pendingAction"));
        if (!pendingAction.isEmpty()) {
            context.put("pendingAction", pendingAction);
        }
        context.put("storeName", "NailGlow 市中心旗舰店");
        context.put("presale", List.of(
                "可先发送手部照片和喜欢的款式，客服会确认甲型、长度和饰品密度。",
                "到店前建议保留 AI 试穿结果，方便美甲师快速复刻。"
        ));
        context.put("aftersale", List.of(
                "完成后 7 天内如有明显翘边或饰品松动，可联系门店复查。",
                "卸甲护理建议间隔 3-4 周，避免强行撕除甲面。"
        ));

        Map<String, Object> appliedAppointment = new LinkedHashMap<>();
        if (autoBook) {
            Map<String, Object> created = createOrRescheduleAppointment(
                    0L,
                    userId,
                    styleId <= 0 ? 1 : styleId,
                    serviceName,
                    scheduledAt,
                    recommendedSlot,
                    "fixed"
            );
            appliedAppointment.putAll(created);
            appointment = created;
            queueAhead = Math.max(0, intValue(created.get("queueNo")) - 1);
            estimatedWait = queueAhead * 35;
            context.put("appointment", created);
            context.put("recommendedSlot", created.getOrDefault("slotTime", recommendedSlot));
            context.put("queueAhead", queueAhead);
            context.put("estimatedWaitMinutes", estimatedWait);
        }

        Map<String, Object> supportCase = new LinkedHashMap<>();
        Map<String, Object> handoffState = new LinkedHashMap<>();
        Map<String, Object> agentPayload = new LinkedHashMap<>(payload);
        agentPayload.put("mode", mode);
        agentPayload.put("context", context);
        agentPayload.put("destination", destination);
        agentPayload.put("styleName", styleName);
        agentPayload.put("routeMode", String.valueOf(payload.getOrDefault("routeMode", "driving")));
        if (StringUtils.hasText(origin)) {
            agentPayload.put("origin", origin);
        }

        Map<String, Object> agent = customerServiceAgentService.chat(agentPayload, toolCalls ->
                executeCustomerToolCalls(toolCalls, userId, styleId <= 0 ? 1 : styleId, serviceName, context, appliedAppointment, supportCase, handoffState)
        );

        Map<String, Object> delegatedRoute = mapValue(agent.get("delegatedRoute"));
        boolean routeHandledByGraph = !delegatedRoute.isEmpty();
        if (routeHandledByGraph) {
            route = delegatedRoute;
            agent.remove("delegatedRoute");
        }

        boolean routeRequested = isRouteIntent(agent) || "route".equals(requestedMode);
        origin = firstText(payload, "origin", "start");
        if (!StringUtils.hasText(origin)) {
            origin = firstText(agent, "routeOrigin", "origin", "start");
        }
        if (routeRequested) {
            mode = "route";
            if (routeHandledByGraph) {
                context.put("route", route);
            } else if (StringUtils.hasText(origin)) {
                Map<String, Object> routePayload = new LinkedHashMap<>(payload);
                routePayload.put("origin", origin);
                routePayload.put("destination", destination);
                routePayload.put("mode", String.valueOf(payload.getOrDefault("routeMode", "driving")));
                routePayload.put("styleName", styleName);
                route = routeAgentService.planRoute(routePayload);
            } else {
                route = Map.of(
                        "ok", false,
                        "summary", "需要当前位置或出发地才能规划路线。请允许浏览器定位，或直接输入“从你的出发地到店怎么走？”",
                        "needsOrigin", true,
                        "navigationUrl", "",
                        "routeSteps", List.of()
                );
            }
            String recommendedStore = firstText(route, "recommendedStoreName", "storeName");
            if (StringUtils.hasText(recommendedStore)) {
                context.put("storeName", recommendedStore);
            }
            context.put("route", route);
            if (!routeHandledByGraph) {
                agentPayload = new LinkedHashMap<>(payload);
                agentPayload.put("mode", mode);
                agentPayload.put("context", context);
                agentPayload.put("disableTools", true);
                agent = customerServiceAgentService.chat(agentPayload);
            }
            if (StringUtils.hasText(String.valueOf(route.getOrDefault("summary", "")))) {
                agent.put("answer", String.valueOf(route.get("summary")));
                agent.put("intent", "route");
            }
        }

        if (!supportCase.isEmpty()) {
            agent.put("category", supportCase.getOrDefault("category", "其他"));
            agent.put("severity", supportCase.getOrDefault("severity", "low"));
            agent.put("shouldNotifyMerchant", supportCase.getOrDefault("shouldNotifyMerchant", false));
            agent.put("important", supportCase.getOrDefault("important", false));
            agent.put("summary", supportCase.getOrDefault("summary", ""));
            agent.put("importantItems", supportCase.getOrDefault("importantItems", List.of()));
            agent.put("merchantFeedback", supportCase);
        }
        if (!handoffState.isEmpty()) {
            agent.put("handoffRequested", true);
            agent.put("handoffReason", handoffState.getOrDefault("handoffReason", "AI 已判断该问题需要人工客服介入"));
            agent.put("handoffMessage", handoffState.getOrDefault("handoffMessage", "已为你转接人工客服，请在当前对话等待回复。"));
            agent.put("shouldNotifyMerchant", true);
        }
        if (!appliedAppointment.isEmpty()) {
            appointment = appliedAppointment;
            recommendedSlot = String.valueOf(appliedAppointment.getOrDefault("slotTime", recommendedSlot));
            queueAhead = Math.max(0, intValue(appliedAppointment.get("queueNo")) - 1);
            estimatedWait = queueAhead * 35;
            context.put("appointment", appliedAppointment);
            context.put("recommendedSlot", recommendedSlot);
            context.put("queueAhead", queueAhead);
            context.put("estimatedWaitMinutes", estimatedWait);
        }

        boolean handoffRequested = bool(agent.get("handoffRequested"));
        if (handoffRequested) {
            agent.put("answer", String.valueOf(agent.getOrDefault("handoffMessage", "已为你转接人工客服，请在当前对话等待回复。")));
            agent.put("shouldNotifyMerchant", true);
            agent.putIfAbsent("handoffReason", "AI 已判断该问题需要人工客服介入");
        }

        long conversationId = supportService.recordChat(userId, mode, userText, agent, context);

        Map<String, Object> data = new LinkedHashMap<>(context);
        data.put("conversationId", conversationId);
        data.put("answer", String.valueOf(agent.getOrDefault("answer", "你好，我是 NailGlow 智能客服。")));
        data.put("managedBy", handoffRequested ? "human" : "ai");
        data.put("handoffRequested", handoffRequested);
        data.put("handoffStatus", handoffRequested ? "requested" : "ai");
        data.put("handoffReason", agent.getOrDefault("handoffReason", ""));
        data.put("intent", agent.getOrDefault("intent", mode));
        data.put("quickReplies", agent.getOrDefault("quickReplies", List.of("售前服务", "售后服务", "查看排队", "采纳并预约")));
        data.put("agentSource", agent.getOrDefault("agentSource", "customer_agent"));
        data.put("merchantFeedback", agent.getOrDefault("merchantFeedback", null));
        data.put("severity", agent.getOrDefault("severity", "low"));
        data.put("category", agent.getOrDefault("category", "其他"));
        data.put("shouldNotifyMerchant", agent.getOrDefault("shouldNotifyMerchant", false));
        if (agent.containsKey("agentReason")) {
            data.put("agentReason", agent.get("agentReason"));
        }
        data.put("recommendedSlot", recommendedSlot);
        data.put("queueAhead", queueAhead);
        data.put("estimatedWaitMinutes", estimatedWait);
        data.put("serviceName", serviceName);
        data.put("serviceDurationMinutes", duration);
        data.put("amount", amount);
        data.put("appointment", appointment);
        return data;
    }

    private String resolveStyleName(long styleId, String fallback) {
        if (styleId <= 0) {
            return fallback;
        }
        List<String> names = jdbc.query("""
                select name
                from nail_styles
                where id = ?
                limit 1
                """, (rs, rowNum) -> rs.getString("name"), styleId);
        return names.isEmpty() ? fallback : names.get(0);
    }

    @GetMapping("/customer-chat/messages")
    public Map<String, Object> customerChatMessages(HttpServletRequest request, @RequestParam(defaultValue = "0") long conversationId) {
        long userId = authService.require(request, "user").id();
        Map<String, Object> data = supportService.userConversationMessages(userId, conversationId);
        return ApiResponse.ok(data);
    }

    private List<Map<String, Object>> executeCustomerToolCalls(List<Map<String, Object>> toolCalls,
                                                               long userId,
                                                               long styleId,
                                                               String serviceName,
                                                               Map<String, Object> context,
                                                               Map<String, Object> appliedAppointment,
                                                               Map<String, Object> supportCase,
                                                               Map<String, Object> handoffState) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> toolCall : toolCalls) {
            String toolName = String.valueOf(toolCall.getOrDefault("name", ""));
            String toolCallId = String.valueOf(toolCall.getOrDefault("id", ""));
            Map<String, Object> arguments = mapValue(toolCall.get("arguments"));
            Map<String, Object> result;
            try {
                result = switch (toolName) {
                    case "create_or_reschedule_appointment" -> executeAppointmentTool(arguments, userId, styleId, serviceName, context, appliedAppointment);
                    case "update_support_case" -> executeSupportCaseTool(arguments, supportCase);
                    case "request_human_handoff" -> executeHandoffTool(arguments, supportCase, handoffState);
                    default -> Map.of("ok", false, "message", "未知工具：" + toolName);
                };
            } catch (Exception ex) {
                result = Map.of("ok", false, "message", ex.getMessage() == null ? "工具执行失败" : ex.getMessage());
            }
            Map<String, Object> toolResult = new LinkedHashMap<>();
            toolResult.put("toolCallId", toolCallId);
            toolResult.put("toolName", toolName);
            toolResult.put("result", result);
            results.add(toolResult);
        }
        return results;
    }

    private Map<String, Object> executeAppointmentTool(Map<String, Object> arguments,
                                                       long userId,
                                                       long styleId,
                                                       String serviceName,
                                                       Map<String, Object> context,
                                                       Map<String, Object> appliedAppointment) {
        String scheduledAtIso = firstText(arguments, "scheduledAtIso", "scheduledAt");
        if (!StringUtils.hasText(scheduledAtIso)) {
            return Map.of("ok", false, "message", "缺少 scheduledAtIso，无法创建预约。");
        }
        LocalDateTime scheduledAt = UserController.parseScheduledAtValue(scheduledAtIso);
        String requestedLabel = firstText(arguments, "userFacingSlotText");
        if (!StringUtils.hasText(requestedLabel)) {
            requestedLabel = UserController.formatAbsoluteSlotLabel(scheduledAt);
        }
        List<String> quickSlots = context.get("quickSlots") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        String fixedSlotLabel = UserController.formatSlotLabel(scheduledAt);
        String slotKind = quickSlots.contains(requestedLabel) || quickSlots.contains(fixedSlotLabel) ? "fixed" : "custom";
        Map<String, Object> currentAppointment = mapValue(context.get("appointment"));
        long appointmentId = longValue(currentAppointment.get("id"));
        String action = String.valueOf(arguments.getOrDefault("action", "create"));
        boolean hasActiveAppointment = appointmentId > 0;
        if ("create".equals(action) && hasActiveAppointment) {
            String currentSlot = firstText(currentAppointment, "slotTimeAdmin", "slotTimeUser", "slotTime");
            String targetSlot = "fixed".equals(slotKind) ? fixedSlotLabel : requestedLabel;
            Map<String, Object> pendingAction = new LinkedHashMap<>();
            pendingAction.put("type", "appointment_reschedule");
            pendingAction.put("awaitingConfirmation", true);
            pendingAction.put("action", "reschedule");
            pendingAction.put("scheduledAtIso", scheduledAt.toString());
            pendingAction.put("userFacingSlotText", targetSlot);
            pendingAction.put("requestSummary", firstText(arguments, "requestSummary"));

            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("ok", false);
            conflict.put("conflictType", "single_active_appointment");
            conflict.put("requiresConfirmation", true);
            conflict.put("currentSlot", currentSlot);
            conflict.put("requestedSlot", targetSlot);
            conflict.put("message", "当前账号只能保留一个有效预约，不能同时保留两条预约。若继续处理，只能把当前预约改到 " + targetSlot + "。");
            conflict.put("pendingAction", pendingAction);
            return conflict;
        }
        Map<String, Object> result = createOrRescheduleAppointment(
                "reschedule".equals(action) ? appointmentId : 0L,
                userId,
                styleId,
                serviceName,
                scheduledAt,
                "fixed".equals(slotKind) ? fixedSlotLabel : requestedLabel,
                slotKind
        );
        result.put("requestedAction", action);
        result.put("effectiveAction", hasActiveAppointment ? "reschedule" : "create");
        result.put("replacedExistingAppointment", hasActiveAppointment);
        appliedAppointment.clear();
        appliedAppointment.putAll(result);
        context.put("appointment", result);
        context.put("recommendedSlot", result.getOrDefault("slotTime", fixedSlotLabel));
        context.put("queueAhead", Math.max(0, intValue(result.get("queueNo")) - 1));
        context.put("estimatedWaitMinutes", Math.max(0, intValue(result.get("queueNo")) - 1) * 35);
        return result;
    }

    private Map<String, Object> executeSupportCaseTool(Map<String, Object> arguments, Map<String, Object> supportCase) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("category", firstText(arguments, "category").isBlank() ? "其他" : firstText(arguments, "category"));
        result.put("severity", normalizeSeverity(firstText(arguments, "severity")));
        result.put("shouldNotifyMerchant", bool(arguments.getOrDefault("shouldNotifyMerchant", false)));
        result.put("important", bool(arguments.getOrDefault("important", false)));
        result.put("summary", firstText(arguments, "summary"));
        Object rawItems = arguments.get("importantItems");
        result.put("importantItems", rawItems instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of());
        supportCase.clear();
        supportCase.putAll(result);
        supportCase.remove("ok");
        return result;
    }

    private Map<String, Object> executeHandoffTool(Map<String, Object> arguments,
                                                   Map<String, Object> supportCase,
                                                   Map<String, Object> handoffState) {
        String reason = firstText(arguments, "handoffReason");
        if (!StringUtils.hasText(reason)) {
            reason = "AI 已判断该问题需要人工客服介入";
        }
        String message = firstText(arguments, "handoffMessage");
        if (!StringUtils.hasText(message)) {
            message = "已为你转接人工客服，请在当前对话等待回复。";
        }
        handoffState.clear();
        handoffState.put("handoffRequested", true);
        handoffState.put("handoffReason", reason);
        handoffState.put("handoffMessage", message);
        if (supportCase.isEmpty()) {
            supportCase.put("category", "其他");
            supportCase.put("severity", "medium");
            supportCase.put("shouldNotifyMerchant", true);
            supportCase.put("important", true);
            supportCase.put("summary", reason);
            supportCase.put("importantItems", List.of(reason));
        } else {
            supportCase.put("shouldNotifyMerchant", true);
            supportCase.put("important", true);
            if (!StringUtils.hasText(String.valueOf(supportCase.getOrDefault("summary", "")))) {
                supportCase.put("summary", reason);
            }
        }
        return Map.of(
                "ok", true,
                "handoffRequested", true,
                "handoffReason", reason,
                "handoffMessage", message
        );
    }

    private Map<String, Object> createOrRescheduleAppointment(long appointmentId,
                                                              long userId,
                                                              long styleId,
                                                              String serviceName,
                                                              LocalDateTime scheduledAt,
                                                              String userFacingSlotText,
                                                              String slotKind) {
        String slotTimeAdmin = UserController.formatAbsoluteSlotLabel(scheduledAt);
        String slotTime = "fixed".equals(slotKind)
                ? UserController.formatSlotLabel(scheduledAt)
                : (StringUtils.hasText(userFacingSlotText) ? userFacingSlotText : slotTimeAdmin);
        int amount = UserController.servicePrice(serviceName);
        int duration = UserController.serviceDuration(serviceName);
        long resolvedAppointmentId = resolveActiveAppointmentId(userId, appointmentId);
        int queueNo = nextQueueNo(scheduledAt, resolvedAppointmentId);
        if (resolvedAppointmentId > 0) {
            jdbc.update("""
                    update appointments
                    set style_id = ?, service_name = ?, slot_time = ?, scheduled_at = ?, store_name = 'NailGlow 市中心旗舰店',
                        status = '已确认', amount = ?, paid_status = '未支付', duration_minutes = ?, queue_no = ?
                    where id = ? and user_id = ?
                    """, styleId, serviceName, slotTime, Timestamp.valueOf(scheduledAt), amount, duration, queueNo, resolvedAppointmentId, userId);
        } else {
            jdbc.update("""
                    insert into appointments(user_id, style_id, service_name, slot_time, scheduled_at, store_name, status, amount, paid_status, duration_minutes, queue_no)
                    values (?, ?, ?, ?, ?, 'NailGlow 市中心旗舰店', '已确认', ?, '未支付', ?, ?)
                    """, userId, styleId, serviceName, slotTime, Timestamp.valueOf(scheduledAt), amount, duration, queueNo);
            resolvedAppointmentId = jdbc.queryForObject("select last_insert_id()", Long.class);
        }
        cancelOtherActiveAppointments(userId, resolvedAppointmentId);
        realtime.broadcast("appointments.changed");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("id", resolvedAppointmentId);
        result.put("status", "已确认");
        result.put("slotTime", slotTime);
        result.put("slotTimeUser", slotTime);
        result.put("slotTimeAdmin", slotTimeAdmin);
        result.put("scheduledAt", String.valueOf(scheduledAt));
        result.put("slotKind", slotKind);
        result.put("serviceName", serviceName);
        result.put("amount", amount);
        result.put("durationMinutes", duration);
        result.put("queueNo", queueNo);
        result.put("paidStatus", "未支付");
        return result;
    }

    private Map<String, Object> findLatestAppointment(long userId) {
        List<Map<String, Object>> rows = jdbc.query("""
                select *
                from appointments
                where user_id = ?
                  and status not in ('已取消', '已完成')
                  and coalesce(scheduled_at, created_at) >= date_sub(now(), interval 1 day)
                order by coalesce(scheduled_at, created_at) desc, id desc
                limit 1
                """, (rs, rowNum) -> {
            LocalDateTime scheduledAt = rs.getTimestamp("scheduled_at") == null
                    ? rs.getTimestamp("created_at").toLocalDateTime()
                    : rs.getTimestamp("scheduled_at").toLocalDateTime();
            String rawSlotTime = rs.getString("slot_time");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("status", rs.getString("status"));
            row.put("slotTime", rawSlotTime);
            row.put("slotTimeUser", rawSlotTime);
            row.put("slotTimeAdmin", UserController.formatAbsoluteSlotLabel(scheduledAt));
            row.put("scheduledAt", String.valueOf(scheduledAt));
            row.put("amount", rs.getBigDecimal("amount"));
            row.put("durationMinutes", rs.getInt("duration_minutes"));
            row.put("queueNo", rs.getInt("queue_no"));
            row.put("paidStatus", rs.getString("paid_status"));
            row.put("serviceName", rs.getString("service_name"));
            row.put("slotKind", rawSlotTime != null && (rawSlotTime.startsWith("今天") || rawSlotTime.startsWith("明天") || rawSlotTime.startsWith("后天")) ? "fixed" : "custom");
            return row;
        }, userId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private boolean isRouteIntent(Map<String, Object> agent) {
        String intent = String.valueOf(agent.getOrDefault("intent", "")).trim();
        String category = String.valueOf(agent.getOrDefault("category", "")).trim();
        return "route".equalsIgnoreCase(intent)
                || "路线".equals(intent)
                || "route".equalsIgnoreCase(category)
                || "路线".equals(category);
    }

    private String normalizeSeverity(String value) {
        return switch (value) {
            case "urgent", "high", "medium", "low" -> value;
            default -> "low";
        };
    }

    private String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private long longValue(Object value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private int intValue(Object value) {
        if (value == null) return 0;
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(value));
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

    private List<String> splitAnswerForStreaming(String answer) {
        if (!StringUtils.hasText(answer)) return List.of();
        List<String> chunks = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < answer.length(); index++) {
            char current = answer.charAt(index);
            builder.append(current);
            if ("，。！？；\n".indexOf(current) >= 0 || builder.length() >= 14) {
                chunks.add(builder.toString());
                builder.setLength(0);
            }
        }
        if (builder.length() > 0) {
            chunks.add(builder.toString());
        }
        return chunks;
    }

    private String recommendSlot() {
        List<String> slots = List.of("今天 15:30", "今天 18:00", "明天 10:30", "明天 14:00");
        for (String slot : slots) {
            LocalDateTime time = UserController.parseSlotTime(slot);
            Integer count = jdbc.queryForObject("""
                    select count(*) from appointments
                    where status in ('已确认', '待到店')
                      and scheduled_at is not null
                      and timestampdiff(minute, scheduled_at, ?) between -90 and 90
                    """, Integer.class, Timestamp.valueOf(time));
            if ((count == null ? 0 : count) < 4) {
                return slot;
            }
        }
        return "明天 14:00";
    }

    private int queueAhead(LocalDateTime scheduledAt) {
        Integer count = jdbc.queryForObject("""
                select count(*) from appointments
                where status in ('已确认','待到店')
                  and date(coalesce(scheduled_at, created_at)) = date(?)
                  and coalesce(scheduled_at, created_at) <= ?
                """, Integer.class, Timestamp.valueOf(scheduledAt), Timestamp.valueOf(scheduledAt));
        return count == null ? 0 : count;
    }

    private int nextQueueNo(LocalDateTime scheduledAt, long excludeAppointmentId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from appointments
                where status in ('已确认', '待到店')
                  and date(coalesce(scheduled_at, created_at)) = date(?)
                  and id <> ?
                """, Integer.class, Timestamp.valueOf(scheduledAt), excludeAppointmentId);
        return (count == null ? 0 : count) + 1;
    }

    private long resolveActiveAppointmentId(long userId, long preferredAppointmentId) {
        if (preferredAppointmentId > 0) {
            List<Long> exact = jdbc.query("""
                    select id
                    from appointments
                    where id = ?
                      and user_id = ?
                      and status not in ('已取消', '已完成')
                    limit 1
                    """, (rs, rowNum) -> rs.getLong("id"), preferredAppointmentId, userId);
            if (!exact.isEmpty()) {
                return exact.get(0);
            }
        }
        List<Long> ids = jdbc.query("""
                select id
                from appointments
                where user_id = ?
                  and status not in ('已取消', '已完成')
                order by coalesce(scheduled_at, created_at) desc, id desc
                limit 1
                """, (rs, rowNum) -> rs.getLong("id"), userId);
        return ids.isEmpty() ? 0L : ids.get(0);
    }

    private void cancelOtherActiveAppointments(long userId, long keepId) {
        if (keepId <= 0) {
            return;
        }
        jdbc.update("""
                update appointments
                set status = '已取消'
                where user_id = ?
                  and id <> ?
                  and status not in ('已取消', '已完成')
                """, userId, keepId);
    }
}
