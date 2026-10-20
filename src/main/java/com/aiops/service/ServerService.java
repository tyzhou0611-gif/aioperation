package com.aiops.service;

import com.aiops.common.PageResult;
import com.aiops.common.ServiceException;
import com.aiops.entity.ServerAsset;
import com.aiops.infrastructure.metrics.MetricsProvider;
import com.aiops.infrastructure.metrics.NodeInstance;
import com.aiops.repository.ServerAssetRepository;
import javax.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器资产管理服务
 * 提供增删改查、模糊搜索；状态探测与同步均走 MetricsProvider（Prometheus up），不使用 SSH。
 */
@Service
@RequiredArgsConstructor
public class ServerService {

    private final ServerAssetRepository repository;
    private final MetricsProvider metricsProvider;
    private final PasswordEncryptor passwordEncryptor;

    /**
     * 分页查询（支持IP、主机名、环境的模糊搜索）
     */
    public PageResult<ServerAsset> pageQuery(String keyword, int page, int pageSize) {
        Specification<ServerAsset> spec = (root, query, cb) -> {
            if (keyword == null || keyword.trim().isEmpty()) {
                return cb.conjunction();
            }
            String pattern = "%" + keyword.trim() + "%";
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.like(root.get("ip"), pattern));
            predicates.add(cb.like(root.get("hostname"), pattern));
            predicates.add(cb.like(root.get("os"), pattern));
            predicates.add(cb.like(root.get("environment"), pattern));
            return cb.or(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageRequest = PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Direction.ASC, "id"));
        Page<ServerAsset> result = repository.findAll(spec, pageRequest);
        result.getContent().forEach(this::scrub);
        return PageResult.of(result);
    }

    public ServerAsset getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ServiceException("服务器不存在，ID: " + id));
    }

    @Transactional
    public Long create(ServerAsset asset) {
        if (repository.findByIpContaining(asset.getIp()).stream()
                .anyMatch(a -> a.getIp().equals(asset.getIp()))) {
            throw new ServiceException("IP地址已存在: " + asset.getIp());
        }
        String plain = asset.getSshPassword();
        asset.setSshPassword(null);
        asset.setSshPasswordEncrypted(plain != null && !plain.isEmpty() ? passwordEncryptor.encrypt(plain) : null);
        return repository.save(asset).getId();
    }

    @Transactional
    public Long update(Long id, ServerAsset update) {
        ServerAsset existing = getById(id);
        existing.setIp(update.getIp());
        existing.setHostname(update.getHostname());
        existing.setOs(update.getOs());
        existing.setCpu(update.getCpu());
        existing.setMemory(update.getMemory());
        existing.setDisk(update.getDisk());
        existing.setEnvironment(update.getEnvironment());
        existing.setSshPort(update.getSshPort());
        existing.setSshUser(update.getSshUser());
        existing.setSshKey(update.getSshKey());
        // 密码：空=不修改；clearPassword=清除；非空=加密更新
        String plain = update.getSshPassword();
        if (update.isClearPassword()) {
            existing.setSshPasswordEncrypted(null);
            existing.setSshPassword(null);
        } else if (plain != null && !plain.isEmpty()) {
            existing.setSshPasswordEncrypted(passwordEncryptor.encrypt(plain));
            existing.setSshPassword(null);
        }
        // 明文字段一律不落库
        existing.setSshPassword(null);
        repository.save(existing);
        return existing.getId();
    }

    /** 脱敏：清空密码字段，仅保留 sshPasswordSet 标志 */
    private ServerAsset scrub(ServerAsset a) {
        if (a == null) return null;
        a.setSshPasswordSet(a.getSshPasswordEncrypted() != null && !a.getSshPasswordEncrypted().isEmpty());
        a.setSshPassword(null);
        a.setSshPasswordEncrypted(null);
        return a;
    }

    /** 详情（脱敏） */
    public ServerAsset getDetail(Long id) {
        return scrub(getById(id));
    }

    /** SSH 连接用：解密真实密码（无密码返回 null） */
    public String decryptPassword(ServerAsset a) {
        if (a == null) return null;
        String enc = a.getSshPasswordEncrypted();
        if (enc != null && !enc.isEmpty()) return passwordEncryptor.decrypt(enc);
        // 兼容旧明文
        return a.getSshPassword();
    }

    /** 启动时一次性迁移：旧明文 ssh_password → 加密列，并清空明文 */
    @javax.annotation.PostConstruct
    @Transactional
    public void migratePasswords() {
        try {
            int n = 0;
            for (ServerAsset a : repository.findAll()) {
                boolean hasPlain = a.getSshPassword() != null && !a.getSshPassword().isEmpty();
                boolean noEnc = a.getSshPasswordEncrypted() == null || a.getSshPasswordEncrypted().isEmpty();
                if (hasPlain && noEnc) {
                    a.setSshPasswordEncrypted(passwordEncryptor.encrypt(a.getSshPassword()));
                    a.setSshPassword(null);
                    repository.save(a);
                    n++;
                } else if (hasPlain) {
                    a.setSshPassword(null);
                    repository.save(a);
                }
            }
            if (n > 0) org.slf4j.LoggerFactory.getLogger(ServerService.class)
                    .info("SSH 密码迁移完成：{} 条明文已加密", n);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ServerService.class)
                    .warn("SSH 密码迁移跳过：{}", e.getMessage());
        }
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ServiceException("服务器不存在，ID: " + id);
        }
        repository.deleteById(id);
    }

    /**
     * 探测单台服务器状态：查询 Prometheus 的 up 指标（非 SSH）。
     * UNKNOWN（数据源非Prometheus或不可达）时保持原状态。
     */
    @Transactional
    public ServerAsset probeStatus(Long id) {
        ServerAsset asset = getById(id);
        String status = metricsProvider.getServerStatus(asset.getId());
        if (!"UNKNOWN".equals(status) && status != null) {
            asset.setStatus(status);
        }
        return scrub(repository.save(asset));
    }

    /**
     * 探测所有服务器状态（Prometheus up）。
     */
    @Transactional
    public List<ServerAsset> probeAll() {
        List<ServerAsset> all = repository.findAll();
        for (ServerAsset asset : all) {
            String status = metricsProvider.getServerStatus(asset.getId());
            if (!"UNKNOWN".equals(status) && status != null) {
                asset.setStatus(status);
            }
        }
        List<ServerAsset> saved = repository.saveAll(all);
        saved.forEach(this::scrub);
        return saved;
    }

    /**
     * 从 Prometheus 一键同步服务器：
     * - 发现 node_exporter 全部实例；
     * - 已存在（按IP匹配）→ 更新在线/离线状态；
     * - 新实例 → 自动新增；
     * - Prometheus 里已消失的服务器 → 标记 OFFLINE（不删除，保留人工信息）。
     */
    @Transactional
    public Map<String, Object> syncFromPrometheus() {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<NodeInstance> nodes = metricsProvider.discoverNodeInstances();
        if (nodes.isEmpty()) {
            summary.put("source", metricsProvider.getClass().getSimpleName());
            summary.put("added", 0);
            summary.put("updated", 0);
            summary.put("total", repository.count());
            summary.put("message", "未发现可同步节点（当前指标源非 Prometheus 或 Prometheus 不可达）");
            return summary;
        }

        List<ServerAsset> existing = repository.findAll();
        Map<String, ServerAsset> byIp = new LinkedHashMap<>();
        for (ServerAsset s : existing) {
            if (s.getIp() != null) byIp.put(s.getIp().trim(), s);
        }

        int added = 0, updated = 0;
        java.util.Set<String> discoveredIps = new java.util.HashSet<>();
        for (NodeInstance n : nodes) {
            String ip = n.getIp().trim();
            discoveredIps.add(ip);
            String status = n.isOnline() ? "ONLINE" : "OFFLINE";
            ServerAsset s = byIp.get(ip);
            if (s != null) {
                s.setStatus(status);
                repository.save(s);
                updated++;
            } else {
                ServerAsset ns = new ServerAsset();
                ns.setIp(ip);
                ns.setHostname("node-" + ip.substring(ip.lastIndexOf('.') + 1));
                ns.setOs("Linux");
                ns.setStatus(status);
                ns.setEnvironment("production");
                ns.setSshPort(22);
                ns.setSshUser("root");
                repository.save(ns);
                added++;
            }
        }

        // Prometheus 中已消失的已知服务器 → OFFLINE
        int offline = 0;
        for (ServerAsset s : existing) {
            if (s.getIp() != null && !discoveredIps.contains(s.getIp().trim())
                    && "ONLINE".equals(s.getStatus())) {
                s.setStatus("OFFLINE");
                repository.save(s);
                offline++;
            }
        }

        summary.put("source", "Prometheus");
        summary.put("discovered", nodes.size());
        summary.put("added", added);
        summary.put("updated", updated);
        summary.put("markedOffline", offline);
        summary.put("total", repository.count());
        summary.put("message", String.format("同步完成：发现 %d 节点，新增 %d，更新 %d，下线标记 %d",
                nodes.size(), added, updated, offline));
        return summary;
    }
}