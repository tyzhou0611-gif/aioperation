package com.aiops.service;

import com.aiops.entity.KbChunk;
import com.aiops.entity.KbDocument;
import com.aiops.entity.KbKnowledgeBase;
import com.aiops.infrastructure.embedding.EmbeddingProvider;
import com.aiops.infrastructure.embedding.KeywordEmbeddingProvider;
import com.aiops.repository.*;
import com.aiops.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.zip.*;

/**
 * 知识库服务：文档上传、Markdown 智能切块、BM25 向量检索。
 * 黑盒默认使用 KeywordEmbeddingProvider（纯 Java，无外部依赖）。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final KbKnowledgeBaseRepository kbRepo;
    private final KbDocumentRepository docRepo;
    private final KbChunkRepository chunkRepo;
    private final KeywordEmbeddingProvider keywordProvider;
    private final ConfigService configService;

    // ==================== 知识库 CRUD ====================

    @Transactional
    public KbKnowledgeBase createKb(String name, String description, String createBy) {
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setCreateBy(createBy);
        kb = kbRepo.save(kb);
        try { Files.createDirectories(Paths.get("data/kb/" + kb.getId())); }
        catch (Exception e) { log.warn("创建知识库目录失败: {}", e.getMessage()); }
        return kb;
    }

    public List<KbKnowledgeBase> listKbs() { return kbRepo.findAll(); }

    public KbKnowledgeBase getKb(Long id) { return kbRepo.findById(id).orElse(null); }

    @Transactional
    public void deleteKb(Long id) {
        List<KbDocument> docs = docRepo.findByKbIdOrderByCreateTimeDesc(id);
        for (KbDocument d : docs) {
            chunkRepo.deleteByDocId(d.getId());
            docRepo.deleteById(d.getId());
        }
        kbRepo.deleteById(id);
        try { deleteDir(Paths.get("data/kb/" + id)); } catch (Exception ignore) {}
    }

    // ==================== 文档上传与处理 ====================

    @Transactional
    public KbDocument uploadDoc(Long kbId, MultipartFile file) throws IOException {
        KbKnowledgeBase kb = getKb(kbId);
        if (kb == null) throw new com.aiops.common.ServiceException("知识库不存在");

        String fileName = file.getOriginalFilename();
        String fileType = fileName != null && fileName.toLowerCase().endsWith(".md") ? "MD"
                       : fileName != null && fileName.toLowerCase().endsWith(".txt") ? "TXT" : "MD";

        // 保存文件（使用绝对路径，避免 Tomcat temp 相对路径问题）
        String storedName = UUID.randomUUID() + "." + fileType.toLowerCase();
        Path dirPath = Paths.get("data/kb/" + kbId).toAbsolutePath();
        Files.createDirectories(dirPath);
        Path filePath = dirPath.resolve(storedName);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        KbDocument doc = new KbDocument();
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setTitle(fileName != null ? fileName.replaceAll("\\.[^.]+$", "") : "untitled");
        doc.setFileType(fileType);
        doc.setFilePath(filePath.toString());
        doc.setFileSize(file.getSize());
        doc.setStatus("PROCESSING");
        doc.setSource("UPLOAD");
        doc = docRepo.save(doc);

        // 同步处理
        processDocument(doc, kb);

        return doc;
    }

    /** Obsidian vault zip 导入 */
    @Transactional
    public List<KbDocument> importObsidianZip(Long kbId, MultipartFile zipFile) throws IOException {
        KbKnowledgeBase kb = getKb(kbId);
        if (kb == null) throw new com.aiops.common.ServiceException("知识库不存在");

        List<KbDocument> docs = new ArrayList<KbDocument>();
        Path tmpDir = Paths.get("data/kb/" + kbId + "/tmp_" + UUID.randomUUID());
        Files.createDirectories(tmpDir);

        // 解压
        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // 跳过 .obsidian 目录和附件
                if (name.contains(".obsidian/") || name.contains("attachments/") || !name.toLowerCase().endsWith(".md")) continue;

                Path outPath = tmpDir.resolve(name.replace("/", "_"));
                Files.createDirectories(outPath.getParent());
                Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);

                // 创建文档记录
                KbDocument doc = new KbDocument();
                doc.setKbId(kbId);
                doc.setFileName(name);
                doc.setTitle(name.replace("/", " > ").replaceAll("\\.md$", ""));
                doc.setFileType("MD");
                doc.setFilePath(outPath.toString());
                doc.setSource("OBSIDIAN");
                doc.setStatus("PROCESSING");
                doc = docRepo.save(doc);
                processDocument(doc, kb);
                docs.add(doc);
            }
        }
        // 清理临时目录
        deleteDir(tmpDir);
        return docs;
    }

    // ==================== Markdown 解析与切块 ====================

    /** 处理单个文档：解析 Markdown → 切块 → 存库 */
    private void processDocument(KbDocument doc, KbKnowledgeBase kb) {
        try {
            String content = new String(Files.readAllBytes(Paths.get(doc.getFilePath())), StandardCharsets.UTF_8);

            // 解析 frontmatter（提取 tags）
            List<String> tags = extractFrontmatterTags(content);
            if (!tags.isEmpty()) {
                doc.setTags(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tags));
            }

            // 解析标题作为文档标题
            String firstH1 = extractFirstH1(content);
            if (firstH1 != null) doc.setTitle(firstH1);

            // 智能切块
            int chunkSize = kb.getChunkSize() != null ? kb.getChunkSize() : 500;
            int overlap = kb.getChunkOverlap() != null ? kb.getChunkOverlap() : 50;
            List<ChunkInfo> chunks = chunkMarkdown(content, chunkSize, overlap);

            // 存储切块
            String provider = configService.getString("ai.embedding.provider", "keyword");
            EmbeddingProvider embProvider = "openai".equals(provider) ? null : keywordProvider; // 黑盒只用 keyword

            int idx = 0;
            for (ChunkInfo ci : chunks) {
                KbChunk chunk = new KbChunk();
                chunk.setDocId(doc.getId());
                chunk.setKbId(doc.getKbId());
                chunk.setChunkIndex(idx++);
                chunk.setContent(ci.content);
                chunk.setHeadingPath(ci.headingPath);
                chunk.setTokens(ci.content.length() / 4); // 粗估 token 数
                chunk.setEmbeddingStatus("DONE");
                chunkRepo.save(chunk);
            }

            doc.setChunkCount(chunks.size());
            doc.setStatus("SUCCESS");
            docRepo.save(doc);

            // 更新知识库文档数
            kb.setDocCount((kb.getDocCount() == null ? 0 : kb.getDocCount()) + 1);
            kbRepo.save(kb);

            log.info("文档[{}]处理完成: {} 个切块", doc.getTitle(), chunks.size());
        } catch (Exception e) {
            log.error("文档处理失败: {}", e.getMessage(), e);
            doc.setStatus("FAILED");
            doc.setErrorMsg(e.getMessage() != null && e.getMessage().length() > 400 ? e.getMessage().substring(0, 400) : e.getMessage());
            docRepo.save(doc);
        }
    }

    // ==================== Markdown 切块算法 ====================

    public static class ChunkInfo {
        public String content;
        public String headingPath;
    }

    /**
     * 按 Markdown 标题层级智能切块：
     * - 遇到 # / ## / ### 作为切块边界
     * - 代码块 ``` 不切分
     * - 超长块按段落二次切分
     * - 块间保留 overlap 字符
     */
    public List<ChunkInfo> chunkMarkdown(String md, int chunkSize, int overlap) {
        List<ChunkInfo> result = new ArrayList<ChunkInfo>();
        if (md == null || md.trim().isEmpty()) return result;

        // 按"##"标题切分（保留标题层级路径）
        List<Section> sections = splitByHeadings(md);
        String prevTail = "";

        for (Section sec : sections) {
            String fullContent = (prevTail.isEmpty() ? "" : prevTail + "\n") + sec.body;
            if (fullContent.length() <= chunkSize * 2) {
                // 直接作为一个块
                ChunkInfo ci = new ChunkInfo();
                ci.content = fullContent.trim();
                ci.headingPath = sec.headingPath;
                if (!ci.content.isEmpty()) result.add(ci);
            } else {
                // 超长 → 按段落二次切分
                List<String> subChunks = splitByParagraph(fullContent, chunkSize);
                for (String sub : subChunks) {
                    ChunkInfo ci = new ChunkInfo();
                    ci.content = sub.trim();
                    ci.headingPath = sec.headingPath;
                    if (!ci.content.isEmpty()) result.add(ci);
                }
            }
            // 保留 overlap
            prevTail = sec.body.length() > overlap ? sec.body.substring(sec.body.length() - overlap) : sec.body;
        }
        return result;
    }

    private static class Section {
        String headingPath;
        String body;
    }

    /** 按标题层级切分，生成带路径的 Section */
    private List<Section> splitByHeadings(String md) {
        List<Section> sections = new ArrayList<Section>();
        String[] lines = md.split("\n");
        StringBuilder curBody = new StringBuilder();
        List<String> headingStack = new ArrayList<String>(); // [H1, H2, H3]
        boolean inCodeBlock = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // 代码块开关
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                curBody.append(line).append("\n");
                continue;
            }
            // 代码块内不切分
            if (inCodeBlock) {
                curBody.append(line).append("\n");
                continue;
            }

            // 检测标题行
            if (trimmed.startsWith("#") && !trimmed.startsWith("#!") && !inCodeBlock) {
                // 先把之前攒的内容作为一个 section
                if (curBody.length() > 0) {
                    Section sec = new Section();
                    sec.headingPath = String.join(" > ", headingStack);
                    sec.body = curBody.toString().trim();
                    if (!sec.body.isEmpty()) sections.add(sec);
                    curBody.setLength(0);
                }
                // 更新标题栈
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                String title = trimmed.substring(level).trim();
                // 标题栈：只保留 ≤ level 的
                while (headingStack.size() >= level) headingStack.remove(headingStack.size() - 1);
                headingStack.add(title);
                curBody.append(line).append("\n"); // 标题行也进正文
            } else {
                curBody.append(line).append("\n");
            }
        }
        // 最后一个 section
        if (curBody.length() > 0) {
            Section sec = new Section();
            sec.headingPath = String.join(" > ", headingStack);
            sec.body = curBody.toString().trim();
            if (!sec.body.isEmpty()) sections.add(sec);
        }
        return sections;
    }

    /** 按段落（\n\n）二次切分，保证每块不超过 maxLen */
    private List<String> splitByParagraph(String text, int maxLen) {
        List<String> chunks = new ArrayList<String>();
        String[] paras = text.split("\n\n");
        StringBuilder cur = new StringBuilder();
        for (String para : paras) {
            if (cur.length() + para.length() + 2 > maxLen && cur.length() > 0) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append("\n\n");
            cur.append(para);
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        return chunks;
    }

    // ==================== Obsidian 特殊语法解析 ====================

    /** 提取 frontmatter（--- 之间的） tags */
    private List<String> extractFrontmatterTags(String content) {
        List<String> tags = new ArrayList<String>();
        Matcher fm = Pattern.compile("(?s)^---\n(.*?)\n---").matcher(content);
        if (fm.find()) {
            String yaml = fm.group(1);
            Matcher tm = Pattern.compile("(?m)^tags:\\s*\\[(.*?)\\]").matcher(yaml);
            if (tm.find()) {
                tags.addAll(Arrays.asList(tm.group(1).split(",\\s*")));
            }
        }
        // 提取正文中的 #tag（非标题）
        Matcher tagM = Pattern.compile("(?m)(?<![\\w#])#([\\u4e00-\\u9fa5\\w-/]+)").matcher(content);
        while (tagM.find()) {
            String t = tagM.group(1);
            if (!t.isEmpty() && !tags.contains(t)) tags.add(t);
        }
        return tags;
    }

    private String extractFirstH1(String content) {
        Matcher m = Pattern.compile("(?m)^#\\s+(.+)$").matcher(content);
        return m.find() ? m.group(1).trim() : null;
    }

    // ==================== 检索 ====================

    /** BM25 关键词检索 top-K */
    public List<KbChunk> retrieve(Long kbId, String query, int topK) {
        List<KbChunk> all = chunkRepo.findByKbId(kbId);
        if (all.isEmpty()) return Collections.emptyList();

        // 查询向量化
        float[] queryVec = keywordProvider.embed(query);
        // 每个块的向量化 + 计算相似度
        List<ScoredChunk> scored = new ArrayList<ScoredChunk>();
        for (KbChunk c : all) {
            float[] chunkVec = keywordProvider.embed(c.getContent());
            float score = KeywordEmbeddingProvider.cosine(queryVec, chunkVec);
            // 叠加关键词命中加分
            score += keywordOverlapBonus(query, c.getContent());
            scored.add(new ScoredChunk(c, score));
        }
        scored.sort((a, b) -> Float.compare(b.score, a.score));
        return scored.stream().limit(topK).map(s -> s.chunk).collect(Collectors.toList());
    }

    private float keywordOverlapBonus(String query, String content) {
        List<String> qTokens = KeywordEmbeddingProvider.tokenize(query);
        int hits = 0;
        String lowerContent = content.toLowerCase();
        for (String t : qTokens) {
            if (t.length() > 1 && lowerContent.contains(t.toLowerCase())) hits++;
        }
        return hits * 0.05f;
    }

    private static class ScoredChunk {
        KbChunk chunk;
        float score;
        ScoredChunk(KbChunk c, float s) { chunk = c; score = s; }
    }

    // ==================== 查询接口 ====================

    public List<KbDocument> listDocs(Long kbId) { return docRepo.findByKbIdOrderByCreateTimeDesc(kbId); }

    public List<KbChunk> listChunks(Long docId) { return chunkRepo.findByDocIdOrderByChunkIndex(docId); }

    @Transactional
    public void deleteDoc(Long docId) {
        KbDocument doc = docRepo.findById(docId).orElse(null);
        if (doc == null) return;
        chunkRepo.deleteByDocId(docId);
        docRepo.deleteById(docId);
        // 减少计数
        KbKnowledgeBase kb = getKb(doc.getKbId());
        if (kb != null) {
            kb.setDocCount(Math.max(0, (kb.getDocCount() == null ? 1 : kb.getDocCount()) - 1));
            kbRepo.save(kb);
        }
        try { Files.deleteIfExists(Paths.get(doc.getFilePath())); } catch (Exception ignore) {}
    }

    // ==================== 工具 ====================

    private void deleteDir(Path path) {
        if (!Files.exists(path)) return;
        try {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception ignore) {} });
        } catch (Exception ignore) {}
    }
}