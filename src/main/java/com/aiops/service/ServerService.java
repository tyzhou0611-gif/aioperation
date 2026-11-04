package com.aiops.service;

import com.aiops.common.PageResult;
import com.aiops.common.ServiceException;
import com.aiops.entity.Project;
import com.aiops.entity.ProjectAsset;
import com.aiops.entity.ServerAsset;
import com.aiops.infrastructure.metrics.MetricsProvider;
import com.aiops.infrastructure.metrics.NodeInstance;
import com.aiops.repository.ProjectAssetRepository;
import com.aiops.repository.ProjectRepository;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务器资产管理服务
 * 提供增删改查、模糊搜索；状态来源见 {@link ServerStatusService}（Prometheus/SSH/TCP/ICMP）。
 */
@Service
@RequiredArgsConstructor
public class ServerService {

    private final ServerAssetRepository repository;
    private final MetricsProvider metricsProvider;
    private final PasswordEncryptor passwordEncryptor;
    private final AccessControlService accessControl;
    private final ProjectAssetRepository projectAssetRepository;
    private final ProjectRepository projectRepository;

    /**
     * 分页查询（支持IP、主机名、环境的模糊搜索，按项目/角色可见范围过滤）
     */
    public PageResult<ServerAsset> pageQuery(String keyword, Long projectId, int page, int pageSize, String username) {
        final Set<Long> visibleServerIds = accessControl.accessibleServerIds(username);
        final Set<Long> projectServerIds;
        if (projectId != null) {
            Set<Long> allowedProjects = accessControl.accessibleProjectIds(username);
            if (allowedProjects != null && !allowedProjects.contains(projectId)) {
                projectServerIds = new HashSet<Long>(); // 无权访问该项目
            } else {
                projectServerIds = new HashSet<Long>();
                for (ProjectAsset pa : projectAssetRepository.findByProjectIdAndAssetType(projectId, "SERVER")) {
                    if (pa.getAssetId() != null) projectServerIds.add(pa.getAssetId());
                }
            }
        } else {
            projectServerIds = null;
        }

        final Set<Long> restrict = intersect(visibleServerIds, projectServerIds);

        Specification<ServerAsset> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.trim().isEmpty()) {
                String pattern = "%" + keyword.trim() + "%";
                List<Predicate> like = new ArrayList<>();
                like.add(cb.like(root.get("ip"), pattern));
                like.add(cb.like(root.get("hostname"), pattern));
                like.add(cb.like(root.get("os"), pattern));
                like.add(cb.like(root.get("environment"), pattern));
                like.add(cb.like(root.get("tags"), pattern));
                predicates.add(cb.or(like.toArray(new Predicate[0])));
            }
            if (restrict != null) {
                if (restrict.isEmpty()) return cb.disjunction();
                predicates.add(root.get("id").in(restrict));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageRequest = PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Direction.ASC, "id"));
        Page<ServerAsset> result = repository.findAll(spec, pageRequest);
        result.getContent().forEach(s -> {
            scrub(s);
            populateProjects(s);
        });
        return PageResult.of(result);
    }

    /** null=不限制；否则取交集 */
    private Set<Long> intersect(Set<Long> a, Set<Long> b) {
        if (a == null) return b;
        if (b == null) return a;
        Set<Long> out = new HashSet<Long>(a);
        out.retainAll(b);
        return out;
    }

    /** 出参补充服务器所属项目 */
    private void populateProjects(ServerAsset s) {
        List<Long> ids = new ArrayList<Long>();
        List<String> names = new ArrayList<String>();
        if (s != null && s.getId() != null) {
            for (ProjectAsset pa : projectAssetRepository.findByAssetTypeAndAssetId("SERVER", s.getId())) {
                ids.add(pa.getProjectId());
            }
            if (!ids.isEmpty()) {
                for (Project p : projectRepository.findAllById(ids)) {
                    if (p != null) names.add(p.getName());
                }
            }
        }
        s.setProjectIds(ids);
        s.setProjectNames(names);
    }

    public ServerAsset getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ServiceException("服务器不存在，ID: " + id));
    }

    @Transactional
    public Long create(ServerAsset asset) {
        validateTags(asset.getTags());
        if (repository.findByIpContaining(asset.getIp()).stream()
                .anyMatch(a -> a.getIp().equals(asset.getIp()))) {
            throw new ServiceException("IP地址已存在: " + asset.getIp());
        }
        String plain = asset.getSshPassword();
        asset.setSshPassword(null);
        asset.setSshPasswordEncrypted(plain != null && !plain.isEmpty() ? passwordEncryptor.encrypt(plain) : null);
        asset.setStatus("UNKNOWN");
        asset.setStatusSource(null);
        return repository.save(asset).getId();
    }

    @Transactional
    public Long update(Long id, ServerAsset update) {
        validateTags(update.getTags());
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
        existing.setTags(update.getTags());
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

    /**
     * 以现有服务器为模板复制一条新记录。
     * 密码留空时直接复用源服务器的加密密码（不暴露明文）；也可传入新密码覆盖。
     */
    @Transactional
    public Long clone(Long sourceId, ServerAsset update) {
        validateTags(update == null ? null : update.getTags());
        ServerAsset source = getById(sourceId);
        if (update == null || update.getIp() == null || update.getIp().trim().isEmpty()) {
            throw new ServiceException("IP地址不能为空");
        }
        String ip = update.getIp().trim();
        if (repository.findByIpContaining(ip).stream().anyMatch(a -> ip.equals(a.getIp()))) {
            throw new ServiceException("IP地址已存在: " + ip);
        }
        ServerAsset a = new ServerAsset();
        a.setIp(ip);
        a.setHostname(update.getHostname());
        a.setOs(update.getOs());
        a.setCpu(update.getCpu());
        a.setMemory(update.getMemory());
        a.setDisk(update.getDisk());
        a.setEnvironment(update.getEnvironment());
        a.setSshPort(update.getSshPort());
        a.setSshUser(update.getSshUser());
        a.setTags(update.getTags());
        a.setStatus("UNKNOWN");
        a.setStatusSource(null);
        // 密码：新密码优先；留空则复用源服务器密文；clearPassword=true 则不带密码
        if (update.isClearPassword()) {
            a.setSshPasswordEncrypted(null);
        } else if (update.getSshPassword() != null && !update.getSshPassword().isEmpty()) {
            a.setSshPasswordEncrypted(passwordEncryptor.encrypt(update.getSshPassword()));
        } else {
            a.setSshPasswordEncrypted(source.getSshPasswordEncrypted());
        }
        // 私钥：新私钥优先；留空则复用源服务器
        a.setSshKey(update.getSshKey() != null && !update.getSshKey().trim().isEmpty()
                ? update.getSshKey() : source.getSshKey());
        a.setSshPassword(null);
        return repository.save(a).getId();
    }

    /** 标签：JSON 数组字符串，只做容量保护 */
    private void validateTags(String tags) {
        if (tags == null || tags.trim().isEmpty()) return;
        if (tags.length() > 1000) {
            throw new ServiceException("标签内容过长（最多 1000 字符），请精简后再保存");
        }
    }

    /** 脱敏：清空密码字段，仅保留 sshPasswordSet 标志 */
    private ServerAsset scrub(ServerAsset a) {
        if (a == null) return null;
        a.setSshPasswordSet(a.getSshPasswordEncrypted() != null && !a.getSshPasswordEncrypted().isEmpty());
        a.setSshPassword(null);
        a.setSshPasswordEncrypted(null);
        return a;
    }

    /** 详情（脱敏，补充所属项目） */
    public ServerAsset getDetail(Long id) {
        ServerAsset s = scrub(getById(id));
        populateProjects(s);
        return s;
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

        int added = 0, updated = 0, unchanged = 0, preserved = 0;
        java.time.LocalDateTime probeTime = java.time.LocalDateTime.now();
        java.util.Set<String> discoveredIps = new java.util.HashSet<>();
        for (NodeInstance n : nodes) {
            String ip = n.getIp().trim();
            discoveredIps.add(ip);
            String status = n.isOnline() ? "ONLINE" : "OFFLINE";
            ServerAsset s = byIp.get(ip);
            if (s != null) {
                // 增量同步：只更新状态/来源/探测时间，绝不覆盖主机名、环境、SSH、标签、项目等人工字段
                if ("MAINTENANCE".equalsIgnoreCase(s.getStatus())) {
                    preserved++;
                    continue;
                }
                boolean changed = !status.equalsIgnoreCase(s.getStatus() == null ? "" : s.getStatus());
                s.setStatus(status);
                s.setStatusSource("prometheus");
                s.setLastProbeTime(probeTime);
                repository.save(s);
                if (changed) updated++; else unchanged++;
            } else {
                ServerAsset ns = new ServerAsset();
                ns.setIp(ip);
                ns.setHostname("node-" + ip.substring(ip.lastIndexOf('.') + 1));
                ns.setOs("Linux");
                ns.setStatus(status);
                ns.setStatusSource("prometheus");
                ns.setLastProbeTime(probeTime);
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
                    && "ONLINE".equals(s.getStatus())
                    && "prometheus".equalsIgnoreCase(s.getStatusSource())) {
                s.setStatus("OFFLINE");
                s.setStatusSource("prometheus");
                s.setLastProbeTime(probeTime);
                repository.save(s);
                offline++;
            }
        }

        summary.put("source", "Prometheus");
        summary.put("discovered", nodes.size());
        summary.put("added", added);
        summary.put("updated", updated);
        summary.put("unchanged", unchanged);
        summary.put("preserved", preserved);
        summary.put("markedOffline", offline);
        summary.put("total", repository.count());
        summary.put("message", String.format(
                "增量同步完成：发现 %d 节点，新增 %d，状态更新 %d，未变化 %d，维护中保留 %d，下线标记 %d（主机名/环境/SSH/标签/项目等人工字段不会被覆盖）",
                nodes.size(), added, updated, unchanged, preserved, offline));
        return summary;
    }
}