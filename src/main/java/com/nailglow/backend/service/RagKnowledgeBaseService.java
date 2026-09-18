package com.nailglow.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the offline side of the Milvus Lite knowledge base.
 *
 * Customer chat retrieval remains in the LangGraph Python process so that it
 * does not require a second subprocess per user message. This service exists
 * for administrator-triggered status, chunk preview, and reindex operations.
 */
@Service
public class RagKnowledgeBaseService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SystemSettingService systemSettingService;

    @Value("${nailglow.python.bin:${PYTHON_BIN:python}}")
    private String pythonBin;

    @Value("${nailglow.rag.enabled:${RAG_ENABLED:true}}")
    private boolean enabled;

    @Value("${nailglow.rag.embedding-base-url:${RAG_EMBEDDING_BASE_URL:https://ark.cn-beijing.volces.com/api/v3}}")
    private String embeddingBaseUrl;

    @Value("${nailglow.rag.embedding-model:${RAG_EMBEDDING_MODEL:doubao-embedding-vision-251215}}")
    private String embeddingModel;

    @Value("${nailglow.rag.embedding-multimodal:${RAG_EMBEDDING_MULTIMODAL:true}}")
    private boolean embeddingMultimodal;

    @Value("${nailglow.rag.rerank-base-url:${RAG_RERANK_BASE_URL:https://dashscope.aliyuncs.com/compatible-api/v1}}")
    private String rerankBaseUrl;

    @Value("${nailglow.rag.rerank-model:${RAG_RERANK_MODEL:qwen3-rerank}}")
    private String rerankModel;

    @Value("${nailglow.rag.rerank-api-style:${RAG_RERANK_API_STYLE:auto}}")
    private String rerankApiStyle;

    @Value("${nailglow.rag.timeout-seconds:${RAG_TIMEOUT_SECONDS:180}}")
    private long timeoutSeconds;

    @Value("${RAG_EMBEDDING_API_KEY:}")
    private String configuredEmbeddingApiKey;

    @Value("${RAG_RERANK_API_KEY:}")
    private String configuredRerankApiKey;

    @Value("${DASHSCOPE_API_KEY:}")
    private String configuredDashscopeApiKey;

    public RagKnowledgeBaseService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    public Map<String, Object> status() {
        return run("status", Map.of());
    }

    public Map<String, Object> previewChunks(int limit) {
        return run("preview_chunks", Map.of("limit", Math.max(1, Math.min(limit, 100))));
    }

    public Map<String, Object> rebuild() {
        return run("build", Map.of());
    }

    private Map<String, Object> run(String action, Map<String, Object> extra) {
        Path scriptPath = Path.of("src", "main", "python", "rag_knowledge_base.py").toAbsolutePath().normalize();
        if (!Files.exists(scriptPath)) {
            return Map.of("ok", false, "error", "未找到 RAG 知识库脚本：" + scriptPath);
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(pythonBin, scriptPath.toString());
            builder.directory(Path.of(".").toAbsolutePath().normalize().toFile());
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONUTF8", "1");
            applyRagEnvironment(builder);
            Process process = builder.start();

            Map<String, Object> payload = new LinkedHashMap<>(extra);
            payload.put("action", action);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(mapper.writeValueAsString(payload));
            }

            boolean finished = process.waitFor(Math.max(20, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Map.of("ok", false, "error", "RAG " + action + " 执行超时");
            }
            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            if (process.exitValue() != 0 && stdout.isBlank()) {
                return Map.of("ok", false, "error", stderr.isBlank() ? "RAG 脚本执行失败" : stderr);
            }
            Map<String, Object> result = mapper.readValue(stdout.isBlank() ? "{}" : stdout, Map.class);
            result.putIfAbsent("ok", process.exitValue() == 0);
            return result;
        } catch (Exception ex) {
            return Map.of("ok", false, "error", "RAG 调用失败：" + ex.getMessage());
        }
    }

    private void applyRagEnvironment(ProcessBuilder builder) {
        builder.environment().put("RAG_ENABLED", String.valueOf(enabled));
        builder.environment().put("RAG_EMBEDDING_BASE_URL", systemSettingService.effectiveAiBaseUrl("rag_embedding_base_url", embeddingBaseUrl));
        builder.environment().put("RAG_EMBEDDING_MODEL", systemSettingService.getText("rag_embedding_model", embeddingModel));
        builder.environment().put("RAG_EMBEDDING_MULTIMODAL", String.valueOf(embeddingMultimodal));
        builder.environment().put("RAG_RERANK_BASE_URL", rerankBaseUrl);
        builder.environment().put("RAG_RERANK_MODEL", rerankModel);
        builder.environment().put("RAG_RERANK_API_STYLE", rerankApiStyle);
        builder.environment().put("RAG_TIMEOUT_SECONDS", String.valueOf(timeoutSeconds));

        String embeddingKey = systemSettingService.effectiveAiApiKey(
                "rag_embedding_api_key",
                configuredEmbeddingApiKey,
                "RAG_EMBEDDING_API_KEY",
                "DEEPSEEK_API_KEY"
        );
        if (!embeddingKey.isBlank()) {
            builder.environment().put("RAG_EMBEDDING_API_KEY", embeddingKey);
        }
        String rerankKey = firstNonBlank(
                systemSettingService.getText("rag_rerank_api_key", ""),
                configuredDashscopeApiKey,
                configuredRerankApiKey,
                firstEnvironment("DASHSCOPE_API_KEY", "RAG_RERANK_API_KEY")
        );
        if (!rerankKey.isBlank()) {
            builder.environment().put("RAG_RERANK_API_KEY", rerankKey);
        }
    }

    private String firstEnvironment(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
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
