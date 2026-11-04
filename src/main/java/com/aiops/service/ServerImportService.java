package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.ServerAsset;
import com.aiops.repository.ServerAssetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从 Xshell / SecureCRT / OpenSSH config / CSV / JSON 导入服务器连接。
 * 两阶段：preview 解析并返回候选，confirm 按选择创建/更新。
 * Xshell/SecureCRT 的密码是加密的，不导入；可在确认时统一填写密码。
 */
@Service
@RequiredArgsConstructor
public class ServerImportService {

    private static final Logger log = LoggerFactory.getLogger(ServerImportService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ServerAssetRepository repository;
    private final PasswordEncryptor passwordEncryptor;

    private final Map<String, Batch> batches = new ConcurrentHashMap<String, Batch>();

    public Map<String, Object> preview(MultipartFile[] files) throws Exception {
        if (files == null || files.length == 0) throw new ServiceException("请上传 Xshell 会话/SSH config/CSV/JSON 文件");
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            String name = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
            byte[] bytes = file.getBytes();
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                parseZip(name, bytes, candidates);
            } else {
                parseContent(name, new String(bytes, StandardCharsets.UTF_8), candidates);
            }
        }
        // 按 IP 去重（保留第一条），并标记是否已存在
        Map<String, ServerAsset> existingByIp = new HashMap<String, ServerAsset>();
        for (ServerAsset s : repository.findAll()) {
            if (s.getIp() != null) existingByIp.put(s.getIp().trim(), s);
        }
        Map<String, Candidate> unique = new LinkedHashMap<String, Candidate>();
        for (Candidate c : candidates) {
            if (c.ip == null || c.ip.trim().isEmpty()) continue;
            if (!unique.containsKey(c.ip)) unique.put(c.ip, c);
        }
        List<Candidate> list = new ArrayList<Candidate>(unique.values());
        list.sort(Comparator.comparing(c -> c.ip));
        for (int i = 0; i < list.size(); i++) {
            Candidate c = list.get(i);
            c.index = i;
            c.exists = existingByIp.containsKey(c.ip);
        }

        String importId = UUID.randomUUID().toString().replace("-", "");
        Batch b = new Batch();
        b.candidates = list;
        b.expiresAt = System.currentTimeMillis() + 30 * 60 * 1000;
        batches.put(importId, b);
        cleanup();

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("importId", importId);
        out.put("total", list.size());
        out.put("existing", list.stream().filter(c -> c.exists).count());
        out.put("candidates", candidatesView(list));
        return out;
    }

    @Transactional
    public Map<String, Object> confirm(String importId, List<Integer> selected, boolean overwrite, String commonPassword) {
        Batch batch = importId == null ? null : batches.get(importId);
        if (batch == null || batch.expiresAt < System.currentTimeMillis()) {
            throw new ServiceException("导入会话已过期，请重新上传");
        }
        Set<Integer> pick = new HashSet<Integer>();
        if (selected != null) pick.addAll(selected);
        int added = 0, updated = 0, skipped = 0;
        List<Long> addedIds = new ArrayList<Long>();
        Map<String, ServerAsset> existingByIp = new HashMap<String, ServerAsset>();
        for (ServerAsset s : repository.findAll()) {
            if (s.getIp() != null) existingByIp.put(s.getIp().trim(), s);
        }
        for (Candidate c : batch.candidates) {
            if (!pick.isEmpty() && !pick.contains(c.index)) continue;
            if (c.ip == null || c.ip.trim().isEmpty()) { skipped++; continue; }
            String pwd = commonPassword != null && !commonPassword.isEmpty() ? commonPassword : c.password;
            ServerAsset existing = existingByIp.get(c.ip);
            if (existing != null) {
                if (!overwrite) { skipped++; continue; }
                if (c.hostname != null && !c.hostname.isEmpty()) existing.setHostname(c.hostname);
                if (c.port > 0) existing.setSshPort(c.port);
                if (c.user != null && !c.user.isEmpty()) existing.setSshUser(c.user);
                if (c.environment != null && !c.environment.isEmpty()) existing.setEnvironment(c.environment);
                if (pwd != null && !pwd.isEmpty()) existing.setSshPasswordEncrypted(passwordEncryptor.encrypt(pwd));
                repository.save(existing);
                updated++;
            } else {
                ServerAsset s = new ServerAsset();
                s.setIp(c.ip);
                s.setHostname(c.hostname == null || c.hostname.isEmpty() ? c.ip : c.hostname);
                s.setSshPort(c.port > 0 ? c.port : 22);
                s.setSshUser(c.user == null || c.user.isEmpty() ? "root" : c.user);
                s.setEnvironment(c.environment == null || c.environment.isEmpty() ? "production" : c.environment);
                s.setStatus("UNKNOWN");
                if (pwd != null && !pwd.isEmpty()) s.setSshPasswordEncrypted(passwordEncryptor.encrypt(pwd));
                repository.save(s);
                existingByIp.put(c.ip, s);
                addedIds.add(s.getId());
                added++;
            }
        }
        batches.remove(importId);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("added", added);
        out.put("updated", updated);
        out.put("skipped", skipped);
        out.put("addedIds", addedIds);
        return out;
    }

    // ==================== 解析 ====================

    private void parseZip(String name, byte[] bytes, List<Candidate> out) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String entryName = entry.getName();
                if (entryName.startsWith("__MACOSX/")) continue;
                String lower = entryName.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".xsh") || lower.endsWith(".ini") || lower.endsWith(".csv")
                        || lower.endsWith(".json") || lower.endsWith(".conf") || lower.endsWith(".config")
                        || lower.endsWith(".txt") || lower.endsWith("config"))) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                parseContent(entryName, new String(bos.toByteArray(), StandardCharsets.UTF_8), out);
            }
        }
    }

    private void parseContent(String filename, String content, List<Candidate> out) {
        if (content == null || content.trim().isEmpty()) return;
        String lower = filename.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".json")) { parseJson(filename, content, out); return; }
            if (lower.endsWith(".csv")) { parseCsv(filename, content, out); return; }
            if (lower.endsWith(".xsh")) { parseXshell(filename, content, out); return; }
            if (content.contains("S:\"Hostname\"") || content.contains("S:\"Username\"")) {
                parseSecureCrt(filename, content, out); return;
            }
            if (Pattern.compile("(?m)^\\s*Host\\s+\\S+").matcher(content).find()) {
                parseSshConfig(filename, content, out); return;
            }
            parseXshell(filename, content, out);
        } catch (Exception e) {
            log.warn("解析导入文件失败 {}: {}", filename, e.getMessage());
        }
    }

    private void parseXshell(String filename, String content, List<Candidate> out) {
        Map<String, String> kv = new HashMap<String, String>();
        for (String line : content.split("\n")) {
            Matcher m = Pattern.compile("^\\s*([A-Za-z0-9_]+)\\s*=\\s*(.*)$").matcher(line);
            if (!m.find()) continue;
            String k = m.group(1).toLowerCase(Locale.ROOT);
            String v = trim(m.group(2));
            if (k.equals("host") || k.equals("port") || k.equals("username") || k.equals("name")) {
                if (!kv.containsKey(k)) kv.put(k, v);
            }
        }
        String host = kv.get("host");
        if (host == null || host.isEmpty()) return;
        Candidate c = new Candidate();
        c.ip = normalizeHost(host);
        c.hostname = kv.get("name");
        if (c.hostname == null || c.hostname.isEmpty()) c.hostname = baseName(filename);
        c.port = parsePort(kv.get("port"), 22);
        c.user = kv.get("username");
        c.environment = "production";
        c.source = "Xshell: " + filename;
        out.add(c);
    }

    private void parseSecureCrt(String filename, String content, List<Candidate> out) {
        String host = extract(content, "S:\"Hostname\"\\s*=\\s*(.+)");
        if (host == null) host = extract(content, "S:\"Host\"\\s*=\\s*(.+)");
        if (host == null || host.isEmpty()) return;
        Candidate c = new Candidate();
        c.ip = normalizeHost(host);
        c.hostname = baseName(filename);
        String user = extract(content, "S:\"Username\"\\s*=\\s*(.+)");
        c.user = user;
        String port = extract(content, "D:\"Port\"\\s*=\\s*(.+)");
        c.port = parsePort(port, 22);
        c.environment = "production";
        c.source = "SecureCRT: " + filename;
        out.add(c);
    }

    private void parseSshConfig(String filename, String content, List<Candidate> out) {
        String alias = null;
        Candidate c = null;
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) continue;
            String key = parts[0].toLowerCase(Locale.ROOT);
            String val = trim(parts[1]);
            if (key.equals("host")) {
                flushCandidate(c, out);
                c = null;
                alias = val;
                if (alias.contains("*") || alias.contains("?")) alias = null;
                else {
                    c = new Candidate();
                    c.hostname = alias;
                    c.environment = "production";
                }
                continue;
            }
            if (c == null) continue;
            if (key.equals("hostname")) c.ip = normalizeHost(val);
            else if (key.equals("user")) c.user = val;
            else if (key.equals("port")) c.port = parsePort(val, 22);
        }
        flushCandidate(c, out);
    }

    private void flushCandidate(Candidate c, List<Candidate> out) {
        if (c == null) return;
        if (c.ip == null || c.ip.isEmpty()) c.ip = c.hostname;
        if (c.ip == null || c.ip.isEmpty()) return;
        c.source = "SSH config";
        out.add(c);
    }

    private void parseCsv(String filename, String content, List<Candidate> out) {
        String[] lines = content.split("\\r?\\n");
        if (lines.length == 0) return;
        List<String> header = splitCsv(lines[0]);
        Map<String, Integer> idx = new HashMap<String, Integer>();
        for (int i = 0; i < header.size(); i++) idx.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        boolean hasHeader = idx.containsKey("ip") || idx.containsKey("host") || idx.containsKey("hostname");
        int start = hasHeader ? 1 : 0;
        if (!hasHeader) {
            idx.put("ip", 0); idx.put("hostname", 1); idx.put("port", 2);
            idx.put("user", 3); idx.put("environment", 4); idx.put("password", 5);
        }
        for (int i = start; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            List<String> cols = splitCsv(lines[i]);
            Candidate c = new Candidate();
            c.ip = normalizeHost(get(cols, idx, "ip", get(cols, idx, "host", get(cols, idx, "hostname", null))));
            c.hostname = get(cols, idx, "hostname", null);
            c.port = parsePort(get(cols, idx, "port", null), 22);
            c.user = get(cols, idx, "user", get(cols, idx, "username", null));
            c.environment = get(cols, idx, "environment", get(cols, idx, "env", "production"));
            c.password = get(cols, idx, "password", null);
            c.source = "CSV: " + filename;
            if (c.ip != null && !c.ip.isEmpty()) out.add(c);
        }
    }

    private void parseJson(String filename, String content, List<Candidate> out) throws Exception {
        JsonNode root = mapper.readTree(content);
        JsonNode arr = root.isArray() ? root : root.path("servers");
        if (!arr.isArray()) return;
        for (JsonNode n : arr) {
            Candidate c = new Candidate();
            String host = text(n, "ip");
            if (host == null) host = text(n, "host");
            if (host == null) host = text(n, "hostname");
            c.ip = normalizeHost(host);
            c.hostname = text(n, "hostname");
            if (c.hostname == null) c.hostname = text(n, "name");
            String port = text(n, "port");
            if (port == null) port = text(n, "sshPort");
            c.port = parsePort(port, 22);
            c.user = text(n, "user");
            if (c.user == null) c.user = text(n, "sshUser");
            c.environment = text(n, "environment");
            c.password = text(n, "password");
            c.source = "JSON: " + filename;
            if (c.ip != null && !c.ip.isEmpty()) out.add(c);
        }
    }

    // ==================== 辅助 ====================

    private static class Candidate {
        int index;
        String ip;
        String hostname;
        int port = 22;
        String user;
        String environment = "production";
        String password;
        String source;
        boolean exists;
    }

    private static class Batch {
        List<Candidate> candidates;
        long expiresAt;
    }

    private List<Map<String, Object>> candidatesView(List<Candidate> list) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Candidate c : list) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("index", c.index);
            m.put("ip", c.ip);
            m.put("hostname", c.hostname);
            m.put("port", c.port);
            m.put("user", c.user == null ? "root" : c.user);
            m.put("environment", c.environment == null ? "production" : c.environment);
            m.put("source", c.source);
            m.put("passwordSet", c.password != null && !c.password.isEmpty());
            m.put("exists", c.exists);
            out.add(m);
        }
        return out;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        batches.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }

    private String extract(String content, String regex) {
        Matcher m = Pattern.compile("(?m)^\\s*" + regex).matcher(content);
        return m.find() ? trim(m.group(1)) : null;
    }

    private String get(List<String> cols, Map<String, Integer> idx, String key, String def) {
        Integer i = idx.get(key);
        if (i == null || i >= cols.size()) return def;
        String v = cols.get(i);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private List<String> splitCsv(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quote && i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                else quote = !quote;
            } else if (ch == ',' && !quote) {
                out.add(sb.toString()); sb.setLength(0);
            } else sb.append(ch);
        }
        out.add(sb.toString());
        return out;
    }

    private String normalizeHost(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("\"", "").replace("'", "");
        if (s.startsWith("ssh://")) s = s.substring("ssh://".length());
        if (s.startsWith("http://")) s = s.substring("http://".length());
        if (s.startsWith("https://")) s = s.substring("https://".length());
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.substring(colon + 1).matches("\\d+")) s = s.substring(0, colon);
        return s.trim();
    }

    private int parsePort(String raw, int def) {
        if (raw == null || raw.trim().isEmpty()) return def;
        try {
            String s = raw.trim().replace("\"", "");
            if (s.startsWith("000000")) return Integer.parseInt(s, 16);
            int p = Integer.parseInt(s);
            return p > 0 && p <= 65535 ? p : def;
        } catch (Exception e) {
            return def;
        }
    }

    private String trim(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() > 1) v = v.substring(1, v.length() - 1);
        if (v.startsWith("'") && v.endsWith("'") && v.length() > 1) v = v.substring(1, v.length() - 1);
        return v.trim();
    }

    private String baseName(String filename) {
        String n = filename == null ? "server" : filename;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        return n.replaceAll("(?i)\\.(xsh|ini|csv|json|conf|config|txt)$", "");
    }

    private String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText(null);
    }
}