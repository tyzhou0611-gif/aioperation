package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.FtpServer;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

/**
 * FTP/SFTP 客户端：列目录、测试连接、下载文件到平台本地暂存。
 * 注意：FTP 为明文协议，建议内网使用；跨网段请使用 SFTP。
 */
@Service
public class FtpClientService {

    private static final Logger log = LoggerFactory.getLogger(FtpClientService.class);

    private final PasswordEncryptor encryptor;

    public FtpClientService(PasswordEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    private String password(FtpServer s) {
        String enc = s.getPasswordEncrypted();
        if (enc != null && !enc.isEmpty()) {
            try { return encryptor.decrypt(enc); } catch (Exception e) { return enc; }
        }
        // 未保存的临时配置：直接使用明文密码（仅用于“测试连接”）
        return s.getPassword() == null ? "" : s.getPassword();
    }

    public List<Map<String, Object>> list(FtpServer s, String path) {
        String p = normalizePath(s, path);
        if (isSftp(s)) return listSftp(s, p);
        return listFtp(s, p);
    }

    public Map<String, Object> test(FtpServer s) {
        long start = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            List<Map<String, Object>> files = list(s, normalizePath(s, null));
            out.put("success", true);
            out.put("message", "连接成功，目录内 " + files.size() + " 项");
            out.put("count", files.size());
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "连接失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }

    /** 下载远程文件到本地 path */
    public long download(FtpServer s, String remotePath, Path local) {
        String rp = normalizePath(s, remotePath);
        try {
            Files.createDirectories(local.getParent());
            if (isSftp(s)) return downloadSftp(s, rp, local);
            return downloadFtp(s, rp, local);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(500, "下载失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private boolean isSftp(FtpServer s) {
        return "SFTP".equalsIgnoreCase(s.getProtocol());
    }

    private String normalizePath(FtpServer s, String path) {
        String base = s.getBasePath() == null || s.getBasePath().trim().isEmpty() ? "/" : s.getBasePath().trim();
        if (path == null || path.trim().isEmpty()) return base;
        String p = path.trim();
        if (p.startsWith("/")) return p;
        if (base.endsWith("/")) return base + p;
        return base + "/" + p;
    }

    // ==================== FTP ====================

    private FTPClient connectFtp(FtpServer s) throws Exception {
        FTPClient client = new FTPClient();
        client.setControlEncoding("UTF-8");
        client.setConnectTimeout(10000);
        client.setDefaultTimeout(15000);
        client.setDataTimeout(java.time.Duration.ofSeconds(60));
        client.connect(s.getHost(), s.getPort() == null ? 21 : s.getPort());
        if (!client.login(s.getUsername() == null ? "anonymous" : s.getUsername(), password(s))) {
            throw new ServiceException(500, "FTP 登录失败: " + client.getReplyString());
        }
        client.setFileType(FTP.BINARY_FILE_TYPE);
        if (s.getPassiveMode() == null || s.getPassiveMode() != 0) client.enterLocalPassiveMode();
        else client.enterLocalActiveMode();
        return client;
    }

    private List<Map<String, Object>> listFtp(FtpServer s, String path) {
        FTPClient client = null;
        try {
            client = connectFtp(s);
            FTPFile[] files = client.listFiles(path);
            if (files == null) throw new ServiceException(500, "读取 FTP 目录失败: " + client.getReplyString());
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (FTPFile f : files) {
                String name = f.getName();
                if (".".equals(name) || "..".equals(name)) continue;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("name", name);
                m.put("path", join(path, name));
                m.put("directory", f.isDirectory());
                m.put("size", f.getSize());
                m.put("modifiedAt", f.getTimestamp() == null ? null
                        : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(f.getTimestamp().getTime()));
                out.add(m);
            }
            out.sort((a, b) -> {
                boolean da = Boolean.TRUE.equals(a.get("directory"));
                boolean db = Boolean.TRUE.equals(b.get("directory"));
                if (da != db) return da ? -1 : 1;
                return String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name")));
            });
            return out;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(500, "读取 FTP 目录失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (client != null) {
                try { client.logout(); } catch (Exception ignore) { }
                try { client.disconnect(); } catch (Exception ignore) { }
            }
        }
    }

    private long downloadFtp(FtpServer s, String remotePath, Path local) throws Exception {
        FTPClient client = null;
        try {
            client = connectFtp(s);
            try (OutputStream out = Files.newOutputStream(local)) {
                InputStream in = client.retrieveFileStream(remotePath);
                if (in == null) throw new ServiceException(500, "下载 FTP 文件失败: " + client.getReplyString());
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
            }
            if (!client.completePendingCommand()) {
                throw new ServiceException(500, "FTP 下载未完成: " + client.getReplyString());
            }
            return Files.size(local);
        } finally {
            if (client != null) {
                try { client.logout(); } catch (Exception ignore) { }
                try { client.disconnect(); } catch (Exception ignore) { }
            }
        }
    }

    // ==================== SFTP ====================

    private Session connectSftp(FtpServer s) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(s.getUsername() == null ? "root" : s.getUsername(),
                s.getHost(), s.getPort() == null ? 22 : s.getPort());
        session.setPassword(password(s));
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password,keyboard-interactive,publickey");
        session.setConfig(config);
        session.connect(15000);
        return session;
    }

    private List<Map<String, Object>> listSftp(FtpServer s, String path) {
        Session session = null;
        ChannelSftp sftp = null;
        try {
            session = connectSftp(s);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10000);
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(path);
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (ChannelSftp.LsEntry e : entries) {
                String name = e.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("name", name);
                m.put("path", join(path, name));
                m.put("directory", e.getAttrs().isDir());
                m.put("size", e.getAttrs().getSize());
                m.put("modifiedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(e.getAttrs().getMTime() * 1000L)));
                out.add(m);
            }
            out.sort((a, b) -> {
                boolean da = Boolean.TRUE.equals(a.get("directory"));
                boolean db = Boolean.TRUE.equals(b.get("directory"));
                if (da != db) return da ? -1 : 1;
                return String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name")));
            });
            return out;
        } catch (Exception e) {
            throw new ServiceException(500, "读取 SFTP 目录失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private long downloadSftp(FtpServer s, String remotePath, Path local) throws Exception {
        Session session = null;
        ChannelSftp sftp = null;
        try {
            session = connectSftp(s);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10000);
            sftp.get(remotePath, local.toString());
            return Files.size(local);
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private String join(String dir, String name) {
        if (dir.endsWith("/")) return dir + name;
        return dir + "/" + name;
    }
}