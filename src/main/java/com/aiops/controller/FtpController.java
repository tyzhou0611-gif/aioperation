package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.FtpServer;
import com.aiops.service.FtpClientService;
import com.aiops.service.FtpServerService;
import com.aiops.service.FtpStagingService;
import com.aiops.service.FtpTransferService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** FTP/SFTP 资产管理 + 拉取到本地 + 分发到受管服务器 */
@RestController
@RequestMapping("/api/ftp")
public class FtpController {

    private final FtpServerService serverService;
    private final FtpClientService clientService;
    private final FtpStagingService stagingService;
    private final FtpTransferService transferService;

    public FtpController(FtpServerService serverService, FtpClientService clientService,
                         FtpStagingService stagingService, FtpTransferService transferService) {
        this.serverService = serverService;
        this.clientService = clientService;
        this.stagingService = stagingService;
        this.transferService = transferService;
    }

    // ==================== FTP 资产 ====================

    @GetMapping("/servers")
    public Result<List<FtpServer>> servers() {
        return Result.success(serverService.list());
    }

    @GetMapping("/servers/{id}")
    public Result<FtpServer> server(@PathVariable Long id) {
        return Result.success(serverService.get(id));
    }

    @PostMapping("/servers")
    public Result<FtpServer> create(@RequestBody FtpServer body) {
        return Result.success("FTP 资产已创建", serverService.create(body));
    }

    @PutMapping("/servers/{id}")
    public Result<FtpServer> update(@PathVariable Long id, @RequestBody FtpServer body) {
        return Result.success("FTP 资产已更新", serverService.update(id, body));
    }

    @DeleteMapping("/servers/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        serverService.delete(id);
        return Result.success("FTP 资产已删除", null);
    }

    @PostMapping("/servers/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable Long id) {
        return Result.success(clientService.test(serverService.raw(id)));
    }

    /** 测试尚未保存的 FTP 配置（密码可用明文传入） */
    @PostMapping("/test")
    public Result<Map<String, Object>> testDraft(@RequestBody FtpServer body) {
        return Result.success(clientService.test(body));
    }

    // ==================== 远程目录 / 拉取 ====================

    @GetMapping("/servers/{id}/files")
    public Result<Map<String, Object>> files(@PathVariable Long id,
                                             @RequestParam(required = false) String path) {
        FtpServer s = serverService.raw(id);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("path", path == null || path.trim().isEmpty()
                ? (s.getBasePath() == null || s.getBasePath().trim().isEmpty() ? "/" : s.getBasePath()) : path);
        out.put("files", clientService.list(s, path));
        return Result.success(out);
    }

    /** 从 FTP 拉取到平台本地暂存 */
    @PostMapping("/servers/{id}/pull")
    public Result<Map<String, Object>> pull(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String remotePath = body.get("remotePath") == null ? "" : String.valueOf(body.get("remotePath")).trim();
        return Result.success("已拉取到本地暂存", stagingService.pull(serverService.raw(id), remotePath));
    }

    // ==================== 本地暂存 / 分发 ====================

    @GetMapping("/staged")
    public Result<Map<String, Object>> staged() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("dir", stagingService.dir().toString());
        out.put("files", stagingService.list());
        return Result.success(out);
    }

    @DeleteMapping("/staged")
    public Result<Void> deleteStaged(@RequestParam String name) {
        stagingService.delete(name);
        return Result.success("暂存文件已删除", null);
    }

    /** 下载暂存文件到浏览器本地（文件名使用原始文件名） */
    @GetMapping("/staged/download")
    public void downloadStaged(@RequestParam String name, HttpServletResponse response) throws Exception {
        Path file = stagingService.existing(name);
        String fileName = stagingService.originalName(name);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()).replace("+", "%20");
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        response.setContentLengthLong(Files.size(file));
        try (InputStream in = Files.newInputStream(file); OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

    /** 把暂存文件分发到受管服务器（SFTP） */
    @PostMapping("/distribute")
    public Result<Map<String, Object>> distribute(@RequestBody Map<String, Object> body) {
        return Result.success("分发任务已完成", transferService.distribute(body));
    }
}