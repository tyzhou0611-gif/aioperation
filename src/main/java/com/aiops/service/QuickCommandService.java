package com.aiops.service;

import java.util.Arrays;
import com.aiops.entity.AppAsset;
import com.aiops.entity.ExecLog;
import com.aiops.entity.MiddlewareAsset;
import com.aiops.repository.AppAssetRepository;
import com.aiops.repository.ExecLogRepository;
import com.aiops.repository.MiddlewareAssetRepository;
import com.aiops.repository.ServerAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一键命令下发服务：
 * - 解析目标的宿主服务器（SERVER/MIDDLEWARE/APP）
 * - 危险命令黑名单拦截
 * - 复用 ExecService 异步执行，前端轮询结果
 * - 执行记录写入 exec_log
 */
@Service
public class QuickCommandService {

    private static final Logger log = LoggerFactory.getLogger(QuickCommandService.class);

    private final ServerAssetRepository serverRepository;
    private final MiddlewareAssetRepository middlewareRepository;
    private final AppAssetRepository appRepository;
    private final ExecService execService;
    private final ExecLogRepository execLogRepository;
    private final ThreadPoolTaskExecutor sshExecutor;

    /** 内存任务表（黑盒单实例足够；多实例可换 Redis） */
    private final Map<String, ExecTask> tasks = new ConcurrentHashMap<>();

    private static final String[] BLACKLIST = {
            "rm -rf", "rm -fr", "mkfs", "dd if=", "shutdown", "reboot", ":(){", "> /dev/sd", "chmod -R 777 /"
    };

    public QuickCommandService(ServerAssetRepository serverRepository,
                              MiddlewareAssetRepository middlewareRepository,
                              AppAssetRepository appRepository,
                              ExecService execService,
                              ExecLogRepository execLogRepository,
                              ThreadPoolTaskExecutor sshExecutor) {
        this.serverRepository = serverRepository;
        this.middlewareRepository = middlewareRepository;
        this.appRepository = appRepository;
        this.execService = execService;
        this.execLogRepository = execLogRepository;
        this.sshExecutor = sshExecutor;
    }

    /** 校验命令是否命中黑名单 */
    public String checkBlacklist(String command) {
        if (command == null || command.trim().isEmpty()) return "命令不能为空";
        String low = command.toLowerCase(Locale.ROOT);
        for (String b : BLACKLIST) {
            if (low.contains(b)) return "命中危险命令黑名单，禁止执行: " + b;
        }
        return null; // 通过
    }

    /**
     * 提交一键命令，立即返回 taskId。
     * @return ExecTask（初始 RUNNING）；若无法解析宿主服务器或黑名单，则 status=ERROR
     */
    public ExecTask submit(String targetType, Long targetId, String command, String operator) {
        String taskStr = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ExecTask task = new ExecTask(taskStr);

        // 黑名单
        String bl = checkBlacklist(command);
        if (bl != null) {
            task.setStatus("ERROR"); task.setSuccess(false); task.setOutput(bl);
            return task;
        }

        // 解析宿主服务器
        ResolvedTarget rt = resolve(targetType, targetId);
        if (rt == null) {
            task.setStatus("ERROR"); task.setSuccess(false);
            task.setOutput("未找到目标资产或其关联的宿主服务器，无法执行（黑盒环境属正常降级）");
            return task;
        }
        task.setTargetIp(rt.ip);

        // 异步执行
        final Long serverId = rt.serverId;
        final String finalCmd = command;
        tasks.put(taskStr, task);
        sshExecutor.execute(() -> {
            try {
                List<SshResult> results = execService.executeOnServers(Arrays.asList(serverId), finalCmd);
                SshResult r = results.isEmpty() ? null : results.get(0);
                if (r == null) {
                    task.setStatus("ERROR"); task.setSuccess(false); task.setOutput("无执行结果（目标服务器不可达）");
                } else if (r.isSuccess()) {
                    task.setStatus("SUCCESS"); task.setSuccess(true);
                    task.setOutput(mergeOutput(r));
                } else {
                    task.setStatus("FAILED"); task.setSuccess(false);
                    task.setOutput(r.getErrorMessage() != null ? r.getErrorMessage() : mergeOutput(r));
                }
                writeLog(operator, targetType, targetId, rt.ip, finalCmd, task.getOutput(), task.isSuccess());
            } catch (Exception e) {
                task.setStatus("ERROR"); task.setSuccess(false);
                task.setOutput("执行异常: " + e.getMessage() + "（SSH 不可达时属预期降级）");
                log.warn("一键命令执行异常: {}", e.getMessage());
            }
        });
        return task;
    }

    public ExecTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    private String mergeOutput(SshResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.getStdout() != null) sb.append(r.getStdout());
        if (r.getStderr() != null && !r.getStderr().trim().isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[stderr] ").append(r.getStderr());
        }
        return sb.length() == 0 ? "(命令执行完成，无输出)" : sb.toString();
    }

    private void writeLog(String operator, String targetType, Long targetId, String ip,
                          String command, String result, boolean success) {
        try {
            ExecLog el = new ExecLog();
            el.setOperator(operator); el.setTargetType(targetType); el.setTargetId(targetId);
            el.setTargetIp(ip); el.setCommand(command);
            el.setResult(result != null && result.length() > 2000 ? result.substring(0, 2000) : result);
            el.setSuccess(success ? 1 : 0);
            execLogRepository.save(el);
        } catch (Exception e) {
            log.warn("写入 exec_log 失败: {}", e.getMessage());
        }
    }

    private ResolvedTarget resolve(String targetType, Long targetId) {
        if (targetType == null || targetId == null) return null;
        String tt = targetType.toUpperCase(Locale.ROOT);
        if ("SERVER".equals(tt)) {
            return serverRepository.findById(targetId)
                    .map(s -> new ResolvedTarget(s.getId(), s.getIp())).orElse(null);
        } else if ("MIDDLEWARE".equals(tt)) {
            return middlewareRepository.findById(targetId)
                    .map(MiddlewareAsset::getServerId).filter(id -> id != null)
                    .flatMap(serverRepository::findById)
                    .map(s -> new ResolvedTarget(s.getId(), s.getIp())).orElse(null);
        } else if ("APP".equals(tt)) {
            return appRepository.findById(targetId)
                    .map(AppAsset::getServerId).filter(id -> id != null)
                    .flatMap(serverRepository::findById)
                    .map(s -> new ResolvedTarget(s.getId(), s.getIp())).orElse(null);
        }
        return null;
    }

    /** 解析后的宿主服务器引用 */
    private static class ResolvedTarget {
        final Long serverId; final String ip;
        ResolvedTarget(Long serverId, String ip) { this.serverId = serverId; this.ip = ip; }
    }
}