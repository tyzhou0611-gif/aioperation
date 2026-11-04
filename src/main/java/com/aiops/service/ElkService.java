package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.ElkConnection;
import com.aiops.repository.ElkConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ELK 连接管理：Elasticsearch 连通性测试、索引列表、日志检索。
 * 密码 AES 加密存储；返回给前端时只给出 passwordSet。
 */
@Service
public class ElkService {

    private static final Logger log = LoggerFactory.getLogger(ElkService.class);

    private final ElkConnectionRepository repository;
    private final PasswordEncryptor passwordEncryptor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    public ElkService(ElkConnectionRepository repository, PasswordEncryptor passwordEncryptor) {
        this.repository = repository;
        this.passwordEncryptor = passwordEncryptor;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(20000);
        this.restTemplate = new RestTemplate(f);
    }

    // ==================== 连接管理 ====================

    public List<ElkConnection> list() {
        List<ElkConnection> out = new ArrayList<ElkConnection>();
        for (ElkConnection c : repository.findAllByOrderByIdDesc()) out.add(toSafe(c));
        return out;
    }

    @Transactional
    public ElkConnection create(ElkConnection input) {
        validate(input);
        input.setId(null);
        input.setEsUrl(normalizeUrl(input.getEsUrl()));
        input.setLogstashUrl(normalizeUrl(input.getLogstashUrl()));
        String plain = input.getEsPassword();
        input.setEsPassword(null);
        input.setEsPasswordEncrypted(plain != null && !plain.isEmpty() ? passwordEncryptor.encrypt(plain) : null);
        input.setStatus("DISCONNECTED");
        return toSafe(repository.save(input));
    }

    @Transactional
    public ElkConnection update(Long id, ElkConnection input) {
        ElkConnection existing = getRaw(id);
        validate(input);
        existing.setName(input.getName().trim());
        existing.setEsUrl(normalizeUrl(input.getEsUrl()));
        existing.setLogstashUrl(normalizeUrl(input.getLogstashUrl()));
        existing.setEsUsername(input.getEsUsername());
        if (input.getEsPassword() != null && !input.getEsPassword().isEmpty()) {
            existing.setEsPasswordEncrypted(passwordEncryptor.encrypt(input.getEsPassword()));
        }
        return toSafe(repository.save(existing));
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    public ElkConnection getRaw(Long id) {
        return repository.findById(id).orElseThrow(() -> new ServiceException("ELK 连接不存在: " + id));
    }

    // ==================== 连通性测试 ====================

    @Transactional
    public Map<String, Object> test(Long id) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        ElkConnection conn = getRaw(id);
        long start = System.currentTimeMillis();
        boolean esOk = false;
        try {
            JsonNode root = json(HttpMethod.GET, conn.getEsUrl() + "/", null, conn);
            String cluster = root.path("cluster_name").asText("");
            String version = root.path("version").path("number").asText("");
            result.put("esVersion", version);
            result.put("clusterName", cluster);
            result.put("message", "Elasticsearch 连接成功" + (version.isEmpty() ? "" : "（v" + version + "）")
                    + (cluster.isEmpty() ? "" : "，集群 " + cluster));
            esOk = true;
        } catch (Exception e) {
            result.put("message", "Elasticsearch 连接失败: " + errorMessage(e));
        }

        if (conn.getLogstashUrl() != null && !conn.getLogstashUrl().trim().isEmpty()) {
            try {
                JsonNode ls = json(HttpMethod.GET, conn.getLogstashUrl() + "/_node/stats", null, conn);
                String version = ls.path("jvm").path("version").asText(ls.path("version").asText(""));
                result.put("logstashReachable", true);
                result.put("logstashMessage", "Logstash 连接成功" + (version.isEmpty() ? "" : "（" + version + "）"));
            } catch (Exception e) {
                result.put("logstashReachable", false);
                result.put("logstashMessage", "Logstash 连接失败: " + errorMessage(e));
            }
        }

        conn.setStatus(esOk ? "CONNECTED" : "ERROR");
        repository.save(conn);
        result.put("reachable", esOk);
        result.put("elapsedMs", System.currentTimeMillis() - start);
        return result;
    }

    // ==================== 索引与日志检索 ====================

    public List<Map<String, Object>> indices(Long id) {
        ElkConnection conn = getRaw(id);
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(conn.getEsUrl() + "/_cat/indices")
                    .queryParam("format", "json")
                    .queryParam("bytes", "b")
                    .build().encode().toUri();
            JsonNode root = jsonUri(HttpMethod.GET, uri, null, conn);
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("index", node.path("index").asText(""));
                    m.put("health", node.path("health").asText(""));
                    m.put("status", node.path("status").asText(""));
                    m.put("docs", node.path("docs.count").asText("0"));
                    long size = node.path("store.size").asLong(0);
                    m.put("size", humanSize(size));
                    m.put("sizeBytes", size);
                    out.add(m);
                }
            }
            return out;
        } catch (Exception e) {
            throw new ServiceException("获取索引失败: " + errorMessage(e));
        }
    }

    /** 检索日志：query 为空返回最新日志；index 为空查询全部索引 */
    public Map<String, Object> search(Long id, String query, String index, int size) {
        ElkConnection conn = getRaw(id);
        if (size <= 0) size = 20;
        if (size > 200) size = 200;
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            if (query == null || query.trim().isEmpty()) {
                body.put("query", singletonMap("match_all", new LinkedHashMap<String, Object>()));
            } else {
                Map<String, Object> qs = new LinkedHashMap<String, Object>();
                qs.put("query", query.trim());
                qs.put("lenient", true);
                qs.put("analyze_wildcard", true);
                qs.put("default_operator", "AND");
                body.put("query", singletonMap("query_string", qs));
            }
            body.put("size", size);
            body.put("sort", new Object[]{singletonMap("@timestamp", sortSpec()), singletonMap("_doc", new LinkedHashMap<String, Object>())});

            String path = (index == null || index.trim().isEmpty() || "*".equals(index.trim()))
                    ? "/_search" : "/" + index.trim() + "/_search";
            JsonNode root = json(HttpMethod.POST, conn.getEsUrl() + path, mapper.writeValueAsString(body), conn);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            JsonNode hitsNode = root.path("hits");
            result.put("took", root.path("took").asInt(0));
            JsonNode totalNode = hitsNode.path("total");
            long total = totalNode.isObject() ? totalNode.path("value").asLong(0) : totalNode.asLong(0);
            result.put("total", total);

            List<Map<String, Object>> hits = new ArrayList<Map<String, Object>>();
            for (JsonNode h : hitsNode.path("hits")) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("index", h.path("_index").asText(""));
                item.put("id", h.path("_id").asText(""));
                item.put("score", h.path("_score").asDouble());
                JsonNode source = h.path("_source");
                String ts = source.path("@timestamp").asText(source.path("timestamp").asText(""));
                item.put("timestamp", ts);
                item.put("source", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(source));
                hits.add(item);
            }
            result.put("hits", hits);
            return result;
        } catch (Exception e) {
            throw new ServiceException("日志检索失败: " + errorMessage(e));
        }
    }

    // ==================== 集群与分片 ====================

    /** 集群概览：健康状态 + 节点资源 + 分片分配（单项失败不影响其他项） */
    public Map<String, Object> cluster(Long id) {
        ElkConnection conn = getRaw(id);
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<String, Object>();

        Map<String, Object> health = new LinkedHashMap<String, Object>();
        try {
            JsonNode h = jsonUri(HttpMethod.GET,
                    UriComponentsBuilder.fromHttpUrl(conn.getEsUrl()).pathSegment("_cluster", "health")
                            .build().encode().toUri(), null, conn);
            health.put("status", h.path("status").asText(""));
            health.put("clusterName", h.path("cluster_name").asText(""));
            health.put("numberOfNodes", asLong(h, "number_of_nodes"));
            health.put("numberOfDataNodes", asLong(h, "number_of_data_nodes"));
            health.put("activePrimaryShards", asLong(h, "active_primary_shards"));
            health.put("activeShards", asLong(h, "active_shards"));
            health.put("relocatingShards", asLong(h, "relocating_shards"));
            health.put("initializingShards", asLong(h, "initializing_shards"));
            health.put("unassignedShards", asLong(h, "unassigned_shards"));
            health.put("delayedUnassignedShards", asLong(h, "delayed_unassigned_shards"));
            health.put("pendingTasks", asLong(h, "number_of_pending_tasks"));
            health.put("activeShardsPercent", asDouble(h, "active_shards_percent_as_number"));
            result.put("health", health);
            result.put("clusterName", h.path("cluster_name").asText(""));
        } catch (Exception e) {
            result.put("healthError", errorMessage(e));
        }

        // 节点（合并 allocation 中的分片数/磁盘占用）
        Map<String, Map<String, Object>> allocation = new LinkedHashMap<String, Map<String, Object>>();
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(conn.getEsUrl())
                    .pathSegment("_cat", "allocation")
                    .queryParam("format", "json")
                    .queryParam("bytes", "b")
                    .build().encode().toUri();
            JsonNode rows = jsonUri(HttpMethod.GET, uri, null, conn);
            for (JsonNode n : rows) {
                String name = n.path("node").asText("");
                if (name.isEmpty()) continue;
                Map<String, Object> a = new LinkedHashMap<String, Object>();
                a.put("shards", asLong(n, "shards"));
                a.put("diskIndicesBytes", asLong(n, "disk.indices"));
                a.put("diskUsedBytes", asLong(n, "disk.used"));
                a.put("diskAvailBytes", asLong(n, "disk.avail"));
                a.put("diskTotalBytes", asLong(n, "disk.total"));
                a.put("diskPercent", asDouble(n, "disk.percent"));
                allocation.put(name, a);
            }
        } catch (Exception e) {
            result.put("allocationError", errorMessage(e));
        }

        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(conn.getEsUrl())
                    .pathSegment("_cat", "nodes")
                    .queryParam("format", "json")
                    .queryParam("h", "name,ip,heap.percent,disk.used_percent,cpu,load_1m,node.role,master")
                    .build().encode().toUri();
            JsonNode rows = jsonUri(HttpMethod.GET, uri, null, conn);
            for (JsonNode n : rows) {
                String name = n.path("name").asText("");
                Map<String, Object> node = new LinkedHashMap<String, Object>();
                node.put("name", name);
                node.put("ip", n.path("ip").asText(""));
                node.put("heapPercent", asDouble(n, "heap.percent"));
                node.put("diskUsedPercent", asDouble(n, "disk.used_percent"));
                node.put("cpu", asLong(n, "cpu"));
                node.put("load1m", asDouble(n, "load_1m"));
                node.put("roles", n.path("node.role").asText(""));
                node.put("master", "*".equals(n.path("master").asText("")));
                Map<String, Object> a = allocation.get(name);
                if (a != null) node.putAll(a);
                nodes.add(node);
            }
        } catch (Exception e) {
            result.put("nodesError", errorMessage(e));
        }
        result.put("nodes", nodes);
        result.put("elapsedMs", System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 分片列表：可按索引/状态/主副本/节点过滤，结果最多返回 limit 条（默认 2000）。
     * stateCounts 基于过滤前全量统计，便于前端展示各状态总览。
     */
    public Map<String, Object> shards(Long id, String index, String state, String prirep,
                                      String node, int limit) {
        ElkConnection conn = getRaw(id);
        if (limit <= 0) limit = 2000;
        if (limit > 5000) limit = 5000;
        long start = System.currentTimeMillis();
        try {
            UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(conn.getEsUrl())
                    .pathSegment("_cat", "shards");
            if (index != null && !index.trim().isEmpty() && !"*".equals(index.trim())) {
                b.pathSegment(index.trim());
            }
            URI uri = b.queryParam("format", "json")
                    .queryParam("bytes", "b")
                    .queryParam("h", "index,shard,prirep,state,docs,store,ip,node,unassigned.reason")
                    .build().encode().toUri();
            JsonNode root = jsonUri(HttpMethod.GET, uri, null, conn);

            List<Map<String, Object>> matched = new ArrayList<Map<String, Object>>();
            Map<String, Long> stateCounts = new LinkedHashMap<String, Long>();
            int total = root.isArray() ? root.size() : 0;
            for (JsonNode n : root) {
                String st = n.path("state").asText("");
                stateCounts.put(st, stateCounts.getOrDefault(st, 0L) + 1);
                if (!matches(state, n.path("state").asText(""))) continue;
                if (!matches(prirep, n.path("prirep").asText(""))) continue;
                if (node != null && !node.trim().isEmpty()
                        && !node.trim().equalsIgnoreCase(n.path("node").asText(""))) continue;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("index", n.path("index").asText(""));
                m.put("shard", asLong(n, "shard"));
                m.put("prirep", n.path("prirep").asText(""));
                m.put("state", st);
                m.put("docs", asLong(n, "docs"));
                long store = asLong(n, "store");
                m.put("storeBytes", store);
                m.put("store", humanSize(store));
                m.put("ip", n.path("ip").asText(""));
                m.put("node", n.path("node").asText(""));
                String reason = n.path("unassigned.reason").asText("");
                m.put("unassignedReason", reason.isEmpty() ? null : reason);
                matched.add(m);
            }
            matched.sort((a, bb) -> {
                int c = String.valueOf(a.get("index")).compareTo(String.valueOf(bb.get("index")));
                if (c != 0) return c;
                return Long.compare((Long) a.get("shard"), (Long) bb.get("shard"));
            });
            int filtered = matched.size();
            boolean truncated = filtered > limit;
            List<Map<String, Object>> page = truncated ? new ArrayList<Map<String, Object>>(matched.subList(0, limit)) : matched;

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("total", total);
            result.put("filtered", filtered);
            result.put("returned", page.size());
            result.put("truncated", truncated);
            result.put("limit", limit);
            result.put("stateCounts", stateCounts);
            result.put("shards", page);
            result.put("elapsedMs", System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            throw new ServiceException("获取分片状态失败: " + errorMessage(e));
        }
    }

    private boolean matches(String filter, String value) {
        return filter == null || filter.trim().isEmpty() || filter.trim().equalsIgnoreCase(value);
    }

    private long asLong(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isNumber()) return v.asLong();
        try { return Long.parseLong(v.asText("0")); } catch (Exception e) { return 0L; }
    }

    private double asDouble(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isNumber()) return v.asDouble();
        try { return Double.parseDouble(v.asText("0")); } catch (Exception e) { return 0d; }
    }

    // ==================== HTTP ====================

    private JsonNode json(HttpMethod method, String url, String body, ElkConnection conn) {
        try {
            return jsonUri(method, URI.create(normalizeUrl(url)), body, conn);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("非法的 Elasticsearch 地址: " + url);
        }
    }

    private JsonNode jsonUri(HttpMethod method, URI uri, String body, ElkConnection conn) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        if (conn.getEsUsername() != null && !conn.getEsUsername().trim().isEmpty()) {
            String pwd = decrypt(conn);
            headers.setBasicAuth(conn.getEsUsername().trim(), pwd == null ? "" : pwd);
        }
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<String>(body, headers);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(uri, method, entity, String.class);
            String text = resp.getBody();
            return text == null || text.trim().isEmpty() ? mapper.createObjectNode() : mapper.readTree(text);
        } catch (HttpStatusCodeException e) {
            throw new ServiceException("HTTP " + e.getRawStatusCode() + ": " + trim(e.getResponseBodyAsString()));
        } catch (ResourceAccessException e) {
            throw new ServiceException("无法连接: " + e.getMessage());
        } catch (Exception e) {
            throw new ServiceException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private String decrypt(ElkConnection conn) {
        String enc = conn.getEsPasswordEncrypted();
        if (enc == null || enc.isEmpty()) return null;
        try { return passwordEncryptor.decrypt(enc); }
        catch (Exception e) { throw new ServiceException("密码解密失败，请重新设置连接密码"); }
    }

    private void validate(ElkConnection input) {
        if (input.getName() == null || input.getName().trim().isEmpty()) throw new ServiceException("连接名称不能为空");
        if (input.getEsUrl() == null || input.getEsUrl().trim().isEmpty()) throw new ServiceException("Elasticsearch 地址不能为空");
        input.setName(input.getName().trim());
    }

    private String normalizeUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        return u.replaceAll("/+$", "");
    }

    private Map<String, Object> sortSpec() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("order", "desc");
        m.put("unmapped_type", "boolean");
        return m;
    }

    private Map<String, Object> singletonMap(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(key, value);
        return m;
    }

    private String humanSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return (i == 0 ? String.valueOf((long) v) : String.format("%.1f", v)) + " " + units[i];
    }

    private String trim(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private String errorMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : trim(msg);
    }

    private ElkConnection toSafe(ElkConnection src) {
        if (src == null) return null;
        ElkConnection c = new ElkConnection();
        c.setId(src.getId());
        c.setName(src.getName());
        c.setEsUrl(src.getEsUrl());
        c.setLogstashUrl(src.getLogstashUrl());
        c.setEsUsername(src.getEsUsername());
        c.setStatus(src.getStatus());
        c.setCreateTime(src.getCreateTime());
        c.setUpdateTime(src.getUpdateTime());
        c.setPasswordSet(src.getEsPasswordEncrypted() != null && !src.getEsPasswordEncrypted().isEmpty());
        return c;
    }
}