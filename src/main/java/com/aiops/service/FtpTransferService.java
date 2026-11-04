package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.ServerAsset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 把 FTP 暂存文件通过 SFTP 分发到受管服务器 */
@Service
public class FtpTransferService {

    private static final Logger log = LoggerFactory.getLogger(FtpTransferService.class);

    private final FtpStagingService staging;
    private final ServerSshTransferService ssh;
    private final ServerService serverService;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public FtpTransferService(FtpStagingService staging, ServerSshTransferService ssh, ServerService serverService) {
        this.staging = staging;
        this.ssh = ssh;
        this.serverService = serverService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> distribute(Map<String, Object> body) {
        if (body == null) throw new ServiceException(400, "参数不能为空");
        String storedName = str(body.get("storedName"));
        if (storedName.isEmpty()) throw new ServiceException(400, "请选择暂存文件");
        Path local = staging.existing(storedName);
        String fileName = str(body.get("fileName"));
        if (fileName.isEmpty()) fileName = staging.originalName(storedName);
        validateFileName(fileName);
        String remoteDir = str(body.get("remoteDir"));
        if (remoteDir.isEmpty()) remoteDir = "/tmp";
        validateRemoteDir(remoteDir);
        boolean overwrite = asBool(body.get("overwrite"), true);
        String mode = str(body.get("mode"));
        if (!mode.isEmpty() && !mode.matches("^[0-7]{3,4}$")) throw new ServiceException(400, "权限位必须是 644/755 这样的格式");
        String postCommand = str(body.get("postCommand"));
        if (postCommand.length() > 1000 || postCommand.contains("\0")) throw new ServiceException(400, "后置命令过长或包含非法字符");

        List<Long> serverIds = new ArrayList<Long>();
        Object rawIds = body.get("serverIds");
        if (rawIds instanceof List) {
            for (Object o : (List<?>) rawIds) {
                Long id = asLong(o);
                if (id != null && !serverIds.contains(id)) serverIds.add(id);
            }
        }
        if (serverIds.isEmpty()) throw new ServiceException(400, "请选择至少一台目标服务器");
        if (serverIds.size() > 50) throw new ServiceException(400, "单次最多分发到 50 台服务器");

        final String fName = fileName;
        final String fDir = remoteDir;
        final boolean fOverwrite = overwrite;
        final String fMode = mode.isEmpty() ? null : mode;
        final String fPost = postCommand;

        List<Callable<Map<String, Object>>> tasks = new ArrayList<Callable<Map<String, Object>>>();
        for (Long id : serverIds) {
            final Long sid = id;
            tasks.add(() -> distributeOne(sid, local, fName, fDir, fOverwrite, fMode, fPost));
        }
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        try {
            for (Future<Map<String, Object>> f : pool.invokeAll(tasks)) {
                try { results.add(f.get()); }
                catch (Exception e) { Map<String, Object> r = new LinkedHashMap<String, Object>(); r.put("success", false); r.put("message", e.getMessage()); results.add(r); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(500, "分发被中断");
        }
        int ok = 0;
        for (Map<String, Object> r : results) if (Boolean.TRUE.equals(r.get("success"))) ok++;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", results.size());
        out.put("success", ok);
        out.put("failed", results.size() - ok);
        out.put("fileName", fileName);
        out.put("remoteDir", remoteDir);
        out.put("results", results);
        return out;
    }

    private Map<String, Object> distributeOne(Long serverId, Path local, String fileName, String remoteDir,
                                              boolean overwrite, String mode, String postCommand) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("serverId", serverId);
        ServerAsset server = null;
        try {
            server = serverService.getById(serverId);
            out.put("hostname", server.getHostname());
            out.put("ip", server.getIp());
            Map<String, Object> r = ssh.distribute(server, local, remoteDir, fileName, overwrite, mode);
            out.putAll(r);
            out.put("serverId", serverId);
            if (Boolean.TRUE.equals(r.get("success")) && postCommand != null && !postCommand.trim().isEmpty()) {
                String cmd = postCommand.replace("{file}", ServerSshTransferService.sh(String.valueOf(r.get("remotePath"))));
                try {
                    String cmdOut = ssh.exec(server, cmd, 120);
                    out.put("postCommand", cmd);
                    out.put("postCommandSuccess", true);
                    out.put("postCommandResult", abbreviate(cmdOut, 1000));
                } catch (Exception e) {
                    out.put("postCommand", cmd);
                    out.put("postCommandSuccess", false);
                    out.put("postCommandResult", e.getMessage());
                }
            }
            log.info("FTP 分发 [{}] {} -> {}: {}", server.getHostname(), fileName, remoteDir,
                    Boolean.TRUE.equals(r.get("success")) ? "成功" : "失败");
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return out;
    }

    private void validateFileName(String name) {
        if (name == null || name.length() > 200 || name.contains("..") || name.contains("/") || name.contains("\\")
                || !name.matches("^[A-Za-z0-9._\\-\\u4e00-\\u9fa5 ]+$")) {
            throw new ServiceException(400, "文件名不合法");
        }
    }

    private void validateRemoteDir(String dir) {
        if (dir == null || dir.length() > 300 || !dir.startsWith("/") || dir.contains("..")
                || !dir.matches("^/[A-Za-z0-9._/\\-\\u4e00-\\u9fa5 ]*$")) {
            throw new ServiceException(400, "远程目录必须是绝对路径，且不能包含 ..");
        }
    }

    private boolean asBool(Object v, boolean def) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v == null) return def;
        return "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
    }

    private Long asLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\t\\r]", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}