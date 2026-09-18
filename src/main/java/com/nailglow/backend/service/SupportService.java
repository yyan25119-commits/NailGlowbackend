package com.nailglow.backend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SupportService {
    private final JdbcTemplate jdbc;
    private final AdminRealtimeService realtime;
    private final ObjectMapper mapper = new ObjectMapper();

    public SupportService(JdbcTemplate jdbc, AdminRealtimeService realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    public long recordChat(long userId, String mode, String userText, Map<String, Object> agent, Map<String, Object> context) {
        long conversationId = findOrCreateConversation(userId);
        String answer = String.valueOf(agent.getOrDefault("answer", ""));
        String agentSource = String.valueOf(agent.getOrDefault("agentSource", "customer_agent"));
        Map<String, Object> triage = triage(mode, userText, agent);
        boolean notify = bool(triage.get("shouldNotifyMerchant"));
        boolean important = bool(triage.get("important"));
        boolean handoffRequested = bool(agent.get("handoffRequested"));
        String handoffReason = text(firstPresent(agent.get("handoffReason"), triage.get("summary"), "用户需要人工客服介入"));

        insertMessage(conversationId, userId, "user", userText, mode, "", Map.of());
        insertMessage(conversationId, userId, "assistant", answer, mode, agentSource, Map.of("context", context, "agent", agent));
        jdbc.update("""
                update support_conversations
                set category = ?, severity = ?, notify_merchant = notify_merchant or ? or ?, important = important or ?,
                    title = ?, summary = ?, important_items_json = ?, latest_message = ?,
                    handoff_requested = handoff_requested or ?,
                    handoff_status = case
                        when ? then case when handoff_status = 'manual' then 'manual' else 'requested' end
                        else handoff_status
                    end,
                    handoff_reason = case when ? then ? else handoff_reason end,
                    last_message_at = current_timestamp, updated_at = current_timestamp
                where id = ?
                """,
                triage.get("category"),
                triage.get("severity"),
                notify,
                handoffRequested,
                important,
                triage.get("title"),
                triage.get("summary"),
                json(triage.get("importantItems")),
                shorten(userText, 1100),
                handoffRequested,
                handoffRequested,
                handoffRequested,
                handoffReason,
                conversationId);

        if (notify || handoffRequested) {
            realtime.broadcast("support.changed", Map.of("conversationId", conversationId));
        }
        return conversationId;
    }

    public List<Map<String, Object>> listMerchantConversations(String status, String category, String severity, boolean importantOnly) {
        StringBuilder sql = new StringBuilder("""
                select c.*, u.nickname, u.account
                from support_conversations c
                left join users u on u.id = c.user_id
                where c.notify_merchant = true
                """);
        List<Object> args = new ArrayList<>();
        if (hasText(status)) {
            sql.append(" and c.status = ?");
            args.add(status);
        }
        if (hasText(category)) {
            sql.append(" and c.category = ?");
            args.add(category);
        }
        if (hasText(severity)) {
            sql.append(" and c.severity = ?");
            args.add(severity);
        }
        if (importantOnly) {
            sql.append(" and c.important = true");
        }
        sql.append(" order by c.important desc, c.last_message_at desc limit 100");
        return jdbc.query(sql.toString(), (rs, rowNum) -> conversationRow(rs), args.toArray());
    }

    public List<Map<String, Object>> messages(long conversationId) {
        return jdbc.query("""
                select *
                from support_messages
                where conversation_id = ?
                order by created_at asc, id asc
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("conversationId", rs.getLong("conversation_id"));
            row.put("role", rs.getString("role"));
            row.put("content", rs.getString("content"));
            row.put("mode", rs.getString("mode"));
            row.put("agentSource", rs.getString("agent_source"));
            Map<String, Object> metadata = parseMap(rs.getString("metadata_json"));
            row.put("metadata", metadata);
            row.put("navigationUrl", navigationUrl(metadata));
            row.put("ragSources", ragSources(metadata));
            row.put("createdAt", String.valueOf(rs.getTimestamp("created_at").toLocalDateTime()));
            return row;
        }, conversationId);
    }

    public Map<String, Object> conversationForUser(long userId, long conversationId) {
        if (conversationId <= 0) return Map.of();
        List<Map<String, Object>> rows = jdbc.query("""
                select c.*, u.nickname, u.account
                from support_conversations c
                left join users u on u.id = c.user_id
                where c.id = ? and c.user_id = ?
                limit 1
                """, (rs, rowNum) -> conversationRow(rs), conversationId, userId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Map<String, Object> activeHandoffConversation(long userId, long preferredConversationId) {
        Map<String, Object> preferred = conversationForUser(userId, preferredConversationId);
        if (isActiveHandoff(preferred)) return preferred;
        List<Map<String, Object>> rows = jdbc.query("""
                select c.*, u.nickname, u.account
                from support_conversations c
                left join users u on u.id = c.user_id
                where c.user_id = ?
                  and c.handoff_requested = true
                  and c.handoff_status in ('requested', 'manual')
                  and c.status in ('未处理', '处理中')
                  and c.last_message_at > date_sub(now(), interval 6 hour)
                order by c.last_message_at desc
                limit 1
                """, (rs, rowNum) -> conversationRow(rs), userId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Map<String, Object> userConversationMessages(long userId, long conversationId) {
        Map<String, Object> conversation = conversationForUser(userId, conversationId);
        if (conversation.isEmpty()) {
            return Map.of("conversation", Map.of(), "messages", List.of());
        }
        return Map.of("conversation", conversation, "messages", messages(conversationId));
    }

    public Map<String, Object> latestAssistantAgentState(long userId, long conversationId) {
        long resolvedConversationId = conversationId > 0 ? conversationId : findOrCreateConversation(userId);
        Map<String, Object> conversation = conversationForUser(userId, resolvedConversationId);
        if (conversation.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbc.query("""
                select metadata_json
                from support_messages
                where conversation_id = ?
                  and role = 'assistant'
                order by created_at desc, id desc
                limit 1
                """, (rs, rowNum) -> parseMap(rs.getString("metadata_json")), resolvedConversationId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> metadata = rows.get(0);
        Object agent = metadata.get("agent");
        if (agent instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    public long recordManualUserMessage(long userId, long conversationId, String userText, String mode) {
        Map<String, Object> conversation = activeHandoffConversation(userId, conversationId);
        if (conversation.isEmpty()) {
            return recordChat(userId, mode, userText, Map.of(
                    "answer", "已为你转接人工客服，请在当前对话等待回复。",
                    "handoffRequested", true,
                    "handoffReason", "用户需要人工客服继续处理",
                    "handoffMessage", "已为你转接人工客服，请在当前对话等待回复。",
                    "shouldNotifyMerchant", true,
                    "severity", "medium",
                    "category", "其他",
                    "important", true,
                    "summary", "用户需要人工客服继续处理"
            ), Map.of());
        }
        long id = Long.parseLong(String.valueOf(conversation.get("id")));
        insertMessage(id, userId, "user", userText, mode, "", Map.of("managedBy", "human"));
        jdbc.update("""
                update support_conversations
                set latest_message = ?, notify_merchant = true, status = '处理中',
                    handoff_requested = true,
                    handoff_status = case when handoff_status = 'manual' then 'manual' else 'requested' end,
                    last_message_at = current_timestamp, updated_at = current_timestamp
                where id = ?
                """, shorten(userText, 1100), id);
        realtime.broadcast("support.changed", Map.of("conversationId", id));
        return id;
    }

    public void takeoverConversation(long id) {
        jdbc.update("""
                update support_conversations
                set status = '处理中', notify_merchant = true, handoff_requested = true,
                    handoff_status = 'manual',
                    handoff_reason = coalesce(handoff_reason, '后台客服已接管'),
                    updated_at = current_timestamp
                where id = ?
                """, id);
        realtime.broadcast("support.changed", Map.of("conversationId", id));
    }

    public long addMerchantReply(long conversationId, String content) {
        Long userId = jdbc.queryForObject("select user_id from support_conversations where id = ?", Long.class, conversationId);
        if (userId == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        insertMessage(conversationId, userId, "merchant", content, "manual", "merchant_console", Map.of("managedBy", "human"));
        jdbc.update("""
                update support_conversations
                set status = '处理中', notify_merchant = true, handoff_requested = true, handoff_status = 'manual',
                    latest_message = ?, last_message_at = current_timestamp, updated_at = current_timestamp
                where id = ?
                """, shorten(content, 1100), conversationId);
        realtime.broadcast("support.changed", Map.of("conversationId", conversationId));
        return conversationId;
    }

    public void updateConversation(long id, Map<String, Object> body) {
        String status = text(body.getOrDefault("status", ""));
        String note = text(body.getOrDefault("merchantNote", ""));
        Object importantValue = body.get("important");
        if (hasText(status)) {
            if (isClosedStatus(status)) {
                jdbc.update("""
                        update support_conversations
                        set status = ?, handoff_status = 'ai', handoff_requested = false, updated_at = current_timestamp
                        where id = ?
                        """, status, id);
            } else {
                jdbc.update("update support_conversations set status = ?, updated_at = current_timestamp where id = ?", status, id);
            }
        }
        if (hasText(note)) {
            jdbc.update("update support_conversations set merchant_note = ?, updated_at = current_timestamp where id = ?", note, id);
        }
        if (importantValue != null) {
            jdbc.update("update support_conversations set important = ?, updated_at = current_timestamp where id = ?", bool(importantValue), id);
        }
        realtime.broadcast("support.changed", Map.of("conversationId", id));
    }

    public void regenerateSummary(long id) {
        List<Map<String, Object>> rows = messages(id);
        String latestUser = rows.stream()
                .filter(row -> "user".equals(row.get("role")))
                .reduce((left, right) -> right)
                .map(row -> String.valueOf(row.get("content")))
                .orElse("");
        Map<String, Object> triage = triage("general", latestUser, Map.of());
        jdbc.update("""
                update support_conversations
                set summary = ?, important_items_json = ?, category = ?, severity = ?, important = ?, updated_at = current_timestamp
                where id = ?
                """, triage.get("summary"), json(triage.get("importantItems")), triage.get("category"), triage.get("severity"), bool(triage.get("important")), id);
        realtime.broadcast("support.changed", Map.of("conversationId", id));
    }

    private long findOrCreateConversation(long userId) {
        List<Long> rows = jdbc.query("""
                select id
                from support_conversations
                where user_id = ? and status in ('未处理', '处理中')
                  and last_message_at > date_sub(now(), interval 6 hour)
                order by last_message_at desc
                limit 1
                """, (rs, rowNum) -> rs.getLong("id"), userId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        jdbc.update("""
                insert into support_conversations(user_id, status, category, severity, notify_merchant, important, title, summary, important_items_json, latest_message, last_message_at)
                values (?, '未处理', '其他', 'low', false, false, '新的客服咨询', '', '[]', '', current_timestamp)
                """, userId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void insertMessage(long conversationId, long userId, String role, String content, String mode, String agentSource, Map<String, Object> metadata) {
        jdbc.update("""
                insert into support_messages(conversation_id, user_id, role, content, mode, agent_source, metadata_json)
                values (?, ?, ?, ?, ?, ?, ?)
                """, conversationId, userId, role, content, mode, agentSource, json(metadata));
    }

    private Map<String, Object> triage(String mode, String message, Map<String, Object> agent) {
        Map<?, ?> feedback = agent.get("merchantFeedback") instanceof Map<?, ?> map ? map : Map.of();
        String category = text(firstPresent(feedback.get("category"), agent.get("category"), "其他"));
        String severity = normalizeSeverity(text(firstPresent(feedback.get("severity"), agent.get("severity"), "low")));
        boolean shouldNotify = bool(firstPresent(feedback.get("shouldNotifyMerchant"), agent.get("shouldNotifyMerchant"), false));
        boolean handoffRequested = bool(agent.get("handoffRequested"));
        if (handoffRequested) {
            shouldNotify = true;
            if ("low".equals(severity)) {
                severity = "medium";
            }
        }
        boolean important = handoffRequested
                || bool(firstPresent(feedback.get("important"), agent.get("important"), false))
                || ("urgent".equals(severity) || "high".equals(severity)) && shouldNotify;
        Object items = firstPresent(feedback.get("importantItems"), agent.get("importantItems"), List.of());
        if (!(items instanceof List<?>)) {
            items = List.of(String.valueOf(items));
        }
        String summary = text(firstPresent(feedback.get("summary"), agent.get("summary"), ""));
        if (!hasText(summary) && shouldNotify) {
            summary = "AI 已判定需要商家跟进，请查看上下文消息。";
        }
        if (handoffRequested && !hasText(summary)) {
            summary = text(firstPresent(agent.get("handoffReason"), "用户需要人工客服介入"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("category", category);
        result.put("severity", severity);
        result.put("shouldNotifyMerchant", shouldNotify);
        result.put("important", important);
        result.put("title", titleFor(category, severity));
        result.put("summary", summary);
        result.put("importantItems", items);
        return result;
    }

    private Map<String, Object> conversationRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("userId", rs.getLong("user_id"));
        row.put("user", rs.getString("nickname"));
        row.put("account", rs.getString("account"));
        row.put("status", rs.getString("status"));
        row.put("category", rs.getString("category"));
        row.put("severity", rs.getString("severity"));
        row.put("important", rs.getBoolean("important"));
        row.put("title", rs.getString("title"));
        row.put("summary", rs.getString("summary"));
        row.put("importantItems", parseList(rs.getString("important_items_json")));
        row.put("latestMessage", rs.getString("latest_message"));
        row.put("merchantNote", rs.getString("merchant_note"));
        row.put("handoffRequested", rs.getBoolean("handoff_requested"));
        row.put("handoffStatus", rs.getString("handoff_status"));
        row.put("handoffReason", rs.getString("handoff_reason"));
        row.put("lastMessageAt", String.valueOf(rs.getTimestamp("last_message_at").toLocalDateTime()));
        row.put("createdAt", String.valueOf(rs.getTimestamp("created_at").toLocalDateTime()));
        return row;
    }

    private String titleFor(String category, String severity) {
        return category + " · " + switch (severity) {
            case "urgent" -> "紧急";
            case "high" -> "重要";
            case "medium" -> "待确认";
            default -> "普通";
        };
    }

    private String normalizeSeverity(String value) {
        return switch (value) {
            case "urgent", "high", "medium", "low" -> value;
            default -> "low";
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isActiveHandoff(Map<String, Object> conversation) {
        if (conversation == null || conversation.isEmpty()) return false;
        String status = text(conversation.get("status"));
        String handoffStatus = text(conversation.get("handoffStatus"));
        return bool(conversation.get("handoffRequested"))
                && ("requested".equals(handoffStatus) || "manual".equals(handoffStatus))
                && ("未处理".equals(status) || "处理中".equals(status));
    }

    private boolean isClosedStatus(String status) {
        return "已处理".equals(status) || "忽略".equals(status) || "resolved".equals(status) || "ignored".equals(status);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value == null) continue;
            if (value instanceof String text && text.isBlank()) continue;
            return value;
        }
        return "";
    }

    private String shorten(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private List<Object> parseList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            Object parsed = mapper.readValue(raw, List.class);
            if (parsed instanceof List<?> list) return new ArrayList<>(list);
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Object parsed = mapper.readValue(raw, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private String navigationUrl(Map<String, Object> metadata) {
        Object context = metadata.get("context");
        if (context instanceof Map<?, ?> contextMap) {
            Object route = contextMap.get("route");
            if (route instanceof Map<?, ?> routeMap) {
                Object navigationUrl = routeMap.get("navigationUrl");
                if (navigationUrl != null && hasText(String.valueOf(navigationUrl))) {
                    return String.valueOf(navigationUrl);
                }
            }
        }
        return "";
    }

    private List<Object> ragSources(Map<String, Object> metadata) {
        Object agent = metadata.get("agent");
        if (agent instanceof Map<?, ?> agentMap) {
            Object sources = agentMap.get("ragSources");
            if (sources instanceof List<?> list) {
                return new ArrayList<>(list);
            }
        }
        return List.of();
    }
}
