package com.nailglow.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AdminRealtimeService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public void add(WebSocketSession session) {
        sessions.add(session);
        send(session, Map.of("type", "connected", "time", LocalDateTime.now().toString()));
    }

    /**
     * 移除会话。
     *
     * @return 该会话此前确实在集合中（用于避免重复递减在线计数）
     */
    public boolean remove(WebSocketSession session) {
        return sessions.remove(session);
    }

    public void broadcast(String type) {
        broadcast(type, Map.of());
    }

    public void broadcast(String type, Map<String, Object> payload) {
        Map<String, Object> message = Map.of(
                "type", type,
                "time", LocalDateTime.now().toString(),
                "payload", payload
        );
        for (WebSocketSession session : sessions) {
            send(session, message);
        }
    }

    private void send(WebSocketSession session, Map<String, Object> payload) {
        if (!session.isOpen()) {
            sessions.remove(session);
            return;
        }
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
        } catch (IOException error) {
            sessions.remove(session);
        }
    }
}
