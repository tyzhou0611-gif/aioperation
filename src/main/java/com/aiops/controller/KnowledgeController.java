package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.KbChunk;
import com.aiops.entity.KbDocument;
import com.aiops.entity.KbKnowledgeBase;
import com.aiops.service.KnowledgeService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.Authentication;

import java.util.*;

/**
 * 知识库管理接口
 *
 * - POST   /api/kb                 创建知识库
 * - GET    /api/kb                 列出所有知识库
 * - GET    /api/kb/{id}            知识库详情
 * - DELETE /api/kb/{id}            删除知识库
 * - POST   /api/kb/{id}/upload     上传 Markdown 文档
 * - POST   /api/kb/{id}/import-obsidian  导入 Obsidian vault zip
 * - GET    /api/kb/{id}/docs       列出文档
 * - DELETE /api/kb/doc/{docId}      删除文档
 * - GET    /api/kb/doc/{docId}/chunks  查看切块
 * - POST   /api/kb/{id}/retrieve   检索测试
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeController {

    private final KnowledgeService kbService;

    public KnowledgeController(KnowledgeService kbService) {
        this.kbService = kbService;
    }

    @PostMapping
    public Result<KbKnowledgeBase> create(@RequestBody Map<String, String> body, Authentication auth) {
        String name = body.get("name");
        String desc = body.get("description");
        String user = auth != null ? auth.getName() : "anonymous";
        return Result.success("创建成功", kbService.createKb(name, desc, user));
    }

    @GetMapping
    public Result<List<KbKnowledgeBase>> list() {
        return Result.success(kbService.listKbs());
    }

    @GetMapping("/{id}")
    public Result<KbKnowledgeBase> detail(@PathVariable Long id) {
        return Result.success(kbService.getKb(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        kbService.deleteKb(id);
        return Result.success("已删除", null);
    }

    @PostMapping("/{id}/upload")
    public Result<KbDocument> upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws Exception {
        return Result.success("上传成功", kbService.uploadDoc(id, file));
    }

    @PostMapping("/{id}/import-obsidian")
    public Result<List<KbDocument>> importObsidian(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws Exception {
        return Result.success("导入完成", kbService.importObsidianZip(id, file));
    }

    @GetMapping("/{id}/docs")
    public Result<List<KbDocument>> listDocs(@PathVariable Long id) {
        return Result.success(kbService.listDocs(id));
    }

    @DeleteMapping("/doc/{docId}")
    public Result<Void> deleteDoc(@PathVariable Long docId) {
        kbService.deleteDoc(docId);
        return Result.success("已删除", null);
    }

    @GetMapping("/doc/{docId}/chunks")
    public Result<List<KbChunk>> listChunks(@PathVariable Long docId) {
        return Result.success(kbService.listChunks(docId));
    }

    @PostMapping("/{id}/retrieve")
    public Result<List<Map<String, Object>>> retrieve(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String query = body.get("query");
        int topK = body.get("topK") != null ? Integer.parseInt(body.get("topK")) : 5;
        List<KbChunk> chunks = kbService.retrieve(id, query, topK);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (KbChunk c : chunks) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", c.getId());
            m.put("content", c.getContent());
            m.put("headingPath", c.getHeadingPath());
            m.put("chunkIndex", c.getChunkIndex());
            out.add(m);
        }
        return Result.success(out);
    }
}