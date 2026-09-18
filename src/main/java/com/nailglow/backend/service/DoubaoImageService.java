package com.nailglow.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DoubaoImageService {
    private static final Logger logger = LoggerFactory.getLogger(DoubaoImageService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();
    private final SystemSettingService systemSettingService;

    @Value("${nailglow.doubao.enabled:true}")
    private boolean enabled;

    @Value("${nailglow.doubao.api-key:}")
    private String apiKey;

    @Value("${nailglow.doubao.endpoint:}")
    private String endpoint;

    @Value("${nailglow.doubao.model:}")
    private String model;

    @Value("${nailglow.doubao.size:1024x1024}")
    private String size;

    @Value("${nailglow.doubao.timeout-seconds:240}")
    private long timeoutSeconds;

    public DoubaoImageService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    public GenerationResult generate(String styleName, String styleDescription,
                                     String handImageReferenceUrl,
                                     String handImageDataUrl,
                                     String styleImageReferenceUrl) {
        String prompt = buildPrompt(styleName, styleDescription);
        String effectiveApiKey = systemSettingService.effectiveAiApiKey(
                "doubao_api_key",
                apiKey,
                "DOUBAO_IMAGE_API_KEY",
                "LUMIO_API_KEY",
                "LUMIO_IMAGE_API_KEY"
        );
        String effectiveModel = systemSettingService.getText("doubao_model", model);
        String effectiveEndpoint = systemSettingService.effectiveImageGenerationEndpoint(endpoint);
        String effectiveSize = systemSettingService.effectiveImageGenerationSize(size);
        if (!enabled || !StringUtils.hasText(effectiveApiKey)) {
            return GenerationResult.local(prompt, "未配置图生图 API Key，已使用本地预览结果；配置后会自动调用远端生图接口。");
        }
        if (!StringUtils.hasText(effectiveModel)) {
            return GenerationResult.local(prompt, "未配置图生图模型，已使用本地预览结果。");
        }
        if (!StringUtils.hasText(effectiveEndpoint)) {
            return GenerationResult.local(prompt, "未配置图生图接口地址，已使用本地预览结果。");
        }
        if (!StringUtils.hasText(effectiveSize)) {
            return GenerationResult.local(prompt, "未配置图生图尺寸，已使用本地预览结果。");
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", effectiveModel);
            body.put("prompt", prompt);
            body.put("size", effectiveSize);
            String requestUrl = effectiveEndpoint;
            List<String> referenceImages = buildReferenceImages(
                    handImageReferenceUrl,
                    handImageDataUrl,
                    styleImageReferenceUrl
            );
            if (!referenceImages.isEmpty()) {
                if (usesArkImagePayload(effectiveEndpoint, effectiveModel)) {
                    body.put("image", referenceImages.size() == 1 ? referenceImages.get(0) : referenceImages);
                    body.put("sequential_image_generation", "disabled");
                    body.put("stream", false);
                    body.put("response_format", "url");
                    body.put("watermark", false);
                } else if (usesOpenAiEditPayload(effectiveEndpoint, effectiveModel)) {
                    List<Map<String, String>> editImages = buildEditImages(
                            handImageReferenceUrl,
                            styleImageReferenceUrl
                    );
                    if (editImages.isEmpty()) {
                        return GenerationResult.local(prompt, "缺少可公开访问的参考图，已保留任务并使用本地预览。");
                    }
                    requestUrl = toEditsEndpoint(effectiveEndpoint);
                    body.put("images", editImages);
                    body.put("input_fidelity", "high");
                } else {
                    body.put("reference_images", referenceImages);
                }
            }

            long effectiveTimeoutSeconds = Math.max(90, timeoutSeconds);
            String requestBody = mapper.writeValueAsString(body);
            int maxAttempts = usesOpenAiEditPayload(effectiveEndpoint, effectiveModel) ? 3 : 1;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(requestUrl))
                        .timeout(Duration.ofSeconds(effectiveTimeoutSeconds))
                        .header("Authorization", "Bearer " + effectiveApiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    JsonNode root = mapper.readTree(response.body());
                    String url = extractImageUrl(root);
                    String base64 = extractBase64(root);
                    if (!url.isBlank()) {
                        return GenerationResult.remote(prompt, url);
                    }
                    if (!base64.isBlank()) {
                        return GenerationResult.remote(prompt, "data:image/png;base64," + base64);
                    }
                    logger.warn("Lumio image generation response missing image payload: {}", abbreviate(response.body(), 600));
                    return GenerationResult.local(prompt, "图生图接口未返回图片地址，已使用本地预览。");
                }

                boolean retryable = isRetryableUpstreamError(response.statusCode(), response.body());
                if (!retryable || attempt >= maxAttempts) {
                    logger.warn("Lumio image generation returned status {} body={}", response.statusCode(), abbreviate(response.body(), 600));
                    return GenerationResult.local(prompt, "图生图接口返回 " + response.statusCode() + "，已保留任务并使用本地预览。");
                }

                logger.warn("Lumio image generation transient failure on attempt {} status {} body={}",
                        attempt, response.statusCode(), abbreviate(response.body(), 400));
                sleepBeforeRetry(attempt);
            }

            return GenerationResult.local(prompt, "图生图接口暂时不可用，已保留任务并使用本地预览。");
        } catch (Exception ex) {
            logger.warn("Lumio image generation failed and fell back to local preview", ex);
            return GenerationResult.local(prompt, "图生图调用失败：" + ex.getMessage());
        }
    }

    private String buildPrompt(String styleName, String styleDescription) {
        String safeStyleName = StringUtils.hasText(styleName) ? styleName.trim() : "未命名款式";
        String safeStyleDescription = StringUtils.hasText(styleDescription) ? styleDescription.trim() : "以参考图 2 的肉眼可见指甲设计为准，不自行发挥。";
        return ("""
                任务：在用户自己的手上试戴美甲。最终图片必须看起来像“参考图 1 的这只手戴上了参考图 2 的美甲”。
                图片顺序：
                - 参考图 1 是最终成图的主体和画布，必须保留它的手部姿势、手掌朝向、手指位置、手指比例、肤色、光照、背景、裁切范围和相机角度。
                - 参考图 2 只是美甲款式素材，只能读取指甲区域的颜色、图案、装饰、光泽、质感和跳色/排列关系。
                - 如果参考图 2 的手势、背景、彩虹光、道具或皮肤与参考图 1 不一致，全部忽略；只看参考图 2 的指甲设计。
                - 款式名称和文字说明只作索引备注；如果与参考图 2 的肉眼可见美甲不一致，一律以参考图 2 为准。

                当前选中款式备注：
                - 款式名称：%s
                - 文字说明：%s

                关键要求：
                1. 成图构图必须跟参考图 1 一致，不能复制参考图 2 的手、手势、背景、彩虹光、阴影、皮肤、镜头角度或整体照片。
                2. 只在参考图 1 的指甲区域做编辑；除指甲外，参考图 1 的手和背景必须尽量保持原样。
                3. 用户手一开始怎么摆放，生成后的试穿图也要保持原样；不允许把试穿做成另一只手、另一种姿势或另一张摆拍照片。
                4. 将参考图 2 的美甲设计贴合到参考图 1 的每个指甲上，甲型、长度、前缘轮廓、弧度和角度都必须以参考图 2 的肉眼可见效果为准，再映射到参考图 1 的对应手指上。
                5. 不要根据款式名称、标签、文字说明、常见美甲审美或模型默认偏好自行改色、换图案、增删装饰。
                6. 如果参考图 2 是黑色星星款，参考图 1 的手上也必须呈现黑色星星款；如果参考图 2 不是红色，严禁生成红色或酒红色。
                7. 如果参考图 2 有跳色、单指图案、星星、钻饰、猫眼、法式边或渐变位置，按可见结构映射到参考图 1 的对应指甲。
                8. 不添加额外手指、文字、水印、首饰、夸张背景，不改变用户手的身份特征。
                9. 甲长必须严格跟参考图 2 一致：参考图 2 是短甲，就生成短甲；参考图 2 是中长甲、长甲或明显延长甲，就必须保留同等级别的可见长度，绝不能私自缩成短甲、秃甲或更保守的长度。
                10. 如果参考图 2 的指尖明显超出肉色甲床，成图中对应指甲也必须有清晰可见的自由边或延长甲片；允许只在指甲区域内生成合理延长效果，但绝不改变参考图 1 的手指本体长度、粗细、姿势和构图。
                11. 不要把参考图 2 的长方甲、杏仁甲、尖甲、方圆甲等甲型擅自改成更短、更圆或更钝的版本；长度和甲型都必须优先服从参考图 2。

                生成要求：
                输出一张干净、真实、可用于用户确认“这款美甲在自己手上效果”的试戴图。第一优先级是保留参考图 1 的手和构图，第二优先级是让指甲款式与参考图 2 一致。若二者冲突，绝不允许改变参考图 1 的手势和环境；但只要参考图 2 明确展示了长甲或特定甲型，成图就必须把这类长度和甲型特征保留下来，不能自动弱化。
                """).formatted(safeStyleName, safeStyleDescription);
    }

    private boolean usesArkImagePayload(String endpoint, String modelName) {
        String normalizedEndpoint = String.valueOf(endpoint).toLowerCase();
        String normalizedModel = String.valueOf(modelName).toLowerCase();
        return normalizedEndpoint.contains("ark.cn-beijing.volces.com")
                || normalizedModel.contains("seedream");
    }

    private boolean usesOpenAiEditPayload(String endpoint, String modelName) {
        String normalizedEndpoint = String.valueOf(endpoint).toLowerCase();
        String normalizedModel = String.valueOf(modelName).toLowerCase();
        return normalizedModel.startsWith("gpt-image")
                || normalizedEndpoint.contains("api.openai.com")
                || normalizedEndpoint.contains("api.lumio.games");
    }

    private List<String> buildReferenceImages(String handImageReferenceUrl,
                                              String handImageDataUrl,
                                              String styleImageReferenceUrl) {
        List<String> images = new java.util.ArrayList<>();
        String handReference = firstNonBlank(handImageReferenceUrl, handImageDataUrl);
        if (StringUtils.hasText(handReference)) {
            images.add(handReference);
        }
        if (StringUtils.hasText(styleImageReferenceUrl)) {
            images.add(styleImageReferenceUrl.trim());
        }
        return images;
    }

    private List<Map<String, String>> buildEditImages(String handImageReferenceUrl,
                                                      String styleImageReferenceUrl) {
        List<Map<String, String>> images = new java.util.ArrayList<>();
        addEditImage(images, handImageReferenceUrl);
        addEditImage(images, styleImageReferenceUrl);
        return images;
    }

    private void addEditImage(List<Map<String, String>> images, String candidateUrl) {
        if (!StringUtils.hasText(candidateUrl)) {
            return;
        }
        String normalized = candidateUrl.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return;
        }
        Map<String, String> image = new LinkedHashMap<>();
        image.put("image_url", normalized);
        images.add(image);
    }

    private String toEditsEndpoint(String endpoint) {
        String normalized = String.valueOf(endpoint).trim();
        if (!StringUtils.hasText(normalized)) {
            return normalized;
        }
        return normalized
                .replace("/v1/images/generations", "/v1/images/edits")
                .replace("/api/v3/images/generations", "/api/v3/images/edits");
    }

    private boolean isRetryableUpstreamError(int statusCode, String responseBody) {
        if (statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return true;
        }
        String normalizedBody = String.valueOf(responseBody).toLowerCase();
        return normalizedBody.contains("upstream_error")
                || normalizedBody.contains("temporarily unavailable")
                || normalizedBody.contains("try again");
    }

    private void sleepBeforeRetry(int attempt) {
        long waitMillis = Math.min(4_000L, 1_500L * Math.max(1, attempt));
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
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

    private String extractImageUrl(JsonNode root) {
        String[] containers = {"data", "result", "results", "output", "outputs"};
        for (String container : containers) {
            JsonNode node = root.path(container);
            String url = extractNodeImageUrl(node);
            if (StringUtils.hasText(url)) {
                return url;
            }
        }
        return firstText(root, "url", "image_url", "imageUrl");
    }

    private String extractBase64(JsonNode root) {
        String[] containers = {"data", "result", "results", "output", "outputs"};
        for (String container : containers) {
            JsonNode node = root.path(container);
            String base64 = extractNodeBase64(node);
            if (StringUtils.hasText(base64)) {
                return base64;
            }
        }
        return firstText(root, "b64_json", "base64");
    }

    private String extractNodeImageUrl(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String url = extractNodeImageUrl(item);
                if (StringUtils.hasText(url)) {
                    return url;
                }
            }
            return "";
        }
        if (node.isObject()) {
            String url = firstText(node, "url", "image_url", "imageUrl");
            if (StringUtils.hasText(url)) {
                return url;
            }
            String base64 = extractNodeBase64(node);
            if (StringUtils.hasText(base64)) {
                return "data:image/png;base64," + base64;
            }
        }
        return "";
    }

    private String extractNodeBase64(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return "";
        }
        return firstText(node, "b64_json", "base64");
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull() || fields == null) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                String text = value.asText("");
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    private String abbreviate(String text, int maxLength) {
        if (!StringUtils.hasText(text) || maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    public record GenerationResult(boolean remote, String imageUrl, String prompt, String message) {
        static GenerationResult remote(String prompt, String imageUrl) {
            return new GenerationResult(true, imageUrl, prompt, "Lumio 生图完成");
        }

        static GenerationResult local(String prompt, String message) {
            return new GenerationResult(false, "", prompt, message);
        }
    }
}
