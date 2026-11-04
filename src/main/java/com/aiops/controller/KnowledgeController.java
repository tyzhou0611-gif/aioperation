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

    /** 页面直接新建 Markdown 文档 */
    @PostMapping("/{id}/doc")
    public Result<KbDocument> createDoc(@PathVariable Long id, @RequestBody Map<String, String> body) throws Exception {
        return Result.success("创建成功", kbService.createDoc(id,
                body.get("title"), body.get("fileName"), body.get("content")));
    }

    /** 页面编辑文档内容 */
    @PutMapping("/doc/{docId}")
    public Result<KbDocument> updateDoc(@PathVariable Long docId, @RequestBody Map<String, String> body) throws Exception {
        return Result.success("保存成功", kbService.updateDoc(docId,
                body.get("title"), body.get("fileName"), body.get("content")));
    }

    /** 多选 / 文件夹上传；paths 为相对路径（保留目录结构） */
    @PostMapping("/{id}/upload-batch")
    public Result<Map<String, Object>> uploadBatch(@PathVariable Long id,
                                                    @RequestParam("files") MultipartFile[] files,
                                                    @RequestParam(value = "paths", required = false) String[] paths,
                                                    @RequestParam(value = "source", required = false, defaultValue = "UPLOAD") String source) throws Exception {
        return Result.success("导入完成", kbService.uploadBatch(id, files, paths, source));
    }

    @PostMapping("/{id}/import-obsidian")
    public Result<Map<String, Object>> importObsidian(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws Exception {
        return Result.success("导入完成", kbService.importObsidianZip(id, file));
    }

    /** 知识树（文件夹层级） */
    @GetMapping("/{id}/tree")
    public Result<List<Map<String, Object>>> tree(@PathVariable Long id) {
        return Result.success(kbService.getTree(id));
    }

    /** 知识图谱（文档 + 标签 + 未创建的双链） */
    @GetMapping("/{id}/graph")
    public Result<Map<String, Object>> graph(@PathVariable Long id) {
        return Result.success(kbService.getGraph(id));
    }

    /** 文档预览（正文、标题大纲、标签、双链、反向链接） */
    @GetMapping("/doc/{docId}/content")
    public Result<Map<String, Object>> docContent(@PathVariable Long docId) {
        return Result.success(kbService.getDocContent(docId));
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