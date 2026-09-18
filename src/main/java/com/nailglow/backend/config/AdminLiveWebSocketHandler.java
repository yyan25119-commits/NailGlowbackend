package com.nailglow.backend.config;

import com.nailglow.backend.service.AdminRealtimeService;
import com.nailglow.backend.service.AuthService;
import com.nailglow.backend.service.RealtimeTrafficService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Arrays;

@Component
public class AdminLiveWebSocketHandler extends TextWebSocketHandler {
    private final AuthService authService;
    private final AdminRealtimeService realtimeService;
    private final RealtimeTrafficService trafficService;

    public AdminLiveWebSocketHandler(AuthService authService, AdminRealtimeService realtimeService,
                                     RealtimeTrafficService trafficService) {
        this.authService = authService;
        this.realtimeService = realtimeService;
        this.trafficService = trafficService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = tokenFrom(session.getUri());
        boolean allowed = authService.authenticateToken(token)
                .map(user -> "admin".equals(user.role()))
                .orElse(false);
        if (!allowed) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("请先登录管理端"));
            return;
        }
        realtimeService.add(session);
        // 在线连接数记入 Redis，供管理端实时流量统计读取
        trafficService.sessionOpened();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (realtimeService.remove(session)) {
            trafficService.sessionClosed();
        }
    }

    private String tokenFrom(URI uri) {
        if (uri == null || uri.getQuery() == null) {
            return "";
        }
        return Arrays.stream(uri.getQuery().split("&"))
                .map(part -> part.split("=", 2))
                .filter(pair -> pair.length == 2 && "token".equals(pair[0]))
                .map(pair -> pair[1])
                .findFirst()
                .orElse("");
    }
}
