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

    public List<KbKnowledgeBase> listKbs() {
        List<KbKnowledgeBase> kbs = kbRepo.findAll();
        // 以实际文档数为准，修复历史数据中的计数偏差
        for (KbKnowledgeBase kb : kbs) {
            kb.setDocCount((int) docRepo.countByKbId(kb.getId()));
        }
        return kbs;
    }

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
        return ingestMarkdown(kbId, file.getOriginalFilename(), file.getBytes(), "UPLOAD");
    }

    /** 页面直接新建 Markdown 文档（写入磁盘并切块） */
    @Transactional
    public KbDocument createDoc(Long kbId, String title, String fileName, String content) throws IOException {
        KbKnowledgeBase kb = getKb(kbId);
        if (kb == null) throw new com.aiops.common.ServiceException(400, "知识库不存在");
        byte[] bytes = contentBytes(content);
        String path = normalizeRelPath(fileName == null || fileName.trim().isEmpty()
                ? (safeTitle(title) + ".md") : fileName);
        path = uniqueFileName(kbId, path);
        if (path.length() > 200) throw new com.aiops.common.ServiceException(400, "文件路径不能超过 200 个字符");

        KbDocument doc = new KbDocument();
        doc.setKbId(kbId);
        doc.setFileName(path);
        doc.setTitle(trimTitle(title, baseName(path)));
        doc.setFileType("MD");
        doc.setSource("MANUAL");
        doc.setStatus("PROCESSING");
        doc.setFileSize((long) bytes.length);
        doc.setChunkCount(0);
        doc = docRepo.save(doc);

        Path filePath = Paths.get("data/kb/" + kbId).toAbsolutePath().resolve(UUID.randomUUID() + ".md");
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, bytes);
        doc.setFilePath(filePath.toString());
        doc = docRepo.save(doc);

        processDocument(doc, kb);
        refreshKbCount(kbId);
        return doc;
    }

    /** 页面编辑已有 Markdown 文档；文件名变化时校验同库内不重复 */
    @Transactional
    public KbDocument updateDoc(Long docId, String title, String fileName, String content) throws IOException {
        KbDocument doc = docRepo.findById(docId)
                .orElseThrow(() -> new com.aiops.common.ServiceException(404, "文档不存在: " + docId));
        KbKnowledgeBase kb = getKb(doc.getKbId());
        if (kb == null) throw new com.aiops.common.ServiceException(400, "知识库不存在");
        byte[] bytes = contentBytes(content);
        String path = normalizeRelPath(fileName == null || fileName.trim().isEmpty()
                ? (doc.getFileName() == null || doc.getFileName().trim().isEmpty() ? safeTitle(title) + ".md" : doc.getFileName())
                : fileName);
        if (path.length() > 200) throw new com.aiops.common.ServiceException(400, "文件路径不能超过 200 个字符");
        KbDocument sameName = docRepo.findByKbIdAndFileName(doc.getKbId(), path).orElse(null);
        if (sameName != null && !sameName.getId().equals(docId)) {
            throw new com.aiops.common.ServiceException(400, "文件名已存在: " + path);
        }

        if (doc.getFilePath() == null || doc.getFilePath().trim().isEmpty()) {
            Path filePath = Paths.get("data/kb/" + doc.getKbId()).toAbsolutePath().resolve(UUID.randomUUID() + ".md");
            Files.createDirectories(filePath.getParent());
            doc.setFilePath(filePath.toString());
        }
        Path filePath = Paths.get(doc.getFilePath());
        if (filePath.getParent() != null) Files.createDirectories(filePath.getParent());
        Files.write(filePath, bytes);

        doc.setFileName(path);
        doc.setTitle(trimTitle(title, baseName(path)));
        doc.setFileType("MD");
        doc.setFileSize((long) bytes.length);
        doc.setSource("MANUAL");
        doc.setStatus("PROCESSING");
        doc.setErrorMsg(null);
        doc = docRepo.save(doc);

        processDocument(doc, kb);
        refreshKbCount(doc.getKbId());
        return doc;
    }

    private byte[] contentBytes(String content) {
        String text = content == null ? "" : content;
        if (text.length() > 2 * 1024 * 1024) {
            throw new com.aiops.common.ServiceException(400, "文档内容不能超过 2MB");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private String safeTitle(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) t = "未命名文档";
        t = t.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) t = "未命名文档";
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    private String trimTitle(String title, String fallback) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) t = fallback;
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private String uniqueFileName(Long kbId, String path) {
        if (!docRepo.findByKbIdAndFileName(kbId, path).isPresent()) return path;
        String lower = path.toLowerCase();
        String ext = lower.endsWith(".markdown") ? ".markdown" : lower.endsWith(".txt") ? ".txt" : ".md";
        String stem = path.substring(0, path.length() - ext.length());
        for (int i = 2; i < 1000; i++) {
            String candidate = stem + "-" + i + ext;
            if (!docRepo.findByKbIdAndFileName(kbId, candidate).isPresent()) return candidate;
        }
        throw new com.aiops.common.ServiceException(400, "同名文档过多，请更换文件名");
    }

    private void refreshKbCount(Long kbId) {
        KbKnowledgeBase latest = getKb(kbId);
        if (latest != null) {
            latest.setDocCount((int) docRepo.countByKbId(kbId));
            kbRepo.save(latest);
        }
    }

    /** 多文件 / 文件夹上传；paths 为浏览器 webkitRelativePath（保持目录结构） */
    @Transactional
    public Map<String, Object> uploadBatch(Long kbId, MultipartFile[] files, String[] paths, String source) throws IOException {
        if (getKb(kbId) == null) throw new com.aiops.common.ServiceException("知识库不存在");
        int imported = 0, updated = 0, skipped = 0;
        List<KbDocument> docs = new ArrayList<KbDocument>();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                MultipartFile f = files[i];
                String rel = paths != null && i < paths.length && paths[i] != null && !paths[i].trim().isEmpty()
                        ? paths[i] : f.getOriginalFilename();
                if (!isMarkdown(rel)) { skipped++; continue; }
                IngestResult r = ingestMarkdownInternal(kbId, rel, f.getBytes(), source);
                if (r.doc == null) { skipped++; continue; }
                docs.add(r.doc);
                if (r.created) imported++; else updated++;
            }
        }
        return importSummary(imported, updated, skipped, docs);
    }

    /** Obsidian vault zip 导入：保留相对路径，自动去掉公共根目录（如 MyVault/） */
    @Transactional
    public Map<String, Object> importObsidianZip(Long kbId, MultipartFile zipFile) throws IOException {
        if (getKb(kbId) == null) throw new com.aiops.common.ServiceException("知识库不存在");
        List<ZipEntryData> entries = new ArrayList<ZipEntryData>();
        long total = 0;
        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("__MACOSX/") || name.startsWith(".obsidian/") || name.contains("/.obsidian/")) continue;
                if (!isMarkdown(name)) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    total += n;
                    if (total > 100L * 1024 * 1024) {
                        throw new com.aiops.common.ServiceException("压缩包解压后超过 100MB 限制");
                    }
                }
                entries.add(new ZipEntryData(name, bos.toByteArray()));
                if (entries.size() > 2000) throw new com.aiops.common.ServiceException("笔记数量超过 2000 篇限制");
            }
        }
        String root = commonRoot(entries);
        int imported = 0, updated = 0, skipped = 0;
        List<KbDocument> docs = new ArrayList<KbDocument>();
        for (ZipEntryData e : entries) {
            String rel = stripRoot(e.name, root);
            IngestResult r = ingestMarkdownInternal(kbId, rel, e.data, "OBSIDIAN");
            if (r.doc == null) { skipped++; continue; }
            docs.add(r.doc);
            if (r.created) imported++; else updated++;
        }
        return importSummary(imported, updated, skipped, docs);
    }

    private Map<String, Object> importSummary(int imported, int updated, int skipped, List<KbDocument> docs) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("imported", imported);
        m.put("updated", updated);
        m.put("skipped", skipped);
        m.put("total", docs.size());
        m.put("docs", docs);
        return m;
    }

    private KbDocument ingestMarkdown(Long kbId, String relPath, byte[] bytes, String source) throws IOException {
        return ingestMarkdownInternal(kbId, relPath, bytes, source).doc;
    }

    /** 单篇入库：按相对路径 upsert（重导入不会产生重复文档），并刷新知识库文档数 */
    private IngestResult ingestMarkdownInternal(Long kbId, String relPath, byte[] bytes, String source) throws IOException {
        KbKnowledgeBase kb = getKb(kbId);
        if (kb == null) throw new com.aiops.common.ServiceException("知识库不存在");
        String path = normalizeRelPath(relPath);
        if (!isMarkdown(path)) return new IngestResult(null, false);

        KbDocument doc = docRepo.findByKbIdAndFileName(kbId, path).orElse(null);
        boolean created = doc == null;
        if (created) {
            doc = new KbDocument();
            doc.setKbId(kbId);
        } else {
            chunkRepo.deleteByDocId(doc.getId());
            try { if (doc.getFilePath() != null) Files.deleteIfExists(Paths.get(doc.getFilePath())); } catch (Exception ignore) {}
        }

        String fileType = path.toLowerCase().endsWith(".txt") ? "TXT" : "MD";
        String storedName = UUID.randomUUID() + "." + fileType.toLowerCase();
        Path dirPath = Paths.get("data/kb/" + kbId).toAbsolutePath();
        Files.createDirectories(dirPath);
        Path filePath = dirPath.resolve(storedName);
        Files.write(filePath, bytes);

        doc.setFileName(path);
        doc.setTitle(baseName(path));
        doc.setFileType(fileType);
        doc.setFilePath(filePath.toString());
        doc.setFileSize((long) bytes.length);
        doc.setStatus("PROCESSING");
        doc.setErrorMsg(null);
        doc.setSource(source);
        doc = docRepo.save(doc);

        processDocument(doc, kb);

        KbKnowledgeBase latest = getKb(kbId);
        if (latest != null) {
            latest.setDocCount((int) docRepo.countByKbId(kbId));
            kbRepo.save(latest);
        }
        return new IngestResult(doc, created);
    }

    private static class IngestResult {
        KbDocument doc;
        boolean created;
        IngestResult(KbDocument doc, boolean created) { this.doc = doc; this.created = created; }
    }

    private static class ZipEntryData {
        String name;
        byte[] data;
        ZipEntryData(String name, byte[] data) { this.name = name; this.data = data; }
    }

    private String commonRoot(List<ZipEntryData> entries) {
        String root = null;
        for (ZipEntryData e : entries) {
            int idx = e.name.indexOf('/');
            if (idx <= 0) return null; // 根目录下直接有文件，不去根
            String top = e.name.substring(0, idx);
            if (root == null) root = top;
            else if (!root.equals(top)) return null;
        }
        return root;
    }

    private String stripRoot(String name, String root) {
        if (root != null && name.startsWith(root + "/")) return name.substring(root.length() + 1);
        return name;
    }

    private boolean isMarkdown(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".txt");
    }

    /** 规范化 Obsidian 相对路径，防止 ../ 穿越，保留目录结构 */
    private String normalizeRelPath(String path) {
        if (path == null || path.trim().isEmpty()) return "untitled.md";
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        List<String> parts = new ArrayList<String>();
        for (String raw : p.split("/")) {
            String s = raw.trim().replaceAll("[\\p{Cntrl}]", "");
            if (s.isEmpty() || ".".equals(s) || "..".equals(s)) continue;
            parts.add(s);
        }
        String out = String.join("/", parts);
        if (out.isEmpty()) out = "untitled";
        if (!isMarkdown(out)) out += ".md";
        return out;
    }

    private String baseName(String path) {
        String name = path == null ? "untitled" : path;
        int idx = name.lastIndexOf('/');
        if (idx >= 0) name = name.substring(idx + 1);
        return name.replaceAll("(?i)\\.(md|markdown|txt)$", "");
    }

    // ==================== Markdown 解析与切块 ====================

    /** 处理单个文档：解析 frontmatter / 标签 / 标题 → 切块 → 存库 */
    private void processDocument(KbDocument doc, KbKnowledgeBase kb) {
        try {
            String content = new String(Files.readAllBytes(Paths.get(doc.getFilePath())), StandardCharsets.UTF_8);
            Frontmatter fm = parseFrontmatter(content);
            String body = fm.body;

            // 标签 = frontmatter tags + 正文 #tag
            List<String> tags = new ArrayList<String>(fm.tags);
            for (String t : extractInlineTags(body)) {
                if (!tags.contains(t)) tags.add(t);
            }
            doc.setTags(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tags));

            // 标题优先取 H1，否则用文件名
            String firstH1 = extractFirstH1(body);
            if (firstH1 != null && !firstH1.trim().isEmpty()) doc.setTitle(firstH1.trim());

            // 智能切块（去掉 frontmatter）
            int chunkSize = kb.getChunkSize() != null ? kb.getChunkSize() : 500;
            int overlap = kb.getChunkOverlap() != null ? kb.getChunkOverlap() : 50;
            List<ChunkInfo> chunks = chunkMarkdown(body, chunkSize, overlap);

            chunkRepo.deleteByDocId(doc.getId());
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
                chunk.setTokens(ci.content.length() / 4);
                chunk.setEmbeddingStatus("DONE");
                chunkRepo.save(chunk);
            }

            doc.setChunkCount(chunks.size());
            doc.setStatus("SUCCESS");
            doc.setErrorMsg(null);
            docRepo.save(doc);

            log.info("文档[{}]处理完成: {} 个切块", doc.getTitle(), chunks.size());
        } catch (Exception e) {
            log.error("文档处理失败: {}", e.getMessage(), e);
            doc.setStatus("FAILED");
            String msg = e.getMessage();
            doc.setErrorMsg(msg != null && msg.length() > 400 ? msg.substring(0, 400) : msg);
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

    // ==================== Obsidian Markdown 解析 ====================

    private static class Frontmatter {
        String body = "";
        List<String> tags = new ArrayList<String>();
    }

    private Frontmatter parseFrontmatter(String content) {
        Frontmatter fm = new Frontmatter();
        fm.body = content == null ? "" : content;
        if (!fm.body.startsWith("---")) return fm;
        int end = fm.body.indexOf("\n---", 3);
        if (end < 0) return fm;
        String yaml = fm.body.substring(3, end).trim();
        int start = end + 4;
        if (start < fm.body.length() && fm.body.charAt(start) == '\n') start++;
        fm.body = start <= fm.body.length() ? fm.body.substring(start) : "";

        boolean inTags = false;
        for (String rawLine : yaml.split("\n")) {
            String line = rawLine.trim();
            if (line.toLowerCase().startsWith("tags:")) {
                String val = line.substring(5).trim();
                if (val.startsWith("[")) {
                    addTags(fm.tags, val.replace("[", "").replace("]", ""));
                    inTags = false;
                } else if (!val.isEmpty()) {
                    addTags(fm.tags, val);
                    inTags = false;
                } else {
                    inTags = true;
                }
            } else if (inTags) {
                if (line.startsWith("- ")) addTags(fm.tags, line.substring(2));
                else if (!line.isEmpty()) inTags = false;
            }
        }
        return fm;
    }

    private void addTags(List<String> tags, String csv) {
        if (csv == null) return;
        for (String t : csv.split("[,\\s]+")) {
            String v = t.trim().replaceAll("^['\"]|['\"]$", "").replaceAll("^#", "");
            if (!v.isEmpty() && !tags.contains(v)) tags.add(v);
        }
    }

    /** 提取正文 #tag（排除代码块和标题） */
    private List<String> extractInlineTags(String body) {
        List<String> tags = new ArrayList<String>();
        if (body == null) return tags;
        String noCode = body.replaceAll("(?s)```.*?```", " ").replaceAll("`[^`]*`", " ");
        // 双链里的 #标题 不是标签，先整体去掉 [[...]]
        noCode = noCode.replaceAll("!?\\[\\[[^\\]]+\\]\\]", " ");
        Matcher m = Pattern.compile("(?<![\\w#/])#([\\p{L}\\p{N}_\\-/]+)").matcher(noCode);
        while (m.find()) {
            String t = m.group(1).trim();
            if (!t.isEmpty() && t.length() < 50 && !tags.contains(t)) tags.add(t);
        }
        return tags;
    }

    /** 解析 [[双链]] 与 ![[嵌入]]，跳过图片等附件 */
    private List<Map<String, String>> extractWikilinks(String body) {
        List<Map<String, String>> links = new ArrayList<Map<String, String>>();
        if (body == null) return links;
        String noCode = body.replaceAll("(?s)```.*?```", " ");
        Matcher m = Pattern.compile("(!?)\\[\\[([^\\]]+)\\]\\]").matcher(noCode);
        Set<String> seen = new HashSet<String>();
        while (m.find()) {
            String type = m.group(1).isEmpty() ? "WIKILINK" : "EMBED";
            String target = normalizeWikilinkTarget(m.group(2));
            if (target.isEmpty() || isAttachment(target)) continue;
            if (!seen.add(type + ":" + target.toLowerCase())) continue;
            Map<String, String> link = new LinkedHashMap<String, String>();
            link.put("target", target);
            link.put("type", type);
            links.add(link);
        }
        return links;
    }

    private String normalizeWikilinkTarget(String raw) {
        String t = raw == null ? "" : raw.trim();
        int pipe = t.indexOf('|');
        if (pipe >= 0) t = t.substring(0, pipe);
        int hash = t.indexOf('#');
        if (hash >= 0) t = t.substring(0, hash);
        t = t.trim();
        if (t.startsWith("./")) t = t.substring(2);
        return t.replaceAll("(?i)\\.(md|markdown)$", "");
    }

    private boolean isAttachment(String target) {
        return target.toLowerCase().matches(".*\\.(png|jpe?g|gif|svg|webp|bmp|pdf|mp3|mp4|mov|avi|zip|rar|7z|docx?|xlsx?|pptx?|canvas)$");
    }

    private List<Map<String, Object>> extractHeadings(String body) {
        List<Map<String, Object>> headings = new ArrayList<Map<String, Object>>();
        if (body == null) return headings;
        String noCode = body.replaceAll("(?s)```.*?```", "");
        Matcher m = Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*#?\\s*$").matcher(noCode);
        int idx = 0;
        while (m.find()) {
            Map<String, Object> h = new LinkedHashMap<String, Object>();
            h.put("level", m.group(1).length());
            h.put("text", m.group(2).trim());
            h.put("index", idx++);
            headings.add(h);
        }
        return headings;
    }

    private String extractFirstH1(String content) {
        Matcher m = Pattern.compile("(?m)^#\\s+(.+)$").matcher(content);
        return m.find() ? m.group(1).trim() : null;
    }

    // ==================== 树/图谱公共辅助 ====================

    private Map<String, KbDocument> buildResolver(List<KbDocument> docs) {
        Map<String, KbDocument> resolver = new LinkedHashMap<String, KbDocument>();
        for (KbDocument d : docs) {
            String fn = normalizeRelPath(d.getFileName() != null ? d.getFileName() : d.getTitle());
            addResolver(resolver, d.getTitle(), d);
            addResolver(resolver, fn, d);
            addResolver(resolver, fn.replaceAll("(?i)\\.(md|markdown|txt)$", ""), d);
            int idx = fn.lastIndexOf('/');
            if (idx >= 0) addResolver(resolver, fn.substring(idx + 1).replaceAll("(?i)\\.(md|markdown|txt)$", ""), d);
        }
        return resolver;
    }

    private void addResolver(Map<String, KbDocument> resolver, String key, KbDocument doc) {
        String k = resolveKey(key);
        if (!k.isEmpty() && !resolver.containsKey(k)) resolver.put(k, doc);
    }

    private String resolveKey(String s) {
        if (s == null) return "";
        String x = s.replace('\\', '/').trim().toLowerCase();
        while (x.startsWith("./")) x = x.substring(2);
        return x.replaceAll("(?i)\\.(md|markdown|txt)$", "");
    }

    private String readDocContent(KbDocument doc) {
        try {
            if (doc.getFilePath() == null) return "";
            return new String(Files.readAllBytes(Paths.get(doc.getFilePath())), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取文档失败 {}: {}", doc.getId(), e.getMessage());
            return "";
        }
    }

    private List<String> readDocTags(KbDocument doc) {
        Frontmatter fm = parseFrontmatter(readDocContent(doc));
        List<String> tags = new ArrayList<String>(fm.tags);
        for (String t : extractInlineTags(fm.body)) {
            if (!tags.contains(t)) tags.add(t);
        }
        return tags;
    }

    private List<String> parseTagsJson(String json) {
        List<String> tags = new ArrayList<String>();
        if (json == null || json.trim().isEmpty()) return tags;
        try {
            List<?> list = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
            for (Object o : list) if (o != null) tags.add(o.toString());
        } catch (Exception ignore) {}
        return tags;
    }

    private String displayTitle(KbDocument doc) {
        if (doc.getTitle() != null && !doc.getTitle().trim().isEmpty()) return doc.getTitle().trim();
        return baseName(doc.getFileName());
    }

    private String topFolder(String fileName) {
        String path = normalizeRelPath(fileName);
        int idx = path.indexOf('/');
        return idx > 0 ? path.substring(0, idx) : "根目录";
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
        // 以实际文档数校正计数
        KbKnowledgeBase kb = getKb(doc.getKbId());
        if (kb != null) {
            kb.setDocCount((int) docRepo.countByKbId(doc.getKbId()));
            kbRepo.save(kb);
        }
        try { Files.deleteIfExists(Paths.get(doc.getFilePath())); } catch (Exception ignore) {}
    }

    // ==================== 知识树 / 知识图谱 / 文档预览 ====================

    /** 文件夹树（Obsidian vault 目录结构） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getTree(Long kbId) {
        List<KbDocument> docs = docRepo.findByKbIdOrderByCreateTimeDesc(kbId);
        List<Map<String, Object>> root = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> folderMap = new HashMap<String, Map<String, Object>>();

        for (KbDocument doc : docs) {
            String path = normalizeRelPath(doc.getFileName() != null ? doc.getFileName() : doc.getTitle());
            String[] parts = path.split("/");
            List<Map<String, Object>> current = root;
            StringBuilder fp = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i].isEmpty()) continue;
                if (fp.length() > 0) fp.append('/');
                fp.append(parts[i]);
                String key = fp.toString();
                Map<String, Object> folder = folderMap.get(key);
                if (folder == null) {
                    folder = new LinkedHashMap<String, Object>();
                    folder.put("id", "dir:" + key);
                    folder.put("label", parts[i]);
                    folder.put("type", "folder");
                    folder.put("path", key);
                    folder.put("children", new ArrayList<Map<String, Object>>());
                    folderMap.put(key, folder);
                    current.add(folder);
                }
                current = (List<Map<String, Object>>) folder.get("children");
            }
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("id", "doc:" + doc.getId());
            node.put("label", displayTitle(doc));
            node.put("type", "doc");
            node.put("docId", doc.getId());
            node.put("path", path);
            node.put("source", doc.getSource());
            node.put("chunkCount", doc.getChunkCount());
            node.put("tags", parseTagsJson(doc.getTags()));
            current.add(node);
        }
        sortTree(root);
        return root;
    }

    @SuppressWarnings("unchecked")
    private void sortTree(List<Map<String, Object>> nodes) {
        nodes.sort((a, b) -> {
            String ta = "folder".equals(a.get("type")) ? "0" : "1";
            String tb = "folder".equals(b.get("type")) ? "0" : "1";
            int c = ta.compareTo(tb);
            if (c != 0) return c;
            return String.valueOf(a.get("label")).compareToIgnoreCase(String.valueOf(b.get("label")));
        });
        for (Map<String, Object> n : nodes) {
            Object ch = n.get("children");
            if (ch instanceof List) sortTree((List<Map<String, Object>>) ch);
        }
    }

    /** 知识图谱：文档节点 + 标签节点 + 未创建的双链目标 */
    public Map<String, Object> getGraph(Long kbId) {
        List<KbDocument> docs = docRepo.findByKbIdOrderByCreateTimeDesc(kbId);
        Map<String, KbDocument> resolver = buildResolver(docs);
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<String, Map<String, Object>>();
        List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
        Set<String> edgeKeys = new HashSet<String>();

        for (KbDocument doc : docs) {
            String did = "doc:" + doc.getId();
            Frontmatter fm = parseFrontmatter(readDocContent(doc));
            List<String> tags = new ArrayList<String>(fm.tags);
            for (String t : extractInlineTags(fm.body)) {
                if (!tags.contains(t)) tags.add(t);
            }

            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("id", did);
            node.put("name", displayTitle(doc));
            node.put("category", "DOC");
            node.put("docId", doc.getId());
            node.put("fileName", doc.getFileName());
            node.put("folder", topFolder(doc.getFileName()));
            node.put("chunkCount", doc.getChunkCount());
            node.put("tags", tags);
            nodes.put(did, node);

            // 标签节点
            for (String tag : tags) {
                String tid = "tag:" + tag.toLowerCase();
                if (!nodes.containsKey(tid)) {
                    Map<String, Object> tn = new LinkedHashMap<String, Object>();
                    tn.put("id", tid);
                    tn.put("name", "#" + tag);
                    tn.put("category", "TAG");
                    nodes.put(tid, tn);
                }
                addGraphEdge(edges, edgeKeys, did, tid, "TAG");
            }

            // 双链
            for (Map<String, String> link : extractWikilinks(fm.body)) {
                String target = link.get("target");
                KbDocument td = resolver.get(resolveKey(target));
                String tid;
                if (td != null) {
                    if (td.getId().equals(doc.getId())) continue;
                    tid = "doc:" + td.getId();
                } else {
                    tid = "missing:" + resolveKey(target);
                    if (!nodes.containsKey(tid)) {
                        Map<String, Object> mn = new LinkedHashMap<String, Object>();
                        mn.put("id", tid);
                        mn.put("name", target);
                        mn.put("category", "MISSING");
                        nodes.put(tid, mn);
                    }
                }
                addGraphEdge(edges, edgeKeys, did, tid, td != null ? "WIKILINK" : "MISSING");
            }
        }

        // 计算节点度数 → 大小
        Map<String, Integer> degree = new HashMap<String, Integer>();
        for (Map<String, Object> e : edges) {
            String s = String.valueOf(e.get("source"));
            String t = String.valueOf(e.get("target"));
            degree.put(s, degree.getOrDefault(s, 0) + 1);
            degree.put(t, degree.getOrDefault(t, 0) + 1);
        }
        int docCount = 0, tagCount = 0, missingCount = 0;
        for (Map<String, Object> n : nodes.values()) {
            String id = String.valueOf(n.get("id"));
            int d = degree.getOrDefault(id, 0);
            String cat = String.valueOf(n.get("category"));
            int size;
            if ("DOC".equals(cat)) { size = 28 + Math.min(26, d * 4); docCount++; }
            else if ("TAG".equals(cat)) { size = 16 + Math.min(12, d * 2); tagCount++; }
            else { size = 18; missingCount++; }
            n.put("symbolSize", size);
            n.put("value", d);
        }

        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        stats.put("docCount", docCount);
        stats.put("tagCount", tagCount);
        stats.put("linkCount", edges.size());
        stats.put("missingCount", missingCount);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("nodes", new ArrayList<Map<String, Object>>(nodes.values()));
        result.put("edges", edges);
        result.put("stats", stats);
        return result;
    }

    private void addGraphEdge(List<Map<String, Object>> edges, Set<String> keys, String s, String t, String type) {
        if (!keys.add(s + "->" + t + ":" + type)) return;
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("source", s);
        e.put("target", t);
        e.put("type", type);
        edges.add(e);
    }

    /** 文档预览：正文、标题大纲、标签、双链、反向链接 */
    public Map<String, Object> getDocContent(Long docId) {
        KbDocument doc = docRepo.findById(docId)
                .orElseThrow(() -> new com.aiops.common.ServiceException(404, "文档不存在: " + docId));
        Frontmatter fm = parseFrontmatter(readDocContent(doc));
        List<String> tags = new ArrayList<String>(fm.tags);
        for (String t : extractInlineTags(fm.body)) {
            if (!tags.contains(t)) tags.add(t);
        }
        List<Map<String, Object>> headings = extractHeadings(fm.body);
        List<Map<String, String>> links = extractWikilinks(fm.body);

        List<KbDocument> docs = docRepo.findByKbIdOrderByCreateTimeDesc(doc.getKbId());
        Map<String, KbDocument> resolver = buildResolver(docs);
        List<Map<String, Object>> backlinks = new ArrayList<Map<String, Object>>();
        Set<Long> seen = new HashSet<Long>();
        for (KbDocument other : docs) {
            if (other.getId().equals(docId) || !seen.add(other.getId())) continue;
            Frontmatter ofm = parseFrontmatter(readDocContent(other));
            for (Map<String, String> link : extractWikilinks(ofm.body)) {
                KbDocument resolved = resolver.get(resolveKey(link.get("target")));
                if (resolved != null && resolved.getId().equals(docId)) {
                    Map<String, Object> b = new LinkedHashMap<String, Object>();
                    b.put("docId", other.getId());
                    b.put("title", displayTitle(other));
                    backlinks.add(b);
                    break;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", doc.getId());
        result.put("title", displayTitle(doc));
        result.put("fileName", doc.getFileName());
        result.put("source", doc.getSource());
        result.put("chunkCount", doc.getChunkCount());
        result.put("content", fm.body);
        result.put("tags", tags);
        result.put("headings", headings);
        result.put("wikilinks", links);
        result.put("backlinks", backlinks);
        return result;
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