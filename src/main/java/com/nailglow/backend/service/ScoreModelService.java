package com.nailglow.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ScoreModelService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public ScoreModelService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Value("${nailglow.python.bin:${PYTHON_BIN:python}}")
    private String pythonBin;

    @Value("${nailglow.score-model.timeout-seconds:60}")
    private long timeoutSeconds;

    @Value("${nailglow.score-model.use-mediapipe:true}")
    private boolean useMediapipe;

    public Map<String, Object> predict(Path imagePath, String styleCode) {
        Path scriptPath = Path.of("src", "main", "python", "score_model_predict.py").toAbsolutePath().normalize();
        Path modelPath = activeModelPath();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("imagePath", imagePath.toAbsolutePath().toString());
        request.put("styleCode", styleCode == null || styleCode.isBlank() ? "nail_01" : styleCode);
        request.put("modelPath", modelPath.toString());
        request.put("useMediapipe", useMediapipe);

        try {
            ProcessBuilder builder = new ProcessBuilder(pythonBin, scriptPath.toString())
                    .directory(Path.of(".").toAbsolutePath().normalize().toFile());
            builder.environment().put("PYTHONUTF8", "1");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            Process process = builder.start();

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(mapper.writeValueAsString(request));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return fallback("评分模型执行超时", styleCode);
            }

            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            if (process.exitValue() != 0 || stdout.isBlank()) {
                return fallback(stderr.isBlank() ? "评分模型未返回结果" : stderr, styleCode);
            }
            Map<String, Object> result = mapper.readValue(stdout, Map.class);
            result.put("source", modelPath.getFileName().toString());
            result.putIfAbsent("scored", result.get("score") instanceof Number);
            result.putIfAbsent("confidence", result.get("scored").equals(Boolean.TRUE) ? 0.45 : 0.0);
            result.putIfAbsent("confidenceLevel", "LEGACY");
            result.putIfAbsent("handProfile", Map.of());
            result.putIfAbsent("reasons", List.of());
            return result;
        } catch (Exception ex) {
            return fallback("评分模型调用失败：" + ex.getMessage(), styleCode);
        }
    }

    private Path activeModelPath() {
        try {
            var rows = jdbc.query("""
                    select file_path
                    from score_model_versions
                    where status = 'active'
                    order by coalesce(activated_at, created_at) desc, id desc
                    limit 1
                    """, (rs, rowNum) -> rs.getString("file_path"));
            if (!rows.isEmpty()) {
                Path path = resolveModelPath(rows.get(0));
                if (Files.exists(path)) return path;
            }
        } catch (Exception ignored) {
            // Local databases from older runs may not have score_model_versions yet.
        }
        Path managed = Path.of("models", "score_model.joblib").toAbsolutePath().normalize();
        if (Files.exists(managed)) return managed;
        return Path.of("score_model.joblib").toAbsolutePath().normalize();
    }

    private Path resolveModelPath(String value) {
        Path path = Path.of(value == null || value.isBlank() ? "score_model.joblib" : value);
        if (!path.isAbsolute()) {
            path = Path.of(".").toAbsolutePath().normalize().resolve(path).normalize();
        }
        return path.toAbsolutePath().normalize();
    }

    private Map<String, Object> fallback(String message, String styleCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scored", false);
        result.put("score", null);
        result.put("confidence", 0.0);
        result.put("confidenceLevel", "LOW");
        result.put("styleCode", styleCode);
        result.put("source", "fallback");
        result.put("message", message);
        result.put("metrics", Map.of());
        result.put("handProfile", Map.of());
        result.put("reasons", List.of("评分服务不可用，本次仅展示AI试穿结果，不生成虚假分数"));
        result.put("modelSchemaVersion", "unscored-fallback");
        return result;
    }

    private String readAll(InputStream stream) throws Exception {
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
