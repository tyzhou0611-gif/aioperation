package com.aiops.service;

import com.aiops.common.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
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
 * Exporter 离线安装包管理：
 * - 管理员通过页面上传 tar.gz/tgz/tar.xz/tar
 * - 安装时按文件名读取，通过 SFTP 分发到目标机
 */
@Service
public class AgentPackageService {

    private static final Logger log = LoggerFactory.getLogger(AgentPackageService.class);
    private static final long MAX_SIZE = 200L * 1024 * 1024;

    private final String dirConfig;
    private Path dir;

    public AgentPackageService(@Value("${app.agents.package-dir:./data/agent-packages}") String dirConfig) {
        this.dirConfig = dirConfig;
    }

    @PostConstruct
    public void init() {
        try {
            this.dir = Paths.get(dirConfig).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            log.info("Exporter 安装包目录: {}", dir);
        } catch (Exception e) {
            log.warn("创建 Exporter 安装包目录失败: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        ensureDir();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            Map<String, Object> m = new LinkedHashMap<String, Object>();
                            m.put("name", p.getFileName().toString());
                            m.put("size", Files.size(p));
                            m.put("modifiedAt", LocalDateTime.ofInstant(
                                    Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis()), ZoneId.systemDefault()).toString());
                            out.add(m);
                        } catch (Exception ignore) { }
                    });
        } catch (Exception e) {
            throw new ServiceException(500, "读取安装包目录失败: " + e.getMessage());
        }
        return out;
    }

    public Map<String, Object> save(MultipartFile file) {
        ensureDir();
        if (file == null || file.isEmpty()) throw new ServiceException(400, "请选择安装包文件");
        if (file.getSize() > MAX_SIZE) throw new ServiceException(400, "安装包不能超过 200MB");
        String name = safeName(file.getOriginalFilename());
        if (!isSupported(name)) {
            throw new ServiceException(400, "仅支持 .tar.gz / .tgz / .tar.xz / .tar 安装包");
        }
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(dir)) throw new ServiceException(400, "非法文件名");
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new ServiceException(500, "保存安装包失败: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("name", name);
        out.put("size", file.getSize());
        out.put("sha256", sha256(target));
        log.info("Exporter 安装包已上传: {} ({} bytes)", name, file.getSize());
        return out;
    }

    public void delete(String name) {
        Path p = resolve(name);
        try {
            Files.deleteIfExists(p);
            log.info("Exporter 安装包已删除: {}", name);
        } catch (Exception e) {
            throw new ServiceException(500, "删除安装包失败: " + e.getMessage());
        }
    }

    /** 校验文件名并返回实际路径 */
    public Path resolve(String name) {
        ensureDir();
        String safe = safeName(name);
        if (!isSupported(safe)) throw new ServiceException(400, "不支持的安装包类型: " + name);
        Path p = dir.resolve(safe).normalize();
        if (!p.startsWith(dir)) throw new ServiceException(400, "非法文件名");
        if (!Files.exists(p)) throw new ServiceException(404, "安装包不存在: " + safe);
        return p;
    }

    public String sha256(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public Path dir() { ensureDir(); return dir; }

    private void ensureDir() {
        try {
            if (dir == null) dir = Paths.get(dirConfig).toAbsolutePath().normalize();
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new ServiceException(500, "安装包目录不可用: " + e.getMessage());
        }
    }

    private String safeName(String name) {
        if (name == null) throw new ServiceException(400, "文件名不能为空");
        String v = name.replace("\\", "/");
        int idx = v.lastIndexOf('/');
        if (idx >= 0) v = v.substring(idx + 1);
        v = v.trim();
        if (v.isEmpty() || v.contains("..") || !v.matches("^[A-Za-z0-9._-]+$")) {
            throw new ServiceException(400, "文件名只能包含字母/数字/点/下划线/横线");
        }
        return v;
    }

    private boolean isSupported(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".tar.xz") || n.endsWith(".tar");
    }
}