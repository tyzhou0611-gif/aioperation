package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.infrastructure.ai.AiChatClient;
import com.aiops.infrastructure.ai.AIClientFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统级 AI 接口：测试连接 / 状态信息。
 */
@RestController
@RequestMapping("/api/system/ai")
public class AiSystemController {

    private final AIClientFactory factory;

    public AiSystemController(AIClientFactory factory) {
        this.factory = factory;
    }

    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        return Result.success(factory.info());
    }

    /** 发送一条消息测试大模型连通（body 可空，默认"你好"） */
    @PostMapping("/test")
    public Result<Map<String, Object>> test(@RequestBody(required = false) Map<String, Object> body) {
        String msg = body != null && body.get("message") != null ? body.get("message").toString() : "你好";
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("provider", factory.info().get("provider"));
        AiChatClient client = factory.getActive();
        if (client == null) {
            out.put("success", false);
            out.put("mode", "RULE");
            out.put("message", "未启用大模型（provider=none 或已降级），AI 助手使用本地规则模式");
            return Result.success(out);
        }
        try {
            long t0 = System.currentTimeMillis();
            List<Map<String, String>> history = new ArrayList<Map<String, String>>();
            Map<String, String> um = new HashMap<String, String>();
            um.put("role", "user"); um.put("content", msg);
            history.add(um);
            String reply = client.chat("你是运维助手，简洁回答。", history);
            out.put("success", reply != null && !reply.isEmpty());
            out.put("mode", "LLM");
            out.put("elapsedMs", System.currentTimeMillis() - t0);
            out.put("reply", reply == null ? "(空)" : (reply.length() > 500 ? reply.substring(0, 500) : reply));
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "测试失败: " + e.getMessage());
        }
        return Result.success(out);
    }
}