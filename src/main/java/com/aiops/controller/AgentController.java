package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.ServerAgent;
import com.aiops.repository.ServerAgentRepository;
import com.aiops.service.AgentCatalogService;
import com.aiops.service.AgentInstallService;
import com.aiops.service.AgentPackageService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prometheus Exporter 安装包与一键安装 API */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentCatalogService catalogService;
    private final AgentPackageService packageService;
    private final AgentInstallService installService;
    private final ServerAgentRepository agentRepository;

    public AgentController(AgentCatalogService catalogService, AgentPackageService packageService,
                           AgentInstallService installService, ServerAgentRepository agentRepository) {
        this.catalogService = catalogService;
        this.packageService = packageService;
        this.installService = installService;
        this.agentRepository = agentRepository;
    }

    @GetMapping("/catalog")
    public Result<List<Map<String, Object>>> catalog() {
        return Result.success(catalogService.list());
    }

    @GetMapping("/packages")
    public Result<Map<String, Object>> packages() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("dir", packageService.dir().toString());
        out.put("packages", packageService.list());
        return Result.success(out);
    }

    @PostMapping("/packages")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        return Result.success("安装包已上传", packageService.save(file));
    }

    @DeleteMapping("/packages/{name:.+}")
    public Result<Void> deletePackage(@PathVariable("name") String name) {
        packageService.delete(name);
        return Result.success("安装包已删除", null);
    }

    @PostMapping("/install")
    public Result<Map<String, Object>> install(@RequestBody Map<String, Object> body) {
        return Result.success("安装任务已完成", installService.install(body));
    }

    @PostMapping("/uninstall")
    public Result<Map<String, Object>> uninstall(@RequestBody Map<String, Object> body) {
        return Result.success("卸载任务已完成", installService.uninstall(body));
    }

    @GetMapping("/installed")
    public Result<List<ServerAgent>> installed(@RequestParam(required = false) Long serverId) {
        if (serverId != null) return Result.success(agentRepository.findByServerId(serverId));
        return Result.success(agentRepository.findAll());
    }

    /** 删除安装记录（仅记录，不会卸载远端服务） */
    @DeleteMapping("/installed/{id}")
    public Result<Void> deleteInstalled(@PathVariable Long id) {
        if (agentRepository.existsById(id)) agentRepository.deleteById(id);
        return Result.success("安装记录已删除", null);
    }
}