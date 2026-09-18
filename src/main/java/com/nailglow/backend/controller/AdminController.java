package com.nailglow.backend.controller;

import com.nailglow.backend.ApiResponse;
import com.nailglow.backend.service.AdminRealtimeService;
import com.nailglow.backend.service.AuthService;
import com.nailglow.backend.service.DailyReportService;
import com.nailglow.backend.service.ExternalTrendCollectorService;
import com.nailglow.backend.service.RagKnowledgeBaseService;
import com.nailglow.backend.service.SupportService;
import com.nailglow.backend.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final SupportService supportService;
    private final AdminRealtimeService realtime;
    private final ExternalTrendCollectorService externalTrendCollector;
    private final RagKnowledgeBaseService ragKnowledgeBaseService;
    private final SystemSettingService systemSettingService;
    private final DailyReportService dailyReportService;
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    @Value("${nailglow.python.bin:${PYTHON_BIN:python}}")
    private String pythonBin;

    @Value("${nailglow.score-model.use-mediapipe:true}")
    private boolean scoreModelUseMediapipe;

    @Value("${nailglow.score-model.training-timeout-seconds:180}")
    private long scoreModelTrainingTimeoutSeconds;

    @Value("${nailglow.score-model.activation-min-samples:20}")
    private int scoreModelActivationMinSamples;

    @Value("${nailglow.score-model.activation-min-groups:3}")
    private int scoreModelActivationMinGroups;

    @Value("${nailglow.score-model.activation-min-validation-score:60}")
    private double scoreModelActivationMinValidationScore;

    @Value("${nailglow.doubao.api-key:}")
    private String aiApiKey;

    @Value("${nailglow.customer-agent.ai-base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String customerAgentAiBaseUrl;

    @Value("${nailglow.route-agent.ai-base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String routeAgentAiBaseUrl;

    @Value("${nailglow.trend-agent.ai-base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String trendAgentAiBaseUrl;

    @Value("${nailglow.daily-report.ai-base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String dailyReportAiBaseUrl;

    public AdminController(JdbcTemplate jdbc,
                           SupportService supportService,
                           AdminRealtimeService realtime,
                           ExternalTrendCollectorService externalTrendCollector,
                           RagKnowledgeBaseService ragKnowledgeBaseService,
                           SystemSettingService systemSettingService,
                           DailyReportService dailyReportService) {
        this.jdbc = jdbc;
        this.supportService = supportService;
        this.realtime = realtime;
        this.externalTrendCollector = externalTrendCollector;
        this.ragKnowledgeBaseService = ragKnowledgeBaseService;
        this.systemSettingService = systemSettingService;
        this.dailyReportService = dailyReportService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Integer todayTasks = jdbc.queryForObject("select count(*) from try_on_tasks where date(created_at) = curdate()", Integer.class);
        Integer users = jdbc.queryForObject("select count(*) from users where role = 'user'", Integer.class);
        Integer photos = jdbc.queryForObject("select count(*) from try_on_tasks where hand_photo_name is not null", Integer.class);
        Integer done = jdbc.queryForObject("select count(*) from try_on_tasks where status = 'done'", Integer.class);
        Integer appointments = jdbc.queryForObject("select count(*) from appointments", Integer.class);
        Integer pendingAppointments = jdbc.queryForObject("select count(*) from appointments where status <> '已完成'", Integer.class);
        Map<String, Object> revenue = revenueSummary();
        List<Map<String, Object>> topRows = jdbc.queryForList("""
                select name, try_count, avg_score from nail_styles
                order by try_count desc limit 1
                """);
        Map<String, Object> top = topRows.isEmpty() ? Map.of("NAME", "暂无", "AVG_SCORE", 0) : topRows.get(0);
        List<Map<String, Object>> recent = jdbc.query("""
                select t.id, u.nickname, t.style_names, t.score, t.provider, t.created_at
                from try_on_tasks t left join users u on u.id = t.user_id
                order by t.created_at desc limit 8
                """, (rs, rowNum) -> Map.of(
                "taskId", rs.getString("id"),
                "user", rs.getString("nickname"),
                "style", rs.getString("style_names"),
                "score", rs.getInt("score"),
                "provider", rs.getString("provider"),
                "time", String.valueOf(rs.getTimestamp("created_at").toLocalDateTime())
        ));
        List<Map<String, Object>> topStyles = jdbc.query("""
                select * from nail_styles order by try_count desc, avg_score desc limit 5
                """, (rs, rowNum) -> UserController.styleRow(rs));
        List<Map<String, Object>> recentAppointments = jdbc.query("""
                select a.*, u.nickname, s.name as style_name
                from appointments a
                join (
                    select user_id, max(id) as latest_id
                    from appointments
                    group by user_id
                ) latest on latest.latest_id = a.id
                left join users u on u.id = a.user_id
                left join nail_styles s on s.id = a.style_id
                order by coalesce(a.scheduled_at, a.created_at) desc, a.id desc limit 6
                """, (rs, rowNum) -> appointmentRow(rs));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stats", List.of(
                stat("今日试戴任务", todayTasks == null ? 0 : todayTasks, "实时"),
                stat("已上传照片", photos == null ? 0 : photos, "实时"),
                stat("AI 评分完成", done == null ? 0 : done, "实时"),
                stat("预约记录", appointments == null ? 0 : appointments, (pendingAppointments == null ? 0 : pendingAppointments) + " 待处理"),
                stat("今日应收", "¥" + revenue.get("todayReceivable"), "预约金额"),
                stat("最热门款式", top.get("NAME"), "均分 " + top.get("AVG_SCORE"))
        ));
        data.put("topStyle", top);
        data.put("recent", recent);
        data.put("topStyles", topStyles);
        data.put("appointments", recentAppointments);
        data.put("revenue", revenue);
        data.put("revenueTrend", revenueTrend());
        data.put("serviceMix", serviceRevenueMix());
        data.put("appointmentStatus", appointmentStatusMix());
        data.put("conversion", Map.of(
                "tasks", done == null ? 0 : done,
                "appointments", appointments == null ? 0 : appointments,
                "rate", conversionRate(done, appointments)
        ));
        return ApiResponse.ok(data);
    }

    @GetMapping("/nail-styles")
    public Map<String, Object> styles(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status) {
        String sql = "select * from nail_styles where 1=1";
        Object[] args;
        if (StringUtils.hasText(keyword) && StringUtils.hasText(status)) {
            sql += " and (name like ? or tags like ?) and status = ? order by updated_at desc";
            args = new Object[]{"%" + keyword + "%", "%" + keyword + "%", status};
        } else if (StringUtils.hasText(keyword)) {
            sql += " and (name like ? or tags like ?) order by updated_at desc";
            args = new Object[]{"%" + keyword + "%", "%" + keyword + "%"};
        } else if (StringUtils.hasText(status)) {
            sql += " and status = ? order by updated_at desc";
            args = new Object[]{status};
        } else {
            sql += " order by updated_at desc";
            args = new Object[]{};
        }
        List<Map<String, Object>> list = jdbc.query(sql, (rs, rowNum) -> UserController.styleRow(rs), args);
        return ApiResponse.ok(Map.of("list", list, "total", list.size()));
    }

    @PostMapping("/nail-styles")
    public Map<String, Object> createStyle(@RequestBody Map<String, Object> body) {
        jdbc.update("""
                insert into nail_styles(name, tag, tags, description, image_url, colors, status, try_count, avg_score)
                values (?, ?, ?, ?, ?, ?, ?, 0, ?)
                """, value(body, "name", "新款美甲"), value(body, "tag", "新品 / 试运营"),
                normalizeList(value(body, "tags", "新品,试运营")), value(body, "desc", "后台新增款式，等待运营完善说明。"),
                value(body, "imageUrl", ""), normalizeList(value(body, "colors", "#f5d3dc,#fff8fb,#d58ca0")),
                value(body, "status", "审核中"), Double.parseDouble(value(body, "averageScore", "88")));
        return ApiResponse.ok(Map.of("created", true));
    }

    @PutMapping("/nail-styles/{id}")
    public Map<String, Object> updateStyle(@PathVariable long id, @RequestBody Map<String, Object> body) {
        jdbc.update("""
                update nail_styles
                set name = ?, tag = ?, tags = ?, description = ?, image_url = ?, colors = ?, status = ?, avg_score = ?, updated_at = current_timestamp
                where id = ?
                """, value(body, "name", "未命名款式"), value(body, "tag", "新品 / 试运营"),
                normalizeList(value(body, "tags", "新品,试运营")), value(body, "desc", "后台编辑款式。"),
                value(body, "imageUrl", ""), normalizeList(value(body, "colors", "#f5d3dc,#fff8fb,#d58ca0")),
                value(body, "status", "审核中"), Double.parseDouble(value(body, "averageScore", "88")), id);
        return ApiResponse.ok(Map.of("updated", true));
    }

    @PostMapping("/nail-styles/{id}/toggle")
    public Map<String, Object> toggleStyle(@PathVariable long id) {
        String status = jdbc.queryForObject("select status from nail_styles where id = ?", String.class, id);
        String next = "上架".equals(status) ? "下架" : "上架";
        jdbc.update("update nail_styles set status = ?, updated_at = current_timestamp where id = ?", next, id);
        return ApiResponse.ok(Map.of("status", next));
    }

    @DeleteMapping("/nail-styles/{id}")
    public Map<String, Object> deleteStyle(@PathVariable long id) {
        jdbc.update("delete from nail_styles where id = ?", id);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/traffic")
    public Map<String, Object> traffic() {
        Integer online = jdbc.queryForObject("select count(*) from users where last_login_at > date_sub(now(), interval 2 day) and role = 'user'", Integer.class);
        Integer generating = jdbc.queryForObject("select count(*) from try_on_tasks where created_at > date_sub(now(), interval 3 hour)", Integer.class);
        Integer total = jdbc.queryForObject("select count(*) from try_on_tasks", Integer.class);
        Integer totalTasks = jdbc.queryForObject("select count(*) from try_on_tasks", Integer.class);
        Integer doneTasks = jdbc.queryForObject("select count(*) from try_on_tasks where status = 'done'", Integer.class);
        List<Map<String, Object>> distribution = scoreDistribution(totalTasks == null ? 0 : totalTasks);
        List<Map<String, Object>> styles = jdbc.query("""
                select name, try_count, avg_score, colors from nail_styles
                order by try_count desc limit 8
                """, (rs, rowNum) -> Map.of(
                "name", rs.getString("name"),
                "tryCount", rs.getInt("try_count"),
                "score", rs.getDouble("avg_score"),
                "colors", List.of(rs.getString("colors").split(","))
        ));
        Map<String, Object> data = new LinkedHashMap<>(Map.of(
                "cards", List.of(
                        stat("当前在线用户", online == null ? 0 : online, "实时"),
                        stat("正在试穿人数", generating == null ? 0 : generating, "近 3 小时"),
                        stat("累计试戴任务", total == null ? 0 : total, "实时"),
                        stat("AI 生成成功率", successRate(doneTasks, totalTasks), "实时")
                ),
                "distribution", distribution,
                "styles", styles,
                "trend", taskTrend()
        ));
        data.put("externalMonitor", externalTrendCollector.latestSnapshot());
        return ApiResponse.ok(data);
    }

    @GetMapping("/style-monitor")
    public Map<String, Object> styleMonitor() {
        return ApiResponse.ok(externalTrendCollector.latestSnapshot());
    }

    @PostMapping("/style-monitor/refresh")
    public Map<String, Object> refreshStyleMonitor() {
        try {
            Map<String, Object> data = externalTrendCollector.refreshSnapshot();
            realtime.broadcast("trend_monitor.changed", Map.of("refreshedAt", data.getOrDefault("refreshedAt", "")));
            return ApiResponse.ok(data);
        } catch (Exception ex) {
            Map<String, Object> fallback = externalTrendCollector.snapshotWithWarning(ex.getMessage());
            realtime.broadcast("trend_monitor.changed", Map.of(
                    "refreshedAt", fallback.getOrDefault("refreshedAt", ""),
                    "degraded", true
            ));
            return ApiResponse.ok(fallback);
        }
    }

    @GetMapping("/style-monitor/session")
    public Map<String, Object> styleMonitorSessionStatus() {
        return ApiResponse.ok(externalTrendCollector.sessionStatus());
    }

    @PostMapping("/style-monitor/session/save")
    public Map<String, Object> saveStyleMonitorSession() {
        try {
            return ApiResponse.ok(externalTrendCollector.saveSessionState());
        } catch (Exception ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/style-monitor/session/import")
    public Map<String, Object> importStyleMonitorSession(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(externalTrendCollector.importSessionState(body == null ? Map.of() : body));
        } catch (Exception ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/style-monitor/session/open-login-browser")
    public Map<String, Object> openStyleMonitorLoginBrowser() {
        try {
            return ApiResponse.ok(externalTrendCollector.openLoginBrowser());
        } catch (Exception ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/style-monitor/{id}/publish")
    public Map<String, Object> publishStyleMonitor(@PathVariable long id) {
        Map<String, Object> data = externalTrendCollector.publishTrend(id);
        realtime.broadcast("trend_monitor.changed", Map.of("trendId", id, "publishedStyleId", data.getOrDefault("publishedStyleId", 0)));
        realtime.broadcast("styles.changed", Map.of("source", "external_trend_publish", "styleId", data.getOrDefault("publishedStyleId", 0)));
        return ApiResponse.ok(data);
    }

    @GetMapping("/rag/status")
    public Map<String, Object> ragStatus() {
        return ApiResponse.ok(ragKnowledgeBaseService.status());
    }

    @GetMapping("/rag/chunks")
    public Map<String, Object> ragChunks(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(ragKnowledgeBaseService.previewChunks(limit));
    }

    @PostMapping("/rag/reindex")
    public Map<String, Object> reindexRag() {
        Map<String, Object> result = ragKnowledgeBaseService.rebuild();
        if (Boolean.TRUE.equals(result.get("ok"))) {
            realtime.broadcast("rag.changed", Map.of("chunks", result.getOrDefault("chunks", 0)));
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/users")
    public Map<String, Object> users() {
        List<Map<String, Object>> list = jdbc.query("""
                select * from users
                where role = 'user'
                order by case status when '待审核' then 0 else 1 end, last_login_at desc
                """, (rs, rowNum) -> userRow(rs));
        return ApiResponse.ok(Map.of("list", list, "total", list.size()));
    }

    @PostMapping("/users/{id}/approve")
    public Map<String, Object> approveUser(@PathVariable long id) {
        int updated = jdbc.update("update users set status = '正常' where id = ? and role = 'user'", id);
        if (updated == 0) {
            return ApiResponse.fail("用户不存在");
        }
        realtime.broadcast("users.changed", Map.of("userId", id, "status", "正常"));
        return ApiResponse.ok(Map.of("status", "正常"));
    }

    @PostMapping("/users/{id}/toggle")
    public Map<String, Object> toggleUser(@PathVariable long id) {
        String status = jdbc.queryForObject("select status from users where id = ? and role = 'user'", String.class, id);
        String next = "正常".equals(status) ? "观察" : "正常";
        jdbc.update("update users set status = ? where id = ? and role = 'user'", next, id);
        realtime.broadcast("users.changed", Map.of("userId", id, "status", next));
        return ApiResponse.ok(Map.of("status", next));
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable long id) {
        Integer exists = jdbc.queryForObject("select count(*) from users where id = ? and role = 'user'", Integer.class, id);
        if (exists == null || exists == 0) {
            return ApiResponse.fail("用户不存在");
        }
        jdbc.update("delete from customer_photo_ratings where photo_id in (select id from customer_photos where user_id = ?)", id);
        jdbc.update("delete from customer_photo_ratings where user_id = ?", id);
        jdbc.update("delete from customer_photos where user_id = ?", id);
        jdbc.update("delete from support_messages where conversation_id in (select id from support_conversations where user_id = ?)", id);
        jdbc.update("delete from support_conversations where user_id = ?", id);
        jdbc.update("delete from auth_sessions where user_id = ?", id);
        jdbc.update("delete from appointments where user_id = ?", id);
        jdbc.update("delete from try_on_tasks where user_id = ?", id);
        jdbc.update("delete from users where id = ? and role = 'user'", id);
        realtime.broadcast("users.changed", Map.of("userId", id, "deleted", true));
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/tasks")
    public Map<String, Object> tasks() {
        List<Map<String, Object>> list = jdbc.query("""
                select * from try_on_tasks order by created_at desc limit 50
                """, (rs, rowNum) -> UserController.taskRow(rs));
        return ApiResponse.ok(Map.of("list", list, "total", list.size()));
    }

    @GetMapping("/appointments")
    public Map<String, Object> appointments() {
        List<Map<String, Object>> list = jdbc.query("""
                select a.*, u.nickname, s.name as style_name
                from appointments a
                join (
                    select user_id, max(id) as latest_id
                    from appointments
                    group by user_id
                ) latest on latest.latest_id = a.id
                left join users u on u.id = a.user_id
                left join nail_styles s on s.id = a.style_id
                order by coalesce(a.scheduled_at, a.created_at) desc, a.id desc limit 100
                """, (rs, rowNum) -> appointmentRow(rs));
        return ApiResponse.ok(Map.of("list", list, "total", list.size()));
    }

    @GetMapping("/activity-badges")
    public Map<String, Object> activityBadges() {
        Integer support = jdbc.queryForObject("""
                select count(*)
                from support_conversations
                where notify_merchant = true
                  and status in ('未处理', '处理中')
                """, Integer.class);
        Integer photos = jdbc.queryForObject("""
                select count(*)
                from customer_photos
                where status = 'pending'
                """, Integer.class);
        Integer appointments = jdbc.queryForObject("""
                select count(*)
                from appointments a
                join (
                    select user_id, max(id) as latest_id
                    from appointments
                    group by user_id
                ) latest on latest.latest_id = a.id
                where a.status in ('已确认', '待到店')
                """, Integer.class);
        Integer pendingUsers = jdbc.queryForObject("""
                select count(*)
                from users
                where role = 'user'
                  and status = '待审核'
                """, Integer.class);

        int supportCount = support == null ? 0 : support;
        int photoCount = photos == null ? 0 : photos;
        int appointmentCount = appointments == null ? 0 : appointments;
        int pendingUserCount = pendingUsers == null ? 0 : pendingUsers;
        int total = supportCount + photoCount + appointmentCount + pendingUserCount;
        return ApiResponse.ok(Map.of(
                "users", pendingUserCount,
                "support", supportCount,
                "photos", photoCount,
                "appointments", appointmentCount,
                "total", total,
                "hasActivity", total > 0
        ));
    }

    @GetMapping("/daily-report")
    public Map<String, Object> dailyReport(@RequestParam(defaultValue = "false") boolean force) {
        return ApiResponse.ok(dailyReportService.weeklyReport(force));
    }

    @PutMapping("/appointments/{id}/status")
    public Map<String, Object> updateAppointmentStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String status = value(body, "status", "已确认");
        String paidStatus = "已完成".equals(status) ? "已支付" : value(body, "paidStatus", "未支付");
        jdbc.update("update appointments set status = ?, paid_status = ? where id = ?", status, paidStatus, id);
        realtime.broadcast("appointments.changed", Map.of("appointmentId", id));
        return ApiResponse.ok(Map.of("id", id, "status", status, "paidStatus", paidStatus));
    }

    @PostMapping("/tasks/{taskId}/retry")
    public Map<String, Object> retryTask(@PathVariable String taskId) {
        jdbc.update("update try_on_tasks set status = 'processing', provider = 'retry', completed_at = null where id = ?", taskId);
        jdbc.update("update try_on_tasks set status = 'done', completed_at = current_timestamp where id = ?", taskId);
        realtime.broadcast("tasks.changed", Map.of("taskId", taskId));
        return ApiResponse.ok(Map.of("taskId", taskId, "status", "done"));
    }

    @GetMapping({"/support/conversations", "/support-conversations"})
    public Map<String, Object> supportConversations(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String category,
                                                    @RequestParam(required = false) String severity,
                                                    @RequestParam(defaultValue = "false") boolean importantOnly) {
        List<Map<String, Object>> list = supportService.listMerchantConversations(status, category, severity, importantOnly);
        return ApiResponse.ok(Map.of("list", list, "total", list.size()));
    }

    @GetMapping({"/support/conversations/{id}/messages", "/support-conversations/{id}/messages"})
    public Map<String, Object> supportMessages(@PathVariable long id) {
        List<Map<String, Object>> list = supportService.messages(id);
        return ApiResponse.ok(Map.of("list", list, "total", list.size()));
    }

    @PutMapping({"/support/conversations/{id}", "/support-conversations/{id}/status"})
    public Map<String, Object> updateSupportConversation(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        supportService.updateConversation(id, body == null ? Map.of("status", "已处理") : body);
        return ApiResponse.ok(Map.of("id", id, "updated", true));
    }

    @PostMapping("/support/conversations/{id}/takeover")
    public Map<String, Object> takeoverSupportConversation(@PathVariable long id) {
        supportService.takeoverConversation(id);
        return ApiResponse.ok(Map.of("id", id, "handoffStatus", "manual"));
    }

    @PostMapping("/support/conversations/{id}/messages")
    public Map<String, Object> sendSupportMessage(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        String content = value(body == null ? Map.of() : body, "content", "").trim();
        if (!StringUtils.hasText(content)) {
            return ApiResponse.fail("回复内容不能为空");
        }
        supportService.addMerchantReply(id, content);
        return ApiResponse.ok(Map.of("id", id, "sent", true));
    }

    @PostMapping("/support/conversations/{id}/summary")
    public Map<String, Object> regenerateSupportSummary(@PathVariable long id) {
        supportService.regenerateSummary(id);
        return ApiResponse.ok(Map.of("id", id, "updated", true));
    }

    @GetMapping("/customer-photos")
    public Map<String, Object> customerPhotos(@RequestParam(required = false) String status) {
        String sql = """
                select p.*, u.nickname, u.account, s.name as style_name_joined,
                       coalesce(avg(r.rating), 0) as rating_average,
                       coalesce(avg(r.fit_score), 0) as fit_average,
                       coalesce(avg(r.color_score), 0) as color_average,
                       coalesce(avg(r.style_score), 0) as style_average,
                       coalesce(avg(r.scene_score), 0) as scene_average,
                       coalesce(avg(r.aesthetic_score), 0) as aesthetic_average,
                       count(r.id) as rating_count
                from customer_photos p
                left join users u on u.id = p.user_id
                left join nail_styles s on s.id = p.style_id
                left join customer_photo_ratings r on r.photo_id = p.id
                where 1=1
                """;
        Object[] args = new Object[]{};
        if (StringUtils.hasText(status)) {
            sql += " and p.status = ? group by p.id, u.nickname, u.account, s.name order by p.submitted_at desc, p.created_at desc";
            args = new Object[]{status};
        } else {
            sql += " group by p.id, u.nickname, u.account, s.name order by p.submitted_at desc, p.created_at desc";
        }
        List<Map<String, Object>> list = jdbc.query(sql, (rs, rowNum) -> customerPhotoRow(rs), args);
        return ApiResponse.ok(Map.of("list", list, "total", list.size()));
    }

    @PutMapping("/customer-photos/{id}/approve")
    public Map<String, Object> approveCustomerPhoto(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        int score = intValue(payload.get("score"));
        jdbc.update("""
                update customer_photos
                set status = 'approved', reject_reason = null,
                    note = case when ? <> '' then ? else note end,
                    score = case when ? > 0 then ? else score end,
                    reviewed_at = current_timestamp
                where id = ?
                """, value(payload, "note", ""), value(payload, "note", ""), score, score, id);
        realtime.broadcast("customer_photos.changed", Map.of("photoId", id));
        return ApiResponse.ok(Map.of("id", id, "status", "approved", "item", findCustomerPhoto(id)));
    }

    @PutMapping("/customer-photos/{id}/reject")
    public Map<String, Object> rejectCustomerPhoto(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        String reason = value(body == null ? Map.of() : body, "rejectReason", "图片不适合公开展示");
        jdbc.update("update customer_photos set status = 'rejected', reject_reason = ?, reviewed_at = current_timestamp where id = ?", reason, id);
        realtime.broadcast("customer_photos.changed", Map.of("photoId", id));
        return ApiResponse.ok(Map.of("id", id, "status", "rejected", "rejectReason", reason, "item", findCustomerPhoto(id)));
    }

    @DeleteMapping("/customer-photos/{id}")
    public Map<String, Object> deleteCustomerPhoto(@PathVariable long id) {
        jdbc.update("delete from customer_photo_ratings where photo_id = ?", id);
        jdbc.update("delete from customer_photos where id = ?", id);
        realtime.broadcast("customer_photos.changed", Map.of("photoId", id));
        return ApiResponse.ok(Map.of("id", id, "deleted", true));
    }

    @PostMapping("/reset-data")
    public Map<String, Object> resetData() {
        Map<String, Object> deleted = new LinkedHashMap<>();
        deleted.put("customerPhotoRatings", jdbc.update("delete from customer_photo_ratings"));
        deleted.put("supportMessages", jdbc.update("delete from support_messages"));
        deleted.put("supportConversations", jdbc.update("delete from support_conversations"));
        deleted.put("customerPhotos", jdbc.update("delete from customer_photos"));
        deleted.put("appointments", jdbc.update("delete from appointments"));
        deleted.put("tryOnTasks", jdbc.update("delete from try_on_tasks"));
        deleted.put("userSessions", jdbc.update("""
                delete s from auth_sessions s
                left join users u on u.id = s.user_id
                where s.role = 'user' or u.role = 'user'
                """));
        deleted.put("users", jdbc.update("delete from users where role = 'user'"));
        jdbc.update("update nail_styles set try_count = 0");
        clearUploadDirectory(Path.of("uploads", "customer-photos"));

        resetAutoIncrement("support_messages", 1);
        resetAutoIncrement("support_conversations", 1);
        resetAutoIncrement("customer_photo_ratings", 1);
        resetAutoIncrement("customer_photos", 1);
        resetAutoIncrement("appointments", 1);
        resetAutoIncrement("users", 1_000_000);

        realtime.broadcast("admin.reset", deleted);
        realtime.broadcast("appointments.changed", deleted);
        realtime.broadcast("tasks.changed", deleted);
        realtime.broadcast("support.changed", deleted);
        realtime.broadcast("customer_photos.changed", deleted);
        return ApiResponse.ok(Map.of("deleted", deleted, "reset", true));
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        List<Map<String, Object>> list = jdbc.query("""
                select * from system_settings order by key_name
                """, (rs, rowNum) -> Map.of(
                "key", rs.getString("key_name"),
                "title", rs.getString("title"),
                "desc", rs.getString("description"),
                "value", rs.getString("value_text"),
                "updatedAt", String.valueOf(rs.getTimestamp("updated_at").toLocalDateTime())
        ));
        Set<String> aiKeys = new LinkedHashSet<>(List.of(
                "shared_ai_api_key",
                "doubao_api_key",
                "doubao_endpoint",
                "customer_agent_base_url",
                "customer_agent_api_key",
                "route_agent_base_url",
                "route_agent_api_key",
                "trend_agent_base_url",
                "trend_agent_api_key",
                "daily_report_base_url",
                "daily_report_api_key",
                "amap_web_service_key",
                "doubao_model",
                "doubao_size",
                "customer_agent_model",
                "route_agent_model",
                "trend_agent_model",
                "daily_report_model"
        ));
        List<String> aiOrder = List.of(
                "shared_ai_api_key",
                "doubao_api_key",
                "doubao_endpoint",
                "doubao_model",
                "doubao_size",
                "customer_agent_base_url",
                "customer_agent_api_key",
                "customer_agent_model",
                "route_agent_base_url",
                "route_agent_api_key",
                "route_agent_model",
                "trend_agent_base_url",
                "trend_agent_api_key",
                "trend_agent_model",
                "daily_report_base_url",
                "daily_report_api_key",
                "daily_report_model",
                "amap_web_service_key"
        );
        List<Map<String, Object>> aiConfigs = list.stream()
                .filter(item -> aiKeys.contains(String.valueOf(item.get("key"))))
                .map(item -> normalizeAiSetting(item))
                .sorted(Comparator.comparingInt(item -> {
                    int index = aiOrder.indexOf(String.valueOf(item.get("key")));
                    return index >= 0 ? index : Integer.MAX_VALUE;
                }))
                .toList();
        Set<String> hiddenGeneralKeys = Set.of("score_rule", "upload_limit", "store_capacity");
        List<Map<String, Object>> general = list.stream()
                .filter(item -> !aiKeys.contains(String.valueOf(item.get("key"))))
                .filter(item -> !hiddenGeneralKeys.contains(String.valueOf(item.get("key"))))
                .toList();
        return ApiResponse.ok(Map.of(
                "list", general,
                "aiConfigs", aiConfigs,
                "aiKeyStatus", systemSettingService.aiKeyStatus(aiApiKey),
                "dailyReportKeyStatus", systemSettingService.aiKeyStatus("daily_report_api_key", aiApiKey, "DAILY_REPORT_API_KEY"),
                "amapKeyStatus", Map.of(
                        "configured", StringUtils.hasText(systemSettingService.effectiveAmapWebServiceKey()),
                        "source", systemSettingService.amapKeySource(),
                        "masked", systemSettingService.masked(systemSettingService.effectiveAmapWebServiceKey())
                )
        ));
    }

    @PutMapping("/settings/{key}")
    public Map<String, Object> updateSetting(@PathVariable String key, @RequestBody Map<String, Object> body) {
        jdbc.update("update system_settings set value_text = ?, updated_at = current_timestamp where key_name = ?",
                value(body, "value", ""), key);
        return ApiResponse.ok(Map.of("updated", true));
    }

    @GetMapping("/score-models")
    public Map<String, Object> scoreModels() {
        ensureDefaultScoreModel();
        List<Map<String, Object>> list = jdbc.query("""
                select *
                from score_model_versions
                order by case status when 'active' then 0 when 'candidate' then 1 when 'backup' then 2 else 3 end,
                         coalesce(activated_at, created_at) desc, id desc
                """, (rs, rowNum) -> scoreModelRow(rs));
        return ApiResponse.ok(Map.of(
                "list", list,
                "active", list.stream().filter(item -> "active".equals(item.get("status"))).findFirst().orElse(Map.of())
        ));
    }

    private Map<String, Object> normalizeAiSetting(Map<String, Object> item) {
        String key = String.valueOf(item.get("key"));
        String value = String.valueOf(item.getOrDefault("value", ""));
        boolean secret = key.endsWith("_api_key") || "amap_web_service_key".equals(key);
        String effectiveValue = switch (key) {
            case "shared_ai_api_key" -> systemSettingService.masked(systemSettingService.effectiveSharedAiApiKey(aiApiKey));
            case "doubao_api_key" -> systemSettingService.masked(systemSettingService.effectiveAiApiKey("doubao_api_key", aiApiKey, "DOUBAO_IMAGE_API_KEY"));
            case "doubao_endpoint" -> systemSettingService.effectiveImageGenerationEndpoint(value);
            case "customer_agent_base_url" -> systemSettingService.effectiveAiBaseUrl("customer_agent_base_url", customerAgentAiBaseUrl);
            case "customer_agent_api_key" -> systemSettingService.masked(systemSettingService.effectiveAiApiKey("customer_agent_api_key", aiApiKey, "CUSTOMER_AGENT_API_KEY"));
            case "route_agent_base_url" -> systemSettingService.effectiveAiBaseUrl("route_agent_base_url", routeAgentAiBaseUrl);
            case "route_agent_api_key" -> systemSettingService.masked(systemSettingService.effectiveAiApiKey("route_agent_api_key", aiApiKey, "ROUTE_AGENT_API_KEY"));
            case "trend_agent_base_url" -> systemSettingService.effectiveAiBaseUrl("trend_agent_base_url", trendAgentAiBaseUrl);
            case "trend_agent_api_key" -> systemSettingService.masked(systemSettingService.effectiveAiApiKey("trend_agent_api_key", aiApiKey, "TREND_AGENT_API_KEY"));
            case "daily_report_base_url" -> systemSettingService.effectiveAiBaseUrl("daily_report_base_url", dailyReportAiBaseUrl);
            case "daily_report_api_key" -> systemSettingService.masked(systemSettingService.effectiveAiApiKey("daily_report_api_key", aiApiKey, "DAILY_REPORT_API_KEY"));
            case "amap_web_service_key" -> systemSettingService.masked(systemSettingService.effectiveAmapWebServiceKey());
            case "doubao_size" -> systemSettingService.effectiveImageGenerationSize(value);
            default -> value;
        };
        String source = switch (key) {
            case "shared_ai_api_key" -> systemSettingService.sharedAiApiKeySource(aiApiKey);
            case "doubao_api_key" -> systemSettingService.aiApiKeySource("doubao_api_key", aiApiKey, "DOUBAO_IMAGE_API_KEY");
            case "customer_agent_base_url" -> systemSettingService.aiBaseUrlSource("customer_agent_base_url", customerAgentAiBaseUrl);
            case "customer_agent_api_key" -> systemSettingService.aiApiKeySource("customer_agent_api_key", aiApiKey, "CUSTOMER_AGENT_API_KEY");
            case "route_agent_base_url" -> systemSettingService.aiBaseUrlSource("route_agent_base_url", routeAgentAiBaseUrl);
            case "route_agent_api_key" -> systemSettingService.aiApiKeySource("route_agent_api_key", aiApiKey, "ROUTE_AGENT_API_KEY");
            case "trend_agent_base_url" -> systemSettingService.aiBaseUrlSource("trend_agent_base_url", trendAgentAiBaseUrl);
            case "trend_agent_api_key" -> systemSettingService.aiApiKeySource("trend_agent_api_key", aiApiKey, "TREND_AGENT_API_KEY");
            case "daily_report_base_url" -> systemSettingService.aiBaseUrlSource("daily_report_base_url", dailyReportAiBaseUrl);
            case "daily_report_api_key" -> systemSettingService.aiApiKeySource("daily_report_api_key", aiApiKey, "DAILY_REPORT_API_KEY");
            case "amap_web_service_key" -> systemSettingService.amapKeySource();
            default -> StringUtils.hasText(value) ? "系统设置" : "默认值";
        };
        Map<String, Object> normalized = new LinkedHashMap<>(item);
        normalized.put("secret", secret);
        normalized.put("effectiveValue", effectiveValue);
        normalized.put("source", source);
        normalized.put("value", secret ? "" : value);
        return normalized;
    }

    @PostMapping("/score-models/retrain")
    public Map<String, Object> retrainScoreModel() throws IOException {
        ensureDefaultScoreModel();
        Path source = activeScoreModelPath();
        Files.createDirectories(modelsDir().resolve("candidates"));
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String versionName = "score_model_candidate_" + stamp + ".joblib";
        Path target = modelsDir().resolve("candidates").resolve(versionName).toAbsolutePath().normalize();
        ScoreModelTrainingResult training = trainCandidateModel(target, source);
        if (!Files.exists(target)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        int sampleCount = training.sampleCount() > 0
                ? training.sampleCount()
                : countBySql("select count(*) from customer_photo_ratings");
        int groupCount = training.groupCount();
        int responseCount = training.questionnaireResponseCount() > 0
                ? training.questionnaireResponseCount()
                : countBySql("select count(*) from customer_photo_ratings");
        double validationScore = training.validationScore();
        jdbc.update("""
                insert into score_model_versions(
                  version_name, file_path, status, sample_count, group_count,
                  questionnaire_response_count, validation_score, schema_version,
                  dataset_source, metrics_json, file_size, created_at
                ) values (?, ?, 'candidate', ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
                """, "候选模型 " + stamp, target.toString(), sampleCount, groupCount,
                responseCount, validationScore, training.schemaVersion(),
                training.trained() ? "real-feedback" : "fallback-copy",
                training.metricsJson(), fileSize(target));
        realtime.broadcast("score_models.changed", Map.of("created", versionName));
        return scoreModels();
    }

    @PostMapping("/score-models/{id}/activate")
    public Map<String, Object> activateScoreModel(@PathVariable long id) {
        Map<String, Object> model = findScoreModel(id);
        if (model.isEmpty()) {
            return ApiResponse.fail("模型不存在");
        }
        Path path = resolveModelPath(String.valueOf(model.get("filePath")));
        if (!Files.exists(path)) {
            return ApiResponse.fail("模型文件不存在");
        }
        if ("nailglow-score-v2".equals(model.get("schemaVersion"))) {
            int sampleCount = ((Number) model.getOrDefault("sampleCount", 0)).intValue();
            int groupCount = ((Number) model.getOrDefault("groupCount", 0)).intValue();
            double validationScore = ((Number) model.getOrDefault("validationScore", 0)).doubleValue();
            if (sampleCount < scoreModelActivationMinSamples
                    || groupCount < scoreModelActivationMinGroups
                    || validationScore < scoreModelActivationMinValidationScore) {
                return ApiResponse.fail(
                        "候选模型未达到激活门槛：至少 " + scoreModelActivationMinSamples
                                + " 个样本、" + scoreModelActivationMinGroups
                                + " 个独立手部组，且交叉验证分不低于 "
                                + scoreModelActivationMinValidationScore);
            }
        }
        jdbc.update("update score_model_versions set status = 'backup' where status = 'active'");
        jdbc.update("update score_model_versions set status = 'active', activated_at = current_timestamp where id = ?", id);
        realtime.broadcast("score_models.changed", Map.of("activeId", id));
        return scoreModels();
    }

    @PostMapping("/score-models/{id}/backup")
    public Map<String, Object> backupScoreModel(@PathVariable long id) throws IOException {
        Map<String, Object> model = findScoreModel(id);
        if (model.isEmpty()) {
            return ApiResponse.fail("模型不存在");
        }
        Path source = resolveModelPath(String.valueOf(model.get("filePath")));
        if (!Files.exists(source)) {
            return ApiResponse.fail("模型文件不存在");
        }
        Files.createDirectories(modelsDir().resolve("backups"));
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path target = modelsDir().resolve("backups").resolve(source.getFileName().toString().replace(".joblib", "_" + stamp + ".joblib")).toAbsolutePath().normalize();
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        jdbc.update("""
                insert into score_model_versions(
                  version_name, file_path, status, sample_count, group_count,
                  questionnaire_response_count, validation_score, schema_version,
                  dataset_source, metrics_json, file_size, created_at
                ) values (?, ?, 'backup', ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
                """, "备份 " + stamp, target.toString(), model.get("sampleCount"),
                model.getOrDefault("groupCount", 0),
                model.getOrDefault("questionnaireResponseCount", 0),
                model.get("validationScore"), model.getOrDefault("schemaVersion", "legacy-v1"),
                model.getOrDefault("datasetSource", "backup"),
                model.getOrDefault("metricsJson", "{}"), fileSize(target));
        realtime.broadcast("score_models.changed", Map.of("backupId", id));
        return scoreModels();
    }

    @DeleteMapping("/score-models/{id}")
    public Map<String, Object> deleteScoreModel(@PathVariable long id) throws IOException {
        Map<String, Object> model = findScoreModel(id);
        if (model.isEmpty()) {
            return ApiResponse.fail("模型不存在");
        }
        if ("active".equals(model.get("status"))) {
            return ApiResponse.fail("当前启用模型不能删除，请先切换到其他模型");
        }
        Path path = resolveModelPath(String.valueOf(model.get("filePath")));
        if (path.startsWith(modelsDir().toAbsolutePath().normalize())) {
            Files.deleteIfExists(path);
        }
        jdbc.update("delete from score_model_versions where id = ?", id);
        realtime.broadcast("score_models.changed", Map.of("deletedId", id));
        return scoreModels();
    }

    private ScoreModelTrainingResult trainCandidateModel(Path target, Path fallbackSource) {
        List<Map<String, Object>> samples = scoreTrainingSamples();
        if (samples.isEmpty()) {
            return ScoreModelTrainingResult.failed();
        }
        Path scriptPath = Path.of("src", "main", "python", "train_score_model.py").toAbsolutePath().normalize();
        if (!Files.exists(scriptPath)) {
            return ScoreModelTrainingResult.failed();
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(pythonBin, scriptPath.toString())
                    .directory(Path.of(".").toAbsolutePath().normalize().toFile());
            builder.environment().put("PYTHONUTF8", "1");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            Process process = builder.start();
            Map<String, Object> request = Map.of(
                    "samples", samples,
                    "outputPath", target.toString(),
                    "fallbackModelPath", fallbackSource.toString(),
                    "useMediapipe", scoreModelUseMediapipe,
                    "datasetMetadata", Map.of(
                            "synthetic", false,
                            "source", "customer_photo_ratings",
                            "labelContract", "five-dimension-v2"
                    )
            );
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(mapper.writeValueAsString(request));
            }
            boolean finished = process.waitFor(
                    Math.max(60, scoreModelTrainingTimeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ScoreModelTrainingResult.failed();
            }
            String stdout = readAll(process.getInputStream());
            if (process.exitValue() != 0 || stdout.isBlank()) {
                return ScoreModelTrainingResult.failed();
            }
            Map<String, Object> result = mapper.readValue(stdout, Map.class);
            return new ScoreModelTrainingResult(
                    true,
                    number(result.get("sampleCount")).intValue(),
                    number(result.get("groupCount")).intValue(),
                    number(result.get("questionnaireResponseCount")).intValue(),
                    number(result.get("validationScore")).doubleValue(),
                    String.valueOf(result.getOrDefault("schemaVersion", "unknown")),
                    mapper.writeValueAsString(result.getOrDefault("cvMetrics", Map.of()))
            );
        } catch (Exception ignored) {
            return ScoreModelTrainingResult.failed();
        }
    }

    private List<Map<String, Object>> scoreTrainingSamples() {
        return jdbc.query("""
                select p.id as photo_id, p.image_url,
                       coalesce(s.style_code, 'nail_01') as style_code,
                       count(r.id) as rating_count,
                       avg(coalesce(r.fit_score, r.rating * 20)) as hand_fit,
                       avg(coalesce(r.color_score, r.rating * 20)) as skin_tone,
                       avg(coalesce(r.style_score, r.rating * 20)) as style_match,
                       avg(coalesce(r.scene_score, r.rating * 20)) as scene_fit,
                       avg(coalesce(r.aesthetic_score, r.rating * 20)) as aesthetic,
                       avg(r.perceived_length_score) as perceived_length,
                       avg(r.perceived_slenderness_score) as perceived_slenderness,
                       avg(r.perceived_palm_width_score) as perceived_palm_width,
                       avg(r.perceived_softness_score) as perceived_softness,
                       avg((coalesce(r.fit_score, r.rating * 20)
                         + coalesce(r.color_score, r.rating * 20)
                         + coalesce(r.style_score, r.rating * 20)
                         + coalesce(r.scene_score, r.rating * 20)
                         + coalesce(r.aesthetic_score, r.rating * 20)) / 5) as overall_score
                from customer_photo_ratings r
                join customer_photos p on p.id = r.photo_id
                left join nail_styles s on s.id = p.style_id
                where p.image_url is not null and p.image_url <> ''
                group by p.id, p.image_url, s.style_code
                order by p.created_at desc
                limit 500
                """, (rs, rowNum) -> {
            String imageUrl = rs.getString("image_url");
            Path path = imageUrl.startsWith("/uploads/")
                    ? Path.of(imageUrl.substring(1)).toAbsolutePath().normalize()
                    : Path.of(imageUrl).toAbsolutePath().normalize();
            Map<String, Object> targets = new LinkedHashMap<>();
            targets.put("hand_fit", rs.getDouble("hand_fit"));
            targets.put("skin_tone", rs.getDouble("skin_tone"));
            targets.put("style_match", rs.getDouble("style_match"));
            targets.put("scene_fit", rs.getDouble("scene_fit"));
            targets.put("aesthetic", rs.getDouble("aesthetic"));
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("groupId", "photo_" + rs.getLong("photo_id"));
            sample.put("handId", "photo_" + rs.getLong("photo_id"));
            sample.put("imagePath", path.toString());
            sample.put("styleCode", rs.getString("style_code"));
            sample.put("targets", targets);
            Object perceivedLength = rs.getObject("perceived_length");
            Object perceivedSlenderness = rs.getObject("perceived_slenderness");
            Object perceivedPalmWidth = rs.getObject("perceived_palm_width");
            Object perceivedSoftness = rs.getObject("perceived_softness");
            if (perceivedLength != null && perceivedSlenderness != null
                    && perceivedPalmWidth != null && perceivedSoftness != null) {
                sample.put("morphologyTargets", Map.of(
                        "perceived_length", ((Number) perceivedLength).doubleValue(),
                        "perceived_slenderness", ((Number) perceivedSlenderness).doubleValue(),
                        "perceived_palm_width", ((Number) perceivedPalmWidth).doubleValue(),
                        "perceived_softness", ((Number) perceivedSoftness).doubleValue()
                ));
            }
            sample.put("overallScore", rs.getDouble("overall_score"));
            sample.put("ratingCount", rs.getInt("rating_count"));
            return sample;
        });
    }

    private Number number(Object value) {
        if (value instanceof Number number) return number;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private record ScoreModelTrainingResult(
            boolean trained,
            int sampleCount,
            int groupCount,
            int questionnaireResponseCount,
            double validationScore,
            String schemaVersion,
            String metricsJson
    ) {
        private static ScoreModelTrainingResult failed() {
            return new ScoreModelTrainingResult(
                    false, 0, 0, 0, 0.0, "legacy-v1", "{}"
            );
        }
    }

    private String readAll(InputStream stream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private Map<String, Object> scoreModelRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("versionName", rs.getString("version_name"));
        row.put("filePath", rs.getString("file_path"));
        row.put("status", rs.getString("status"));
        row.put("sampleCount", rs.getInt("sample_count"));
        row.put("groupCount", rs.getInt("group_count"));
        row.put("questionnaireResponseCount", rs.getInt("questionnaire_response_count"));
        row.put("validationScore", rs.getDouble("validation_score"));
        row.put("schemaVersion", rs.getString("schema_version"));
        row.put("datasetSource", rs.getString("dataset_source"));
        row.put("metricsJson", rs.getString("metrics_json"));
        try {
            String metricsJson = rs.getString("metrics_json");
            row.put("metrics", StringUtils.hasText(metricsJson)
                    ? mapper.readValue(metricsJson, Map.class)
                    : Map.of());
        } catch (Exception ignored) {
            row.put("metrics", Map.of());
        }
        row.put("fileSize", rs.getLong("file_size"));
        row.put("createdAt", safeTimestamp(rs, "created_at"));
        row.put("activatedAt", safeTimestamp(rs, "activated_at"));
        return row;
    }

    private void ensureDefaultScoreModel() {
        Integer count = jdbc.queryForObject("select count(*) from score_model_versions", Integer.class);
        if (count != null && count > 0) return;
        Path path = defaultScoreModelPath();
        jdbc.update("""
                insert into score_model_versions(
                  version_name, file_path, status, sample_count, group_count,
                  questionnaire_response_count, validation_score, schema_version,
                  dataset_source, metrics_json, file_size, created_at, activated_at
                ) values (
                  'score_model.joblib', ?, 'active', ?, 0, ?, ?, 'legacy-v1',
                  'bootstrap', '{}', ?, current_timestamp, current_timestamp
                )
                """, path.toString(), countBySql("select count(*) from customer_photo_ratings"),
                countBySql("select count(*) from customer_photo_ratings"),
                0.0, fileSize(path));
    }

    private Map<String, Object> findScoreModel(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select *
                from score_model_versions
                where id = ?
                limit 1
                """, (rs, rowNum) -> scoreModelRow(rs), id);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private Path activeScoreModelPath() {
        List<String> paths = jdbc.query("""
                select file_path
                from score_model_versions
                where status = 'active'
                order by coalesce(activated_at, created_at) desc, id desc
                limit 1
                """, (rs, rowNum) -> rs.getString("file_path"));
        if (!paths.isEmpty()) {
            Path active = resolveModelPath(paths.get(0));
            if (Files.exists(active)) return active;
        }
        return defaultScoreModelPath();
    }

    private Path defaultScoreModelPath() {
        Path modelsPath = modelsDir().resolve("score_model.joblib").toAbsolutePath().normalize();
        if (Files.exists(modelsPath)) return modelsPath;
        Path rootPath = Path.of("score_model.joblib").toAbsolutePath().normalize();
        return rootPath;
    }

    private Path modelsDir() {
        return Path.of("models").toAbsolutePath().normalize();
    }

    private Path resolveModelPath(String filePath) {
        Path path = Path.of(filePath == null || filePath.isBlank() ? "score_model.joblib" : filePath);
        if (!path.isAbsolute()) {
            path = Path.of(".").toAbsolutePath().normalize().resolve(path).normalize();
        }
        return path.toAbsolutePath().normalize();
    }

    private long fileSize(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private Map<String, Object> stat(String label, Object value, String growth) {
        return Map.of("label", label, "value", value, "growth", growth);
    }

    private Map<String, Object> revenueSummary() {
        BigDecimal receivable = money("select coalesce(sum(amount), 0) from appointments where status <> '已取消'");
        BigDecimal paid = money("select coalesce(sum(amount), 0) from appointments where status = '已完成' or paid_status = '已支付'");
        BigDecimal todayReceivable = money("""
                select coalesce(sum(amount), 0) from appointments
                where status <> '已取消'
                  and date(coalesce(scheduled_at, created_at)) = curdate()
                """);
        BigDecimal todayPaid = money("""
                select coalesce(sum(amount), 0) from appointments
                where (status = '已完成' or paid_status = '已支付')
                  and date(coalesce(scheduled_at, created_at)) = curdate()
                """);
        return Map.of(
                "receivable", receivable,
                "paid", paid,
                "pending", receivable.subtract(paid),
                "todayReceivable", todayReceivable,
                "todayPaid", todayPaid,
                "orderCount", countBySql("select count(*) from appointments where status <> '已取消'")
        );
    }

    private List<Map<String, Object>> revenueTrend() {
        return jdbc.query("""
                select date_format(days.d, '%m-%d') as day_label, coalesce(sum(a.amount), 0) as amount
                from (
                  select curdate() - interval 6 day as d union all
                  select curdate() - interval 5 day union all
                  select curdate() - interval 4 day union all
                  select curdate() - interval 3 day union all
                  select curdate() - interval 2 day union all
                  select curdate() - interval 1 day union all
                  select curdate()
                ) days
                left join appointments a
                  on date(coalesce(a.scheduled_at, a.created_at)) = days.d
                 and a.status <> '已取消'
                group by days.d
                order by days.d
                """, (rs, rowNum) -> Map.of(
                "label", rs.getString("day_label"),
                "value", rs.getBigDecimal("amount")
        ));
    }

    private List<Map<String, Object>> serviceRevenueMix() {
        BigDecimal total = money("select coalesce(sum(amount), 0) from appointments where status <> '已取消'");
        return jdbc.query("""
                select service_name, coalesce(sum(amount), 0) as amount, count(*) as count_value
                from appointments
                where status <> '已取消'
                group by service_name
                order by amount desc
                """, (rs, rowNum) -> {
            BigDecimal amount = rs.getBigDecimal("amount");
            int percent = total.compareTo(BigDecimal.ZERO) == 0 ? 0 : amount.multiply(BigDecimal.valueOf(100)).divide(total, 0, java.math.RoundingMode.HALF_UP).intValue();
            return Map.of(
                    "label", rs.getString("service_name"),
                    "value", amount,
                    "count", rs.getInt("count_value"),
                    "percent", percent
            );
        });
    }

    private List<Map<String, Object>> appointmentStatusMix() {
        return jdbc.query("""
                select status, count(*) as count_value
                from appointments
                group by status
                order by count_value desc
                """, (rs, rowNum) -> Map.of(
                "label", rs.getString("status"),
                "value", rs.getInt("count_value")
        ));
    }

    private BigDecimal money(String sql) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    private String successRate(Integer done, Integer total) {
        int totalValue = total == null ? 0 : total;
        if (totalValue == 0) return "0%";
        int doneValue = done == null ? 0 : done;
        return "%.1f%%".formatted(doneValue * 100.0 / totalValue);
    }

    private String conversionRate(Integer tasks, Integer appointments) {
        int taskValue = tasks == null ? 0 : tasks;
        if (taskValue == 0) return "0%";
        int appointmentValue = appointments == null ? 0 : appointments;
        return "%.1f%%".formatted(appointmentValue * 100.0 / taskValue);
    }

    private List<Map<String, Object>> scoreDistribution(int totalTasks) {
        int excellent = countBySql("select count(*) from try_on_tasks where score >= 90");
        int good = countBySql("select count(*) from try_on_tasks where score between 80 and 89");
        int normal = countBySql("select count(*) from try_on_tasks where score between 70 and 79");
        int low = countBySql("select count(*) from try_on_tasks where score < 70");
        return List.of(
                bucket("90 分以上", excellent, totalTasks),
                bucket("80-89 分", good, totalTasks),
                bucket("70-79 分", normal, totalTasks),
                bucket("70 分以下", low, totalTasks)
        );
    }

    private Map<String, Object> bucket(String label, int count, int total) {
        int percent = total == 0 ? 0 : (int) Math.round(count * 100.0 / total);
        return Map.of("label", label, "value", percent, "text", count + " 次");
    }

    private int countBySql(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private void resetAutoIncrement(String table, int nextValue) {
        jdbc.execute("alter table " + table + " auto_increment = " + nextValue);
    }

    private void clearUploadDirectory(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            return;
        }
        try (var stream = Files.walk(absolute)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(absolute))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    private List<Integer> taskTrend() {
        return jdbc.query("""
                select count(t.id) as count_value
                from (
                  select curdate() - interval 6 day as d union all
                  select curdate() - interval 5 day union all
                  select curdate() - interval 4 day union all
                  select curdate() - interval 3 day union all
                  select curdate() - interval 2 day union all
                  select curdate() - interval 1 day union all
                  select curdate()
                ) days
                left join try_on_tasks t on date(t.created_at) = days.d
                group by days.d
                order by days.d
                """, (rs, rowNum) -> rs.getInt("count_value"));
    }

    private Map<String, Object> appointmentRow(ResultSet rs) throws SQLException {
        java.sql.Timestamp scheduledAtValue = rs.getTimestamp("scheduled_at");
        java.sql.Timestamp createdAtValue = rs.getTimestamp("created_at");
        LocalDateTime scheduledAt = scheduledAtValue == null
                ? createdAtValue.toLocalDateTime()
                : scheduledAtValue.toLocalDateTime();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("user", safeString(rs, "nickname", "未知用户"));
        row.put("userId", rs.getLong("user_id"));
        row.put("styleId", rs.getLong("style_id"));
        row.put("styleName", safeString(rs, "style_name", "未选择款式"));
        row.put("serviceName", rs.getString("service_name"));
        row.put("slotTime", rs.getString("slot_time"));
        row.put("slotTimeUser", rs.getString("slot_time"));
        row.put("slotTimeAdmin", UserController.formatAbsoluteSlotLabel(scheduledAt));
        row.put("storeName", rs.getString("store_name"));
        row.put("status", rs.getString("status"));
        row.put("amount", rs.getBigDecimal("amount"));
        row.put("paidStatus", safeString(rs, "paid_status", "未支付"));
        row.put("durationMinutes", rs.getInt("duration_minutes"));
        row.put("queueNo", rs.getInt("queue_no"));
        row.put("scheduledAt", String.valueOf(scheduledAt));
        row.put("createdAt", String.valueOf(createdAtValue.toLocalDateTime()));
        return row;
    }

    private Map<String, Object> userRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("publicId", AuthService.publicIdFor(rs.getLong("id")));
        row.put("name", rs.getString("nickname"));
        row.put("account", rs.getString("account"));
        row.put("joined", String.valueOf(rs.getTimestamp("joined_at").toLocalDateTime()));
        row.put("last", String.valueOf(rs.getTimestamp("last_login_at").toLocalDateTime()));
        row.put("tries", rs.getInt("try_count"));
        row.put("favoriteStyle", rs.getString("favorite_style"));
        row.put("status", rs.getString("status"));
        return row;
    }

    private Map<String, Object> customerPhotoRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("userId", rs.getLong("user_id"));
        row.put("user", safeString(rs, "nickname", "未知用户"));
        row.put("account", safeString(rs, "account", ""));
        row.put("styleId", rs.getLong("style_id"));
        String joined = safeString(rs, "style_name_joined", "");
        row.put("style", joined.isBlank() ? rs.getString("style_name") : joined);
        row.put("note", rs.getString("note"));
        row.put("score", rs.getInt("score"));
        row.put("imageUrl", rs.getString("image_url"));
        row.put("status", safeString(rs, "status", "pending"));
        row.put("rejectReason", safeString(rs, "reject_reason", ""));
        row.put("fileName", safeString(rs, "original_file_name", ""));
        row.put("imageSize", rs.getLong("image_size"));
        row.put("mimeType", safeString(rs, "mime_type", ""));
        row.put("submittedAt", safeTimestamp(rs, "submitted_at"));
        row.put("reviewedAt", safeTimestamp(rs, "reviewed_at"));
        row.put("createdAt", String.valueOf(rs.getTimestamp("created_at").toLocalDateTime()));
        row.put("ratingAverage", safeDouble(rs, "rating_average", 0));
        row.put("ratingCount", safeInt(rs, "rating_count", 0));
        row.put("dimensionAverages", Map.of(
                "handFit", safeDouble(rs, "fit_average", 0),
                "skinTone", safeDouble(rs, "color_average", 0),
                "styleMatch", safeDouble(rs, "style_average", 0),
                "scene", safeDouble(rs, "scene_average", 0),
                "aesthetic", safeDouble(rs, "aesthetic_average", 0)
        ));
        return row;
    }

    private Map<String, Object> findCustomerPhoto(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select p.*, u.nickname, u.account, s.name as style_name_joined,
                       coalesce(avg(r.rating), 0) as rating_average,
                       coalesce(avg(r.fit_score), 0) as fit_average,
                       coalesce(avg(r.color_score), 0) as color_average,
                       coalesce(avg(r.style_score), 0) as style_average,
                       coalesce(avg(r.scene_score), 0) as scene_average,
                       coalesce(avg(r.aesthetic_score), 0) as aesthetic_average,
                       count(r.id) as rating_count
                from customer_photos p
                left join users u on u.id = p.user_id
                left join nail_styles s on s.id = p.style_id
                left join customer_photo_ratings r on r.photo_id = p.id
                where p.id = ?
                group by p.id, u.nickname, u.account, s.name
                limit 1
                """, (rs, rowNum) -> customerPhotoRow(rs), id);
        return rows.isEmpty() ? Map.of("id", id) : rows.get(0);
    }

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String value(Map<String, Object> body, String key, String fallback) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private String safeString(ResultSet rs, String column, String fallback) {
        try {
            String value = rs.getString(column);
            return value == null || value.isBlank() ? fallback : value;
        } catch (SQLException ignored) {
            return fallback;
        }
    }

    private int safeInt(ResultSet rs, String column, int fallback) {
        try {
            return rs.getInt(column);
        } catch (SQLException ignored) {
            return fallback;
        }
    }

    private double safeDouble(ResultSet rs, String column, double fallback) {
        try {
            return rs.getDouble(column);
        } catch (SQLException ignored) {
            return fallback;
        }
    }

    private String safeTimestamp(ResultSet rs, String column) {
        try {
            java.sql.Timestamp value = rs.getTimestamp(column);
            return value == null ? "" : String.valueOf(value.toLocalDateTime());
        } catch (SQLException ignored) {
            return "";
        }
    }

    private String normalizeList(String value) {
        return value.replace("，", ",").replace("、", ",");
    }
}
