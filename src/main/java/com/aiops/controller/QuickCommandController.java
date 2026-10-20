package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.CommandTemplate;
import com.aiops.repository.CommandTemplateRepository;
import com.aiops.service.ExecTask;
import com.aiops.service.QuickCommandService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一键命令下发接口
 */
@RestController
@RequestMapping("/api/exec")
public class QuickCommandController {

    private final CommandTemplateRepository templateRepository;
    private final QuickCommandService quickCommandService;

    public QuickCommandController(CommandTemplateRepository templateRepository,
                                  QuickCommandService quickCommandService) {
        this.templateRepository = templateRepository;
        this.quickCommandService = quickCommandService;
    }

    /** 按目标类型/中间件类型获取命令模板 */
    @GetMapping("/templates")
    public Result<List<CommandTemplate>> templates(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String middlewareType) {
        if (targetType != null && !targetType.trim().isEmpty()) {
            String tt = targetType.toUpperCase(Locale.ROOT);
            if (middlewareType != null && !middlewareType.trim().isEmpty()) {
                String mt = middlewareType.toUpperCase(Locale.ROOT);
                // 目标限定模板 + 该中间件专属模板
                List<CommandTemplate> byMw = templateRepository.findByTargetTypeAndMiddlewareType(tt, mt);
                return Result.success(byMw);
            }
            return Result.success(templateRepository.findByTargetType(tt));
        }
        return Result.success(templateRepository.findAll());
    }

    /** 提交一键命令（异步，返回 taskId） */
    @PostMapping("/quick")
    public Result<ExecTask> quick(@RequestBody Map<String, Object> body, Authentication auth) {
        String targetType = str(body.get("targetType"));
        Long targetId = body.get("targetId") == null ? null : Long.valueOf(body.get("targetId").toString());
        String command = str(body.get("command"));
        String operator = auth != null ? auth.getName() : "anonymous";
        ExecTask task = quickCommandService.submit(targetType, targetId, command, operator);
        return Result.success(task);
    }

    /** 轮询任务结果 */
    @GetMapping("/quick/{taskId}")
    public Result<ExecTask> poll(@PathVariable String taskId) {
        ExecTask task = quickCommandService.getTask(taskId);
        if (task == null) {
            ExecTask missing = new ExecTask(taskId);
            missing.setStatus("ERROR"); missing.setOutput("任务不存在或已过期");
            return Result.success(missing);
        }
        return Result.success(task);
    }

    private String str(Object o) { return o == null ? null : o.toString(); }
}