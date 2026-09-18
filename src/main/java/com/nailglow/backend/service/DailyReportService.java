package com.nailglow.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DailyReportService {
    private static final int REPORT_DAYS = 7;
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    private static final Duration CACHE_TTL = Duration.ofMinutes(1);
    private static final String COMPLAINT_SCOPE = """
            last_message_at >= date_sub(now(), interval 7 day)
              and (category in ('投诉', '退款', '售后', '到店异常')
                or severity in ('urgent', 'high')
                or important = true)
            """;

    private static final List<DimensionRule> DIMENSIONS = List.of(
            new DimensionRule("handFit", "手型适配度", "fit_score", "补充短甲、宽甲、甲床偏短等手型适配图，给低适配款增加替代推荐。"),
            new DimensionRule("skinTone", "肤色显白度", "color_score", "对低显白款做冷暖皮分层标注，优先补充裸粉、冰透、奶白和车厘子红等显白版本。"),
            new DimensionRule("styleMatch", "风格匹配度", "style_score", "把款式标签拆到通勤、约会、夏日、拍照、婚礼等场景，减少用户选款预期偏差。"),
            new DimensionRule("scene", "场景实用性", "scene_score", "为夸张款增加日常替代款，预约前提示通勤、家务、拍摄等使用场景差异。"),
            new DimensionRule("aesthetic", "整体美观度", "aesthetic_score", "复核低分款的配色、饰品密度和成片光泽，优先重拍展示图或下调推荐位。")
    );

    private static final List<KeywordRule> KEYWORDS = List.of(
            new KeywordRule("显白", List.of("显白", "美白", "白皙")),
            new KeywordRule("夏日", List.of("夏日", "夏天", "清爽", "薄荷")),
            new KeywordRule("短甲", List.of("短甲", "短款", "中短甲")),
            new KeywordRule("法式", List.of("法式", "奶油白")),
            new KeywordRule("猫眼", List.of("猫眼", "极光")),
            new KeywordRule("裸粉", List.of("裸粉", "裸色", "蜜桃", "奶油粉")),
            new KeywordRule("冰透", List.of("冰透", "清透", "水光")),
            new KeywordRule("通勤", List.of("通勤", "日常", "极简")),
            new KeywordRule("车厘子红", List.of("车厘子", "酒红", "红美甲")),
            new KeywordRule("珍珠", List.of("珍珠", "白月光"))
    );

    private final JdbcTemplate jdbc;
    private final SystemSettingService systemSettingService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    @Value("${nailglow.doubao.api-key:}")
    private String apiKey;

    @Value("${nailglow.daily-report.ai-base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String aiBaseUrl;

    @Value("${nailglow.daily-report.ai-model:doubao-seed-2-0-pro-260215}")
    private String aiModel;

    @Value("${nailglow.daily-report.timeout-seconds:90}")
    private long timeoutSeconds;

    private Map<String, Object> cachedReport;
    private LocalDateTime cachedAt;
    private Map<String, Object> cachedAiOverlay;
    private String cachedAiModel;
    private String cachedAiRangeLabel;

    public DailyReportService(JdbcTemplate jdbc, SystemSettingService systemSettingService) {
        this.jdbc = jdbc;
        this.systemSettingService = systemSettingService;
    }

    public Map<String, Object> weeklyReport() {
        return weeklyReport(false);
    }

    public synchronized Map<String, Object> weeklyReport(boolean force) {
        if (!force && cachedReport != null && cachedAt != null
                && cachedAt.isAfter(LocalDateTime.now().minus(CACHE_TTL))) {
            return cachedReport;
        }

        Map<String, Object> complaint = complaintSection();
        Map<String, Object> rating = ratingSection();
        Map<String, Object> consumption = consumptionSection();
        List<String> recommendations = recommendations(complaint, rating, consumption);
        String recommendationSummary = recommendationSummary(complaint, rating, consumption, recommendations);

        Map<String, Object> data = new LinkedHashMap<>();
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(REPORT_DAYS - 1L);
        data.put("rangeLabel", start.format(DAY_LABEL) + " 至 " + end.format(DAY_LABEL));
        data.put("generatedAt", LocalDateTime.now().toString());
        data.put("cards", reportCards(complaint, rating, consumption));
        data.put("complaint", complaint);
        data.put("rating", rating);
        data.put("consumption", consumption);
        data.put("recommendations", recommendations);
        data.put("recommendationSummary", recommendationSummary);
        data.put("ai", Map.of("generated", false, "source", "rule_fallback", "message", "周报数据每 1 分钟刷新一次，AI 总结仅在手动生成时更新。"));

        if (force) {
            applyAiInsights(data);
        } else {
            applyCachedAiOverlay(data);
        }
        cachedReport = data;
        cachedAt = LocalDateTime.now();
        return data;
    }

    private void applyAiInsights(Map<String, Object> data) {
        String effectiveApiKey = systemSettingService.effectiveAiApiKey("daily_report_api_key", apiKey, "DAILY_REPORT_API_KEY");
        String effectiveBaseUrl = systemSettingService.effectiveAiBaseUrl("daily_report_base_url", aiBaseUrl);
        String effectiveModel = systemSettingService.getText("daily_report_model", aiModel);
        if (!StringUtils.hasText(effectiveApiKey)) {
            data.put("ai", Map.of("generated", false, "source", "rule_fallback", "model", effectiveModel, "message", "未配置运营日报 AI Key，已使用规则化日报。"));
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", effectiveModel);
            body.put("temperature", 0.25);
            body.put("max_tokens", 1600);
            body.put("thinking", Map.of("type", "disabled"));
            body.put("messages", List.of(
                    Map.of("role", "system", "content", dailyReportSystemPrompt()),
                    Map.of("role", "user", "content", mapper.writeValueAsString(dailyReportAiContext(data)))
            ));

            HttpRequest request = HttpRequest.newBuilder(URI.create(chatEndpoint(effectiveBaseUrl)))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                data.put("ai", Map.of("generated", false, "source", "rule_fallback", "model", effectiveModel, "message", "AI 接口返回 " + response.statusCode() + "，已使用规则化日报。"));
                return;
            }

            JsonNode root = mapper.readTree(response.body());
            String content = root.path("choices").isArray() && !root.path("choices").isEmpty()
                    ? root.path("choices").get(0).path("message").path("content").asText("")
                    : "";
            Map<String, Object> aiReport = mapper.readValue(extractJsonObject(content), Map.class);
            if (aiReport.isEmpty()) {
                data.put("ai", Map.of("generated", false, "source", "rule_fallback", "model", effectiveModel, "message", "AI 未返回有效 JSON，已使用规则化日报。"));
                return;
            }
            mergeAiReport(data, aiReport, effectiveModel);
            cachedAiOverlay = mapper.convertValue(aiReport, Map.class);
            cachedAiModel = effectiveModel;
            cachedAiRangeLabel = String.valueOf(data.getOrDefault("rangeLabel", ""));
        } catch (Exception ex) {
            data.put("ai", Map.of("generated", false, "source", "rule_fallback", "model", effectiveModel, "message", "AI 日报生成失败：" + ex.getMessage()));
        }
    }

    private void applyCachedAiOverlay(Map<String, Object> data) {
        if (cachedAiOverlay == null || cachedAiOverlay.isEmpty()) {
            return;
        }
        String currentRangeLabel = String.valueOf(data.getOrDefault("rangeLabel", ""));
        if (!StringUtils.hasText(currentRangeLabel) || !currentRangeLabel.equals(cachedAiRangeLabel)) {
            return;
        }
        mergeAiReport(data, cachedAiOverlay, firstNonBlank(cachedAiModel, aiModel));
        data.put("ai", Map.of(
                "generated", true,
                "source", "cached_ai_overlay",
                "model", firstNonBlank(cachedAiModel, aiModel),
                "message", "周报数据已刷新，AI 总结沿用上次手动生成结果。"
        ));
    }

    private String dailyReportSystemPrompt() {
        return """
                你是 NailGlow 美甲门店的资深运营顾问。你的任务不是复述数量，而是根据真实客服客诉、返图五维评分、低分款式、预约消费和选款偏好，写出可以直接给店长执行的 7 日运营日报。

                要求：
                - 只依据输入数据，不编造不存在的客诉人数、订单数、款式名或评分。
                - 如果样本很少，要明确说“样本不足”，但仍给出最稳妥的运营动作。
                - 如果近 7 日没有有效客诉，客诉总结必须直接返回“暂未产生有效客诉”，不要展开成多条近义句，不要重复“暂无”“未产生”等同义表达。
                - 客诉总结必须归因到具体原因，例如材料/消毒、打磨手法、封层固化、服务等待、色差不显白、退款补偿口径、人工介入闭环。
                - 低分款式要指出用户为什么不满意，例如不显白、风格不匹配、手型适配差、日常场景不实用、质感/持久不足。
                - 建议必须是门店能执行的动作，不要写空话。
                - 尽量输出整体复盘语气，不要同一句意思反复改写。
                - 输出必须是合法 JSON，不要 Markdown，不要代码块。

                JSON 结构：
                {
                  "summary": {
                    "complaint": "一段 60-120 字客诉情况总结",
                    "rating": "一段 60-120 字返图评分总结",
                    "consumption": "一段 60-120 字消费选款总结"
                  },
                  "complaint": {
                    "insight": "一段 60-120 字总体客诉归因；如果没有有效客诉就留空字符串",
                    "actions": ["3-6 条客诉处理和预防动作"]
                  },
                  "rating": {
                    "actions": ["2-4 条返图评分和低分款式优化动作"]
                  },
                  "consumption": {
                    "portrait": "一段 60-120 字用户画像总结，说明本周顾客更偏好什么类型的美甲",
                    "actions": ["2-4 条选款、陈列、客服推荐或活动动作"]
                  },
                  "recommendations": ["3-5 条不重复的运营建议"],
                  "recommendationSummary": "一段 80-140 字的本周总体优化建议，不分条"
                }
                """;
    }

    private Map<String, Object> dailyReportAiContext(Map<String, Object> data) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("rangeLabel", data.get("rangeLabel"));
        context.put("generatedAt", data.get("generatedAt"));
        context.put("complaintMetrics", data.get("complaint"));
        context.put("ratingMetrics", data.get("rating"));
        context.put("consumptionMetrics", data.get("consumption"));
        context.put("complaintSamples", complaintSamples());
        context.put("ratingCommentSamples", ratingCommentSamples());
        return context;
    }

    private void mergeAiReport(Map<String, Object> data, Map<String, Object> aiReport, String model) {
        Map<String, Object> complaint = mapValue(data.get("complaint"));
        Map<String, Object> rating = mapValue(data.get("rating"));
        Map<String, Object> consumption = mapValue(data.get("consumption"));
        Map<String, Object> summary = mapValue(aiReport.get("summary"));
        if (boolValue(complaint.get("hasValidComplaint"))) {
            replaceIfText(complaint, "summary", summary.get("complaint"));
        } else {
            complaint.put("summary", "暂未产生有效客诉");
        }
        replaceIfText(rating, "summary", summary.get("rating"));
        replaceIfText(consumption, "summary", summary.get("consumption"));

        Map<String, Object> complaintAi = mapValue(aiReport.get("complaint"));
        if (boolValue(complaint.get("hasValidComplaint"))) {
            String complaintInsight = firstNonBlank(
                    stringValue(complaintAi.get("insight")),
                    joinSentences(stringList(complaintAi.get("rootCauses"), 4))
            );
            if (StringUtils.hasText(complaintInsight)) {
                complaint.put("insight", complaintInsight);
            }
        } else {
            complaint.put("insight", "");
        }
        List<String> complaintActions = uniqueStrings(stringList(complaintAi.get("actions"), 6), 4);
        if (boolValue(complaint.get("hasValidComplaint")) && !complaintActions.isEmpty()) {
            complaint.put("actions", complaintActions);
        }

        Map<String, Object> ratingAi = mapValue(aiReport.get("rating"));
        List<String> ratingActions = uniqueStrings(stringList(ratingAi.get("actions"), 4), 3);
        if (!ratingActions.isEmpty()) {
            rating.put("actions", ratingActions);
        }

        Map<String, Object> consumptionAi = mapValue(aiReport.get("consumption"));
        replaceIfText(consumption, "profileSummary", consumptionAi.get("portrait"));
        List<String> consumptionActions = uniqueStrings(stringList(consumptionAi.get("actions"), 4), 3);
        if (!consumptionActions.isEmpty()) {
            consumption.put("actions", consumptionActions);
        }

        List<String> aiRecommendations = uniqueStrings(stringList(aiReport.get("recommendations"), 5), 4);
        if (!aiRecommendations.isEmpty()) {
            data.put("recommendations", aiRecommendations);
        }
        replaceIfText(data, "recommendationSummary", aiReport.get("recommendationSummary"));
        data.put("complaint", complaint);
        data.put("rating", rating);
        data.put("consumption", consumption);
        data.put("ai", Map.of("generated", true, "source", "volcengine_chat", "model", model, "message", "AI 已基于近 7 日真实数据生成运营日报。"));
    }

    private String chatEndpoint(String configuredBaseUrl) {
        String base = StringUtils.hasText(configuredBaseUrl) ? configuredBaseUrl.trim() : "https://ark.cn-beijing.volces.com/api/v3";
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base.replaceAll("/+$", "") + "/chat/completions";
    }

    private String extractJsonObject(String content) {
        String text = String.valueOf(content == null ? "" : content).trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }

    private void replaceIfText(Map<String, Object> target, String key, Object value) {
        if (StringUtils.hasText(String.valueOf(value == null ? "" : value))) {
            target.put(key, String.valueOf(value).trim());
        }
    }

    private List<String> stringList(Object value, int limit) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .map(item -> String.valueOf(item == null ? "" : item).trim())
                .filter(StringUtils::hasText)
                .limit(limit)
                .toList();
    }

    private List<Map<String, Object>> reportCards(Map<String, Object> complaint,
                                                  Map<String, Object> rating,
                                                  Map<String, Object> consumption) {
        return List.of(
                card("7日客诉", intValue(complaint.get("total")) + " 条", "未解决 " + intValue(complaint.get("unresolved"))),
                card("过敏反馈", intValue(complaint.get("allergyCount")) + " 位", intValue(complaint.get("allergyCount")) > 0 ? "需质检复盘" : "暂未集中出现"),
                card("返图评分", oneDecimal(doubleValue(rating.get("avgScore"))) + " 分", intValue(rating.get("ratingCount")) + " 条评分"),
                card("7日消费", "¥" + moneyText(consumption.get("revenue")), intValue(consumption.get("orderCount")) + " 单")
        );
    }

    private Map<String, Object> complaintSection() {
        int total = count("""
                select count(*)
                from support_conversations
                where %s
                """.formatted(COMPLAINT_SCOPE));
        int unresolved = count("""
                select count(*)
                from support_conversations
                where %s
                  and status in ('未处理', '处理中')
                """.formatted(COMPLAINT_SCOPE));
        int allergyCount = count("""
                select count(*)
                from support_conversations
                where %s
                  and concat_ws(' ', coalesce(category, ''), coalesce(summary, ''), coalesce(latest_message, ''), coalesce(important_items_json, ''))
                      regexp '过敏|红肿|刺痛|发炎|起疹|不适'
                """.formatted(COMPLAINT_SCOPE));
        int urgentCount = count("""
                select count(*)
                from support_conversations
                where %s
                  and severity = 'urgent'
                """.formatted(COMPLAINT_SCOPE));
        int highCount = count("""
                select count(*)
                from support_conversations
                where %s
                  and severity = 'high'
                """.formatted(COMPLAINT_SCOPE));
        int qualityCount = complaintKeywordCount("脱落|掉色|掉钻|起翘|开裂|不牢|持久|封层|太厚|厚重|甲面|气泡|不平|粗糙");
        int serviceCount = complaintKeywordCount("排队|等待|等太久|预约|迟到|超时|爽约|接待|服务态度|态度|沟通|客服|没人理|不回复");
        int colorMismatchCount = complaintKeywordCount("不显白|显黑|色差|偏黄|老气|不适合|不搭|太亮|太暗|饱和");
        int painCount = complaintKeywordCount("疼|痛|刺痛|破皮|出血|磨伤|酸|灼热|发炎");
        int refundCount = complaintKeywordCount("退款|退钱|赔偿|补偿|重做|返修|差评|投诉");
        int handoffCount = count("""
                select count(*)
                from support_conversations
                where %s
                  and (handoff_requested = true or handoff_status in ('requested', 'manual'))
                """.formatted(COMPLAINT_SCOPE));
        List<Map<String, Object>> categoryMix = jdbc.query("""
                select category as label, count(*) as value
                from support_conversations
                where %s
                group by category
                order by value desc
                limit 8
                """.formatted(COMPLAINT_SCOPE), (rs, rowNum) -> labelValue(rs));
        List<Map<String, Object>> severityMix = jdbc.query("""
                select severity as label, count(*) as value
                from support_conversations
                where %s
                group by severity
                order by field(severity, 'urgent', 'high', 'medium', 'low'), value desc
                """.formatted(COMPLAINT_SCOPE), (rs, rowNum) -> labelValue(rs));
        List<Map<String, Object>> statusMix = jdbc.query("""
                select status as label, count(*) as value
                from support_conversations
                where %s
                group by status
                order by field(status, '未处理', '处理中', '已处理', '忽略'), value desc
                """.formatted(COMPLAINT_SCOPE), (rs, rowNum) -> labelValue(rs));
        List<Map<String, Object>> recentIssues = jdbc.query("""
                select category, severity, summary, latest_message, last_message_at
                from support_conversations
                where %s
                order by field(severity, 'urgent', 'high', 'medium', 'low'), last_message_at desc
                limit 6
                """.formatted(COMPLAINT_SCOPE), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", safeString(rs, "category"));
            row.put("severity", safeString(rs, "severity"));
            row.put("summary", firstNonBlank(safeString(rs, "summary"), safeString(rs, "latest_message"), "用户反馈待复核"));
            row.put("time", String.valueOf(rs.getTimestamp("last_message_at").toLocalDateTime()));
            return row;
        });
        List<Map<String, Object>> issueDimensions = complaintIssueDimensions(
                qualityCount,
                serviceCount,
                allergyCount,
                painCount,
                colorMismatchCount,
                refundCount,
                handoffCount,
                unresolved
        ).stream()
                .filter(item -> intValue(item.get("value")) > 0)
                .toList();

        boolean hasValidComplaint = total > 0
                || unresolved > 0
                || allergyCount > 0
                || urgentCount > 0
                || highCount > 0
                || qualityCount > 0
                || serviceCount > 0
                || colorMismatchCount > 0
                || painCount > 0
                || refundCount > 0
                || handoffCount > 0
                || !recentIssues.isEmpty();

        String topCategory = categoryMix.isEmpty() ? "暂无集中分类" : String.valueOf(categoryMix.get(0).get("label"));
        Map<String, Object> topIssue = issueDimensions.stream()
                .findFirst()
                .orElse(Map.of("label", "暂无集中维度", "value", 0));
        String summary = !hasValidComplaint
                ? "暂未产生有效客诉"
                : "近 7 日客诉与高优先级反馈共 " + total + " 条，分类主要集中在“" + topCategory + "”，维度重点是“" + topIssue.get("label") + "”，其中未解决 " + unresolved + " 条。";
        List<String> actions = new ArrayList<>();
        if (hasValidComplaint && allergyCount > 0) {
            actions.add("本周过敏/红肿/刺痛相关反馈 " + allergyCount + " 位，需复核底胶、清洁消毒、固化时长和顾客敏感史提醒。");
        }
        if (hasValidComplaint && painCount > 0) {
            actions.add("疼痛、破皮或灼热反馈 " + painCount + " 条，需复盘打磨力度、死皮处理和照灯时长，必要时暂停相关技师排班。");
        }
        if (hasValidComplaint && qualityCount > 0) {
            actions.add("质量/持久度反馈 " + qualityCount + " 条，重点检查起翘、脱落、封层、固化和饰品牢固度。");
        }
        if (hasValidComplaint && serviceCount > 0) {
            actions.add("服务/等待反馈 " + serviceCount + " 条，建议把预约间隔、排队提示和到店接待话术重新校准。");
        }
        if (hasValidComplaint && colorMismatchCount > 0) {
            actions.add("色差或不显白反馈 " + colorMismatchCount + " 条，款式详情需补冷暖皮对照图，并给客服增加替代色推荐。");
        }
        if (hasValidComplaint && refundCount > 0) {
            actions.add("退款、补偿或返修诉求 " + refundCount + " 条，需统一补救标准，避免不同客服给出不一致承诺。");
        }
        if (hasValidComplaint && handoffCount > 0) {
            actions.add("人工介入 " + handoffCount + " 条，把转人工原因沉淀成客服知识库，减少同类问题重复升级。");
        }
        if (hasValidComplaint && unresolved > 0) {
            actions.add("把未处理与处理中客诉优先分配给人工客服，24 小时内给到补救方案或复访记录。");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hasValidComplaint", hasValidComplaint);
        data.put("total", total);
        data.put("unresolved", unresolved);
        data.put("allergyCount", allergyCount);
        data.put("urgentCount", urgentCount);
        data.put("highCount", highCount);
        data.put("qualityCount", qualityCount);
        data.put("serviceCount", serviceCount);
        data.put("colorMismatchCount", colorMismatchCount);
        data.put("painCount", painCount);
        data.put("refundCount", refundCount);
        data.put("handoffCount", handoffCount);
        data.put("riskScore", Math.min(100, allergyCount * 24 + painCount * 20 + urgentCount * 18 + unresolved * 10 + refundCount * 12 + qualityCount * 8 + serviceCount * 6));
        data.put("issueDimensions", issueDimensions);
        data.put("categoryMix", categoryMix);
        data.put("severityMix", severityMix);
        data.put("statusMix", statusMix);
        data.put("recentIssues", recentIssues);
        data.put("summary", summary);
        data.put("insight", hasValidComplaint ? "" : "");
        data.put("actions", uniqueStrings(actions, 4));
        return data;
    }

    private int complaintKeywordCount(String pattern) {
        return count("""
                select count(*)
                from support_conversations
                where %s
                  and concat_ws(' ',
                    coalesce(category, ''),
                    coalesce(title, ''),
                    coalesce(summary, ''),
                    coalesce(latest_message, ''),
                    coalesce(important_items_json, ''),
                    coalesce(merchant_note, ''),
                    coalesce(handoff_reason, '')
                  ) regexp '%s'
                """.formatted(COMPLAINT_SCOPE, pattern));
    }

    private List<Map<String, Object>> complaintIssueDimensions(int qualityCount,
                                                              int serviceCount,
                                                              int allergyCount,
                                                              int painCount,
                                                              int colorMismatchCount,
                                                              int refundCount,
                                                              int handoffCount,
                                                              int unresolved) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(complaintIssueDimension("quality", "质量/持久", "脱落、起翘、厚度、封层", qualityCount, "检查施工厚度、封层、照灯固化和饰品牢固度。"));
        rows.add(complaintIssueDimension("service", "服务/等待", "预约、排队、接待、回复", serviceCount, "缩短预约缓冲误差，统一排队提示和到店接待话术。"));
        rows.add(complaintIssueDimension("allergy", "过敏/红肿", "过敏、红肿、发炎、起疹", allergyCount, "复核材料、清洁消毒和敏感史提醒，必要时提供低敏方案。"));
        rows.add(complaintIssueDimension("pain", "疼痛/破皮", "疼痛、刺痛、破皮、出血", painCount, "复盘打磨力度、死皮处理和照灯时长，重点回看技师操作。"));
        rows.add(complaintIssueDimension("color", "色差/不显白", "色差、显黑、不搭、偏黄", colorMismatchCount, "补充冷暖皮试色图，给客服准备显白替代款。"));
        rows.add(complaintIssueDimension("refund", "退款/补偿", "退款、返修、重做、差评", refundCount, "统一返修和补偿标准，避免承诺口径不一致。"));
        rows.add(complaintIssueDimension("handoff", "人工介入", "转人工、人工处理中", handoffCount, "沉淀转人工原因，补充客服知识库和快捷回复。"));
        rows.add(complaintIssueDimension("unresolved", "处理闭环", "未处理、处理中", unresolved, "把未闭环事项分配到人，次日复访确认。"));
        return rows.stream()
                .sorted(Comparator.comparingInt((Map<String, Object> item) -> intValue(item.get("value"))).reversed())
                .toList();
    }

    private Map<String, Object> complaintIssueDimension(String key, String label, String detail, int value, String action) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("detail", detail);
        row.put("value", value);
        row.put("risk", value >= 3 ? "高风险" : value > 0 ? "需关注" : "稳定");
        row.put("level", value >= 3 ? "danger" : value > 0 ? "warning" : "stable");
        row.put("action", action);
        return row;
    }

    private List<Map<String, Object>> complaintSamples() {
        return jdbc.query("""
                select status, category, severity, title, summary, latest_message, important_items_json,
                       merchant_note, handoff_requested, handoff_status, handoff_reason, last_message_at
                from support_conversations
                where %s
                order by important desc,
                         field(severity, 'urgent', 'high', 'medium', 'low'),
                         last_message_at desc
                limit 24
                """.formatted(COMPLAINT_SCOPE), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", safeString(rs, "status"));
            row.put("category", safeString(rs, "category"));
            row.put("severity", safeString(rs, "severity"));
            row.put("title", safeString(rs, "title"));
            row.put("summary", safeString(rs, "summary"));
            row.put("latestMessage", safeString(rs, "latest_message"));
            row.put("importantItems", safeString(rs, "important_items_json"));
            row.put("merchantNote", safeString(rs, "merchant_note"));
            row.put("handoffRequested", rs.getBoolean("handoff_requested"));
            row.put("handoffStatus", safeString(rs, "handoff_status"));
            row.put("handoffReason", safeString(rs, "handoff_reason"));
            row.put("time", String.valueOf(rs.getTimestamp("last_message_at").toLocalDateTime()));
            return row;
        });
    }

    private List<Map<String, Object>> ratingCommentSamples() {
        return jdbc.query("""
                select coalesce(s.name, p.style_name, '未命名款式') as style_name,
                       r.rating,
                       coalesce(r.fit_score, r.rating * 20) as fit_score,
                       coalesce(r.color_score, r.rating * 20) as color_score,
                       coalesce(r.style_score, r.rating * 20) as style_score,
                       coalesce(r.scene_score, r.rating * 20) as scene_score,
                       coalesce(r.aesthetic_score, r.rating * 20) as aesthetic_score,
                       coalesce(r.comment, '') as comment,
                       r.created_at
                from customer_photo_ratings r
                join customer_photos p on p.id = r.photo_id
                left join nail_styles s on s.id = p.style_id
                where r.created_at >= date_sub(now(), interval 7 day)
                  and coalesce(r.comment, '') <> ''
                order by ((coalesce(r.fit_score, r.rating * 20)
                  + coalesce(r.color_score, r.rating * 20)
                  + coalesce(r.style_score, r.rating * 20)
                  + coalesce(r.scene_score, r.rating * 20)
                  + coalesce(r.aesthetic_score, r.rating * 20)) / 5) asc,
                  r.created_at desc
                limit 20
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("styleName", safeString(rs, "style_name"));
            row.put("rating", rs.getDouble("rating"));
            row.put("fitScore", round1(rs.getDouble("fit_score")));
            row.put("colorScore", round1(rs.getDouble("color_score")));
            row.put("styleScore", round1(rs.getDouble("style_score")));
            row.put("sceneScore", round1(rs.getDouble("scene_score")));
            row.put("aestheticScore", round1(rs.getDouble("aesthetic_score")));
            row.put("comment", safeString(rs, "comment"));
            row.put("time", String.valueOf(rs.getTimestamp("created_at").toLocalDateTime()));
            return row;
        });
    }

    private Map<String, Object> ratingSection() {
        Map<String, Object> aggregate = jdbc.query("""
                select count(r.id) as rating_count,
                       coalesce(avg(r.rating), 0) as avg_rating,
                       coalesce(avg((coalesce(r.fit_score, r.rating * 20)
                         + coalesce(r.color_score, r.rating * 20)
                         + coalesce(r.style_score, r.rating * 20)
                         + coalesce(r.scene_score, r.rating * 20)
                         + coalesce(r.aesthetic_score, r.rating * 20)) / 5), 0) as avg_score,
                       coalesce(sum(case when ((coalesce(r.fit_score, r.rating * 20)
                         + coalesce(r.color_score, r.rating * 20)
                         + coalesce(r.style_score, r.rating * 20)
                         + coalesce(r.scene_score, r.rating * 20)
                         + coalesce(r.aesthetic_score, r.rating * 20)) / 5) < 70 then 1 else 0 end), 0) as low_count
                from customer_photo_ratings r
                where r.created_at >= date_sub(now(), interval 7 day)
                """, rs -> {
            if (!rs.next()) return Map.of();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ratingCount", rs.getInt("rating_count"));
            row.put("avgRating", rs.getDouble("avg_rating"));
            row.put("avgScore", rs.getDouble("avg_score"));
            row.put("lowCount", rs.getInt("low_count"));
            return row;
        });

        List<Map<String, Object>> dimensions = DIMENSIONS.stream().map(this::dimensionRow).toList();
        Map<String, Object> worst = dimensions.stream()
                .min(Comparator.comparingDouble(item -> doubleValue(item.get("avgScore"))))
                .orElse(Map.of("label", "暂无评分", "avgScore", 0, "issueCount", 0, "suggestion", "等待更多返图评分后再判断。"));
        List<Map<String, Object>> lowStyles = lowStyleRows();

        int ratingCount = intValue(aggregate.get("ratingCount"));
        double avgScore = doubleValue(aggregate.get("avgScore"));
        String summary = ratingCount == 0
                ? "近 7 日暂无新的返图评分，暂不能判断款式质量波动。"
                : "近 7 日累计 " + ratingCount + " 条返图评分，五维均分 " + oneDecimal(avgScore) + " 分，当前最需要关注“" + worst.get("label") + "”。";

        List<String> actions = new ArrayList<>();
        actions.add(String.valueOf(worst.get("suggestion")));
        if (!lowStyles.isEmpty()) {
            Map<String, Object> low = lowStyles.get(0);
            actions.add("优先复盘低分款“" + low.get("styleName") + "”，用户主要问题是“" + low.get("issue") + "”。");
        }
        if (intValue(aggregate.get("lowCount")) > 0) {
            actions.add("把 70 分以下返图拉入质检清单，对应款式先降推荐位，再补拍真实手型效果图。");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ratingCount", ratingCount);
        data.put("avgRating", doubleValue(aggregate.get("avgRating")));
        data.put("avgScore", avgScore);
        data.put("lowCount", intValue(aggregate.get("lowCount")));
        data.put("dimensions", dimensions);
        data.put("worstDimension", worst);
        data.put("lowStyles", lowStyles);
        data.put("summary", summary);
        data.put("actions", actions);
        return data;
    }

    private Map<String, Object> consumptionSection() {
        int orderCount = count("""
                select count(*)
                from appointments
                where coalesce(scheduled_at, created_at) >= date_sub(now(), interval 7 day)
                  and status <> '已取消'
                """);
        BigDecimal revenue = money("""
                select coalesce(sum(amount), 0)
                from appointments
                where coalesce(scheduled_at, created_at) >= date_sub(now(), interval 7 day)
                  and status <> '已取消'
                """);
        List<Map<String, Object>> topStyles = jdbc.query("""
                select coalesce(s.name, a.service_name, '未选款') as style_name,
                       count(*) as order_count,
                       coalesce(sum(a.amount), 0) as amount
                from appointments a
                left join nail_styles s on s.id = a.style_id
                where coalesce(a.scheduled_at, a.created_at) >= date_sub(now(), interval 7 day)
                  and a.status <> '已取消'
                group by coalesce(s.name, a.service_name, '未选款')
                order by order_count desc, amount desc
                limit 6
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("styleName", rs.getString("style_name"));
            row.put("orderCount", rs.getInt("order_count"));
            row.put("amount", rs.getBigDecimal("amount"));
            return row;
        });
        List<Map<String, Object>> serviceMix = jdbc.query("""
                select service_name as label, count(*) as value, coalesce(sum(amount), 0) as amount
                from appointments
                where coalesce(scheduled_at, created_at) >= date_sub(now(), interval 7 day)
                  and status <> '已取消'
                group by service_name
                order by value desc, amount desc
                limit 6
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", rs.getString("label"));
            row.put("value", rs.getInt("value"));
            row.put("amount", rs.getBigDecimal("amount"));
            return row;
        });
        List<Map<String, Object>> keywords = demandKeywords();
        BigDecimal avgTicket = orderCount <= 0
                ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(orderCount), 0, RoundingMode.HALF_UP);
        List<String> profileTags = keywords.stream()
                .map(item -> String.valueOf(item.get("label")))
                .filter(StringUtils::hasText)
                .limit(5)
                .toList();
        List<String> topStyleNames = topStyles.stream()
                .map(item -> String.valueOf(item.get("styleName")))
                .filter(StringUtils::hasText)
                .limit(3)
                .toList();
        boolean hasProfile = orderCount > 0 || !profileTags.isEmpty() || !topStyleNames.isEmpty();
        String topKeyword = keywords.isEmpty() ? "暂无集中偏好" : String.valueOf(keywords.get(0).get("label"));
        String summary = !hasProfile
                ? "近 7 日消费与选款样本不足，暂时无法稳定判断用户画像。"
                : "近 7 日消费 " + orderCount + " 单，应收 ¥" + moneyText(revenue) + "，选款偏好集中在“" + topKeyword + "”。";
        String profileSummary = !hasProfile
                ? "近 7 日消费与选款样本不足，暂时无法稳定判断用户画像。"
                : userProfileSummary(orderCount, avgTicket, profileTags, topStyleNames);
        List<String> actions = new ArrayList<>();
        if (!keywords.isEmpty()) {
            actions.add("本周用户选款多以“" + topKeyword + "”为主，首页推荐位和客服话术可围绕该方向组织套餐。");
        }
        if (keywords.stream().anyMatch(item -> "显白".equals(item.get("label")))) {
            actions.add("“显白/美白”需求明显，给热门款补充冷暖皮对照图，并在详情中标出适合肤色。");
        }
        if (keywords.stream().anyMatch(item -> "夏日".equals(item.get("label")))) {
            actions.add("“夏日”需求出现，增加清透、薄荷、短甲、低饱和款式，适合做夏季专题。");
        }
        if (actions.isEmpty()) {
            actions.add("继续沉淀预约和试穿数据，满 10 单后再调整首页排序。");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hasProfile", hasProfile);
        data.put("orderCount", orderCount);
        data.put("revenue", revenue);
        data.put("avgTicket", avgTicket);
        data.put("topStyles", topStyles);
        data.put("topStyleNames", topStyleNames);
        data.put("serviceMix", serviceMix);
        data.put("keywords", keywords);
        data.put("profileTags", profileTags);
        data.put("profileSummary", profileSummary);
        data.put("summary", summary);
        data.put("actions", uniqueStrings(actions, 3));
        return data;
    }

    private List<String> recommendations(Map<String, Object> complaint,
                                         Map<String, Object> rating,
                                         Map<String, Object> consumption) {
        List<String> rows = new ArrayList<>();
        Map<String, Object> worst = mapValue(rating.get("worstDimension"));
        if (!worst.isEmpty()) {
            rows.add("五维评分当前短板是“" + worst.get("label") + "”，建议：" + worst.get("suggestion"));
        }
        List<Map<String, Object>> complaintDimensions = listValue(complaint.get("issueDimensions"));
        complaintDimensions.stream()
                .filter(item -> intValue(item.get("value")) > 0)
                .findFirst()
                .ifPresent(item -> rows.add("客诉最高维度是“" + item.get("label") + "”(" + item.get("value") + " 条)，建议：" + item.get("action")));
        if (intValue(complaint.get("allergyCount")) > 0) {
            rows.add("本周过敏相关反馈 " + complaint.get("allergyCount") + " 位，需提高消毒、底胶适配和敏感史确认标准。");
        }
        if (intValue(complaint.get("painCount")) > 0) {
            rows.add("疼痛/破皮反馈已出现，需立即回看打磨、死皮处理和照灯流程，避免升级为退款或差评。");
        }
        List<Map<String, Object>> lowStyles = listValue(rating.get("lowStyles"));
        if (!lowStyles.isEmpty()) {
            Map<String, Object> low = lowStyles.get(0);
            rows.add("低分款“" + low.get("styleName") + "”先暂停强推荐，按“" + low.get("issue") + "”补救展示图和服务说明。");
        }
        List<Map<String, Object>> keywords = listValue(consumption.get("keywords"));
        if (!keywords.isEmpty()) {
            rows.add("围绕“" + keywords.get(0).get("label") + "”做 7 日专题，把高评分返图和预约套餐一起承接。");
        }
        if (intValue(complaint.get("unresolved")) > 0) {
            rows.add("未解决客诉 " + complaint.get("unresolved") + " 条，建议设置每日闭环检查，避免高优先级会话滞留。");
        }
        if (rows.isEmpty()) {
            rows.add("继续沉淀返图评分、预约消费和客服反馈数据，待样本增多后再调整推荐节奏。");
        }
        return uniqueStrings(rows, 4);
    }

    private String recommendationSummary(Map<String, Object> complaint,
                                         Map<String, Object> rating,
                                         Map<String, Object> consumption,
                                         List<String> recommendations) {
        String joined = joinSentences(recommendations);
        if (StringUtils.hasText(joined)) {
            return joined;
        }
        if (boolValue(complaint.get("hasValidComplaint"))) {
            return "本周先围绕客诉闭环、返图低分款复盘和客服承接口径做同步优化，避免问题在门店端重复出现。";
        }
        if (boolValue(consumption.get("hasProfile"))) {
            return "本周优先围绕用户偏好的主流款型、显白需求和日常场景标签优化陈列与推荐，让高频审美更快被承接。";
        }
        return "本周样本仍偏少，先继续沉淀消费、返图和客服数据，再根据真实反馈调整陈列和推荐策略。";
    }

    private Map<String, Object> dimensionRow(DimensionRule rule) {
        String scoreColumn = rule.column();
        return jdbc.query("""
                select coalesce(avg(coalesce(%s, rating * 20)), 0) as avg_score,
                       coalesce(sum(case when coalesce(%s, rating * 20) < 70 then 1 else 0 end), 0) as issue_count
                from customer_photo_ratings
                where created_at >= date_sub(now(), interval 7 day)
                """.formatted(scoreColumn, scoreColumn), rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            if (rs.next()) {
                row.put("key", rule.key());
                row.put("label", rule.label());
                row.put("avgScore", round1(rs.getDouble("avg_score")));
                row.put("issueCount", rs.getInt("issue_count"));
                row.put("suggestion", rule.suggestion());
                return row;
            }
            row.put("key", rule.key());
            row.put("label", rule.label());
            row.put("avgScore", 0);
            row.put("issueCount", 0);
            row.put("suggestion", rule.suggestion());
            return row;
        });
    }

    private List<Map<String, Object>> lowStyleRows() {
        return jdbc.query("""
                select coalesce(s.name, p.style_name, '未命名款式') as style_name,
                       count(r.id) as rating_count,
                       coalesce(avg((coalesce(r.fit_score, r.rating * 20)
                         + coalesce(r.color_score, r.rating * 20)
                         + coalesce(r.style_score, r.rating * 20)
                         + coalesce(r.scene_score, r.rating * 20)
                         + coalesce(r.aesthetic_score, r.rating * 20)) / 5), 0) as avg_score,
                       coalesce(avg(coalesce(r.fit_score, r.rating * 20)), 0) as hand_fit,
                       coalesce(avg(coalesce(r.color_score, r.rating * 20)), 0) as skin_tone,
                       coalesce(avg(coalesce(r.style_score, r.rating * 20)), 0) as style_match,
                       coalesce(avg(coalesce(r.scene_score, r.rating * 20)), 0) as scene_score,
                       coalesce(avg(coalesce(r.aesthetic_score, r.rating * 20)), 0) as aesthetic_score,
                       coalesce(group_concat(r.comment separator '；'), '') as comments
                from customer_photo_ratings r
                join customer_photos p on p.id = r.photo_id
                left join nail_styles s on s.id = p.style_id
                where r.created_at >= date_sub(now(), interval 7 day)
                group by coalesce(s.name, p.style_name, '未命名款式')
                having avg_score < 82
                   or skin_tone < 76
                   or hand_fit < 76
                   or style_match < 76
                   or scene_score < 76
                   or aesthetic_score < 76
                order by avg_score asc, rating_count desc
                limit 6
                """, (rs, rowNum) -> lowStyleRow(rs));
    }

    private Map<String, Object> lowStyleRow(ResultSet rs) throws SQLException {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("手型适配度", rs.getDouble("hand_fit"));
        scores.put("肤色显白度", rs.getDouble("skin_tone"));
        scores.put("风格匹配度", rs.getDouble("style_match"));
        scores.put("场景实用性", rs.getDouble("scene_score"));
        scores.put("整体美观度", rs.getDouble("aesthetic_score"));
        Map.Entry<String, Double> worst = scores.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .orElse(Map.entry("综合评分", rs.getDouble("avg_score")));
        String comments = safeString(rs, "comments");
        String issue = styleIssue(comments, worst.getKey());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("styleName", rs.getString("style_name"));
        row.put("ratingCount", rs.getInt("rating_count"));
        row.put("avgScore", round1(rs.getDouble("avg_score")));
        row.put("worstDimension", worst.getKey());
        row.put("issue", issue);
        row.put("suggestion", styleSuggestion(issue, worst.getKey()));
        return row;
    }

    private List<Map<String, Object>> demandKeywords() {
        List<String> texts = jdbc.query("""
                select style_names as text_value
                from try_on_tasks
                where created_at >= date_sub(now(), interval 7 day)
                union all
                select coalesce(s.name, '') as text_value
                from appointments a
                left join nail_styles s on s.id = a.style_id
                where coalesce(a.scheduled_at, a.created_at) >= date_sub(now(), interval 7 day)
                union all
                select coalesce(s.tags, '') as text_value
                from appointments a
                left join nail_styles s on s.id = a.style_id
                where coalesce(a.scheduled_at, a.created_at) >= date_sub(now(), interval 7 day)
                union all
                select coalesce(p.style_name, '') as text_value
                from customer_photos p
                where p.created_at >= date_sub(now(), interval 7 day)
                """, (rs, rowNum) -> rs.getString("text_value"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String text : texts) {
            String normalized = String.valueOf(text == null ? "" : text).toLowerCase(Locale.ROOT);
            for (KeywordRule rule : KEYWORDS) {
                if (rule.tokens().stream().anyMatch(token -> normalized.contains(token.toLowerCase(Locale.ROOT)))) {
                    counts.merge(rule.label(), 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("label", entry.getKey());
                    row.put("value", entry.getValue());
                    return row;
                })
                .toList();
    }

    private String styleIssue(String comments, String fallbackDimension) {
        String text = String.valueOf(comments == null ? "" : comments);
        if (text.contains("不显白") || text.contains("显黑") || text.contains("黄")) return "用户反馈不显白";
        if (text.contains("过敏") || text.contains("红肿") || text.contains("刺痛")) return "用户反馈敏感不适";
        if (text.contains("不搭") || text.contains("不适合") || text.contains("老气")) return "风格与预期不匹配";
        if (text.contains("厚") || text.contains("掉") || text.contains("翘")) return "质感或持久度不足";
        return fallbackDimension + "偏低";
    }

    private String styleSuggestion(String issue, String dimension) {
        if (issue.contains("不显白")) return "增加冷暖皮试色图，降低该款对黄皮用户的推荐权重，补充更显白的裸粉或车厘子红替代款。";
        if (issue.contains("敏感")) return "复核底胶和清洁消毒流程，预约前增加敏感史确认，必要时提供低敏材料选项。";
        if (issue.contains("风格")) return "重写款式标签和适用场景，避免把个性款推给通勤/自然需求用户。";
        if (issue.contains("质感")) return "检查施工厚度、封层和固化时间，返修记录集中到门店质检。";
        return "围绕“" + dimension + "”补充展示图、服务说明和替代款推荐。";
    }

    private String userProfileSummary(int orderCount,
                                      BigDecimal avgTicket,
                                      List<String> profileTags,
                                      List<String> topStyleNames) {
        String keywordText = profileTags.isEmpty() ? "暂无稳定关键词" : String.join("、", profileTags);
        String styleText = topStyleNames.isEmpty() ? "基础大众款" : String.join("、", topStyleNames);
        if (orderCount <= 0) {
            return "本周虽然还没有形成稳定消费闭环，但从试穿与选款记录看，顾客更容易被“" + keywordText + "”方向吸引，可先按这个审美方向整理陈列与客服推荐。";
        }
        return "本周下单用户更偏好“" + keywordText + "”方向，常被选择的款式集中在“" + styleText + "”，说明门店当前更适合承接显白、清透、日常好搭配的主流审美，平均客单约 ¥" + moneyText(avgTicket) + "。";
    }

    private Map<String, Object> card(String label, Object value, String growth) {
        return Map.of("label", label, "value", value, "growth", growth);
    }

    private Map<String, Object> labelValue(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", firstNonBlank(rs.getString("label"), "未分类"));
        row.put("value", rs.getInt("value"));
        return row;
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private BigDecimal money(String sql) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    private String moneyText(Object value) {
        if (value instanceof BigDecimal amount) {
            return amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        if (value == null) {
            return "0";
        }
        try {
            return new BigDecimal(String.valueOf(value)).setScale(0, RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException ex) {
            return "0";
        }
    }

    private String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? 0 : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String safeString(ResultSet rs, String column) {
        try {
            String value = rs.getString(column);
            return value == null ? "" : value;
        } catch (SQLException ignored) {
            return "";
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private List<String> uniqueStrings(List<String> values, int limit) {
        List<String> rows = new ArrayList<>();
        for (String value : values) {
            String normalized = stringValue(value);
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            if (rows.stream().noneMatch(item -> item.equalsIgnoreCase(normalized))) {
                rows.add(normalized);
            }
            if (rows.size() >= limit) {
                break;
            }
        }
        return rows;
    }

    private String joinSentences(List<String> values) {
        List<String> unique = uniqueStrings(values, values == null ? 0 : values.size());
        if (unique.isEmpty()) {
            return "";
        }
        return String.join("；", unique);
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            row.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return row;
    }

    private List<Map<String, Object>> listValue(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : raw) {
            rows.add(mapValue(item));
        }
        return rows;
    }

    private record DimensionRule(String key, String label, String column, String suggestion) {
    }

    private record KeywordRule(String label, List<String> tokens) {
    }
}
