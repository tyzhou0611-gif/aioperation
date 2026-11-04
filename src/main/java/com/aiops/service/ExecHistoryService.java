package com.aiops.service;

import com.aiops.common.PageResult;
import com.aiops.entity.AuditLog;
import com.aiops.entity.ExecRun;
import com.aiops.entity.ExecRunHost;
import com.aiops.entity.ServerAsset;
import com.aiops.repository.AuditLogRepository;
import com.aiops.repository.ExecRunHostRepository;
import com.aiops.repository.ExecRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命令执行历史与审计。
 */
@Service
@RequiredArgsConstructor
public class ExecHistoryService {

    private final ExecRunRepository runRepo;
    private final ExecRunHostRepository hostRepo;
    private final AuditLogRepository auditRepo;

    @Transactional
    public ExecRun start(String requestId, String operator, String command, int serverCount,
                         boolean dryRun, String riskLevel) {
        ExecRun run = new ExecRun();
        run.setRequestId(requestId);
        run.setOperator(operator);
        run.setCommand(command);
        run.setServerCount(serverCount);
        run.setDryRun(dryRun ? 1 : 0);
        run.setRiskLevel(riskLevel);
        run.setStatus("RUNNING");
        run.setStartTime(LocalDateTime.now());
        return runRepo.save(run);
    }

    @Transactional
    public void saveHost(Long runId, ServerAsset server, SshResult r) {
        ExecRunHost h = new ExecRunHost();
        h.setRunId(runId);
        if (server != null) {
            h.setServerId(server.getId());
            h.setHostname(server.getHostname());
            h.setIp(server.getIp());
            h.setEnvironment(server.getEnvironment());
        }
        if (r != null) {
            h.setExitCode(r.getExitCode());
            h.setSuccess(r.isSuccess() ? 1 : 0);
            h.setDurationMs(r.getDurationMs());
            h.setStdout(limit(r.getStdout(), 500_000));
            h.setStderr(limit(r.getStderr(), 200_000));
            h.setErrorMsg(limit(r.getErrorMessage(), 1000));
            h.setKilled(r.isKilled() ? 1 : 0);
        }
        hostRepo.save(h);
    }

    @Transactional
    public void saveDryHost(Long runId, ServerAsset server) {
        ExecRunHost h = new ExecRunHost();
        h.setRunId(runId);
        if (server != null) {
            h.setServerId(server.getId());
            h.setHostname(server.getHostname());
            h.setIp(server.getIp());
            h.setEnvironment(server.getEnvironment());
        }
        h.setSuccess(1);
        h.setExitCode(0);
        h.setDurationMs(0L);
        h.setStdout("[DRY-RUN] 未实际执行，仅预览命令");
        hostRepo.save(h);
    }

    @Transactional
    public void finish(Long runId, int success, int fail, long durationMs, String status) {
        ExecRun run = runRepo.findById(runId).orElse(null);
        if (run == null) return;
        run.setSuccessCount(success);
        run.setFailCount(fail);
        run.setDurationMs(durationMs);
        run.setEndTime(LocalDateTime.now());
        run.setStatus(status == null ? (fail == 0 ? "SUCCESS" : "PARTIAL") : status);
        runRepo.save(run);
    }

    public PageResult<Map<String, Object>> page(int page, int pageSize, String keyword, Long serverId,
                                                String start, String end) {
        if (page < 1) page = 1;
        if (pageSize <= 0) pageSize = 10;
        if (pageSize > 100) pageSize = 100;

        final Long sid = serverId;
        final Set<Long> runIdsByServer = new LinkedHashSet<Long>();
        if (sid != null) {
            for (ExecRunHost h : hostRepo.findByServerId(sid)) runIdsByServer.add(h.getRunId());
        }
        final LocalDateTime from = parseTime(start, true);
        final LocalDateTime to = parseTime(end, false);
        final String kw = keyword == null ? "" : keyword.trim();

        Specification<ExecRun> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<Predicate>();
            if (!kw.isEmpty()) {
                String p = "%" + kw.toLowerCase() + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("command")), p), cb.like(cb.lower(root.get("operator")), p)));
            }
            if (sid != null) {
                if (runIdsByServer.isEmpty()) return cb.disjunction();
                ps.add(root.get("id").in(runIdsByServer));
            }
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createTime"), from));
            if (to != null) ps.add(cb.lessThanOrEqualTo(root.get("createTime"), to));
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
        Page<ExecRun> result = runRepo.findAll(spec, PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Direction.DESC, "id")));

        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        for (ExecRun r : result.getContent()) records.add(runMap(r, false));
        PageResult<Map<String, Object>> pr = new PageResult<Map<String, Object>>();
        pr.setRecords(records);
        pr.setTotal(result.getTotalElements());
        pr.setPage(page);
        pr.setPageSize(pageSize);
        pr.setPages(result.getTotalPages());
        return pr;
    }

    public Map<String, Object> detail(Long id) {
        ExecRun run = runRepo.findById(id).orElse(null);
        if (run == null) return null;
        Map<String, Object> out = runMap(run, true);
        out.put("hosts", hostRepo.findByRunIdOrderByIdAsc(id));
        return out;
    }

    public String exportCsv(Long id) {
        ExecRun run = runRepo.findById(id).orElse(null);
        if (run == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("runId,time,operator,command,server,ip,environment,exitCode,success,durationMs,error,stdout\n");
        for (ExecRunHost h : hostRepo.findByRunIdOrderByIdAsc(id)) {
            sb.append(csv(String.valueOf(run.getId()))).append(',')
              .append(csv(run.getCreateTime() == null ? "" : run.getCreateTime().toString())).append(',')
              .append(csv(run.getOperator())).append(',')
              .append(csv(run.getCommand())).append(',')
              .append(csv(h.getHostname())).append(',')
              .append(csv(h.getIp())).append(',')
              .append(csv(h.getEnvironment())).append(',')
              .append(h.getExitCode() == null ? "" : h.getExitCode()).append(',')
              .append(h.getSuccess() == null ? "" : h.getSuccess()).append(',')
              .append(h.getDurationMs() == null ? "" : h.getDurationMs()).append(',')
              .append(csv(h.getErrorMsg())).append(',')
              .append(csv(h.getStdout())).append('\n');
        }
        return sb.toString();
    }

    /** 写入 AUDIT_LOG（命令执行操作） */
    @Transactional
    public void audit(String operator, String url, String method, String params, int status, long durationMs) {
        try {
            AuditLog a = new AuditLog();
            a.setUsername(operator);
            a.setRequestUrl(url);
            a.setHttpMethod(method);
            a.setRequestParams(limit(params, 2000));
            a.setResponseStatus(status);
            a.setDurationMs(durationMs);
            a.setIpAddress("internal");
            auditRepo.save(a);
        } catch (Exception ignore) {
        }
    }

    private Map<String, Object> runMap(ExecRun r, boolean full) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", r.getId());
        m.put("requestId", r.getRequestId());
        m.put("operator", r.getOperator());
        m.put("command", full ? r.getCommand() : limit(r.getCommand(), 500));
        m.put("serverCount", r.getServerCount());
        m.put("successCount", r.getSuccessCount());
        m.put("failCount", r.getFailCount());
        m.put("dryRun", r.getDryRun());
        m.put("riskLevel", r.getRiskLevel());
        m.put("status", r.getStatus());
        m.put("startTime", r.getStartTime());
        m.put("endTime", r.getEndTime());
        m.put("durationMs", r.getDurationMs());
        m.put("createTime", r.getCreateTime());
        return m;
    }

    private LocalDateTime parseTime(String s, boolean startOfDay) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            String v = s.trim().replace(' ', 'T');
            if (v.length() == 10) v = v + (startOfDay ? "T00:00:00" : "T23:59:59");
            return LocalDateTime.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    private String limit(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "\n...[truncated]" : s;
    }

    private String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"").replace("\r", "").replace("\n", " ");
        return "\"" + v + "\"";
    }
}