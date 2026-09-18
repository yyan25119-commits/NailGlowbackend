package com.nailglow.backend.controller;

import com.nailglow.backend.ApiResponse;
import com.nailglow.backend.service.AdminRealtimeService;
import com.nailglow.backend.service.AuthService;
import com.nailglow.backend.service.AuthenticatedUser;
import com.nailglow.backend.service.DoubaoImageService;
import com.nailglow.backend.service.ScoreModelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/user")
public class UserController {
    private static final List<String> METRIC_LABELS = List.of("手型适配度", "肤色显白度", "风格匹配度", "场景实用性", "整体美观度");
    private static final Pattern SLOT_DATE_TIME_PATTERN = Pattern.compile("(\\d{4}-\\d{1,2}-\\d{1,2})\\s*(\\d{1,2}:\\d{2})");
    private static final Pattern SLOT_MONTH_DAY_TIME_PATTERN = Pattern.compile("(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}:\\d{2})");
    private static final Pattern SLOT_TIME_PATTERN = Pattern.compile("(\\d{1,2}:\\d{2})");
    private static final DateTimeFormatter SLOT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-M-d");
    private static final DateTimeFormatter SLOT_TIME_INPUT_FORMAT = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter SLOT_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SLOT_MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("M月d日");
    private static final DateTimeFormatter SLOT_ABSOLUTE_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final DateTimeFormatter SLOT_ABSOLUTE_WITH_YEAR_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    private static final DateTimeFormatter SLOT_ISO_SECONDS_FORMAT = DateTimeFormatter.ofPattern("yyyy-M-d['T'][' ']H:mm[:ss]");
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
    private final JdbcTemplate jdbc;
    private final DoubaoImageService doubao;
    private final ScoreModelService scoreModel;
    private final AuthService authService;
    private final AdminRealtimeService realtime;
    private final HttpClient remoteImageHttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public UserController(JdbcTemplate jdbc, DoubaoImageService doubao, ScoreModelService scoreModel, AuthService authService, AdminRealtimeService realtime) {
        this.jdbc = jdbc;
        this.doubao = doubao;
        this.scoreModel = scoreModel;
        this.authService = authService;
        this.realtime = realtime;
    }

    @GetMapping("/nail-styles")
    public Map<String, Object> getNailStyles() {
        List<Map<String, Object>> list = jdbc.query("""
                select * from nail_styles
                where status = '上架'
                order by try_count desc, avg_score desc
                """, (rs, rowNum) -> styleRow(rs));
        return ApiResponse.ok(Map.of("list", list, "total", list.size()));
    }

    @PostMapping("/try-on")
    public Map<String, Object> createTryOnTask(
            HttpServletRequest request,
            @RequestParam(value = "styleIds", required = false) String styleIds,
            @RequestParam(value = "handPhoto", required = false) MultipartFile handPhoto
    ) throws IOException {
        long userId = authService.require(request, "user").id();
        List<Long> ids = normalizeSelectedStyleIds(parseIds(styleIds));
        if (ids.isEmpty()) {
            return ApiResponse.fail("请至少选择一款美甲");
        }
        if (handPhoto == null || handPhoto.isEmpty()) {
            return ApiResponse.fail("请上传手部照片");
        }
        String contentType = handPhoto.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ApiResponse.fail("上传文件必须是图片");
        }
        if (handPhoto.getSize() > 10L * 1024 * 1024) {
            return ApiResponse.fail("图片大小不能超过 10MB");
        }

        List<Map<String, Object>> styles = findStyles(ids);
        if (styles.isEmpty()) {
            return ApiResponse.fail("所选美甲款式不存在或已下架");
        }
        byte[] photoBytes = handPhoto.getBytes();
        String dataUrl = "data:%s;base64,%s".formatted(contentType, Base64.getEncoder().encodeToString(photoBytes));
        Path tempImage = Files.createTempFile("nailglow-hand-", extensionOf(handPhoto.getOriginalFilename(), contentType));
        Path handReferenceImage = persistTemporaryTryOnInput(handPhoto.getOriginalFilename(), contentType, photoBytes);
        String handReferenceUrl = publicAbsoluteUrl(request, "/uploads/try-on-inputs/" + handReferenceImage.getFileName());
        List<Path> temporaryReferenceImages = new ArrayList<>();
        temporaryReferenceImages.add(handReferenceImage);

        List<Map<String, Object>> results = new ArrayList<>();
        try {
            Files.write(tempImage, photoBytes);
            for (Map<String, Object> style : styles) {
                String styleName = String.valueOf(style.get("name"));
                String desc = String.valueOf(style.get("desc"));
                PreparedReferenceImage styleReferenceImage = prepareStyleReferenceImage(
                        request,
                        String.valueOf(style.getOrDefault("imageUrl", ""))
                );
                if (styleReferenceImage.temporaryFile() != null) {
                    temporaryReferenceImages.add(styleReferenceImage.temporaryFile());
                }
                DoubaoImageService.GenerationResult generation = doubao.generate(
                        styleName,
                        desc,
                        handReferenceUrl,
                        dataUrl,
                        styleReferenceImage.publicUrl()
                );
                if (!generation.remote() || !StringUtils.hasText(generation.imageUrl())) {
                    return ApiResponse.fail(StringUtils.hasText(generation.message()) ? generation.message() : "AI 试穿生成失败，请稍后重试");
                }
                Map<String, Object> scoreResult = scoreModel.predict(tempImage, String.valueOf(style.getOrDefault("styleCode", "nail_01")));
                boolean scored = Boolean.TRUE.equals(scoreResult.get("scored"))
                        && scoreResult.get("score") instanceof Number;
                int score = scored ? (int) Math.round(((Number) scoreResult.get("score")).doubleValue()) : 0;
                String metrics = metricsJson(scoreResult, score, scored);
                String advice = scored
                        ? buildAdvice(styleName, score)
                        : String.valueOf(scoreResult.getOrDefault(
                                "message", "当前手图质量不足，本次仅展示AI试穿结果，不生成适配分数"));
                String resultImageUrl = generation.imageUrl();
                results.add(resultPayload(style, score, scored, resultImageUrl, advice, metrics, generation, scoreResult));
            }
        } finally {
            Files.deleteIfExists(tempImage);
            for (Path temporaryReferenceImage : temporaryReferenceImages) {
                Files.deleteIfExists(temporaryReferenceImage);
            }
        }

        if (results.isEmpty()) {
            return ApiResponse.fail("试穿生成失败，请稍后重试");
        }

        Map<String, Object> bestResult = results.stream()
                .max(Comparator
                        .comparing((Map<String, Object> item) -> Boolean.TRUE.equals(item.get("scored")))
                        .thenComparingInt(item -> item.get("score") instanceof Number number ? number.intValue() : 0))
                .orElse(results.get(0));
        Map<String, Object> bestStyle = bestResult.get("style") instanceof Map<?, ?> styleMap
                ? new LinkedHashMap<>((Map<String, Object>) styleMap)
                : styles.get(0);
        String styleName = String.valueOf(bestStyle.get("name"));
        int score = bestResult.get("score") instanceof Number number ? number.intValue() : 0;
        String taskId = "tryon_" + UUID.randomUUID();
        String names = String.join(",", styles.stream().map(style -> String.valueOf(style.get("name"))).toList());
        String metrics = String.valueOf(bestResult.getOrDefault("metrics", ""));
        String advice = String.valueOf(bestResult.getOrDefault("advice", ""));
        String resultImageUrl = String.valueOf(bestResult.getOrDefault("resultImageUrl", ""));
        String provider = String.valueOf(bestResult.getOrDefault("provider", "local"));
        String resultsJson = mapper.writeValueAsString(results);

        jdbc.update("""
                insert into try_on_tasks(id, user_id, style_ids, style_names, hand_photo_name, hand_photo_size, status, score,
                  result_image_url, advice, metrics_json, results_json, provider, completed_at)
                values (?, ?, ?, ?, ?, ?, 'done', ?, ?, ?, ?, ?, ?, current_timestamp)
                """, taskId, userId, joinIds(ids), names, handPhoto.getOriginalFilename(), handPhoto.getSize(),
                score, resultImageUrl, advice, metrics, resultsJson, provider);
        jdbc.update("update users set try_count = try_count + 1, favorite_style = ? where id = ?", styleName, userId);
        for (Long id : ids) {
            jdbc.update("update nail_styles set try_count = try_count + 1, updated_at = current_timestamp where id = ?", id);
        }
        realtime.broadcast("tasks.changed");

        return ApiResponse.ok(taskPayload(taskId, styles, bestResult, results));
    }

    @GetMapping("/try-on/{taskId}")
    public Map<String, Object> getTryOnResult(HttpServletRequest request, @PathVariable String taskId) {
        long userId = authService.require(request, "user").id();
        List<Map<String, Object>> rows = jdbc.query("""
                select * from try_on_tasks where id = ? and user_id = ?
                """, (rs, rowNum) -> taskRow(rs), taskId, userId);
        if (rows.isEmpty()) {
            return ApiResponse.fail("试穿任务不存在");
        }
        return ApiResponse.ok(rows.get(0));
    }

    @GetMapping("/history")
    public Map<String, Object> history(HttpServletRequest request) {
        long userId = authService.require(request, "user").id();
        List<Map<String, Object>> list = jdbc.query("""
                select *
                from try_on_tasks
                where user_id = ?
                order by created_at desc
                limit 20
                """, (rs, rowNum) -> taskRow(rs), userId);
        return ApiResponse.ok(Map.of("list", list, "total", list.size()));
    }

    @GetMapping("/customer-photos")
    public Map<String, Object> customerPhotos(HttpServletRequest request,
                                              @RequestParam(defaultValue = "7") int days,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize,
                                              @RequestParam(required = false) Integer limit) {
        long userId = authService.authenticate(request).map(AuthenticatedUser::id).orElse(0L);
        int safeDays = Math.max(1, Math.min(days, 30));
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(limit == null ? pageSize : limit, 80));
        int offset = (safePage - 1) * safePageSize;
        Integer total = jdbc.queryForObject("""
                select count(*)
                from customer_photos
                where status = 'approved'
                  and created_at >= date_sub(current_timestamp, interval ? day)
                """, Integer.class, safeDays);
        List<Map<String, Object>> list = jdbc.query("""
                select p.id, p.style_name, p.note, p.score, p.image_url, p.created_at,
                       coalesce(avg(r.rating), 0) as rating_average,
                       coalesce(avg(r.fit_score), 0) as fit_average,
                       coalesce(avg(r.color_score), 0) as color_average,
                       coalesce(avg(r.style_score), 0) as style_average,
                       coalesce(avg(r.scene_score), 0) as scene_average,
                       coalesce(avg(r.aesthetic_score), 0) as aesthetic_average,
                       coalesce(avg(r.perceived_length_score), 0) as perceived_length_average,
                       coalesce(avg(r.perceived_slenderness_score), 0) as perceived_slenderness_average,
                       coalesce(avg(r.perceived_palm_width_score), 0) as perceived_palm_width_average,
                       coalesce(avg(r.perceived_softness_score), 0) as perceived_softness_average,
                       count(r.id) as rating_count,
                       coalesce(max(case when r.user_id = ? then r.rating end), 0) as my_rating,
                       coalesce(max(case when r.user_id = ? then r.fit_score end), 0) as my_fit,
                       coalesce(max(case when r.user_id = ? then r.color_score end), 0) as my_color,
                       coalesce(max(case when r.user_id = ? then r.style_score end), 0) as my_style,
                       coalesce(max(case when r.user_id = ? then r.scene_score end), 0) as my_scene,
                       coalesce(max(case when r.user_id = ? then r.aesthetic_score end), 0) as my_aesthetic,
                       coalesce(max(case when r.user_id = ? then r.perceived_length_score end), 0) as my_perceived_length,
                       coalesce(max(case when r.user_id = ? then r.perceived_slenderness_score end), 0) as my_perceived_slenderness,
                       coalesce(max(case when r.user_id = ? then r.perceived_palm_width_score end), 0) as my_perceived_palm_width,
                       coalesce(max(case when r.user_id = ? then r.perceived_softness_score end), 0) as my_perceived_softness
                from customer_photos p
                left join customer_photo_ratings r on r.photo_id = p.id
                where p.status = 'approved'
                  and p.created_at >= date_sub(current_timestamp, interval ? day)
                group by p.id, p.style_name, p.note, p.score, p.image_url, p.created_at
                order by p.created_at desc, p.score desc
                limit ? offset ?
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("style", rs.getString("style_name"));
            row.put("note", publicPhotoNote(rs.getString("note")));
            row.put("score", rs.getInt("score"));
            row.put("imageUrl", rs.getString("image_url"));
            row.put("createdAt", String.valueOf(rs.getTimestamp("created_at").toLocalDateTime()));
            row.put("ratingAverage", rs.getDouble("rating_average"));
            row.put("ratingCount", rs.getInt("rating_count"));
            row.put("myRating", rs.getInt("my_rating"));
            row.put("dimensionAverages", Map.of(
                    "handFit", rs.getDouble("fit_average"),
                    "skinTone", rs.getDouble("color_average"),
                    "styleMatch", rs.getDouble("style_average"),
                    "scene", rs.getDouble("scene_average"),
                    "aesthetic", rs.getDouble("aesthetic_average")
            ));
            row.put("myRatingDetail", Map.of(
                    "handFit", rs.getInt("my_fit"),
                    "skinTone", rs.getInt("my_color"),
                    "styleMatch", rs.getInt("my_style"),
                    "scene", rs.getInt("my_scene"),
                    "aesthetic", rs.getInt("my_aesthetic")
            ));
            row.put("morphologyAverages", Map.of(
                    "perceivedLength", rs.getDouble("perceived_length_average"),
                    "perceivedSlenderness", rs.getDouble("perceived_slenderness_average"),
                    "perceivedPalmWidth", rs.getDouble("perceived_palm_width_average"),
                    "perceivedSoftness", rs.getDouble("perceived_softness_average")
            ));
            row.put("myMorphologyDetail", Map.of(
                    "perceivedLength", rs.getInt("my_perceived_length"),
                    "perceivedSlenderness", rs.getInt("my_perceived_slenderness"),
                    "perceivedPalmWidth", rs.getInt("my_perceived_palm_width"),
                    "perceivedSoftness", rs.getInt("my_perceived_softness")
            ));
            return row;
        }, userId, userId, userId, userId, userId, userId,
                userId, userId, userId, userId,
                safeDays, safePageSize, offset);
        return ApiResponse.ok(Map.of(
                "list", list,
                "total", total == null ? 0 : total,
                "page", safePage,
                "pageSize", safePageSize
        ));
    }

    @PostMapping("/customer-photos/{id}/rating")
    public Map<String, Object> rateCustomerPhoto(HttpServletRequest request,
                                                 @PathVariable long id,
                                                 @RequestBody Map<String, Object> payload) {
        long userId = authService.require(request, "user").id();
        Integer exists = jdbc.queryForObject("select count(*) from customer_photos where id = ? and status = 'approved'", Integer.class, id);
        if (exists == null || exists == 0) {
            return ApiResponse.fail("返图不存在或尚未通过审核");
        }
        Integer rated = jdbc.queryForObject(
                "select count(*) from customer_photo_ratings where photo_id = ? and user_id = ?",
                Integer.class,
                id,
                userId
        );
        if (rated != null && rated > 0) {
            return ApiResponse.fail("这个作品你已经评分，不能重复评分或修改");
        }
        int fit = dimensionScore(payload, "handFitScore", "fitScore");
        int color = dimensionScore(payload, "skinToneScore", "colorScore");
        int style = dimensionScore(payload, "styleMatchScore", "styleScore");
        int scene = dimensionScore(payload, "sceneScore", "sceneScore");
        int aesthetic = dimensionScore(payload, "aestheticScore", "aestheticScore");
        Integer perceivedLength = optionalDimensionScore(payload, "perceivedLengthScore", "handLengthScore");
        Integer perceivedSlenderness = optionalDimensionScore(payload, "perceivedSlendernessScore", "handSlendernessScore");
        Integer perceivedPalmWidth = optionalDimensionScore(payload, "perceivedPalmWidthScore", "palmWidthScore");
        Integer perceivedSoftness = optionalDimensionScore(payload, "perceivedSoftnessScore", "handSoftnessScore");
        int rating = clampInt(payload.getOrDefault("rating", Math.round((fit + color + style + scene + aesthetic) / 100.0f)), 1, 5);
        String comment = String.valueOf(payload.getOrDefault("comment", "")).trim();
        if (comment.length() > 200) {
            comment = comment.substring(0, 200);
        }
        jdbc.update("""
                insert into customer_photo_ratings(
                  photo_id, user_id, rating, fit_score, color_score, style_score,
                  scene_score, aesthetic_score, perceived_length_score,
                  perceived_slenderness_score, perceived_palm_width_score,
                  perceived_softness_score, comment
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, rating, fit, color, style, scene, aesthetic,
                perceivedLength, perceivedSlenderness, perceivedPalmWidth,
                perceivedSoftness, comment);
        Map<String, Object> aggregate = ratingAggregate(id, userId);
        realtime.broadcast("customer_photos.changed", Map.of("photoId", id, "rating", true));
        return ApiResponse.ok(aggregate);
    }

    @GetMapping({"/customer-photos/mine", "/return-photos/mine"})
    public Map<String, Object> myCustomerPhotos(HttpServletRequest request) {
        long userId = authService.require(request, "user").id();
        List<Map<String, Object>> list = jdbc.query("""
                select p.*, s.name as style_name_joined
                from customer_photos p
                left join nail_styles s on s.id = p.style_id
                where p.user_id = ?
                order by p.submitted_at desc, p.created_at desc
                limit 30
                """, (rs, rowNum) -> customerPhotoRow(rs), userId);
        return ApiResponse.ok(Map.of("list", list, "total", list.size()));
    }

    @PostMapping({"/customer-photos", "/return-photos"})
    public Map<String, Object> uploadCustomerPhoto(
            HttpServletRequest request,
            @RequestParam(value = "photo", required = false) MultipartFile photo,
            @RequestParam(value = "returnPhoto", required = false) MultipartFile returnPhoto,
            @RequestParam(value = "styleId", required = false) Long styleId,
            @RequestParam(value = "tryOnTaskId", required = false) String tryOnTaskId,
            @RequestParam(value = "note", required = false) String note
    ) throws IOException {
        long userId = authService.require(request, "user").id();
        MultipartFile upload = photo != null && !photo.isEmpty() ? photo : returnPhoto;
        if (upload == null || upload.isEmpty()) {
            return ApiResponse.fail("请上传返图照片");
        }
        String contentType = upload.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ApiResponse.fail("返图必须是图片文件");
        }
        if (upload.getSize() > 10L * 1024 * 1024) {
            return ApiResponse.fail("图片不能超过 10MB");
        }

        String styleName = styleName(styleId);
        Path dir = Path.of("uploads", "customer-photos").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String filename = UUID.randomUUID() + extensionOf(upload.getOriginalFilename(), contentType);
        Path target = dir.resolve(filename).normalize();
        Files.write(target, upload.getBytes());
        String imageUrl = "/uploads/customer-photos/" + filename;
        String publicNote = StringUtils.hasText(note) ? note.trim() : "顾客返图待审核";

        jdbc.update("""
                insert into customer_photos(user_id, style_id, try_on_task_id, style_name, note, score, image_url,
                  original_file_name, image_size, mime_type, status, submitted_at, created_at)
                values (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, 'pending', current_timestamp, current_timestamp)
                """, userId, styleId, tryOnTaskId, styleName, publicNote, imageUrl,
                upload.getOriginalFilename(), upload.getSize(), contentType);
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        realtime.broadcast("customer_photos.changed", Map.of("photoId", id));
        List<Map<String, Object>> rows = jdbc.query("""
                select p.*, s.name as style_name_joined
                from customer_photos p
                left join nail_styles s on s.id = p.style_id
                where p.id = ?
                limit 1
                """, (rs, rowNum) -> customerPhotoRow(rs), id);
        return ApiResponse.ok(rows.isEmpty() ? Map.of("id", id, "status", "pending", "imageUrl", imageUrl) : rows.get(0));
    }

    @PostMapping("/appointments")
    public Map<String, Object> createAppointment(HttpServletRequest request, @RequestBody Map<String, Object> payload) {
        AuthenticatedUser user = authService.require(request, "user");
        String service = String.valueOf(payload.getOrDefault("serviceName", "AI 试穿复刻"));
        String slot = String.valueOf(payload.getOrDefault("slotTime", "今天 18:00"));
        long styleId = Long.parseLong(String.valueOf(payload.getOrDefault("styleId", "1")));
        int amount = servicePrice(service);
        int duration = serviceDuration(service);
        LocalDateTime scheduledAt = parseSlotTime(slot);
        long appointmentId = latestActiveAppointmentId(user.id());
        boolean hadActiveAppointment = appointmentId > 0;
        int queueNo = nextQueueNo(scheduledAt, appointmentId);
        if (appointmentId > 0) {
            jdbc.update("""
                    update appointments
                    set style_id = ?, service_name = ?, slot_time = ?, scheduled_at = ?, store_name = 'NailGlow 市中心旗舰店',
                        status = '已确认', amount = ?, paid_status = '未支付', duration_minutes = ?, queue_no = ?
                    where id = ? and user_id = ?
                    """, styleId, service, slot, Timestamp.valueOf(scheduledAt), amount, duration, queueNo, appointmentId, user.id());
        } else {
            jdbc.update("""
                    insert into appointments(user_id, style_id, service_name, slot_time, scheduled_at, store_name, status, amount, paid_status, duration_minutes, queue_no)
                    values (?, ?, ?, ?, ?, 'NailGlow 市中心旗舰店', '已确认', ?, '未支付', ?, ?)
                    """, user.id(), styleId, service, slot, Timestamp.valueOf(scheduledAt), amount, duration, queueNo);
            appointmentId = jdbc.queryForObject("select last_insert_id()", Long.class);
        }
        cancelOtherActiveAppointments(user.id(), appointmentId);
        realtime.broadcast("appointments.changed");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", appointmentId);
        result.put("status", "已确认");
        result.put("slotTime", slot);
        result.put("slotTimeUser", slot);
        result.put("slotTimeAdmin", formatAbsoluteSlotLabel(scheduledAt));
        result.put("scheduledAt", String.valueOf(scheduledAt));
        result.put("slotKind", "fixed");
        result.put("serviceName", service);
        result.put("amount", amount);
        result.put("durationMinutes", duration);
        result.put("requestedAction", "create");
        result.put("effectiveAction", hadActiveAppointment ? "reschedule" : "create");
        result.put("replacedExistingAppointment", hadActiveAppointment);
        result.put("queueNo", queueNo);
        result.put("paidStatus", "未支付");
        return ApiResponse.ok(result);
    }

    @GetMapping("/appointments/current")
    public Map<String, Object> currentAppointment(HttpServletRequest request) {
        long userId = authService.require(request, "user").id();
        List<Map<String, Object>> list = jdbc.query("""
                select a.*, s.name as style_name
                from appointments a
                left join nail_styles s on s.id = a.style_id
                where a.user_id = ?
                  and a.status not in ('已取消', '已完成')
                order by coalesce(a.scheduled_at, a.created_at) desc, a.id desc
                limit 1
                """, (rs, rowNum) -> currentAppointmentRow(rs), userId);
        return ApiResponse.ok(list.isEmpty() ? Map.of() : list.get(0));
    }

    @GetMapping("/visit-advice")
    public Map<String, Object> visitAdvice() {
        Integer active = jdbc.queryForObject("select count(*) from try_on_tasks where created_at > date_sub(now(), interval 6 hour)", Integer.class);
        Integer waiting = jdbc.queryForObject("select count(*) from appointments where status in ('已确认','待到店') and date(coalesce(scheduled_at, created_at)) = curdate()", Integer.class);
        int people = active == null ? 0 : active;
        String level = people > 20 ? "繁忙" : people > 8 ? "适中" : "宽松";
        int queue = waiting == null ? 0 : waiting;
        return ApiResponse.ok(Map.of(
                "store", "NailGlow 市中心旗舰店",
                "busyLevel", level,
                "queueAhead", queue,
                "estimatedWaitMinutes", queue * 35,
                "recommendedSlot", queue >= 6 ? "明天 10:30" : people > 20 ? "明天 10:30" : "今天 18:00",
                "traffic", "地铁 2 号线市中心站 C 口步行 6 分钟",
                "duration", "预计 75-95 分钟"
        ));
    }

    public static int servicePrice(String service) {
        return switch (service) {
            case "款式微调" -> 198;
            case "卸甲护理" -> 88;
            case "作品确认" -> 128;
            default -> 268;
        };
    }

    public static int serviceDuration(String service) {
        return switch (service) {
            case "款式微调" -> 80;
            case "卸甲护理" -> 45;
            case "作品确认" -> 60;
            default -> 110;
        };
    }

    private long latestActiveAppointmentId(long userId) {
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

    private int nextQueueNo(LocalDateTime scheduledAt, long excludeAppointmentId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from appointments
                where status in ('已确认', '待到店')
                  and date(coalesce(scheduled_at, created_at)) = date(?)
                  and id <> ?
                """, Integer.class, Timestamp.valueOf(scheduledAt), excludeAppointmentId);
        return (count == null ? 0 : count) + 1;
    }

    private Map<String, Object> currentAppointmentRow(ResultSet rs) throws SQLException {
        Timestamp scheduledAtValue = rs.getTimestamp("scheduled_at");
        Timestamp createdAtValue = rs.getTimestamp("created_at");
        LocalDateTime scheduledAt = scheduledAtValue == null
                ? createdAtValue.toLocalDateTime()
                : scheduledAtValue.toLocalDateTime();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("styleId", rs.getLong("style_id"));
        row.put("styleName", safeString(rs, "style_name", "未选择款式"));
        row.put("serviceName", rs.getString("service_name"));
        row.put("slotTime", rs.getString("slot_time"));
        row.put("slotTimeUser", rs.getString("slot_time"));
        row.put("slotTimeAdmin", formatAbsoluteSlotLabel(scheduledAt));
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

    public static LocalDateTime parseSlotTime(String slot) {
        String value = slot == null || slot.isBlank() ? "今天 18:00" : slot.trim();
        Matcher explicitDateTime = SLOT_DATE_TIME_PATTERN.matcher(value);
        if (explicitDateTime.find()) {
            LocalDate explicitDate;
            LocalTime explicitTime;
            try {
                explicitDate = LocalDate.parse(explicitDateTime.group(1), SLOT_DATE_FORMAT);
                explicitTime = LocalTime.parse(explicitDateTime.group(2), SLOT_TIME_INPUT_FORMAT);
                return LocalDateTime.of(explicitDate, explicitTime);
            } catch (Exception ignored) {
            }
        }
        Matcher monthDayTime = SLOT_MONTH_DAY_TIME_PATTERN.matcher(value);
        if (monthDayTime.find()) {
            try {
                LocalDate explicitDate = LocalDate.of(LocalDate.now().getYear(), Integer.parseInt(monthDayTime.group(1)), Integer.parseInt(monthDayTime.group(2)));
                LocalTime explicitTime = LocalTime.parse(monthDayTime.group(3), SLOT_TIME_INPUT_FORMAT);
                return LocalDateTime.of(explicitDate, explicitTime);
            } catch (Exception ignored) {
            }
        }
        LocalDate date = LocalDate.now();
        if (value.contains("后天")) {
            date = date.plusDays(2);
        } else if (value.contains("明天")) {
            date = date.plusDays(1);
        }
        String timePart = value.replace("今天", "").replace("明天", "").replace("后天", "").trim();
        Matcher timeMatcher = SLOT_TIME_PATTERN.matcher(timePart);
        if (timeMatcher.find()) {
            timePart = timeMatcher.group(1);
        }
        LocalTime time;
        try {
            time = LocalTime.parse(timePart, SLOT_TIME_INPUT_FORMAT);
        } catch (Exception ignored) {
            time = LocalTime.of(18, 0);
        }
        return LocalDateTime.of(date, time);
    }

    public static LocalDateTime parseScheduledAtValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!StringUtils.hasText(value)) {
            return parseSlotTime("今天 18:00");
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(value, SLOT_ISO_SECONDS_FORMAT);
        } catch (Exception ignored) {
        }
        String normalized = value.replace("T", " ");
        Matcher explicitDateTime = SLOT_DATE_TIME_PATTERN.matcher(normalized);
        if (explicitDateTime.find()) {
            try {
                return LocalDateTime.of(
                        LocalDate.parse(explicitDateTime.group(1), SLOT_DATE_FORMAT),
                        LocalTime.parse(explicitDateTime.group(2), SLOT_TIME_INPUT_FORMAT)
                );
            } catch (Exception ignored) {
            }
        }
        return parseSlotTime(value);
    }

    public static String formatSlotLabel(LocalDateTime scheduledAt) {
        LocalDate targetDate = scheduledAt.toLocalDate();
        LocalDate today = LocalDate.now();
        String prefix;
        if (targetDate.equals(today)) {
            prefix = "今天";
        } else if (targetDate.equals(today.plusDays(1))) {
            prefix = "明天";
        } else if (targetDate.equals(today.plusDays(2))) {
            prefix = "后天";
        } else {
            prefix = targetDate.format(SLOT_MONTH_DAY_FORMAT);
        }
        return prefix + " " + scheduledAt.toLocalTime().format(SLOT_TIME_FORMAT);
    }

    public static String formatAbsoluteSlotLabel(LocalDateTime scheduledAt) {
        if (scheduledAt == null) {
            return "";
        }
        if (scheduledAt.getYear() == LocalDate.now().getYear()) {
            return scheduledAt.format(SLOT_ABSOLUTE_FORMAT);
        }
        return scheduledAt.format(SLOT_ABSOLUTE_WITH_YEAR_FORMAT);
    }

    private List<Map<String, Object>> findStyles(List<Long> ids) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long id : ids) {
            List<Map<String, Object>> rows = jdbc.query("select * from nail_styles where id = ? and status <> '下架'", (rs, rowNum) -> styleRow(rs), id);
            result.addAll(rows);
        }
        return result;
    }

    private List<Long> parseIds(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        String clean = raw.replace("[", "").replace("]", "").replace("\"", "");
        List<Long> ids = new ArrayList<>();
        for (String part : clean.split(",")) {
            String value = part.trim();
            if (!value.isBlank()) {
                try {
                    ids.add(Long.parseLong(value));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ids;
    }

    private List<Long> normalizeSelectedStyleIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return List.of(ids.get(0));
    }

    private String joinIds(List<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    private PreparedReferenceImage prepareStyleReferenceImage(HttpServletRequest request, String rawImageUrl) {
        if (!StringUtils.hasText(rawImageUrl)) {
            return new PreparedReferenceImage("", null);
        }
        String imageUrl = rawImageUrl.trim();
        if (imageUrl.startsWith("data:")) {
            return persistDataUrlReferenceImage(request, imageUrl);
        }
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            DownloadedImage downloaded = downloadRemoteImage(imageUrl);
            if (downloaded != null) {
                try {
                    Path temporaryFile = persistTemporaryTryOnInput("style-reference", downloaded.contentType(), downloaded.bytes());
                    return new PreparedReferenceImage(
                            publicAbsoluteUrl(request, "/uploads/try-on-inputs/" + temporaryFile.getFileName()),
                            temporaryFile
                    );
                } catch (IOException ignored) {
                }
            }
            return new PreparedReferenceImage(imageUrl, null);
        }
        String normalizedPath = imageUrl.startsWith("/") ? imageUrl : "/" + imageUrl;
        return new PreparedReferenceImage(publicAbsoluteUrl(request, normalizedPath), null);
    }

    private Path persistTemporaryTryOnInput(String originalFilename, String contentType, byte[] bytes) throws IOException {
        Path dir = Path.of("uploads", "try-on-inputs").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String filename = "hand-" + UUID.randomUUID() + extensionOf(originalFilename, contentType);
        Path target = dir.resolve(filename).normalize();
        Files.write(target, bytes);
        return target;
    }

    private PreparedReferenceImage persistDataUrlReferenceImage(HttpServletRequest request, String dataUrl) {
        try {
            int commaIndex = dataUrl.indexOf(',');
            if (commaIndex <= 5) {
                return new PreparedReferenceImage("", null);
            }
            String metadata = dataUrl.substring(5, commaIndex);
            String contentType = metadata.split(";")[0].trim().toLowerCase();
            if (!contentType.startsWith("image/")) {
                contentType = "image/png";
            }
            byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(commaIndex + 1));
            Path temporaryFile = persistTemporaryTryOnInput("style-reference", contentType, bytes);
            return new PreparedReferenceImage(
                    publicAbsoluteUrl(request, "/uploads/try-on-inputs/" + temporaryFile.getFileName()),
                    temporaryFile
            );
        } catch (Exception ignored) {
            return new PreparedReferenceImage("", null);
        }
    }

    private DownloadedImage downloadRemoteImage(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 NailGlow/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = remoteImageHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0 || bytes.length > 10L * 1024 * 1024) {
                return null;
            }
            String contentType = normalizeRemoteImageContentType(
                    response.headers().firstValue("Content-Type").orElse(""),
                    imageUrl
            );
            return new DownloadedImage(bytes, contentType);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeRemoteImageContentType(String headerContentType, String imageUrl) {
        String headerValue = firstNonBlank(headerContentType);
        if (StringUtils.hasText(headerValue)) {
            String normalized = headerValue.split(";")[0].trim().toLowerCase();
            if (normalized.startsWith("image/")) {
                return normalized;
            }
        }
        String lowerUrl = String.valueOf(imageUrl).toLowerCase();
        if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerUrl.endsWith(".webp")) {
            return "image/webp";
        }
        if (lowerUrl.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/png";
    }

    private String publicAbsoluteUrl(HttpServletRequest request, String path) {
        String scheme = firstNonBlank(request.getHeader("X-Forwarded-Proto"), request.getScheme(), "http");
        String host = firstNonBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"), request.getServerName());
        if (!StringUtils.hasText(host)) {
            int port = request.getServerPort();
            host = request.getServerName() + ((port > 0 && port != 80 && port != 443) ? ":" + port : "");
        }
        return scheme + "://" + host + path;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private record PreparedReferenceImage(String publicUrl, Path temporaryFile) {
    }

    private record DownloadedImage(byte[] bytes, String contentType) {
    }

    private String styleName(Long styleId) {
        if (styleId == null) {
            return "未选择款式";
        }
        List<String> rows = jdbc.query("select name from nail_styles where id = ? limit 1", (rs, rowNum) -> rs.getString("name"), styleId);
        return rows.isEmpty() ? "未选择款式" : rows.get(0);
    }

    private Map<String, Object> customerPhotoRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("styleId", rs.getLong("style_id"));
        String joined = safeString(rs, "style_name_joined", "");
        row.put("style", joined.isBlank() ? rs.getString("style_name") : joined);
        row.put("note", rs.getString("note"));
        row.put("score", rs.getInt("score"));
        row.put("imageUrl", rs.getString("image_url"));
        row.put("status", safeString(rs, "status", "pending"));
        row.put("rejectReason", safeString(rs, "reject_reason", ""));
        row.put("submittedAt", safeTimestamp(rs, "submitted_at"));
        row.put("reviewedAt", safeTimestamp(rs, "reviewed_at"));
        return row;
    }

    private Map<String, Object> ratingAggregate(long photoId, long userId) {
        List<Map<String, Object>> rows = jdbc.query("""
                select coalesce(avg(rating), 0) as rating_average,
                       coalesce(avg(fit_score), 0) as fit_average,
                       coalesce(avg(color_score), 0) as color_average,
                       coalesce(avg(style_score), 0) as style_average,
                       coalesce(avg(scene_score), 0) as scene_average,
                       coalesce(avg(aesthetic_score), 0) as aesthetic_average,
                       coalesce(avg(perceived_length_score), 0) as perceived_length_average,
                       coalesce(avg(perceived_slenderness_score), 0) as perceived_slenderness_average,
                       coalesce(avg(perceived_palm_width_score), 0) as perceived_palm_width_average,
                       coalesce(avg(perceived_softness_score), 0) as perceived_softness_average,
                       count(*) as rating_count,
                       coalesce(max(case when user_id = ? then rating end), 0) as my_rating,
                       coalesce(max(case when user_id = ? then fit_score end), 0) as my_fit,
                       coalesce(max(case when user_id = ? then color_score end), 0) as my_color,
                       coalesce(max(case when user_id = ? then style_score end), 0) as my_style,
                       coalesce(max(case when user_id = ? then scene_score end), 0) as my_scene,
                       coalesce(max(case when user_id = ? then aesthetic_score end), 0) as my_aesthetic,
                       coalesce(max(case when user_id = ? then perceived_length_score end), 0) as my_perceived_length,
                       coalesce(max(case when user_id = ? then perceived_slenderness_score end), 0) as my_perceived_slenderness,
                       coalesce(max(case when user_id = ? then perceived_palm_width_score end), 0) as my_perceived_palm_width,
                       coalesce(max(case when user_id = ? then perceived_softness_score end), 0) as my_perceived_softness
                from customer_photo_ratings
                where photo_id = ?
                """, (rs, rowNum) -> Map.of(
                "photoId", photoId,
                "ratingAverage", rs.getDouble("rating_average"),
                "ratingCount", rs.getInt("rating_count"),
                "myRating", rs.getInt("my_rating"),
                "dimensionAverages", Map.of(
                        "handFit", rs.getDouble("fit_average"),
                        "skinTone", rs.getDouble("color_average"),
                        "styleMatch", rs.getDouble("style_average"),
                        "scene", rs.getDouble("scene_average"),
                        "aesthetic", rs.getDouble("aesthetic_average")
                ),
                "myRatingDetail", Map.of(
                        "handFit", rs.getInt("my_fit"),
                        "skinTone", rs.getInt("my_color"),
                        "styleMatch", rs.getInt("my_style"),
                        "scene", rs.getInt("my_scene"),
                        "aesthetic", rs.getInt("my_aesthetic")
                ),
                "morphologyAverages", Map.of(
                        "perceivedLength", rs.getDouble("perceived_length_average"),
                        "perceivedSlenderness", rs.getDouble("perceived_slenderness_average"),
                        "perceivedPalmWidth", rs.getDouble("perceived_palm_width_average"),
                        "perceivedSoftness", rs.getDouble("perceived_softness_average")
                ),
                "myMorphologyDetail", Map.of(
                        "perceivedLength", rs.getInt("my_perceived_length"),
                        "perceivedSlenderness", rs.getInt("my_perceived_slenderness"),
                        "perceivedPalmWidth", rs.getInt("my_perceived_palm_width"),
                        "perceivedSoftness", rs.getInt("my_perceived_softness")
                )
        ), userId, userId, userId, userId, userId, userId,
                userId, userId, userId, userId, photoId);
        return rows.isEmpty() ? Map.of(
                "photoId", photoId,
                "ratingAverage", 0,
                "ratingCount", 0,
                "myRating", 0,
                "dimensionAverages", Map.of("handFit", 0, "skinTone", 0, "styleMatch", 0, "scene", 0, "aesthetic", 0),
                "myRatingDetail", Map.of("handFit", 0, "skinTone", 0, "styleMatch", 0, "scene", 0, "aesthetic", 0),
                "morphologyAverages", Map.of("perceivedLength", 0, "perceivedSlenderness", 0, "perceivedPalmWidth", 0, "perceivedSoftness", 0),
                "myMorphologyDetail", Map.of("perceivedLength", 0, "perceivedSlenderness", 0, "perceivedPalmWidth", 0, "perceivedSoftness", 0)
        ) : rows.get(0);
    }

    private String publicPhotoNote(String note) {
        String value = note == null ? "" : note.trim();
        if (value.isBlank() || value.contains("待审核") || value.contains("审核")) {
            return "顾客真实返图";
        }
        return value;
    }

    private int dimensionScore(Map<String, Object> payload, String primaryKey, String legacyKey) {
        Object value = payload.get(primaryKey);
        if (value == null) value = payload.get(legacyKey);
        if (value == null) value = payload.get("rating");
        int parsed = clampInt(value, 0, 100);
        return parsed <= 5 ? parsed * 20 : parsed;
    }

    private Integer optionalDimensionScore(Map<String, Object> payload, String primaryKey, String legacyKey) {
        Object value = payload.get(primaryKey);
        if (value == null) value = payload.get(legacyKey);
        if (value == null || String.valueOf(value).isBlank()) return null;
        int parsed = clampInt(value, 0, 100);
        return parsed <= 5 ? parsed * 20 : parsed;
    }

    private int clampInt(Object value, int min, int max) {
        int parsed;
        try {
            parsed = Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            parsed = min;
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private String safeTimestamp(ResultSet rs, String column) {
        try {
            Timestamp value = rs.getTimestamp(column);
            return value == null ? "" : String.valueOf(value.toLocalDateTime());
        } catch (SQLException ignored) {
            return "";
        }
    }

    private String metricsJson(Map<String, Object> scoreResult, int score, boolean scored) {
        Object metrics = scoreResult.get("metrics");
        if (metrics instanceof Map<?, ?> metricMap && !metricMap.isEmpty()) {
            try {
                return new tools.jackson.databind.ObjectMapper().writeValueAsString(normalizeMetricLabels(metrics));
            } catch (Exception ignored) {
            }
        }
        if (!scored) {
            return "{}";
        }
        return "{\"手型适配度\":" + Math.min(98, score + 2)
                + ",\"肤色显白度\":" + Math.max(70, score - 1)
                + ",\"风格匹配度\":" + score
                + ",\"场景实用性\":" + Math.max(70, score - 3)
                + ",\"整体美观度\":" + Math.min(99, score + 1) + "}";
    }

    private Object normalizeMetricLabels(Object metrics) {
        if (!(metrics instanceof Map<?, ?> source)) {
            return metrics;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String label = String.valueOf(entry.getKey());
            if (isGarbled(label) && index < METRIC_LABELS.size()) {
                label = METRIC_LABELS.get(index);
            }
            normalized.put(label, entry.getValue());
            index++;
        }
        return normalized;
    }

    private boolean isGarbled(String value) {
        return value == null
                || value.contains("�")
                || value.contains("锟")
                || value.contains("Ã")
                || value.contains("Â")
                || value.matches(".*\\?{2,}.*");
    }

    private String buildAdvice(String styleName, int score) {
        return "%s 的综合适配分为 %d 分。建议保留主色调，甲型以自然方圆或短杏仁为主，边缘留出 1-2mm 透气感。"
                .formatted(styleName, score);
    }

    private String extensionOf(String filename, String contentType) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf('.'));
        }
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    private Map<String, Object> resultPayload(Map<String, Object> style, int score, boolean scored,
                                              String resultImageUrl, String advice, String metrics,
                                              DoubaoImageService.GenerationResult generation,
                                              Map<String, Object> scoreResult) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("style", style);
        data.put("styleId", style.get("id"));
        data.put("styleName", style.get("name"));
        data.put("scored", scored);
        data.put("score", score);
        data.put("confidence", scoreResult.getOrDefault("confidence", 0.0));
        data.put("confidenceLevel", scoreResult.getOrDefault("confidenceLevel", "LOW"));
        data.put("confidenceFactors", scoreResult.getOrDefault("confidenceFactors", Map.of()));
        data.put("handProfile", scoreResult.getOrDefault("handProfile", Map.of()));
        data.put("scoreReasons", scoreResult.getOrDefault("reasons", List.of()));
        data.put("scoreModel", scoreResult.getOrDefault("source", scoreResult.getOrDefault("model", "unknown")));
        data.put("scoreModelSchemaVersion", scoreResult.getOrDefault("modelSchemaVersion", "legacy-v1"));
        data.put("resultImageUrl", resultImageUrl);
        data.put("advice", advice);
        data.put("metrics", metrics);
        data.put("provider", generation.remote() ? "lumio" : "local");
        data.put("prompt", generation.prompt());
        data.put("message", generation.message());
        return data;
    }

    private Map<String, Object> taskPayload(String taskId, List<Map<String, Object>> styles,
                                            Map<String, Object> bestResult, List<Map<String, Object>> results) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("status", "done");
        data.put("styles", styles);
        data.put("results", results);
        data.put("score", bestResult.get("score"));
        data.put("scored", bestResult.get("scored"));
        data.put("confidence", bestResult.get("confidence"));
        data.put("confidenceLevel", bestResult.get("confidenceLevel"));
        data.put("confidenceFactors", bestResult.get("confidenceFactors"));
        data.put("handProfile", bestResult.get("handProfile"));
        data.put("scoreReasons", bestResult.get("scoreReasons"));
        data.put("scoreModel", bestResult.get("scoreModel"));
        data.put("scoreModelSchemaVersion", bestResult.get("scoreModelSchemaVersion"));
        data.put("resultImageUrl", bestResult.get("resultImageUrl"));
        data.put("advice", bestResult.get("advice"));
        data.put("metrics", bestResult.get("metrics"));
        data.put("provider", bestResult.get("provider"));
        data.put("prompt", bestResult.get("prompt"));
        data.put("message", bestResult.get("message"));
        return data;
    }

    static Map<String, Object> styleRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("name", rs.getString("name"));
        row.put("tag", rs.getString("tag"));
        row.put("tags", List.of(rs.getString("tags").split(",")));
        row.put("desc", rs.getString("description"));
        row.put("imageUrl", rs.getString("image_url"));
        row.put("styleCode", safeString(rs, "style_code", "nail_01"));
        row.put("colors", List.of(rs.getString("colors").split(",")));
        row.put("status", rs.getString("status"));
        row.put("tryCount", rs.getInt("try_count"));
        row.put("averageScore", rs.getDouble("avg_score"));
        return row;
    }

    private static String safeString(ResultSet rs, String column, String fallback) {
        try {
            String value = rs.getString(column);
            return value == null || value.isBlank() ? fallback : value;
        } catch (SQLException ignored) {
            return fallback;
        }
    }

    static Map<String, Object> taskRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", rs.getString("id"));
        row.put("styleNames", rs.getString("style_names"));
        row.put("status", rs.getString("status"));
        row.put("score", rs.getInt("score"));
        row.put("resultImageUrl", rs.getString("result_image_url"));
        row.put("advice", rs.getString("advice"));
        row.put("metrics", rs.getString("metrics_json"));
        row.put("results", parseResultsJson(safeString(rs, "results_json", "")));
        row.put("provider", rs.getString("provider"));
        row.put("createdAt", String.valueOf(rs.getTimestamp("created_at").toLocalDateTime()));
        return row;
    }

    private static List<Object> parseResultsJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            Object parsed = new tools.jackson.databind.ObjectMapper().readValue(raw, List.class);
            if (parsed instanceof List<?> list) {
                return new ArrayList<>(list);
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }
}
