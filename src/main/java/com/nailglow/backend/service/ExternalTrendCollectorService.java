package com.nailglow.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Service
public class ExternalTrendCollectorService {
    private static final String XHS = "xiaohongshu";
    private static final String MEITUAN = "meituan";
    private static final String REMOTE_LOGIN_DISPLAY = ":99";
    private static final int REMOTE_LOGIN_VNC_PORT = 5900;
    private static final int REMOTE_LOGIN_NOVNC_PORT = 6080;
    private static final String REMOTE_LOGIN_VIEWER_URL = "/trend-login/vnc.html?autoconnect=1&resize=remote&path=trend-login/websockify";
    private static final DateTimeFormatter BATCH_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern TITLE_NOISE = Pattern.compile("[!！?？~～|/\\\\_]+");
    private static final Pattern GENERIC_TITLE = Pattern.compile("(?i)梦中情甲|小可爱|五选一|六选一|马上做|终于|谁懂啊|真的超美|太好看|姐妹们");
    private static final List<KeywordRule> KEYWORD_RULES = List.of(
            new KeywordRule("法式", List.of("法式")),
            new KeywordRule("猫眼", List.of("猫眼")),
            new KeywordRule("显白", List.of("显白")),
            new KeywordRule("裸粉", List.of("裸粉", "裸色", "奶油粉", "蜜桃粉")),
            new KeywordRule("冰透", List.of("冰透", "清透", "水光")),
            new KeywordRule("渐变", List.of("渐变", "晕染")),
            new KeywordRule("车厘子红", List.of("车厘子", "酒红", "樱桃红")),
            new KeywordRule("小香风", List.of("小香风", "格纹")),
            new KeywordRule("珍珠", List.of("珍珠")),
            new KeywordRule("爱心", List.of("爱心")),
            new KeywordRule("多巴胺", List.of("多巴胺")),
            new KeywordRule("短甲", List.of("短甲", "短款", "中短甲")),
            new KeywordRule("清冷", List.of("清冷", "高级感", "千金")),
            new KeywordRule("夏日", List.of("夏天", "夏日"))
    );
    private static final List<String> DEFAULT_XHS_QUERIES = List.of(
            "法式美甲",
            "显白美甲"
    );

    private final JdbcTemplate jdbc;
    private final TrendMonitorAgentService trendMonitorAgentService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${nailglow.python.bin:${PYTHON_BIN:python}}")
    private String pythonBin;

    @Value("${nailglow.trend-agent.chrome-port:9233}")
    private int chromePort;

    @Value("${nailglow.trend-agent.collector-chrome-port:9234}")
    private int collectorChromePort;

    @Value("${nailglow.trend-agent.chrome-path:}")
    private String chromePath;

    @Value("${nailglow.trend-agent.chrome-profile-dir:runtime/trend-agent-chrome-profile}")
    private String chromeProfileDir;

    @Value("${nailglow.trend-agent.session-state-path:runtime/trend-agent-session/cookies.json}")
    private String sessionStatePath;

    @Value("${nailglow.trend-agent.temp-user-data-dir:runtime/trend-agent-headless-profile}")
    private String tempUserDataDir;

    @Value("${nailglow.trend-agent.proxy-server:${XHS_PROXY_SERVER:}}")
    private String configuredProxyServer;

    @Value("${nailglow.trend-agent.collect-timeout-seconds:120}")
    private int collectTimeoutSeconds;

    @Value("${nailglow.trend-agent.target-count:10}")
    private int targetCount;

    public ExternalTrendCollectorService(JdbcTemplate jdbc, TrendMonitorAgentService trendMonitorAgentService) {
        this.jdbc = jdbc;
        this.trendMonitorAgentService = trendMonitorAgentService;
    }

    public Map<String, Object> latestSnapshot() {
        String batchId = jdbc.query("""
                select batch_id
                from external_style_trend_batches
                order by captured_at desc
                limit 1
                """, (rs, rowNum) -> rs.getString("batch_id")).stream().findFirst().orElse("");
        if (!StringUtils.hasText(batchId)) {
            return emptySnapshot();
        }
        return buildSnapshot(batchId);
    }

    public Map<String, Object> refreshSnapshot() {
        cleanupBeforeRefresh();
        String batchId = "trend_" + LocalDateTime.now().format(BATCH_STAMP) + "_" + UUID.randomUUID().toString().substring(0, 8);
        List<TrendEntry> items = collectLiveEntries();
        List<Map<String, Object>> platformSummary = buildPlatformSummary(items);
        Map<String, Object> insight = trendMonitorAgentService.analyze(Map.of(
                "items", items.stream().map(this::trendEntryPayload).toList(),
                "platforms", platformSummary,
                "targetCount", targetCount
        ));
        persistBatch(batchId, items, insight, platformSummary);
        prunePreviousTrendBatches(batchId);
        return buildSnapshot(batchId);
    }

    public Map<String, Object> snapshotWithWarning(String warning) {
        Map<String, Object> data = new LinkedHashMap<>(latestSnapshot());
        data.put("degraded", true);
        data.put("warning", StringUtils.hasText(warning) ? warning : "站外热门刷新失败，已回退到最近一次可用快照。");
        return data;
    }

    private void cleanupBeforeRefresh() {
        cleanDirectoryContents(resolveTempUserDataDir());
        cleanDirectoryContents(resolveLegacyNestedRuntimeDir().resolve("trend-agent-headless-profile"));
    }

    private void clearPreviousTrendBatches() {
        jdbc.update("delete from external_style_trends");
        jdbc.update("delete from external_style_trend_batches");
    }

    private void prunePreviousTrendBatches(String keepBatchId) {
        if (!StringUtils.hasText(keepBatchId)) {
            return;
        }
        jdbc.update("delete from external_style_trends where batch_id <> ?", keepBatchId);
        jdbc.update("delete from external_style_trend_batches where batch_id <> ?", keepBatchId);
    }

    private Path resolveLegacyNestedRuntimeDir() {
        return Path.of("backend", "runtime").toAbsolutePath().normalize();
    }

    private void cleanDirectoryContents(Path directory) {
        if (directory == null || !Files.exists(directory) || !Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            stream.forEach(this::deleteRecursivelyQuietly);
        } catch (IOException ignored) {
        }
    }

    private void deleteRecursivelyQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> children = Files.list(path)) {
                    children.forEach(this::deleteRecursivelyQuietly);
                }
            }
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    public Map<String, Object> publishTrend(long trendId) {
        TrendRow row = loadTrendRow(trendId);
        if (row == null) {
            throw new IllegalArgumentException("未找到该站外热门款式");
        }
        if (row.publishedStyleId() != null && row.publishedStyleId() > 0) {
            return Map.of(
                    "trendId", trendId,
                    "publishedStyleId", row.publishedStyleId(),
                    "publishStatus", "published",
                    "styleName", row.styleName()
            );
        }

        long styleId = insertPublishedStyle(row);
        jdbc.update("""
                update external_style_trends
                set publish_status = 'published',
                    published_style_id = ?,
                    published_at = current_timestamp
                where id = ?
                """, styleId, trendId);

        return Map.of(
                "trendId", trendId,
                "publishedStyleId", styleId,
                "publishStatus", "published",
                "styleName", row.styleName()
        );
    }

    public Map<String, Object> saveSessionState() {
        Path scriptPath = resolveCollectorScriptPath();
        if (!Files.exists(scriptPath)) {
            throw new IllegalStateException("未找到登录态导出 Agent：" + scriptPath);
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(pythonBin, scriptPath.toString());
            builder.directory(resolveBackendWorkDir().toFile());
            applyCollectorEnvironment(builder);
            Process process = builder.start();
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readAllQuietly(process.getInputStream()));
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readAllQuietly(process.getErrorStream()));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", "export_session");
            payload.put("debugPort", chromePort);
            payload.put("sessionStatePath", resolveSessionStatePath().toString());
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(mapper.writeValueAsString(payload));
            }

            boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("登录态导出超时，请在扫码后的趋势浏览器中保持小红书页面打开");
            }
            String stdout = stdoutFuture.getNow("");
            String stderr = stderrFuture.getNow("");
            if (process.exitValue() != 0) {
                throw new IllegalStateException(stderr.isBlank() ? "登录态导出失败" : stderr);
            }
            if (!stdout.isBlank()) {
                mapper.readValue(stdout, new TypeReference<Map<String, Object>>() {
                });
            }
            Map<String, Object> data = new LinkedHashMap<>(sessionStatus());
            data.put("message", "登录态已保存");
            return data;
        } catch (Exception ex) {
            throw new IllegalStateException("保存小红书登录态失败：" + ex.getMessage(), ex);
        }
    }

    public Map<String, Object> sessionStatus() {
        Path sessionPath = resolveSessionStatePath();
        if (!Files.exists(sessionPath)) {
            return Map.of(
                    "exists", false,
                    "sessionStatePath", sessionPath.toString(),
                    "cookieCount", 0,
                    "exportedAt", "",
                    "loginRequired", true
            );
        }
        try {
            Map<String, Object> exported = mapper.readValue(Files.readString(sessionPath), new TypeReference<>() {
            });
            return Map.of(
                    "exists", true,
                    "sessionStatePath", sessionPath.toString(),
                    "cookieCount", rawListSize(exported.get("cookies")),
                    "exportedAt", stringValue(exported.get("exportedAt")),
                    "loginRequired", rawListSize(exported.get("cookies")) == 0
            );
        } catch (Exception ex) {
            return Map.of(
                    "exists", true,
                    "sessionStatePath", sessionPath.toString(),
                    "cookieCount", 0,
                    "exportedAt", "",
                    "loginRequired", true,
                    "error", ex.getMessage()
            );
        }
    }

    public Map<String, Object> importSessionState(Map<String, Object> body) {
        try {
            Map<String, Object> normalized = normalizeImportedSession(body);
            Path sessionPath = resolveSessionStatePath();
            Files.createDirectories(sessionPath.getParent());
            Files.writeString(sessionPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized), StandardCharsets.UTF_8);
            return sessionStatus();
        } catch (Exception ex) {
            throw new IllegalStateException("导入登录态 JSON 失败：" + ex.getMessage(), ex);
        }
    }

    public Map<String, Object> openLoginBrowser() {
        String chromeExecutable = resolveChromePath();
        if (!StringUtils.hasText(chromeExecutable)) {
            throw new IllegalStateException("未找到 Chrome，可执行文件不可用");
        }
        try {
            Path profilePath = resolveLoginBrowserProfileDir();
            Files.createDirectories(profilePath);
            if (!isWindows()) {
                prepareLinuxRemoteLoginBrowser(chromeExecutable, profilePath);
                return Map.of(
                        "opened", true,
                        "chromePort", chromePort,
                        "profileDir", profilePath.toString(),
                        "viewerUrl", REMOTE_LOGIN_VIEWER_URL,
                        "message", "已启动远程登录浏览器，新标签页将打开小红书登录窗口。"
                );
            }
            List<String> command = new ArrayList<>(List.of(
                    chromeExecutable,
                    "--remote-debugging-port=" + chromePort,
                    "--remote-allow-origins=*",
                    "--user-data-dir=" + profilePath,
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--new-window"
            ));
            String proxyServer = resolveProxyServer();
            if (StringUtils.hasText(proxyServer)) {
                command.add("--proxy-server=" + proxyServer);
            }
            command.add("https://www.xiaohongshu.com/explore");
            new ProcessBuilder(command).start();
            return Map.of(
                    "opened", true,
                    "chromePort", chromePort,
                    "profileDir", profilePath.toString(),
                    "message", "已打开登录浏览器，请商家登录后再点击“保存登录态”。"
            );
        } catch (Exception ex) {
            throw new IllegalStateException("打开登录浏览器失败：" + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> buildSnapshot(String batchId) {
        List<Map<String, Object>> items = loadBatchItems(batchId);
        Map<String, Object> meta = loadBatchMeta(batchId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batchId);
        data.put("refreshedAt", String.valueOf(meta.getOrDefault("capturedAt", "")));
        data.put("items", items);
        data.put("platforms", listValue(meta.get("platforms")));
        data.put("insight", normalizeInsight(meta.get("insight"), items));
        return data;
    }

    private Map<String, Object> emptySnapshot() {
        return Map.of(
                "batchId", "",
                "refreshedAt", "",
                "items", List.of(),
                "platforms", buildPlatformSummary(List.of()),
                "insight", Map.of(
                        "summary", "点击“刷新站外热门”后，后台 AI Agent 会复用已保存的小红书登录态抓取真实热门美甲样本，并给出运营建议。",
                        "signals", List.of(),
                        "actions", List.of("先完成一次抓取，再把高点赞款式一键上架到站内款式库。"),
                        "operationScript", "站外热门样本抓取完成后，系统会自动生成一段可直接用于首页专题说明、客服推荐和活动预告的运营话术。"
                )
        );
    }

    private List<TrendEntry> collectLiveEntries() {
        try {
            ensureSessionStateAvailable();
            List<String> queries = buildSearchQueries();
            List<CollectorItem> xhsItems = loadCollectorItems(queries);
            if (xhsItems.isEmpty()) {
                return List.of();
            }
            List<CollectorItem> ranked = xhsItems.stream()
                    .sorted(Comparator.comparingLong(CollectorItem::likeCount).reversed())
                    .limit(Math.max(1, targetCount))
                    .toList();

            long topLikeCount = ranked.stream().mapToLong(CollectorItem::likeCount).max().orElse(1L);
            List<TrendEntry> entries = new ArrayList<>();
            for (int index = 0; index < ranked.size(); index++) {
                CollectorItem item = ranked.get(index);
                List<String> keywords = detectKeywords(item.title() + " " + item.queryKeyword());
                String styleName = deriveStyleName(item.title(), item.queryKeyword(), keywords);
                double heatScore = normalizeHeatScore(item.likeCount(), topLikeCount);
                entries.add(new TrendEntry(
                        XHS,
                        platformLabel(XHS),
                        limit(item.title(), 120),
                        limit(styleName, 52),
                        limit(item.authorName(), 80),
                        normalizeExternalUrl(item.sourceUrl()),
                        normalizeExternalUrl(item.imageUrl()),
                        buildSnippet(item),
                        String.join(",", keywords),
                        buildNote(item),
                        item.likeCount(),
                        item.likeText(),
                        heatScore,
                        index + 1,
                        "agent"
                ));
            }
            return entries;
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private List<CollectorItem> loadCollectorItems(List<String> queries) throws Exception {
        Path scriptPath = resolveCollectorScriptPath();
        if (!Files.exists(scriptPath)) {
            throw new IllegalStateException("未找到站外抓取 Agent：" + scriptPath);
        }
        if (!isWindows()) {
            ensureLinuxVirtualDisplayReady();
        }

        ProcessBuilder builder = new ProcessBuilder(pythonBin, scriptPath.toString());
        builder.directory(resolveBackendWorkDir().toFile());
        applyCollectorEnvironment(builder);
        if (!isWindows()) {
            builder.environment().put("DISPLAY", REMOTE_LOGIN_DISPLAY);
        }
        Process process = builder.start();
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readAllQuietly(process.getInputStream()));
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readAllQuietly(process.getErrorStream()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "collect");
        payload.put("debugPort", collectorChromePort);
        payload.put("targetCount", Math.max(targetCount, 10));
        payload.put("queries", queries);
        payload.put("sessionStatePath", resolveSessionStatePath().toString());
        payload.put("tempUserDataDir", resolveTempUserDataDir().toString());
        payload.put("chromeExecutable", resolveChromePath());
        payload.put("headless", isWindows());

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(mapper.writeValueAsString(payload));
        }

        boolean finished = process.waitFor(Math.max(45, collectTimeoutSeconds), java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("站外抓取 Agent 执行超时");
        }

        String stdout = stdoutFuture.getNow("");
        String stderr = stderrFuture.getNow("");
        if (process.exitValue() != 0) {
            throw new IllegalStateException(stderr.isBlank() ? "站外抓取 Agent 执行失败" : stderr);
        }

        Map<String, Object> result = mapper.readValue(stdout.isBlank() ? "{}" : stdout, new TypeReference<>() {
        });
        List<Map<String, Object>> rawItems = listValue(result.get("items"));
        Map<String, CollectorItem> deduped = new LinkedHashMap<>();
        for (Map<String, Object> raw : rawItems) {
            CollectorItem item = normalizeCollectorItem(raw);
            if (item == null || !StringUtils.hasText(item.sourceUrl())) {
                continue;
            }
            CollectorItem existing = deduped.get(item.sourceUrl());
            if (existing == null || item.likeCount() > existing.likeCount()) {
                deduped.put(item.sourceUrl(), item);
            }
        }
        return deduped.values().stream()
                .filter(item -> StringUtils.hasText(item.title()) && StringUtils.hasText(item.imageUrl()))
                .toList();
    }

    private List<String> buildSearchQueries() {
        List<Map<String, Object>> styleSeeds = loadStyleSeeds();
        List<String> queries = trendMonitorAgentService.planQueries(Map.of(
                "styles", styleSeeds,
                "seasonHint", currentSeasonHint()
        ));
        if (queries.isEmpty()) {
            return DEFAULT_XHS_QUERIES;
        }
        return queries.stream().filter(StringUtils::hasText).distinct().limit(8).toList();
    }

    private List<Map<String, Object>> loadStyleSeeds() {
        return jdbc.query("""
                select name, tags, try_count
                from nail_styles
                where status = '上架'
                order by try_count desc, avg_score desc, updated_at desc
                limit 12
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", rs.getString("name"));
            row.put("tags", splitKeywords(rs.getString("tags")));
            row.put("tryCount", rs.getInt("try_count"));
            return row;
        });
    }

    private CollectorItem normalizeCollectorItem(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        String title = stringValue(raw.get("title"));
        String queryKeyword = stringValue(raw.get("queryKeyword"));
        List<String> keywords = detectKeywords(title + " " + queryKeyword);
        if (!titleAllowsPublish(title, keywords)) {
            return null;
        }
        long likeCount = longValue(raw.get("likeCount"));
        return new CollectorItem(
                title,
                stringValue(raw.get("authorName")),
                stringValue(raw.get("noteDate")),
                normalizeExternalUrl(stringValue(raw.get("sourceUrl"))),
                normalizeExternalUrl(stringValue(raw.get("imageUrl"))),
                likeCount,
                stringValue(raw.get("likeText")),
                queryKeyword
        );
    }

    private boolean titleAllowsPublish(String title, List<String> keywords) {
        if (!StringUtils.hasText(title)) {
            return false;
        }
        String normalized = title.toLowerCase(Locale.ROOT);
        if (normalized.contains("广告") || normalized.contains("团购")) {
            return false;
        }
        return normalized.contains("美甲")
                || normalized.contains("甲")
                || !keywords.isEmpty();
    }

    private void ensureSessionStateAvailable() {
        Path sessionPath = resolveSessionStatePath();
        if (Files.exists(sessionPath)) {
            return;
        }
        try {
            saveSessionState();
        } catch (Exception ex) {
            throw new IllegalStateException("未找到可用登录态，请先在后台导入商家自己的 session JSON，或打开登录浏览器完成登录后保存登录态。", ex);
        }
    }

    private String resolveChromePath() {
        if (StringUtils.hasText(chromePath) && Files.exists(Path.of(chromePath))) {
            return chromePath;
        }
        List<String> candidates = isWindows()
                ? List.of(
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                System.getenv("LOCALAPPDATA") == null ? "" : System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe"
        )
                : List.of(
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium-browser",
                "/usr/bin/chromium"
        );
        return candidates.stream()
                .filter(StringUtils::hasText)
                .filter(path -> Files.exists(Path.of(path)))
                .findFirst()
                .orElse("");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private void ensureLinuxVirtualDisplayReady() throws IOException, InterruptedException {
        String xvfbExecutable = requireExecutable("/usr/bin/Xvfb", "Xvfb");
        Path runtimeDir = resolveProjectPath("runtime/trend-agent-remote-login");
        Files.createDirectories(runtimeDir);
        Path xvfbLog = runtimeDir.resolve("xvfb.log");
        startBackgroundShellIfMissing(
                "test -S /tmp/.X11-unix/X99",
                shellQuote(xvfbExecutable) + " " + REMOTE_LOGIN_DISPLAY + " -screen 0 1440x900x24 -ac +extension RANDR",
                xvfbLog
        );
        Thread.sleep(600);
    }

    private void prepareLinuxRemoteLoginBrowser(String chromeExecutable, Path profilePath) throws IOException, InterruptedException {
        String x11vncExecutable = requireExecutable("/usr/bin/x11vnc", "x11vnc");
        String websockifyExecutable = requireExecutable("/usr/bin/websockify", "websockify");
        Path noVncDir = Path.of("/usr/share/novnc");
        if (!Files.isDirectory(noVncDir)) {
            throw new IllegalStateException("未找到 noVNC 静态文件，请先安装 novnc");
        }

        Path runtimeDir = resolveProjectPath("runtime/trend-agent-remote-login");
        Files.createDirectories(runtimeDir);
        Path x11vncLog = runtimeDir.resolve("x11vnc.log");
        Path websockifyLog = runtimeDir.resolve("websockify.log");
        Path chromeLog = runtimeDir.resolve("chrome.log");

        ensureLinuxVirtualDisplayReady();
        startBackgroundShellIfMissing(
                "ss -ltn | grep -q ':5900 '",
                shellQuote(x11vncExecutable) + " -display " + REMOTE_LOGIN_DISPLAY + " -forever -shared -rfbport " + REMOTE_LOGIN_VNC_PORT + " -localhost -nopw",
                x11vncLog
        );
        startBackgroundShellIfMissing(
                "ss -ltn | grep -q ':6080 '",
                shellQuote(websockifyExecutable) + " --web " + shellQuote(noVncDir.toString()) + " " + REMOTE_LOGIN_NOVNC_PORT + " localhost:" + REMOTE_LOGIN_VNC_PORT,
                websockifyLog
        );

        Thread.sleep(1200);

        List<String> command = new ArrayList<>(List.of(
                chromeExecutable,
                "--remote-debugging-port=" + chromePort,
                "--remote-allow-origins=*",
                "--user-data-dir=" + profilePath,
                "--no-first-run",
                "--no-default-browser-check",
                "--new-window",
                "--no-sandbox",
                "--disable-dev-shm-usage"
        ));
        String proxyServer = resolveProxyServer();
        if (StringUtils.hasText(proxyServer)) {
            command.add("--proxy-server=" + proxyServer);
        }
        command.add("https://www.xiaohongshu.com/explore");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("DISPLAY", REMOTE_LOGIN_DISPLAY);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(chromeLog.toFile()));
        builder.start();
    }

    private String requireExecutable(String path, String label) {
        if (!Files.isExecutable(Path.of(path))) {
            throw new IllegalStateException("未找到 " + label + "，请先在服务器安装对应组件");
        }
        return path;
    }

    private void startBackgroundShellIfMissing(String checkCommand, String command, Path logPath) throws IOException, InterruptedException {
        String shellCommand = checkCommand
                + " || nohup " + command + " >> " + shellQuote(logPath.toString()) + " 2>&1 &";
        Process process = new ProcessBuilder("bash", "-lc", shellCommand)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()))
                .start();
        process.waitFor();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void applyCollectorEnvironment(ProcessBuilder builder) {
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUTF8", "1");
        String proxyServer = resolveProxyServer();
        if (StringUtils.hasText(proxyServer)) {
            builder.environment().put("XHS_PROXY_SERVER", proxyServer);
        }
    }

    private String resolveProxyServer() {
        if (StringUtils.hasText(configuredProxyServer)) {
            return configuredProxyServer.trim();
        }
        return "";
    }

    private Path resolveCollectorScriptPath() {
        Path direct = Path.of("src", "main", "python", "xhs_trend_collector.py").toAbsolutePath().normalize();
        if (Files.exists(direct)) {
            return direct;
        }
        Path backendRelative = Path.of("backend", "src", "main", "python", "xhs_trend_collector.py").toAbsolutePath().normalize();
        if (Files.exists(backendRelative)) {
            return backendRelative;
        }
        return direct;
    }

    private Path resolveBackendWorkDir() {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve(Path.of("src", "main", "python", "xhs_trend_collector.py")))) {
            return cwd;
        }
        Path backendDir = cwd.resolve("backend").normalize();
        if (Files.exists(backendDir.resolve(Path.of("src", "main", "python", "xhs_trend_collector.py")))) {
            return backendDir;
        }
        return cwd;
    }

    private Path resolveProjectPath(String configuredPath) {
        Path configured = Path.of(configuredPath);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        Path direct = configured.toAbsolutePath().normalize();
        if (Files.exists(direct)) {
            return direct;
        }
        Path backendRelative = Path.of("backend").resolve(configured).toAbsolutePath().normalize();
        if (Files.exists(backendRelative) || Files.exists(Path.of("backend", "src", "main", "python", "xhs_trend_collector.py"))) {
            return backendRelative;
        }
        return direct;
    }

    private Path resolveSessionStatePath() {
        return resolveProjectPath(sessionStatePath);
    }

    private Path resolveTempUserDataDir() {
        return resolveProjectPath(tempUserDataDir);
    }

    private Path resolveLoginBrowserProfileDir() {
        return resolveProjectPath(chromeProfileDir);
    }

    private void persistBatch(String batchId,
                              List<TrendEntry> items,
                              Map<String, Object> insight,
                              List<Map<String, Object>> platformSummary) {
        for (TrendEntry item : items) {
            jdbc.update("""
                    insert into external_style_trends(
                      batch_id, platform, source_title, style_name, author_name, source_url, image_url, source_snippet,
                      keywords, note, like_count, like_text, heat_score, rank_no, capture_method, publish_status, captured_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', current_timestamp)
                    """,
                    batchId,
                    item.platform(),
                    item.sourceTitle(),
                    item.styleName(),
                    item.authorName(),
                    item.sourceUrl(),
                    item.imageUrl(),
                    item.sourceSnippet(),
                    item.keywords(),
                    item.note(),
                    item.likeCount(),
                    item.likeText(),
                    item.heatScore(),
                    item.rankNo(),
                    item.captureMethod());
        }

        jdbc.update("""
                insert into external_style_trend_batches(
                  batch_id, platform_summary_json, insight_summary, insight_signals_json, insight_actions_json, insight_script_text, captured_at
                ) values (?, ?, ?, ?, ?, ?, current_timestamp)
                """,
                batchId,
                writeJson(platformSummary),
                stringValue(insight.get("summary")),
                writeJson(listValue(insight.get("signals"))),
                writeJson(stringList(insight.get("actions"))),
                stringValue(insight.get("operationScript")));
    }

    private List<Map<String, Object>> loadBatchItems(String batchId) {
        return jdbc.query("""
                select *
                from external_style_trends
                where batch_id = ?
                order by rank_no asc, id asc
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("platform", rs.getString("platform"));
            row.put("platformLabel", platformLabel(rs.getString("platform")));
            row.put("sourceTitle", rs.getString("source_title"));
            row.put("styleName", rs.getString("style_name"));
            row.put("authorName", rs.getString("author_name"));
            row.put("sourceUrl", rs.getString("source_url"));
            row.put("imageUrl", rs.getString("image_url"));
            row.put("sourceSnippet", rs.getString("source_snippet"));
            row.put("keywords", splitKeywords(rs.getString("keywords")));
            row.put("note", rs.getString("note"));
            row.put("likeCount", rs.getLong("like_count"));
            row.put("likeText", rs.getString("like_text"));
            row.put("heatScore", rs.getDouble("heat_score"));
            row.put("rankNo", rs.getInt("rank_no"));
            row.put("captureMethod", rs.getString("capture_method"));
            row.put("publishStatus", rs.getString("publish_status"));
            row.put("publishedStyleId", rs.getObject("published_style_id"));
            row.put("publishedAt", rs.getTimestamp("published_at") == null ? "" : String.valueOf(rs.getTimestamp("published_at").toLocalDateTime()));
            row.put("capturedAt", String.valueOf(rs.getTimestamp("captured_at").toLocalDateTime()));
            return row;
        }, batchId);
    }

    private Map<String, Object> loadBatchMeta(String batchId) {
        List<Map<String, Object>> rows = jdbc.query("""
                select *
                from external_style_trend_batches
                where batch_id = ?
                limit 1
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("platforms", readJsonObjectList(rs.getString("platform_summary_json")));
            row.put("insight", Map.of(
                    "summary", stringValue(rs.getString("insight_summary")),
                    "signals", readJsonObjectList(rs.getString("insight_signals_json")),
                    "actions", readJsonStringList(rs.getString("insight_actions_json")),
                    "operationScript", stringValue(rs.getString("insight_script_text"))
            ));
            row.put("capturedAt", String.valueOf(rs.getTimestamp("captured_at").toLocalDateTime()));
            return row;
        }, batchId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private TrendRow loadTrendRow(long trendId) {
        List<TrendRow> rows = jdbc.query("""
                select *
                from external_style_trends
                where id = ?
                limit 1
                """, (rs, rowNum) -> new TrendRow(
                rs.getLong("id"),
                rs.getString("platform"),
                rs.getString("source_title"),
                rs.getString("style_name"),
                rs.getString("author_name"),
                rs.getString("source_url"),
                rs.getString("image_url"),
                rs.getString("source_snippet"),
                rs.getString("keywords"),
                rs.getString("note"),
                rs.getLong("like_count"),
                rs.getString("like_text"),
                (Long) rs.getObject("published_style_id")
        ), trendId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long insertPublishedStyle(TrendRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String styleCode = "external_" + row.id();
        String tags = normalizeList(row.keywords());
        String tag = platformLabel(row.platform()) + " / 站外热门";
        String description = limit(firstNonBlank(
                row.styleName() + " · " + row.sourceTitle(),
                row.note(),
                "从站外热门监控一键上架的款式。"), 360);
        double avgScore = Math.min(98, 88 + Math.min(8, splitKeywords(row.keywords()).size()));

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into nail_styles(style_code, name, tag, tags, description, image_url, colors, status, try_count, avg_score)
                    values (?, ?, ?, ?, ?, ?, ?, '上架', 0, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, styleCode);
            ps.setString(2, row.styleName());
            ps.setString(3, tag);
            ps.setString(4, tags);
            ps.setString(5, description);
            ps.setString(6, row.imageUrl());
            ps.setString(7, paletteForKeywords(splitKeywords(row.keywords())));
            ps.setDouble(8, avgScore);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("站外热门上架失败");
        }
        return key.longValue();
    }

    private List<Map<String, Object>> buildPlatformSummary(List<TrendEntry> items) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String platform : List.of(XHS, MEITUAN)) {
            long count = items.stream().filter(item -> platform.equals(item.platform())).count();
            rows.add(Map.of(
                    "platform", platform,
                    "label", platformLabel(platform),
                    "count", count
            ));
        }
        return rows;
    }

    private Map<String, Object> normalizeInsight(Object rawInsight, List<Map<String, Object>> items) {
        Map<String, Object> insight = mapValue(rawInsight);
        Map<String, Object> heuristic = heuristicInsight(items);
        if (StringUtils.hasText(stringValue(insight.get("summary")))) {
            String operationScript = firstNonBlank(
                    stringValue(insight.get("operationScript")),
                    stringValue(insight.get("script")),
                    heuristic.get("operationScript") == null ? "" : String.valueOf(heuristic.get("operationScript"))
            );
            return Map.of(
                    "summary", stringValue(insight.get("summary")),
                    "signals", listValue(insight.get("signals")),
                    "actions", stringList(insight.get("actions")),
                    "operationScript", operationScript
            );
        }
        return heuristic;
    }

    private Map<String, Object> heuristicInsight(List<Map<String, Object>> items) {
        Map<String, Integer> keywordCounts = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            for (String keyword : splitKeywords(item.get("keywords"))) {
                keywordCounts.merge(keyword, 1, Integer::sum);
            }
        }
        List<String> topKeywords = keywordCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
        List<Map<String, Object>> signals = items.stream()
                .limit(4)
                .map(item -> {
                    Map<String, Object> signal = new LinkedHashMap<>();
                    signal.put("label", stringValue(item.get("styleName")));
                    signal.put("platform", stringValue(item.get("platformLabel")));
                    signal.put("value", "点赞 " + firstNonBlank(stringValue(item.get("likeText")), String.valueOf(item.get("likeCount"))));
                    return signal;
                })
                .toList();
        List<String> actions = new ArrayList<>();
        if (!topKeywords.isEmpty()) {
            actions.add("把“" + topKeywords.get(0) + "”相关款式前置到用户端首屏和试穿推荐位，优先承接站外热度。");
        }
        if (!items.isEmpty()) {
            actions.add("优先把点赞最高的 2 到 3 个站外热门款式上架到站内款式库，并补充同风格衍生款。");
        }
        if (actions.isEmpty()) {
            actions.add("先完成一次真实抓取，后台 AI Agent 才能输出更稳定的运营策略。");
        }
        String summary = topKeywords.isEmpty()
                ? "当前还没有抓到足够的站外样本，请检查登录态后重新刷新。"
                : "站外热门关键词集中在“" + String.join(" / ", topKeywords) + "”，建议优先承接高点赞的显白、法式和清透方向。";
        String operationScript = topKeywords.isEmpty()
                ? "当前站外样本还不足，建议先完成一次真实抓取，再围绕高点赞款式整理首页推荐、客服推荐和活动预告的话术。"
                : "本轮站外热度集中在“" + String.join("、", topKeywords) + "”方向，建议把点赞最高的 2 到 3 款放进首页专题和试穿推荐位，同时在客服推荐中统一强调显白、通勤、拍照出片这些高频卖点，引导用户从种草内容直接进入试穿、收藏和预约转化。";
        return Map.of("summary", summary, "signals", signals, "actions", actions, "operationScript", operationScript);
    }

    private Map<String, Object> trendEntryPayload(TrendEntry item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", item.platform());
        payload.put("platformLabel", item.platformLabel());
        payload.put("title", item.sourceTitle());
        payload.put("styleName", item.styleName());
        payload.put("authorName", item.authorName());
        payload.put("keywords", splitKeywords(item.keywords()));
        payload.put("note", item.note());
        payload.put("likeCount", item.likeCount());
        payload.put("likeText", item.likeText());
        payload.put("heatScore", item.heatScore());
        return payload;
    }

    private String buildSnippet(CollectorItem item) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(item.authorName())) {
            parts.add("作者 " + item.authorName());
        }
        if (StringUtils.hasText(item.noteDate())) {
            parts.add(item.noteDate());
        }
        if (StringUtils.hasText(item.queryKeyword())) {
            parts.add("来源词 " + item.queryKeyword());
        }
        return limit(String.join(" · ", parts), 180);
    }

    private String buildNote(CollectorItem item) {
        String likeText = StringUtils.hasText(item.likeText()) ? item.likeText() : String.valueOf(item.likeCount());
        return "AI Agent 实时抓取 · 点赞 " + likeText + (StringUtils.hasText(item.queryKeyword()) ? " · 检索词 " + item.queryKeyword() : "");
    }

    private double normalizeHeatScore(long likeCount, long topLikeCount) {
        if (topLikeCount <= 0) {
            return 60;
        }
        double ratio = Math.max(0.15, Math.min(1.0, likeCount * 1.0 / topLikeCount));
        return Math.round((60 + ratio * 40) * 10.0) / 10.0;
    }

    private String deriveStyleName(String title, String queryKeyword, List<String> keywords) {
        String cleaned = TITLE_NOISE.matcher(firstNonBlank(title, "")).replaceAll(" ").replaceAll("\\s+", " ").trim();
        boolean generic = GENERIC_TITLE.matcher(cleaned).find() || cleaned.length() < 6;
        if (!generic && (cleaned.contains("美甲") || keywords.size() >= 2)) {
            return limit(cleaned, 30);
        }
        if (!keywords.isEmpty()) {
            String joined = keywords.stream().limit(2).reduce((left, right) -> left + right).orElse("热门");
            return limit(joined + "美甲", 30);
        }
        return limit(firstNonBlank(queryKeyword, cleaned, "热门美甲"), 30);
    }

    private List<String> detectKeywords(String text) {
        String normalized = firstNonBlank(text, "").toLowerCase(Locale.ROOT);
        Set<String> found = new LinkedHashSet<>();
        for (KeywordRule rule : KEYWORD_RULES) {
            boolean matched = rule.tokens().stream().anyMatch(token -> normalized.contains(token.toLowerCase(Locale.ROOT)));
            if (matched) {
                found.add(rule.label());
            }
        }
        return new ArrayList<>(found);
    }

    private List<String> splitKeywords(Object raw) {
        if (raw == null) {
            return List.of();
        }
        return Arrays.stream(String.valueOf(raw).split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String platformLabel(String platform) {
        return XHS.equals(platform) ? "小红书" : MEITUAN.equals(platform) ? "美团" : "站外平台";
    }

    private String paletteForKeywords(List<String> keywords) {
        String joined = String.join(",", keywords);
        if (joined.contains("车厘子红")) return "#7f1828,#f5c6ce,#d43f5f";
        if (joined.contains("清冷") || joined.contains("冰透")) return "#dbe7f6,#f9fbff,#a9bedb";
        if (joined.contains("法式") || joined.contains("裸粉")) return "#f5d9d7,#fff6f8,#d6a198";
        if (joined.contains("多巴胺")) return "#ffb7c4,#ffd86f,#8ec5ff";
        return "#f5d3dc,#fff8fb,#d58ca0";
    }

    private String normalizeList(String value) {
        return Arrays.stream(firstNonBlank(value, "").replace("，", ",").replace("、", ",").split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(6)
                .reduce((left, right) -> left + "," + right)
                .orElse("站外热门");
    }

    private String normalizeExternalUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("data:") || trimmed.length() > 1800) {
            return "";
        }
        return trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.length() <= maxLength ? value.trim() : value.substring(0, maxLength).trim();
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> normalizeImportedSession(Map<String, Object> body) throws Exception {
        Object sessionJson = body.get("sessionJson");
        Map<String, Object> raw = body;
        if (sessionJson instanceof String text && StringUtils.hasText(text)) {
            raw = parseSessionJsonText(text);
        }
        List<?> cookies = raw.get("cookies") instanceof List<?> list ? list : List.of();
        List<Map<String, Object>> normalizedCookies = new ArrayList<>();
        for (Object item : cookies) {
            if (!(item instanceof Map<?, ?> cookie)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", stringValue(cookie.get("name")));
            row.put("value", stringValue(cookie.get("value")));
            row.put("domain", stringValue(cookie.get("domain")));
            row.put("path", firstNonBlank(stringValue(cookie.get("path")), "/"));
            row.put("secure", Boolean.parseBoolean(String.valueOf(cookie.containsKey("secure") ? cookie.get("secure") : true)));
            row.put("httpOnly", Boolean.parseBoolean(String.valueOf(cookie.containsKey("httpOnly") ? cookie.get("httpOnly") : false)));
            String expires = stringValue(cookie.get("expires"));
            if (StringUtils.hasText(expires)) {
                try {
                    row.put("expires", Double.parseDouble(expires));
                } catch (NumberFormatException ignored) {
                }
            }
            String sameSite = stringValue(cookie.get("sameSite"));
            if (StringUtils.hasText(sameSite)) {
                row.put("sameSite", sameSite);
            }
            if (StringUtils.hasText(stringValue(row.get("name"))) && StringUtils.hasText(stringValue(row.get("value")))) {
                normalizedCookies.add(row);
            }
        }
        if (normalizedCookies.isEmpty()) {
            throw new IllegalStateException("session JSON 中没有可用 cookies");
        }
        return Map.of(
                "exportedAt", firstNonBlank(stringValue(raw.get("exportedAt")), LocalDateTime.now().toString()),
                "cookies", normalizedCookies
        );
    }

    private Map<String, Object> parseSessionJsonText(String rawText) throws Exception {
        String text = firstNonBlank(rawText, "").trim();
        if (!StringUtils.hasText(text)) {
            return Map.of();
        }
        try {
            return normalizeSessionPayload(mapper.readValue(text, Object.class));
        } catch (Exception ignored) {
        }
        String sanitized = text
                .replaceFirst("^\\s*(export\\s+default|module\\.exports\\s*=|window\\.[A-Za-z0-9_$]+\\s*=|const\\s+[A-Za-z0-9_$]+\\s*=|let\\s+[A-Za-z0-9_$]+\\s*=|var\\s+[A-Za-z0-9_$]+\\s*=)\\s*", "")
                .replaceFirst(";\\s*$", "")
                .trim();
        try {
            return normalizeSessionPayload(mapper.readValue(sanitized, Object.class));
        } catch (Exception ignored) {
        }
        int arrayStart = sanitized.indexOf('[');
        int arrayEnd = sanitized.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            String arrayText = sanitized.substring(arrayStart, arrayEnd + 1);
            try {
                Object parsed = mapper.readValue(arrayText, Object.class);
                return normalizeSessionPayload(parsed);
            } catch (Exception ignored) {
            }
        }
        int objectStart = sanitized.indexOf('{');
        int objectEnd = sanitized.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            String objectText = sanitized.substring(objectStart, objectEnd + 1);
            return normalizeSessionPayload(mapper.readValue(objectText, Object.class));
        }
        throw new IllegalStateException("无法解析导入内容，请提供 JSON 或可提取 cookies 数组的 cookies.js");
    }

    private Map<String, Object> normalizeSessionPayload(Object payload) {
        if (payload instanceof List<?> list) {
            return Map.of("cookies", list);
        }
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            Object cookies = normalized.get("cookies");
            if (cookies instanceof List<?>) {
                return normalized;
            }
            if (normalized.get("data") instanceof Map<?, ?> dataMap) {
                Map<String, Object> data = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
                    data.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                if (data.get("cookies") instanceof List<?>) {
                    return data;
                }
            }
            return normalized;
        }
        return Map.of();
    }

    private String currentSeasonHint() {
        int month = LocalDateTime.now().getMonthValue();
        if (month >= 3 && month <= 5) return "春季";
        if (month >= 6 && month <= 8) return "夏季";
        if (month >= 9 && month <= 11) return "秋季";
        return "冬季";
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private List<Map<String, Object>> readJsonObjectList(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return mapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> readJsonStringList(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return mapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
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

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .toList();
    }

    private int rawListSize(Object value) {
        return value instanceof List<?> raw ? raw.size() : 0;
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

    private String readAllQuietly(InputStream stream) {
        try {
            return readAll(stream);
        } catch (Exception ex) {
            return "";
        }
    }

    private record KeywordRule(String label, List<String> tokens) {
    }

    private record CollectorItem(String title,
                                 String authorName,
                                 String noteDate,
                                 String sourceUrl,
                                 String imageUrl,
                                 long likeCount,
                                 String likeText,
                                 String queryKeyword) {
    }

    private record TrendEntry(String platform,
                              String platformLabel,
                              String sourceTitle,
                              String styleName,
                              String authorName,
                              String sourceUrl,
                              String imageUrl,
                              String sourceSnippet,
                              String keywords,
                              String note,
                              long likeCount,
                              String likeText,
                              double heatScore,
                              int rankNo,
                              String captureMethod) {
    }

    private record TrendRow(long id,
                            String platform,
                            String sourceTitle,
                            String styleName,
                            String authorName,
                            String sourceUrl,
                            String imageUrl,
                            String sourceSnippet,
                            String keywords,
                            String note,
                            long likeCount,
                            String likeText,
                            Long publishedStyleId) {
    }
}
