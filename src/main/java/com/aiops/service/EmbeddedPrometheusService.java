package com.aiops.service;

import com.aiops.common.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 内置 Prometheus：
 * - 随部署包附带 prometheus/linux-amd64 或 windows-amd64 二进制
 * - 启动时按需拉起进程，配置存 data/prometheus/prometheus.yml
 * - 系统设置页可切换 内置/外部、编辑 YAML、管理静态抓取任务、热加载、查看 targets
 * - 内置模式自动把 prometheus.url 指向本机实例；外部模式保留用户填写的地址
 */
@Service
public class EmbeddedPrometheusService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPrometheusService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final ConfigService config;

    @Value("${app.prometheus.embedded.enabled:true}") private boolean enabled;
    @Value("${app.prometheus.embedded.binary-dir:./prometheus}") private String binaryDir;
    @Value("${app.prometheus.embedded.data-dir:./data/prometheus}") private String dataDir;
    @Value("${app.prometheus.embedded.config-file:./data/prometheus/prometheus.yml}") private String configFile;
    @Value("${app.prometheus.embedded.port:9090}") private int port;
    @Value("${app.prometheus.embedded.listen-address:0.0.0.0}") private String listenAddress;
    @Value("${app.prometheus.embedded.retention:15d}") private String retention;
    @Value("${app.prometheus.embedded.extra-args:}") private String extraArgs;
    @Value("${app.prometheus.embedded.public-url:}") private String publicUrl;
    @Value("${app.agents.sd-token:}") private String sdToken;
    @Value("${server.port:8181}") private int serverPort;

    private final RestTemplate rest;
    private volatile Process process;
    private volatile String lastError = "";
    private volatile long startTime = 0;

    public EmbeddedPrometheusService(ConfigService config) {
        this.config = config;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(5000);
        this.rest = new RestTemplate(f);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) return;
        if (!"embedded".equalsIgnoreCase(mode())) {
            log.info("Prometheus 使用外部实例：{}", config.getString("prometheus.url", ""));
            return;
        }
        try {
            Map<String, Object> st = start();
            if (Boolean.TRUE.equals(st.get("running"))) {
                log.info("内置 Prometheus 已启动：{}（配置 {}）", localUrl(), configPath());
            } else {
                log.warn("内置 Prometheus 未启动：{}", st.get("message"));
            }
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("内置 Prometheus 启动失败：{}", lastError);
        }
    }

    @PreDestroy
    public void shutdown() {
        stopProcessOnly();
    }

    public String mode() {
        return config.getString("prometheus.mode", "embedded");
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Path bin = resolveBinary();
        boolean healthy = healthy();
        boolean running = process != null && process.isAlive();
        String version = "";
        if (healthy) version = remoteVersion();
        if (version.isEmpty()) version = readVersionFile();
        out.put("mode", mode());
        out.put("enabled", enabled);
        out.put("running", running);
        out.put("healthy", healthy);
        out.put("binaryFound", bin != null);
        out.put("binaryPath", bin == null ? "" : bin.toString());
        Path archive = findArchive();
        out.put("archiveFound", archive != null);
        out.put("archivePath", archive == null ? "" : archive.toString());
        out.put("searchDirs", searchDirsText());
        out.put("canStart", bin != null || archive != null);
        // 端口上已有可用的 Prometheus（例如后端被强杀后遗留的子进程）时标记为复用
        out.put("reused", !running && healthy);
        out.put("serving", healthy);
        out.put("version", version);
        out.put("url", "embedded".equalsIgnoreCase(mode()) ? localUrl() : config.getString("prometheus.url", ""));
        out.put("localUrl", localUrl());
        out.put("externalUrl", externalUrl());
        out.put("publicUrl", publicUrl == null ? "" : publicUrl);
        out.put("configFile", configPath().toString());
        out.put("dataDir", dataPath().toString());
        out.put("port", port);
        out.put("listenAddress", listenAddress);
        out.put("startedAt", startTime <= 0 ? "" : java.time.Instant.ofEpochMilli(startTime).toString());
        out.put("pid", process == null ? null : processPid(process));
        out.put("message", lastError == null ? "" : lastError);
        return out;
    }

    public synchronized Map<String, Object> start() {
        if (!enabled) throw new ServiceException(400, "内置 Prometheus 未启用（app.prometheus.embedded.enabled=false）");
        if (!"embedded".equalsIgnoreCase(mode())) throw new ServiceException(400, "当前为外部 Prometheus 模式，请先切换为内置模式");
        lastError = "";
        maybeImportExternalTargets();
        if (process != null && process.isAlive()) {
            if (!healthy()) lastError = "进程在运行但健康检查未通过，可查看 logs/prometheus.log";
            return status();
        }
        // 端口上已有可用的 Prometheus（常见于后端被强杀后遗留的子进程）：直接复用，避免重复拉起导致端口冲突
        if (healthy()) {
            if (startTime <= 0) startTime = System.currentTimeMillis();
            config.setString("prometheus.url", localUrl());
            config.setString("metrics.source", "prometheus");
            log.info("检测到 {} 已有 Prometheus 实例，直接复用", localUrl());
            Map<String, Object> st = status();
            st.put("reused", true);
            return st;
        }
        Path bin = resolveBinary();
        if (bin == null && extractArchiveIfNeeded()) bin = resolveBinary();
        if (bin == null) {
            lastError = "未找到内置 Prometheus 二进制（已查找 " + searchDirsText() + "）；"
                    + "可将 prometheus-*.linux-amd64.tar.gz 放到 data/prometheus 后点击「一键启动」自动解压";
            return status();
        }
        try {
            try { bin.toFile().setExecutable(true); } catch (Exception ignore) { }
            ensureConfig();
            Path logFile = Paths.get("logs", "prometheus.log").toAbsolutePath();
            Files.createDirectories(logFile.getParent());
            List<String> cmd = new ArrayList<String>();
            cmd.add(bin.toString());
            cmd.add("--config.file=" + configPath());
            cmd.add("--storage.tsdb.path=" + dataPath());
            cmd.add("--web.listen-address=" + listenAddress + ":" + port);
            cmd.add("--web.enable-lifecycle");
            if (retention != null && !retention.trim().isEmpty()) {
                cmd.add("--storage.tsdb.retention.time=" + retention.trim());
            }
            if (extraArgs != null && !extraArgs.trim().isEmpty()) {
                for (String a : extraArgs.trim().split("\\s+")) if (!a.isEmpty()) cmd.add(a);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(System.getProperty("user.dir")));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            process = pb.start();
            startTime = System.currentTimeMillis();
            log.info("启动内置 Prometheus：{}", String.join(" ", cmd));
            if (!waitHealthy(30)) {
                lastError = "启动后 30 秒内健康检查未通过，请查看 logs/prometheus.log" + tail(logFile, 600);
                return status();
            }
            config.setString("prometheus.url", localUrl());
            config.setString("metrics.source", "prometheus");
            log.info("内置 Prometheus 健康检查通过：{}", localUrl());
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("启动内置 Prometheus 失败：{}", lastError);
        }
        return status();
    }

    public synchronized Map<String, Object> stop() {
        boolean hadProcess = process != null && process.isAlive();
        stopProcessOnly();
        lastError = "";
        Map<String, Object> st = status();
        if (!hadProcess && Boolean.TRUE.equals(st.get("healthy"))) {
            st.put("message", "端口 " + port + " 上的 Prometheus 不是由当前平台进程启动，无法在此停止，请手动结束该进程");
        }
        return st;
    }

    public synchronized Map<String, Object> restart() {
        stopProcessOnly();
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return start();
    }

    private void stopProcessOnly() {
        Process p = process;
        process = null;
        startTime = 0;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(6, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }

    public synchronized Map<String, Object> reload() {
        if (!healthy()) throw new ServiceException(400, "内置 Prometheus 未运行，无法热加载");
        try {
            ResponseEntity<String> r = rest.postForEntity(URI.create(localUrl() + "/-/reload"), null, String.class);
            lastError = "";
            return message("Prometheus 配置已热加载", r.getStatusCodeValue());
        } catch (Exception e) {
            lastError = "热加载失败：" + brief(e);
            throw new ServiceException(500, lastError);
        }
    }

    public synchronized String configText() {
        ensureConfig();
        try {
            return new String(Files.readAllBytes(configPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ServiceException(500, "读取 Prometheus 配置失败：" + e.getMessage());
        }
    }

    public synchronized Map<String, Object> saveConfig(String content) {
        if (content == null || content.trim().isEmpty()) throw new ServiceException(400, "配置内容不能为空");
        if (content.length() > 2 * 1024 * 1024) throw new ServiceException(400, "配置不能超过 2MB");
        validateConfig(content);
        ensureConfig();
        Path cfg = configPath();
        Path backup = cfg.resolveSibling(cfg.getFileName() + ".bak");
        try {
            Files.copy(cfg, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.write(cfg, content.getBytes(StandardCharsets.UTF_8));
            if (healthy()) {
                try {
                    reload();
                } catch (Exception e) {
                    Files.copy(backup, cfg, StandardCopyOption.REPLACE_EXISTING);
                    try { reload(); } catch (Exception ignore) { }
                    throw new ServiceException(400, "配置校验通过但热加载失败，已恢复旧配置：" + e.getMessage());
                }
            }
            lastError = "";
            Map<String, Object> out = message("Prometheus 配置已保存" + (healthy() ? "并热加载" : ""), 200);
            out.put("content", content);
            return out;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(500, "保存 Prometheus 配置失败：" + e.getMessage());
        }
    }

    public synchronized Map<String, Object> resetConfig() {
        String text = defaultConfig();
        return saveConfig(text);
    }

    public synchronized List<Map<String, Object>> jobs() {
        Map<String, Object> cfg = loadConfigMap();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Object raw = cfg.get("scrape_configs");
        if (!(raw instanceof List)) return out;
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> job = (Map<?, ?>) item;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            String name = str(job.get("job_name"));
            if (name.isEmpty()) continue;
            m.put("jobName", name);
            m.put("metricsPath", str(job.get("metrics_path"), "/metrics"));
            m.put("scrapeInterval", str(job.get("scrape_interval"), ""));
            m.put("scheme", str(job.get("scheme"), "http"));
            m.put("managed", hasHttpSd(job));
            m.put("type", hasHttpSd(job) ? "http_sd" : "static");
            List<String> targets = new ArrayList<String>();
            Map<String, String> labels = new LinkedHashMap<String, String>();
            Object sc = job.get("static_configs");
            if (sc instanceof List) {
                for (Object so : (List<?>) sc) {
                    if (!(so instanceof Map)) continue;
                    Map<?, ?> sm = (Map<?, ?>) so;
                    Object ts = sm.get("targets");
                    if (ts instanceof List) for (Object t : (List<?>) ts) targets.add(str(t));
                    Object ls = sm.get("labels");
                    if (ls instanceof Map) {
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) ls).entrySet()) {
                            labels.put(str(e.getKey()), str(e.getValue()));
                        }
                    }
                }
            }
            m.put("targets", targets);
            m.put("labels", labels);
            out.add(m);
        }
        return out;
    }

    public synchronized Map<String, Object> addJob(Map<String, Object> body) {
        Map<String, Object> cfg = loadConfigMap();
        List<Object> jobs = scrapeConfigList(cfg);
        String name = validateJobName(body.get("jobName"));
        for (Object o : jobs) {
            if (o instanceof Map && name.equals(str(((Map<?, ?>) o).get("job_name")))) {
                throw new ServiceException(400, "抓取任务已存在: " + name);
            }
        }
        jobs.add(buildJob(body, name));
        saveConfig(dumpConfig(cfg));
        return message("抓取任务已新增并热加载", 200);
    }

    public synchronized Map<String, Object> updateJob(String oldName, Map<String, Object> body) {
        Map<String, Object> cfg = loadConfigMap();
        List<Object> jobs = scrapeConfigList(cfg);
        String name = validateJobName(body.get("jobName"));
        int found = -1;
        for (int i = 0; i < jobs.size(); i++) {
            Object o = jobs.get(i);
            if (o instanceof Map && oldName.equals(str(((Map<?, ?>) o).get("job_name")))) { found = i; break; }
        }
        if (found < 0) throw new ServiceException(404, "抓取任务不存在: " + oldName);
        if (!oldName.equals(name)) {
            for (Object o : jobs) {
                if (o instanceof Map && name.equals(str(((Map<?, ?>) o).get("job_name")))) {
                    throw new ServiceException(400, "抓取任务已存在: " + name);
                }
            }
        }
        jobs.set(found, buildJob(body, name));
        saveConfig(dumpConfig(cfg));
        return message("抓取任务已更新并热加载", 200);
    }

    public synchronized Map<String, Object> deleteJob(String name) {
        Map<String, Object> cfg = loadConfigMap();
        List<Object> jobs = scrapeConfigList(cfg);
        boolean removed = jobs.removeIf(o -> o instanceof Map && name.equals(str(((Map<?, ?>) o).get("job_name"))));
        if (!removed) throw new ServiceException(404, "抓取任务不存在: " + name);
        saveConfig(dumpConfig(cfg));
        return message("抓取任务已删除并热加载", 200);
    }

    public synchronized Map<String, Object> switchMode(String newMode, String externalUrl) {
        String m = newMode == null ? "" : newMode.trim().toLowerCase();
        if (!"embedded".equals(m) && !"external".equals(m)) {
            throw new ServiceException(400, "模式只能是 embedded 或 external");
        }
        if ("external".equals(m)) {
            String url = normalize(externalUrl);
            if (url.isEmpty()) throw new ServiceException(400, "外部 Prometheus 地址不能为空");
            config.setString("prometheus.mode", "external");
            config.setString("prometheus.url", url);
            config.setString("prometheus.external.url", url);
            stopProcessOnly();
            lastError = "";
            Map<String, Object> out = status();
            out.put("message", "已切换为外部 Prometheus：" + url);
            return out;
        }
        if (!enabled) throw new ServiceException(400, "内置 Prometheus 未启用");
        // 从外部切换到内置时，把外部当前 targets 快照导入内置配置，避免切换后历史主机指标立刻空白
        if ("external".equalsIgnoreCase(mode())) {
            String ext = externalUrl();
            if (!ext.isEmpty()) {
                try {
                    config.setString("prometheus.external.url", ext);
                    int imported = importTargetsFrom(ext);
                    if (imported > 0) log.info("已从外部 Prometheus 导入 {} 个 targets 到内置配置", imported);
                } catch (Exception e) {
                    log.warn("导入外部 Prometheus targets 失败（不影响切换）：{}", e.getMessage());
                }
            }
        }
        config.setString("prometheus.mode", "embedded");
        Map<String, Object> st = start();
        if (!Boolean.TRUE.equals(st.get("running"))) {
            throw new ServiceException(500, "内置 Prometheus 启动失败：" + st.get("message"));
        }
        config.setString("prometheus.url", localUrl());
        config.setString("metrics.source", "prometheus");
        st.put("message", "已切换为内置 Prometheus：" + localUrl());
        return st;
    }

    /** 首次切到内置时自动导入一次外部 targets，避免升级后原有监控立即空白 */
    private void maybeImportExternalTargets() {
        if ("true".equalsIgnoreCase(config.getString("prometheus.import.done", "false"))) return;
        String ext = externalUrl();
        if (ext.isEmpty()) return;
        try {
            config.setString("prometheus.external.url", ext);
            int imported = importTargetsFrom(ext);
            config.setString("prometheus.import.done", "true");
            if (imported > 0) log.info("首次启用内置 Prometheus，已导入外部 {} 个 targets", imported);
        } catch (Exception e) {
            log.warn("首次导入外部 Prometheus targets 失败，后续可在配置中手动添加：{}", e.getMessage());
        }
    }

    /** 把外部 Prometheus 的 active targets 按 job 快照成内置静态任务（只在 job 不存在时新增） */
    private int importTargetsFrom(String base) {
        try {
            JsonNode root = mapper.readTree(rest.getForObject(URI.create(base + "/api/v1/targets?state=active"), String.class));
            JsonNode active = root.path("data").path("activeTargets");
            Map<String, List<String>> byJob = new LinkedHashMap<String, List<String>>();
            if (active.isArray()) {
                for (JsonNode t : active) {
                    String job = t.path("labels").path("job").asText("");
                    String instance = t.path("labels").path("instance").asText("");
                    if (job.isEmpty() || instance.isEmpty()) continue;
                    if (!byJob.containsKey(job)) byJob.put(job, new ArrayList<String>());
                    if (!byJob.get(job).contains(instance)) byJob.get(job).add(instance);
                }
            }
            if (byJob.isEmpty()) return 0;
            Map<String, Object> cfg = loadConfigMap();
            List<Object> jobs = scrapeConfigList(cfg);
            int imported = 0;
            for (Map.Entry<String, List<String>> e : byJob.entrySet()) {
                String name = "imported-" + e.getKey().replaceAll("[^A-Za-z0-9_-]", "-");
                if (name.length() > 100) name = name.substring(0, 100);
                boolean exists = false;
                for (Object o : jobs) {
                    if (o instanceof Map && name.equals(str(((Map<?, ?>) o).get("job_name")))) { exists = true; break; }
                }
                if (exists) continue;
                Map<String, Object> job = new LinkedHashMap<String, Object>();
                job.put("job_name", name);
                Map<String, Object> labels = new LinkedHashMap<String, Object>();
                labels.put("origin", "external-import");
                Map<String, Object> sc = new LinkedHashMap<String, Object>();
                sc.put("targets", e.getValue());
                sc.put("labels", labels);
                List<Object> scs = new ArrayList<Object>();
                scs.add(sc);
                job.put("static_configs", scs);
                jobs.add(job);
                imported++;
            }
            if (imported > 0) saveConfig(dumpConfig(cfg));
            return imported;
        } catch (Exception e) {
            throw new ServiceException(500, "导入外部 targets 失败：" + brief(e));
        }
    }

    private String externalUrl() {
        String ext = config.getString("prometheus.external.url", "");
        if (ext != null && !ext.trim().isEmpty()) return normalize(ext);
        String current = normalize(config.getString("prometheus.url", ""));
        return isLocalUrl(current) ? "" : current;
    }

    private boolean isLocalUrl(String url) {
        return url != null && (url.contains("127.0.0.1:" + port) || url.contains("localhost:" + port));
    }

    public Map<String, Object> targets() {
        String base = "embedded".equalsIgnoreCase(mode()) ? localUrl() : normalize(config.getString("prometheus.url", ""));
        if (base.isEmpty()) throw new ServiceException(400, "Prometheus 地址未配置");
        try {
            JsonNode root = mapper.readTree(rest.getForObject(URI.create(base + "/api/v1/targets?state=active"), String.class));
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            JsonNode active = root.path("data").path("activeTargets");
            if (active.isArray()) {
                for (JsonNode t : active) {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("job", t.path("labels").path("job").asText(""));
                    m.put("instance", t.path("labels").path("instance").asText(""));
                    m.put("health", t.path("health").asText(""));
                    m.put("scrapeUrl", t.path("scrapeUrl").asText(""));
                    m.put("lastScrape", t.path("lastScrape").asText(""));
                    m.put("lastError", t.path("lastError").asText(""));
                    m.put("exporter", t.path("labels").path("exporter").asText(""));
                    m.put("env", t.path("labels").path("env").asText(""));
                    out.add(m);
                }
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("url", base);
            result.put("targets", out);
            return result;
        } catch (Exception e) {
            throw new ServiceException(500, "查询 Prometheus targets 失败：" + brief(e));
        }
    }

    // ==================== 配置生成 / 校验 ====================

    private String defaultConfig() {
        String sd = "http://127.0.0.1:" + serverPort + "/api/monitor/targets";
        if (sdToken != null && !sdToken.trim().isEmpty()) {
            try { sd += "?token=" + URLEncoder.encode(sdToken.trim(), StandardCharsets.UTF_8.name()); } catch (Exception ignore) { }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("global:\n");
        sb.append("  scrape_interval: 15s\n");
        sb.append("  evaluation_interval: 15s\n");
        sb.append("\n");
        sb.append("scrape_configs:\n");
        sb.append("  # 平台自动发现：服务器管理 → Agent 安装 成功后，Exporter 会自动出现在这里\n");
        sb.append("  - job_name: 'aiops-platform-exporters'\n");
        sb.append("    honor_labels: true\n");
        sb.append("    http_sd_configs:\n");
        sb.append("      - url: '").append(sd).append("'\n");
        sb.append("        refresh_interval: 30s\n");
        sb.append("\n");
        sb.append("  # 在这里追加静态抓取任务，或使用系统设置 → Prometheus → 抓取任务表单\n");
        return sb.toString();
    }

    private void ensureConfig() {
        try {
            Path cfg = configPath();
            Path data = dataPath();
            Files.createDirectories(cfg.getParent());
            Files.createDirectories(data);
            if (!Files.exists(cfg) || Files.size(cfg) == 0) {
                Files.write(cfg, defaultConfig().getBytes(StandardCharsets.UTF_8));
                log.info("已生成默认 Prometheus 配置：{}", cfg);
            }
        } catch (Exception e) {
            throw new ServiceException(500, "初始化 Prometheus 配置失败：" + e.getMessage());
        }
    }

    private void validateConfig(String content) {
        try {
            Object o = new Yaml().load(content);
            if (!(o instanceof Map)) throw new ServiceException(400, "Prometheus 配置必须是 YAML 对象");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(400, "YAML 解析失败：" + e.getMessage());
        }
        Path promtool = resolvePromtool();
        if (promtool == null) return;
        Path tmp = null;
        try {
            tmp = Files.createTempFile("prometheus-check-", ".yml");
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            ProcessBuilder pb = new ProcessBuilder(promtool.toString(), "check", "config", tmp.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = readProcess(p, 15);
            int code = p.exitValue();
            if (code != 0) throw new ServiceException(400, "Prometheus 配置校验失败：" + trim(output, 1200));
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(400, "Prometheus 配置校验失败：" + e.getMessage());
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignore) { }
        }
    }

    private List<Object> scrapeConfigList(Map<String, Object> cfg) {
        Object raw = cfg.get("scrape_configs");
        if (!(raw instanceof List)) {
            List<Object> list = new ArrayList<Object>();
            cfg.put("scrape_configs", list);
            return list;
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) raw;
        return list;
    }

    private Map<String, Object> buildJob(Map<String, Object> body, String name) {
        Map<String, Object> job = new LinkedHashMap<String, Object>();
        job.put("job_name", name);
        String metricsPath = str(body.get("metricsPath"), "/metrics");
        if (!metricsPath.startsWith("/")) throw new ServiceException(400, "metrics_path 必须以 / 开头");
        if (!"/metrics".equals(metricsPath)) job.put("metrics_path", metricsPath);
        String interval = str(body.get("scrapeInterval"));
        if (!interval.isEmpty()) {
            if (!interval.matches("^[0-9]+(ms|s|m|h|d)([0-9]+(ms|s|m|h|d))*$")) {
                throw new ServiceException(400, "抓取间隔格式不合法，如 15s / 1m");
            }
            job.put("scrape_interval", interval);
        }
        List<String> targets = parseTargets(body.get("targets"));
        if (targets.isEmpty()) throw new ServiceException(400, "请至少填写一个抓取目标，如 192.0.2.1:9100");
        Map<String, String> labels = parseLabels(body.get("labels"));
        Map<String, Object> staticConfig = new LinkedHashMap<String, Object>();
        staticConfig.put("targets", targets);
        if (!labels.isEmpty()) staticConfig.put("labels", labels);
        List<Object> sc = new ArrayList<Object>();
        sc.add(staticConfig);
        job.put("static_configs", sc);
        return job;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseTargets(Object raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        Iterable<?> items;
        if (raw instanceof List) items = (List<?>) raw;
        else items = java.util.Arrays.asList(String.valueOf(raw).split("[,\\s]+"));
        for (Object o : items) {
            for (String t : String.valueOf(o).split("[,\\s]+")) {
                String v = t.trim();
                if (v.isEmpty()) continue;
                if (v.contains("://") || v.contains(" ") || !v.contains(":")) {
                    throw new ServiceException(400, "抓取目标必须是 host:port 格式：" + v);
                }
                if (!out.contains(v)) out.add(v);
            }
        }
        return out;
    }

    private Map<String, String> parseLabels(Object raw) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (raw == null) return out;
        Iterable<?> items;
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) out.put(str(e.getKey()), str(e.getValue()));
            return out;
        }
        if (raw instanceof List) items = (List<?>) raw;
        else items = java.util.Arrays.asList(String.valueOf(raw).split("[,\\n;]+"));
        for (Object o : items) {
            for (String part : String.valueOf(o).split("[,\\n;]+")) {
                String v = part.trim();
                if (v.isEmpty()) continue;
                int idx = v.indexOf('=');
                if (idx <= 0) throw new ServiceException(400, "标签格式应为 key=value：" + v);
                out.put(v.substring(0, idx).trim(), v.substring(idx + 1).trim());
            }
        }
        return out;
    }

    private String validateJobName(Object raw) {
        String name = str(raw);
        if (!name.matches("^[A-Za-z0-9_\\-:.]{1,100}$")) {
            throw new ServiceException(400, "任务名只能包含字母/数字/下划线/横线/点/冒号，长度 1-100");
        }
        return name;
    }

    private boolean hasHttpSd(Map<?, ?> job) {
        Object v = job.get("http_sd_configs");
        return v instanceof List && !((List<?>) v).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfigMap() {
        ensureConfig();
        try {
            Object o = new Yaml().load(configFileText());
            if (!(o instanceof Map)) throw new ServiceException(400, "Prometheus 配置格式错误（不是 YAML 对象）");
            return (Map<String, Object>) o;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(500, "读取 Prometheus 配置失败：" + e.getMessage());
        }
    }

    private String dumpConfig(Map<String, Object> cfg) {
        DumperOptions opt = new DumperOptions();
        opt.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opt.setPrettyFlow(true);
        opt.setIndent(2);
        return new Yaml(opt).dump(cfg);
    }

    private String configFileText() throws Exception {
        return new String(Files.readAllBytes(configPath()), StandardCharsets.UTF_8);
    }

    // ==================== 进程/HTTP 工具 ====================

    private boolean healthy() {
        try {
            ResponseEntity<String> r = rest.getForEntity(URI.create(localUrl() + "/-/healthy"), String.class);
            return r.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitHealthy(int seconds) {
        for (int i = 0; i < seconds; i++) {
            if (healthy()) return true;
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    private String remoteVersion() {
        try {
            JsonNode root = mapper.readTree(rest.getForObject(URI.create(localUrl() + "/api/v1/status/buildinfo"), String.class));
            return root.path("data").path("version").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String readVersionFile() {
        for (Path dir : binarySearchDirs()) {
            Path p = dir.resolve("VERSION");
            if (!Files.exists(p) && dir.getParent() != null) p = dir.getParent().resolve("VERSION");
            if (Files.exists(p)) {
                try { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim(); } catch (Exception ignore) { }
            }
        }
        return "";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 查找二进制的候选目录：配置的 binary-dir、数据目录（运维常把二进制直接解压到 data/prometheus）、部署包自带的 prometheus/ */
    private List<Path> binarySearchDirs() {
        List<Path> dirs = new ArrayList<Path>();
        addSearchDir(dirs, binaryDir);
        addSearchDir(dirs, dataDir);
        addSearchDir(dirs, "./prometheus");
        addSearchDir(dirs, "./data/prometheus");
        return dirs;
    }

    private void addSearchDir(List<Path> dirs, String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            Path p = Paths.get(raw).toAbsolutePath().normalize();
            if (!dirs.contains(p)) dirs.add(p);
        } catch (Exception ignore) { }
    }

    private String searchDirsText() {
        StringBuilder sb = new StringBuilder();
        for (Path d : binarySearchDirs()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(d);
        }
        return sb.toString();
    }

    /** 在目录内查找可执行文件：dir/<platform>/x、dir/x、dir/prometheus-<version>/<platform>/x、dir/prometheus-<version>/x */
    private Path findInDir(Path dir, String sub, String name) {
        String[] names = sub == null ? new String[]{ name } : new String[]{ name, sub + "/" + name };
        for (String rel : names) {
            Path p = dir.resolve(rel.replace('/', java.io.File.separatorChar));
            if (Files.isRegularFile(p)) return p;
        }
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "prometheus-*")) {
            for (Path child : ds) {
                if (!Files.isDirectory(child)) continue;
                for (String rel : names) {
                    Path p = child.resolve(rel.replace('/', java.io.File.separatorChar));
                    if (Files.isRegularFile(p)) return p;
                }
            }
        } catch (Exception ignore) { }
        return null;
    }

    private Path resolveBinary() {
        boolean win = isWindows();
        String sub = win ? "windows-amd64" : "linux-amd64";
        String name = win ? "prometheus.exe" : "prometheus";
        for (Path dir : binarySearchDirs()) {
            Path p = findInDir(dir, sub, name);
            if (p != null) {
                try { p.toFile().setExecutable(true); } catch (Exception ignore) { }
                return p;
            }
        }
        return null;
    }

    private Path resolvePromtool() {
        boolean win = isWindows();
        String sub = win ? "windows-amd64" : "linux-amd64";
        String name = win ? "promtool.exe" : "promtool";
        for (Path dir : binarySearchDirs()) {
            Path p = findInDir(dir, sub, name);
            if (p != null) {
                try { p.toFile().setExecutable(true); } catch (Exception ignore) { }
                return p;
            }
        }
        return null;
    }

    /** 查找可自动解压的官方安装包（当前平台对应的 tar.gz / tar.xz / zip） */
    private Path findArchive() {
        String osMark = isWindows() ? "windows-amd64" : "linux-amd64";
        for (Path dir : binarySearchDirs()) {
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "prometheus-*")) {
                for (Path p : ds) {
                    if (!Files.isRegularFile(p)) continue;
                    String n = p.getFileName().toString().toLowerCase();
                    if (!n.contains(osMark)) continue;
                    if (n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".tar.xz") || n.endsWith(".zip")) return p;
                }
            } catch (Exception ignore) { }
        }
        return null;
    }

    /** 只有安装包、没有二进制时：自动解压到数据目录，随后即可启动（新服务器上传 tar.gz 后一键启动） */
    private boolean extractArchiveIfNeeded() {
        Path archive = findArchive();
        if (archive == null) return false;
        Path target = dataPath();
        try {
            Files.createDirectories(target);
            String n = archive.getFileName().toString().toLowerCase();
            String flag = n.endsWith(".zip") ? "-xf" : n.endsWith(".tar.xz") ? "-xJf" : "-xzf";
            List<String> cmd = new ArrayList<String>();
            cmd.add("tar");
            cmd.add(flag);
            cmd.add(archive.toAbsolutePath().toString());
            cmd.add("-C");
            cmd.add(target.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(180, TimeUnit.SECONDS);
            log.info("自动解压 Prometheus 安装包：{} -> {}（完成 {}）", archive, target, done);
            if (!done) {
                lastError = "解压 Prometheus 安装包超时：" + archive;
                return false;
            }
            return resolveBinary() != null;
        } catch (Exception e) {
            lastError = "解压 Prometheus 安装包失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn(lastError);
            return false;
        }
    }

    private Path configPath() {
        return Paths.get(configFile).toAbsolutePath().normalize();
    }

    private Path dataPath() {
        return Paths.get(dataDir).toAbsolutePath().normalize();
    }

    private String localUrl() {
        return "http://127.0.0.1:" + port;
    }

    private int processPid(Process p) {
        try {
            java.lang.reflect.Method m = Process.class.getMethod("pid");
            return ((Long) m.invoke(p)).intValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private String readProcess(Process p, int timeoutSeconds) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            } catch (Exception ignore) { }
        });
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new ServiceException(400, "Prometheus 配置校验超时");
        }
        reader.join(2000);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private String tail(Path file, int max) {
        try {
            if (!Files.exists(file)) return "";
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String t = text.length() <= max ? text : text.substring(text.length() - max);
            return "\n" + t;
        } catch (Exception e) { return ""; }
    }

    private Map<String, Object> message(String msg, int code) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("success", true);
        m.put("code", code);
        m.put("message", msg);
        m.put("status", status());
        return m;
    }

    private String brief(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        String v = s.replaceAll("\\s+", " ").trim();
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private String normalize(String url) {
        if (url == null) return "";
        String v = url.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    private String str(Object v, String def) {
        String s = str(v);
        return s.isEmpty() ? def : s;
    }
}