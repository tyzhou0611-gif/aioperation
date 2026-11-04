package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.FtpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * FTP 拉取到平台本地的暂存区：
 * data/ftp-staging/<ftpId>__<文件名>
 * 之后可通过 SFTP 分发到受管服务器。
 */
@Service
public class FtpStagingService {

    private static final Logger log = LoggerFactory.getLogger(FtpStagingService.class);

    private final FtpClientService ftpClient;
    private final String dirConfig;
    private Path dir;

    public FtpStagingService(FtpClientService ftpClient,
                             @Value("${app.ftp.staging-dir:./data/ftp-staging}") String dirConfig) {
        this.ftpClient = ftpClient;
        this.dirConfig = dirConfig;
    }

    @PostConstruct
    public void init() {
        try {
            dir = Paths.get(dirConfig).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            log.info("FTP 暂存目录: {}", dir);
        } catch (Exception e) {
            log.warn("创建 FTP 暂存目录失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> pull(FtpServer server, String remotePath) {
        ensureDir();
        String original = fileName(remotePath);
        String safe = safeName(original);
        String stored = server.getId() + "__" + safe;
        Path local = resolve(stored);
        long size = ftpClient.download(server, remotePath, local);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("storedName", stored);
        out.put("originalName", safe);
        out.put("size", size);
        out.put("ftpServerId", server.getId());
        out.put("ftpServerName", server.getName());
        out.put("remotePath", remotePath);
        log.info("FTP 文件已拉取: {} -> {} ({} bytes)", remotePath, stored, size);
        return out;
    }

    public List<Map<String, Object>> list() {
        ensureDir();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            String name = p.getFileName().toString();
                            Map<String, Object> m = new LinkedHashMap<String, Object>();
                            m.put("storedName", name);
                            m.put("originalName", originalName(name));
                            m.put("ftpServerId", ftpId(name));
                            m.put("size", Files.size(p));
                            m.put("modifiedAt", LocalDateTime.ofInstant(
                                    Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis()), ZoneId.systemDefault()).toString());
                            out.add(m);
                        } catch (Exception ignore) { }
                    });
        } catch (Exception e) {
            throw new ServiceException(500, "读取暂存目录失败: " + e.getMessage());
        }
        return out;
    }

    public Path resolve(String storedName) {
        ensureDir();
        String safe = safeStoredName(storedName);
        Path p = dir.resolve(safe).normalize();
        if (!p.startsWith(dir)) throw new ServiceException(400, "非法文件名");
        return p;
    }

    public Path existing(String storedName) {
        Path p = resolve(storedName);
        if (!Files.exists(p)) throw new ServiceException(404, "暂存文件不存在: " + storedName);
        return p;
    }

    public String originalName(String storedName) {
        int idx = storedName == null ? -1 : storedName.indexOf("__");
        return idx >= 0 ? storedName.substring(idx + 2) : storedName;
    }

    public Long ftpId(String storedName) {
        int idx = storedName == null ? -1 : storedName.indexOf("__");
        if (idx <= 0) return null;
        try { return Long.parseLong(storedName.substring(0, idx)); } catch (Exception e) { return null; }
    }

    public void delete(String storedName) {
        Path p = resolve(storedName);
        try {
            Files.deleteIfExists(p);
            log.info("FTP 暂存文件已删除: {}", storedName);
        } catch (Exception e) {
            throw new ServiceException(500, "删除暂存文件失败: " + e.getMessage());
        }
    }

    public Path dir() { ensureDir(); return dir; }

    private void ensureDir() {
        try {
            if (dir == null) dir = Paths.get(dirConfig).toAbsolutePath().normalize();
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new ServiceException(500, "暂存目录不可用: " + e.getMessage());
        }
    }

    private String fileName(String path) {
        if (path == null) throw new ServiceException(400, "远程路径不能为空");
        String p = path.replace("\\", "/");
        String name = p.substring(p.lastIndexOf('/') + 1);
        if (name.isEmpty()) throw new ServiceException(400, "远程路径必须指向文件");
        return name;
    }

    private String safeName(String name) {
        String v = name.trim();
        if (v.isEmpty() || v.contains("..") || !v.matches("^[A-Za-z0-9._\\-\\u4e00-\\u9fa5 ]+$")) {
            throw new ServiceException(400, "文件名包含不支持字符: " + name);
        }
        return v;
    }

    private String safeStoredName(String name) {
        if (name == null) throw new ServiceException(400, "文件名不能为空");
        String v = name.replace("\\", "/");
        int idx = v.lastIndexOf('/');
        if (idx >= 0) v = v.substring(idx + 1);
        v = v.trim();
        if (v.isEmpty() || v.contains("..") || !v.matches("^[A-Za-z0-9._\\-\\u4e00-\\u9fa5 ]+$")) {
            throw new ServiceException(400, "非法暂存文件名");
        }
        return v;
    }
}