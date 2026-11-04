package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.service.EmbeddedPrometheusService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 内置/外部 Prometheus 管理：
 * - 状态、启停、热加载
 * - YAML 配置查看/保存/恢复平台默认
 * - 静态抓取任务增删改
 * - 当前 targets 查看
 * - 内置/外部模式切换
 */
@RestController
@RequestMapping("/api/prometheus")
public class PrometheusController {

    private final EmbeddedPrometheusService prometheus;

    public PrometheusController(EmbeddedPrometheusService prometheus) {
        this.prometheus = prometheus;
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(prometheus.status());
    }

    @PostMapping("/start")
    public Result<Map<String, Object>> start() {
        Map<String, Object> st = prometheus.start();
        boolean up = Boolean.TRUE.equals(st.get("healthy")) || Boolean.TRUE.equals(st.get("running"));
        String msg = up
                ? (Boolean.TRUE.equals(st.get("reused")) ? "Prometheus 已在运行（已复用端口上的实例）" : "Prometheus 已启动")
                : "启动失败";
        return Result.success(msg, st);
    }

    @PostMapping("/stop")
    public Result<Map<String, Object>> stop() {
        return Result.success("Prometheus 已停止", prometheus.stop());
    }

    @PostMapping("/restart")
    public Result<Map<String, Object>> restart() {
        Map<String, Object> st = prometheus.restart();
        boolean up = Boolean.TRUE.equals(st.get("healthy")) || Boolean.TRUE.equals(st.get("running"));
        return Result.success(up ? "Prometheus 已重启" : "重启失败", st);
    }

    @PostMapping("/reload")
    public Result<Map<String, Object>> reload() {
        return Result.success("Prometheus 配置已热加载", prometheus.reload());
    }

    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        out.put("content", prometheus.configText());
        out.put("status", prometheus.status());
        return Result.success(out);
    }

    @PutMapping("/config")
    public Result<Map<String, Object>> saveConfig(@RequestBody Map<String, String> body) {
        return Result.success("配置已保存", prometheus.saveConfig(body == null ? null : body.get("content")));
    }

    @PostMapping("/config/reset")
    public Result<Map<String, Object>> resetConfig() {
        return Result.success("已恢复平台默认配置", prometheus.resetConfig());
    }

    @GetMapping("/jobs")
    public Result<List<Map<String, Object>>> jobs() {
        return Result.success(prometheus.jobs());
    }

    @PostMapping("/jobs")
    public Result<Map<String, Object>> addJob(@RequestBody Map<String, Object> body) {
        return Result.success("抓取任务已新增", prometheus.addJob(body));
    }

    @PutMapping("/jobs/{jobName}")
    public Result<Map<String, Object>> updateJob(@PathVariable String jobName, @RequestBody Map<String, Object> body) {
        return Result.success("抓取任务已更新", prometheus.updateJob(jobName, body));
    }

    @DeleteMapping("/jobs/{jobName}")
    public Result<Map<String, Object>> deleteJob(@PathVariable String jobName) {
        return Result.success("抓取任务已删除", prometheus.deleteJob(jobName));
    }

    @GetMapping("/targets")
    public Result<Map<String, Object>> targets() {
        return Result.success(prometheus.targets());
    }

    @PostMapping("/mode")
    public Result<Map<String, Object>> mode(@RequestBody Map<String, String> body) {
        String mode = body == null ? "" : body.get("mode");
        String externalUrl = body == null ? "" : body.get("externalUrl");
        return Result.success("Prometheus 模式已切换", prometheus.switchMode(mode, externalUrl));
    }
}