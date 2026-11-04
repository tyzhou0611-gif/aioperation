package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.ServerAgent;
import com.aiops.entity.ServerAsset;
import com.aiops.repository.ServerAgentRepository;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prometheus Exporter 一键安装 / 卸载：
 * 1. 从平台安装包目录读取离线包（管理员自行上传）
 * 2. SFTP 上传到目标机
 * 3. 生成 systemd 安装脚本并执行（root 直接执行，非 root 使用 sudo -n）
 * 4. 记录 server_agent，供 /api/monitor/targets 做 HTTP SD
 *
 * 支持官方/社区 Exporter，也支持“自定义”任意 tar.gz（填写二进制名即可）。
 * 相同服务器 + Exporter 的并发安装会串行/拒绝，避免临时目录互相删除。
 */
@Service
public class AgentInstallService {

    private static final Logger log = LoggerFactory.getLogger(AgentInstallService.class);
    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_\\-]{1,40}$");
    private static final Pattern BINARY_PATTERN = Pattern.compile("^[A-Za-z0-9._\\-]{1,100}$");
    private static final Pattern LISTEN_FLAG_PATTERN = Pattern.compile("^-{1,2}[A-Za-z0-9._\\-]{1,60}$");
    private static final Pattern CONFIG_FILE_PATTERN = Pattern.compile("^[A-Za-z0-9._\\-]{1,100}$");
    private static final Pattern EXTRA_PORTS_PATTERN = Pattern.compile("^\\d+/(tcp|udp)(,\\d+/(tcp|udp))*$");

    private final AgentCatalogService catalogService;
    private final AgentPackageService packageService;
    private final ServerSshTransferService ssh;
    private final ServerService serverService;
    private final ServerAgentRepository agentRepository;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, ReentrantLock> installLocks = new ConcurrentHashMap<String, ReentrantLock>();

    public AgentInstallService(AgentCatalogService catalogService, AgentPackageService packageService,
                               ServerSshTransferService ssh, ServerService serverService,
                               ServerAgentRepository agentRepository) {
        this.catalogService = catalogService;
        this.packageService = packageService;
        this.ssh = ssh;
        this.serverService = serverService;
        this.agentRepository = agentRepository;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> install(Map<String, Object> body) {
        if (body == null) throw new ServiceException(400, "参数不能为空");
        Map<String, Object> exporter = resolveExporter(body);
        if ("windows".equalsIgnoreCase(AgentCatalogService.str(exporter, "os", ""))) {
            throw new ServiceException(400, "Windows Exporter 请手工安装（当前一键脚本面向 Linux/systemd）");
        }
        String exporterKey = str(exporter.get("key"));
        String packageName = str(body.get("packageName"));
        Path pkg = packageService.resolve(packageName);
        List<Long> serverIds = serverIds(body);

        int defaultPort = AgentCatalogService.integer(exporter, "port", 9100);
        int port = asInt(body.get("port"), defaultPort);
        if (port < 1 || port > 65535) throw new ServiceException(400, "端口必须在 1-65535 之间");
        String installDir = str(body.get("installDir"));
        if (installDir.isEmpty()) installDir = "/opt/prometheus/" + exporterKey;
        validateInstallDir(installDir);
        String runUser = str(body.get("runUser"));
        if (runUser.isEmpty()) runUser = AgentCatalogService.str(exporter, "defaultUser", "prometheus");
        validateUser(runUser);
        String extraArgs = str(body.get("extraArgs"));
        if (extraArgs.isEmpty()) extraArgs = AgentCatalogService.str(exporter, "defaultArgs", "");
        extraArgs = extraArgs.replace("$DIR", installDir);
        validateExtraArgs(extraArgs);
        String envContent = strKeepLines(body.get("envContent"));
        validateEnvContent(envContent);
        String configContent = strKeepLines(body.get("configContent"));
        if (configContent.isEmpty()) configContent = strKeepLines(exporter.get("defaultConfig"));
        if (configContent.length() > 20000) throw new ServiceException(400, "配置文件内容不能超过 20000 字符");
        boolean useSudo = asBool(body.get("useSudo"), true);
        boolean openFirewall = asBool(body.get("openFirewall"), false);
        String extraPorts = AgentCatalogService.str(exporter, "extraPorts", "");
        if (!extraPorts.isEmpty() && !EXTRA_PORTS_PATTERN.matcher(extraPorts).matches()) extraPorts = "";
        String sha256 = packageService.sha256(pkg);
        String version = str(body.get("version"));
        if (version.isEmpty()) version = extractVersion(packageName);
        final int fPort = port;
        final String fInstallDir = installDir;
        final String fRunUser = runUser;
        final String fExtraArgs = extraArgs;
        final String fEnvContent = envContent;
        final String fConfigContent = configContent;
        final String fVersion = version;
        final String fExtraPorts = extraPorts;
        final boolean fUseSudo = useSudo;
        final boolean fOpenFirewall = openFirewall;

        List<Callable<Map<String, Object>>> tasks = new ArrayList<Callable<Map<String, Object>>>();
        for (Long id : serverIds) {
            final Long sid = id;
            tasks.add(() -> installOne(sid, exporter, pkg, packageName, sha256, fVersion, fPort,
                    fInstallDir, fRunUser, fExtraArgs, fEnvContent, fConfigContent, fExtraPorts,
                    fUseSudo, fOpenFirewall));
        }

        List<Map<String, Object>> results = runTasks(tasks);
        int ok = 0;
        for (Map<String, Object> r : results) if (Boolean.TRUE.equals(r.get("success"))) ok++;

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", results.size());
        out.put("success", ok);
        out.put("failed", results.size() - ok);
        out.put("results", results);
        log.info("Exporter 安装完成 exporter={} total={} success={} failed={}", exporterKey, results.size(), ok, results.size() - ok);
        return out;
    }

    /** 卸载：停止并删除 systemd 服务、环境文件和安装目录，保留安装历史记录 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> uninstall(Map<String, Object> body) {
        if (body == null) throw new ServiceException(400, "参数不能为空");
        String exporterKey = str(body.get("exporter"));
        if (!KEY_PATTERN.matcher(exporterKey).matches()) throw new ServiceException(400, "Exporter 标识不合法");
        List<Long> serverIds = serverIds(body);
        boolean useSudo = asBool(body.get("useSudo"), true);
        List<Callable<Map<String, Object>>> tasks = new ArrayList<Callable<Map<String, Object>>>();
        for (Long id : serverIds) {
            final Long sid = id;
            tasks.add(() -> uninstallOne(sid, exporterKey, useSudo));
        }
        List<Map<String, Object>> results = runTasks(tasks);
        int ok = 0;
        for (Map<String, Object> r : results) if (Boolean.TRUE.equals(r.get("success"))) ok++;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", results.size());
        out.put("success", ok);
        out.put("failed", results.size() - ok);
        out.put("results", results);
        return out;
    }

    private List<Map<String, Object>> runTasks(List<Callable<Map<String, Object>>> tasks) {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        try {
            List<Future<Map<String, Object>>> futures = pool.invokeAll(tasks);
            for (Future<Map<String, Object>> f : futures) {
                try { results.add(f.get()); }
                catch (Exception e) {
                    Map<String, Object> r = new LinkedHashMap<String, Object>();
                    r.put("success", false);
                    r.put("message", "任务失败: " + e.getMessage());
                    results.add(r);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(500, "任务被中断");
        }
        return results;
    }

    private List<Long> serverIds(Map<String, Object> body) {
        List<Long> serverIds = new ArrayList<Long>();
        Object rawIds = body.get("serverIds");
        if (rawIds instanceof List) {
            for (Object o : (List<?>) rawIds) {
                Long id = asLong(o);
                if (id != null && !serverIds.contains(id)) serverIds.add(id);
            }
        }
        if (serverIds.isEmpty()) throw new ServiceException(400, "请选择至少一台服务器");
        if (serverIds.size() > 30) throw new ServiceException(400, "单次最多操作 30 台服务器");
        return serverIds;
    }

    private Map<String, Object> installOne(Long serverId, Map<String, Object> exporter, Path pkg,
                                           String packageName, String sha256, String version, int port,
                                           String installDir, String runUser, String extraArgs,
                                           String envContent, String configContent, String extraPorts,
                                           boolean useSudo, boolean openFirewall) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("serverId", serverId);
        String key = str(exporter.get("key"));
        String lockKey = serverId + "|" + key;
        ReentrantLock lock = installLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            out.put("success", false);
            out.put("status", "FAILED");
            out.put("message", "该服务器正在安装 " + key + "，请等待本次安装完成");
            return out;
        }
        ServerAsset server = null;
        Session session = null;
        String remoteDir = "/tmp/aiops-agent-" + key.replaceAll("[^A-Za-z0-9_-]", "")
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            server = serverService.getById(serverId);
            out.put("hostname", server.getHostname());
            out.put("ip", server.getIp());
            session = ssh.open(server);
            ssh.mkdirs(session, remoteDir);
            String remotePkg = remoteDir + "/" + pkg.getFileName();
            String remoteScript = remoteDir + "/install.sh";
            ssh.upload(session, pkg, remotePkg);
            String script = buildScript(exporter, remotePkg, installDir, port, runUser, extraArgs,
                    envContent, configContent, extraPorts, openFirewall, sha256);
            ssh.uploadText(session, script, remoteScript);
            String cmd = "chmod 700 " + ServerSshTransferService.sh(remoteScript) + " && "
                    + "if [ \"$(id -u)\" = \"0\" ]; then bash " + ServerSshTransferService.sh(remoteScript) + "; "
                    + "elif [ \"" + useSudo + "\" = \"true\" ]; then sudo -n bash " + ServerSshTransferService.sh(remoteScript) + "; "
                    + "else bash " + ServerSshTransferService.sh(remoteScript) + "; fi";
            ServerSshTransferService.ExecResult r = ssh.exec(session, cmd, 300);
            boolean ok = r.exitStatus == 0 && r.output.contains("AIOPS_OK");
            out.put("success", ok);
            out.put("status", ok ? "ONLINE" : "FAILED");
            out.put("message", ok ? "安装成功" : "安装失败，请查看输出/日志");
            out.put("output", abbreviate(r.output, 6000));
            saveRecord(serverId, exporter, packageName, version, port, installDir, ok, r.output);
            log.info("Exporter 安装 [{}] {} {}: {}", server.getHostname(), key,
                    server.getIp(), ok ? "成功" : "失败");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            out.put("success", false);
            out.put("status", "FAILED");
            out.put("message", msg);
            saveRecord(serverId, exporter, packageName, version, port, installDir, false, msg);
            log.warn("Exporter 安装失败 serverId={}: {}", serverId, msg);
        } finally {
            if (session != null) {
                try { ssh.exec(session, "rm -rf " + ServerSshTransferService.sh(remoteDir), 15); } catch (Exception ignore) { }
            }
            ssh.close(session);
            lock.unlock();
        }
        return out;
    }

    private Map<String, Object> uninstallOne(Long serverId, String exporterKey, boolean useSudo) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("serverId", serverId);
        ServerAgent agent = agentRepository.findByServerIdAndExporter(serverId, exporterKey).orElse(null);
        if (agent == null) {
            out.put("success", false);
            out.put("message", "未找到该服务器的安装记录");
            return out;
        }
        ServerAsset server;
        Session session = null;
        try {
            server = serverService.getById(serverId);
            out.put("hostname", server.getHostname());
            out.put("ip", server.getIp());
            String service = agent.getServiceName();
            if (service == null || service.trim().isEmpty()) service = "aiops-" + exporterKey.replace('_', '-');
            if (!service.matches("^[A-Za-z0-9._@-]{1,100}$")) {
                throw new ServiceException(400, "服务名不合法，无法卸载");
            }
            String installDir = agent.getInstallDir();
            if (installDir != null && !installDir.trim().isEmpty()) validateInstallDir(installDir);
            String remoteDir = "/tmp/aiops-agent-uninstall-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String remoteScript = remoteDir + "/uninstall.sh";
            session = ssh.open(server);
            ssh.mkdirs(session, remoteDir);
            ssh.uploadText(session, buildUninstallScript(service, installDir), remoteScript);
            String cmd = "chmod 700 " + ServerSshTransferService.sh(remoteScript) + " && "
                    + "if [ \"$(id -u)\" = \"0\" ]; then bash " + ServerSshTransferService.sh(remoteScript) + "; "
                    + "elif [ \"" + useSudo + "\" = \"true\" ]; then sudo -n bash " + ServerSshTransferService.sh(remoteScript) + "; "
                    + "else bash " + ServerSshTransferService.sh(remoteScript) + "; fi";
            ServerSshTransferService.ExecResult r = ssh.exec(session, cmd, 180);
            boolean ok = r.exitStatus == 0 && r.output.contains("AIOPS_UNINSTALL_OK");
            out.put("success", ok);
            out.put("message", ok ? "卸载成功" : "卸载失败，请查看输出/日志");
            out.put("output", abbreviate(r.output, 4000));
            agent.setStatus(ok ? "UNINSTALLED" : "FAILED");
            agent.setMessage(abbreviate(r.output, 1800));
            agentRepository.save(agent);
            try { ssh.exec(session, "rm -rf " + ServerSshTransferService.sh(remoteDir), 15); } catch (Exception ignore) { }
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            agent.setStatus("FAILED");
            agent.setMessage(abbreviate(out.get("message").toString(), 1800));
            agentRepository.save(agent);
            log.warn("Exporter 卸载失败 serverId={} exporter={}: {}", serverId, exporterKey, e.getMessage());
        } finally {
            ssh.close(session);
        }
        return out;
    }

    private void saveRecord(Long serverId, Map<String, Object> exporter, String packageName, String version,
                            int port, String installDir, boolean ok, String message) {
        try {
            String key = str(exporter.get("key"));
            ServerAgent agent = agentRepository.findByServerIdAndExporter(serverId, key).orElseGet(ServerAgent::new);
            agent.setServerId(serverId);
            agent.setExporter(key);
            agent.setServiceName("aiops-" + key.replace('_', '-'));
            agent.setPort(port);
            agent.setInstallDir(installDir);
            agent.setPackageName(packageName);
            agent.setVersion(version == null || version.isEmpty() ? null : version);
            agent.setStatus(ok ? "ONLINE" : "FAILED");
            agent.setMessage(abbreviate(message, 1800));
            if (ok) agent.setInstalledAt(LocalDateTime.now());
            agentRepository.save(agent);
        } catch (Exception e) {
            log.warn("保存 Agent 安装记录失败: {}", e.getMessage());
        }
    }

    /**
     * 解析安装请求中的 exporter：可以是目录内的 key，也可以是 custom（任意离线包）。
     */
    private Map<String, Object> resolveExporter(Map<String, Object> body) {
        String key = str(body.get("exporter"));
        boolean custom = "custom".equalsIgnoreCase(key) || "__custom__".equalsIgnoreCase(key)
                || "custom".equalsIgnoreCase(str(body.get("mode")));
        if (!custom) return catalogService.get(key);

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String customKey = str(body.get("customKey")).toLowerCase();
        if (customKey.isEmpty()) customKey = "custom_exporter";
        if (!KEY_PATTERN.matcher(customKey).matches()) {
            throw new ServiceException(400, "自定义 Exporter 标识需以小写字母开头，只允许小写字母/数字/下划线/横线");
        }
        String binary = str(body.get("binary"));
        if (!BINARY_PATTERN.matcher(binary).matches()) {
            throw new ServiceException(400, "请填写安装包内的二进制文件名（如 node_exporter）");
        }
        String listenFlag = str(body.get("listenFlag"));
        if (listenFlag.isEmpty()) listenFlag = "--web.listen-address";
        if (!LISTEN_FLAG_PATTERN.matcher(listenFlag).matches()) {
            throw new ServiceException(400, "监听参数格式不合法，例如 --web.listen-address 或 --port");
        }
        String configFile = str(body.get("configFile"));
        if (!configFile.isEmpty() && !CONFIG_FILE_PATTERN.matcher(configFile).matches()) {
            throw new ServiceException(400, "配置文件名称不合法");
        }
        m.put("key", customKey);
        m.put("name", str(body.get("customName")).isEmpty() ? customKey : str(body.get("customName")));
        m.put("category", "custom");
        m.put("port", asInt(body.get("port"), 9100));
        m.put("binary", binary);
        m.put("listenFlag", listenFlag);
        if (!configFile.isEmpty()) m.put("configFile", configFile);
        m.put("defaultUser", str(body.get("runUser")).isEmpty() ? "prometheus" : str(body.get("runUser")));
        return m;
    }

    private String buildScript(Map<String, Object> exporter, String pkg, String dir, int port,
                               String runUser, String extraArgs, String envContent, String configContent,
                               String extraPorts, boolean openFirewall, String sha256) {
        String key = str(exporter.get("key"));
        String binary = AgentCatalogService.str(exporter, "binary", key);
        String listenFlag = AgentCatalogService.str(exporter, "listenFlag", "--web.listen-address");
        String service = "aiops-" + key.replace('_', '-');
        String configFile = AgentCatalogService.str(exporter, "configFile", "");
        String envFile = "/etc/default/" + service;
        String marker = "AIOPS_EOF_" + UUID.randomUUID().toString().replace("-", "");
        String cfgMarker = "AIOPS_CFG_" + UUID.randomUUID().toString().replace("-", "");
        boolean requireConfig = "true".equalsIgnoreCase(AgentCatalogService.str(exporter, "requireConfig", "false"));
        boolean blackboxFallback = "blackbox_exporter".equals(key);

        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("set -euo pipefail\n");
        sb.append("trap 'echo \"AIOPS_FAIL line=$LINENO cmd=$BASH_COMMAND\"' ERR\n");
        sb.append("EXPORTER=").append(q(key)).append("\n");
        sb.append("PKG=").append(q(pkg)).append("\n");
        sb.append("DIR=").append(q(dir)).append("\n");
        sb.append("PORT=").append(port).append("\n");
        sb.append("BIN_NAME=").append(q(binary)).append("\n");
        sb.append("SERVICE=").append(q(service)).append("\n");
        sb.append("RUN_USER=").append(q(runUser)).append("\n");
        sb.append("EXTRA_ARGS=").append(q(extraArgs)).append("\n");
        sb.append("ENV_FILE=").append(q(envFile)).append("\n");
        sb.append("LISTEN_FLAG=").append(q(listenFlag)).append("\n");
        sb.append("CONFIG_FILE=").append(q(configFile)).append("\n");
        sb.append("OPEN_FIREWALL=").append(openFirewall ? "1" : "0").append("\n");
        sb.append("EXTRA_PORTS=").append(q(extraPorts)).append("\n");
        sb.append("SHA256_EXPECT=").append(q(sha256)).append("\n");
        sb.append("if [ \"$(id -u)\" != \"0\" ]; then echo 'AIOPS_FAIL 需要 root 权限或免密 sudo'; exit 1; fi\n");
        sb.append("command -v systemctl >/dev/null 2>&1 || { echo 'AIOPS_FAIL systemctl not found'; exit 1; }\n");
        sb.append("if [ \"$RUN_USER\" != \"root\" ] && ! id -u \"$RUN_USER\" >/dev/null 2>&1; then\n");
        sb.append("  command -v useradd >/dev/null 2>&1 || { echo 'AIOPS_FAIL useradd not found'; exit 1; }\n");
        sb.append("fi\n");
        sb.append("if [ -n \"$SHA256_EXPECT\" ]; then\n");
        sb.append("  if command -v sha256sum >/dev/null 2>&1; then\n");
        sb.append("    ACT=$(sha256sum \"$PKG\" | awk '{print $1}')\n");
        sb.append("  else\n");
        sb.append("    ACT=$(openssl dgst -sha256 \"$PKG\" | awk '{print $NF}')\n");
        sb.append("  fi\n");
        sb.append("  [ \"$ACT\" = \"$SHA256_EXPECT\" ] || { echo \"AIOPS_FAIL sha256 mismatch\"; exit 1; }\n");
        sb.append("fi\n");
        sb.append("TMP=$(mktemp -d /tmp/aiops-agent-extract.XXXXXX)\n");
        sb.append("cleanup() { rm -rf \"$TMP\"; }\n");
        sb.append("trap 'cleanup' EXIT\n");
        sb.append("case \"$PKG\" in\n");
        sb.append("  *.tar.gz|*.tgz) tar -xzf \"$PKG\" -C \"$TMP\" ;;\n");
        sb.append("  *.tar.xz) tar -xJf \"$PKG\" -C \"$TMP\" ;;\n");
        sb.append("  *.tar) tar -xf \"$PKG\" -C \"$TMP\" ;;\n");
        sb.append("  *) echo 'AIOPS_FAIL unsupported package'; exit 1 ;;\n");
        sb.append("esac\n");
        sb.append("BIN=$(find \"$TMP\" -type f \\( -name \"$BIN_NAME\" -o -name \"${BIN_NAME}.exe\" \\) -print -quit)\n");
        sb.append("[ -n \"$BIN\" ] || { echo \"AIOPS_FAIL binary $BIN_NAME not found\"; exit 1; }\n");
        sb.append("mkdir -p \"$DIR\"\n");
        sb.append("systemctl stop \"$SERVICE\" >/dev/null 2>&1 || true\n");
        sb.append("install -m 0755 \"$BIN\" \"$DIR/$BIN_NAME\"\n");
        sb.append("chown root:root \"$DIR/$BIN_NAME\"\n");
        if (!configFile.isEmpty()) {
            sb.append("if [ ! -s \"$DIR/$CONFIG_FILE\" ]; then\n");
            sb.append("  CFG_SRC=$(find \"$TMP\" -type f -name \"$CONFIG_FILE\" -print -quit)\n");
            sb.append("  if [ -n \"$CFG_SRC\" ]; then\n");
            sb.append("    install -m 0644 \"$CFG_SRC\" \"$DIR/$CONFIG_FILE\"\n");
            if (!configContent.isEmpty()) {
                sb.append("  else\n");
                sb.append("    cat > \"$DIR/$CONFIG_FILE\" <<'").append(cfgMarker).append("'\n");
                sb.append(configContent).append("\n");
                sb.append(cfgMarker).append("\n");
                sb.append("    chmod 644 \"$DIR/$CONFIG_FILE\"\n");
                sb.append("  fi\n");
            } else if (blackboxFallback) {
                sb.append("  else\n");
                sb.append("    cat > \"$DIR/$CONFIG_FILE\" <<'").append(cfgMarker).append("'\n");
                sb.append("modules:\n  http_2xx:\n    prober: http\n    timeout: 5s\n");
                sb.append(cfgMarker).append("\n");
                sb.append("    chmod 644 \"$DIR/$CONFIG_FILE\"\n");
                sb.append("  fi\n");
            } else if (requireConfig) {
                sb.append("  else\n");
                sb.append("    echo \"AIOPS_FAIL 安装包内未找到 $CONFIG_FILE，请在安装表单中填写配置文件内容后再安装\"\n");
                sb.append("    exit 1\n");
                sb.append("  fi\n");
            } else {
                sb.append("  fi\n");
            }
            sb.append("fi\n");
            sb.append("chmod 644 \"$DIR/$CONFIG_FILE\" 2>/dev/null || true\n");
        }
        sb.append("if [ \"$RUN_USER\" != \"root\" ]; then\n");
        sb.append("  id -u \"$RUN_USER\" >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin \"$RUN_USER\"\n");
        sb.append("fi\n");
        if (!envContent.isEmpty()) {
            sb.append("umask 077\n");
            sb.append("cat > \"$ENV_FILE\" <<'").append(marker).append("'\n");
            sb.append(envContent).append("\n");
            sb.append(marker).append("\n");
            sb.append("chmod 600 \"$ENV_FILE\"\n");
        }
        sb.append("cat > \"/etc/systemd/system/${SERVICE}.service\" <<EOF_SERVICE\n");
        sb.append("[Unit]\nDescription=AI-Ops ${EXPORTER} exporter\nAfter=network-online.target\nWants=network-online.target\n\n");
        sb.append("[Service]\n");
        sb.append("Type=simple\n");
        sb.append("User=${RUN_USER}\n");
        sb.append("WorkingDirectory=${DIR}\n");
        sb.append("EnvironmentFile=-${ENV_FILE}\n");
        if ("--port".equals(listenFlag)) {
            sb.append("ExecStart=${DIR}/${BIN_NAME} ${LISTEN_FLAG}=${PORT} ${EXTRA_ARGS}\n");
        } else {
            sb.append("ExecStart=${DIR}/${BIN_NAME} ${LISTEN_FLAG}=:${PORT} ${EXTRA_ARGS}\n");
        }
        sb.append("Restart=on-failure\nRestartSec=5\nLimitNOFILE=65536\n\n");
        sb.append("[Install]\nWantedBy=multi-user.target\nEOF_SERVICE\n");
        sb.append("systemctl daemon-reload\n");
        sb.append("systemctl enable \"$SERVICE\" >/dev/null 2>&1 || true\n");
        sb.append("systemctl reset-failed \"$SERVICE\" >/dev/null 2>&1 || true\n");
        sb.append("systemctl restart \"$SERVICE\"\n");
        sb.append("OK=no\n");
        sb.append("i=0\n");
        sb.append("while [ $i -lt 15 ]; do\n");
        sb.append("  if command -v curl >/dev/null 2>&1; then\n");
        sb.append("    if curl -fsS --max-time 2 \"http://127.0.0.1:${PORT}/metrics\" >/dev/null 2>&1; then OK=yes; break; fi\n");
        sb.append("  elif command -v wget >/dev/null 2>&1; then\n");
        sb.append("    if wget -q -T 2 -O /dev/null \"http://127.0.0.1:${PORT}/metrics\" >/dev/null 2>&1; then OK=yes; break; fi\n");
        sb.append("  else\n");
        sb.append("    if systemctl is-active --quiet \"$SERVICE\"; then OK=yes; break; fi\n");
        sb.append("  fi\n");
        sb.append("  i=$((i+1)); sleep 1\n");
        sb.append("done\n");
        sb.append("if [ \"$OK\" != \"yes\" ]; then\n");
        sb.append("  echo 'AIOPS_FAIL service not healthy'\n");
        sb.append("  systemctl status --no-pager \"$SERVICE\" | tail -n 30 || true\n");
        sb.append("  journalctl -u \"$SERVICE\" --no-pager -n 30 || true\n");
        sb.append("  exit 1\n");
        sb.append("fi\n");
        sb.append("if [ \"$OPEN_FIREWALL\" = \"1\" ]; then\n");
        sb.append("  PORTS=\"${PORT}/tcp\"\n");
        sb.append("  if [ -n \"$EXTRA_PORTS\" ]; then PORTS=\"$PORTS,$EXTRA_PORTS\"; fi\n");
        sb.append("  if command -v firewall-cmd >/dev/null 2>&1; then\n");
        sb.append("    OLDIFS=$IFS; IFS=','; for p in $PORTS; do IFS=$OLDIFS; firewall-cmd --permanent --add-port=\"$p\" >/dev/null 2>&1 || true; IFS=','; done; IFS=$OLDIFS\n");
        sb.append("    firewall-cmd --reload >/dev/null 2>&1 || true\n");
        sb.append("  fi\n");
        sb.append("  if command -v ufw >/dev/null 2>&1; then\n");
        sb.append("    OLDIFS=$IFS; IFS=','; for p in $PORTS; do IFS=$OLDIFS; ufw allow \"$p\" >/dev/null 2>&1 || true; IFS=','; done; IFS=$OLDIFS\n");
        sb.append("  fi\n");
        sb.append("fi\n");
        sb.append("echo \"AIOPS_OK exporter=${EXPORTER} service=${SERVICE} port=${PORT} dir=${DIR}\"\n");
        return sb.toString();
    }

    private String buildUninstallScript(String service, String installDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("set -euo pipefail\n");
        sb.append("SERVICE=").append(q(service)).append("\n");
        sb.append("DIR=").append(q(installDir == null ? "" : installDir)).append("\n");
        sb.append("if [ \"$(id -u)\" != \"0\" ]; then echo 'AIOPS_FAIL 需要 root 权限或免密 sudo'; exit 1; fi\n");
        sb.append("systemctl stop \"$SERVICE\" >/dev/null 2>&1 || true\n");
        sb.append("systemctl disable \"$SERVICE\" >/dev/null 2>&1 || true\n");
        sb.append("rm -f \"/etc/systemd/system/${SERVICE}.service\"\n");
        sb.append("rm -f \"/etc/default/${SERVICE}\"\n");
        sb.append("systemctl daemon-reload >/dev/null 2>&1 || true\n");
        sb.append("systemctl reset-failed \"$SERVICE\" >/dev/null 2>&1 || true\n");
        sb.append("case \"$DIR\" in\n");
        sb.append("  \"\"|/|*..*) echo \"AIOPS_WARN skip unsafe dir: $DIR\" ;;\n");
        sb.append("  *) rm -rf \"$DIR\" ;;\n");
        sb.append("esac\n");
        sb.append("echo \"AIOPS_UNINSTALL_OK service=${SERVICE}\"\n");
        return sb.toString();
    }

    private String q(String s) { return ServerSshTransferService.sh(s); }

    private void validateInstallDir(String dir) {
        if (dir == null || dir.length() > 200 || !dir.matches("^/[A-Za-z0-9._/-]+$") || dir.contains("..")) {
            throw new ServiceException(400, "安装目录不合法，必须是 /opt/... 这样的绝对路径");
        }
    }

    private void validateUser(String user) {
        if (user == null || !user.matches("^[A-Za-z_][A-Za-z0-9_-]{0,30}$")) {
            throw new ServiceException(400, "运行用户不合法");
        }
    }

    private void validateExtraArgs(String args) {
        if (args == null) return;
        if (args.length() > 1000) throw new ServiceException(400, "额外参数过长");
        if (args.contains("\n") || args.contains("\r") || args.contains("\0") || args.contains("\"") || args.contains("\\")) {
            throw new ServiceException(400, "额外参数不能包含换行/双引号/反斜杠");
        }
    }

    private void validateEnvContent(String env) {
        if (env == null || env.trim().isEmpty()) return;
        if (env.length() > 4000) throw new ServiceException(400, "环境变量内容过长");
        for (String line : env.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (!l.matches("^[A-Za-z_][A-Za-z0-9_]*=.*$")) {
                throw new ServiceException(400, "环境变量格式错误: " + l);
            }
            if (l.contains("AIOPS_EOF_") || l.contains("AIOPS_CFG_")) {
                throw new ServiceException(400, "环境变量包含非法内容");
            }
        }
    }

    private String extractVersion(String packageName) {
        if (packageName == null) return "";
        Matcher m = VERSION.matcher(packageName);
        return m.find() ? m.group(1) : "";
    }

    private boolean asBool(Object v, boolean def) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v == null) return def;
        return "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
    }

    private int asInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    private Long asLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    private String strKeepLines(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\t\\x0B\\f\\r]", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}