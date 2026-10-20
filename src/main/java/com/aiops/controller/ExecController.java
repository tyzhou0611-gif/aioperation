package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.service.ExecService;
import com.aiops.service.SshResult;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/exec")
public class ExecController {

    private final ExecService execService;

    public ExecController(ExecService execService) { this.execService = execService; }

    @PostMapping("/batch")
    public Result<List<SshResult>> batchExec(@Valid @RequestBody BatchExecRequest request) {
        List<SshResult> results = execService.executeOnServers(request.getServerIds(), request.getCommand());
        long successCount = results.stream().filter(SshResult::isSuccess).count();
        long failCount = results.size() - successCount;
        return Result.success(
                String.format("执行完成，成功: %d, 失败: %d", successCount, failCount),
                results);
    }

    public static class BatchExecRequest {
        @NotEmpty(message = "服务器ID列表不能为空")
        @Size(min = 1, max = 50, message = "批量执行服务器数量范围: 1~50")
        private List<Long> serverIds;

        @NotBlank(message = "Shell命令不能为空")
        @Size(max = 5000, message = "命令长度不能超过5000字符")
        private String command;

        public List<Long> getServerIds() { return serverIds; }
        public void setServerIds(List<Long> v) { this.serverIds = v; }
        public String getCommand() { return command; }
        public void setCommand(String v) { this.command = v; }
    }
}