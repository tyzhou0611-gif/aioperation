<template>
  <div v-if="doc" class="doc-preview">
    <div class="doc-head">
      <div style="min-width:0">
        <h3>{{ doc.title }}</h3>
        <div class="doc-meta">{{ doc.fileName }}<span v-if="doc.chunkCount"> · {{ doc.chunkCount }} 个切块</span></div>
      </div>
      <div class="tags">
        <el-tag v-for="t in doc.tags || []" :key="t" size="small" effect="plain">#{{ t }}</el-tag>
      </div>
    </div>
    <div class="doc-body">
      <div ref="contentRef" class="md-content" v-html="rendered"></div>
      <aside class="doc-side">
        <div v-if="doc.headings?.length" class="side-block">
          <div class="side-title">标题大纲</div>
          <div v-for="h in doc.headings" :key="h.index" class="outline-item"
               :class="'lv' + h.level" @click="scrollToHeading(h)">{{ h.text }}</div>
        </div>
        <div v-if="doc.backlinks?.length" class="side-block">
          <div class="side-title">反向链接 ({{ doc.backlinks.length }})</div>
          <div v-for="b in doc.backlinks" :key="b.docId" class="side-link" @click="$emit('open', b.docId)">
            ← {{ b.title }}
          </div>
        </div>
        <div v-if="doc.wikilinks?.length" class="side-block">
          <div class="side-title">出链 ({{ doc.wikilinks.length }})</div>
          <div v-for="l in doc.wikilinks" :key="l.target + l.type" class="side-link muted">
            {{ l.type === 'EMBED' ? '嵌入' : '→' }} {{ l.target }}
          </div>
        </div>
        <el-empty v-if="!doc.headings?.length && !doc.backlinks?.length && !doc.wikilinks?.length"
                  description="无大纲/链接" :image-size="50" />
      </aside>
    </div>
  </div>
  <el-empty v-else description="暂无内容" />
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps<{ doc: any | null }>()
defineEmits<{ (e: 'open', docId: number): void }>()

const contentRef = ref<HTMLElement>()

function escapeHtml(s: string) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

const rendered = computed(() => {
  const raw = props.doc?.content || ''
  // 把 Obsidian [[双链]] 渲染成可视化标签（点击跳转由大纲/反向链接完成）
  const withLinks = raw.replace(/!?\[\[([^\]]+)\]\]/g, (_m: string, inner: string) => {
    const name = inner.split('|')[0].split('#')[0].trim()
    return `<span class="wl">${escapeHtml(name)}</span>`
  })
  try {
    const html = marked.parse(withLinks, { async: false }) as string
    return DOMPurify.sanitize(html, { ADD_ATTR: ['target'] })
  } catch {
    return `<pre>${escapeHtml(raw)}</pre>`
  }
})

function scrollToHeading(h: any) {
  const nodes = contentRef.value?.querySelectorAll('h1,h2,h3,h4,h5,h6')
  if (!nodes) return
  for (const el of Array.from(nodes)) {
    if ((el.textContent || '').trim() === h.text) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' })
      break
    }
  }
}
</script>

<style scoped>
.doc-preview { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.doc-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 12px;
  padding-bottom: 10px; border-bottom: 1px solid #ebeef5; margin-bottom: 10px;
}
.doc-head h3 { margin: 0; font-size: 18px; color: #303133; }
.doc-meta { color: #909399; font-size: 12px; margin-top: 4px; word-break: break-all; }
.tags { display: flex; flex-wrap: wrap; gap: 4px; justify-content: flex-end; }
.doc-body { display: flex; gap: 16px; flex: 1; min-height: 0; }
.md-content { flex: 1; overflow: auto; padding-right: 10px; font-size: 14px; line-height: 1.75; color: #303133; }
.md-content :deep(h1), .md-content :deep(h2), .md-content :deep(h3),
.md-content :deep(h4), .md-content :deep(h5), .md-content :deep(h6) {
  margin: 14px 0 8px; color: #303133; line-height: 1.4;
}
.md-content :deep(h1) { font-size: 22px; }
.md-content :deep(h2) { font-size: 18px; border-bottom: 1px solid #ebeef5; padding-bottom: 4px; }
.md-content :deep(h3) { font-size: 16px; }
.md-content :deep(p) { margin: 6px 0; }
.md-content :deep(a) { color: #409eff; }
.md-content :deep(code) { background: #f5f7fa; padding: 1px 5px; border-radius: 3px; font-size: 13px; }
.md-content :deep(pre) { background: #282c34; color: #e6e6e6; padding: 12px; border-radius: 6px; overflow: auto; }
.md-content :deep(pre code) { background: transparent; padding: 0; color: inherit; }
.md-content :deep(ul), .md-content :deep(ol) { padding-left: 22px; margin: 6px 0; }
.md-content :deep(blockquote) { margin: 8px 0; padding: 4px 12px; border-left: 3px solid #409eff; background: #f5f7fa; color: #606266; }
.md-content :deep(table) { border-collapse: collapse; margin: 8px 0; }
.md-content :deep(th), .md-content :deep(td) { border: 1px solid #ebeef5; padding: 5px 10px; }
.md-content :deep(th) { background: #f5f7fa; }
.md-content :deep(img) { max-width: 100%; }
.md-content :deep(.wl) { color: #409eff; background: #ecf5ff; padding: 0 4px; border-radius: 3px; }
.doc-side { width: 230px; flex: 0 0 230px; overflow: auto; border-left: 1px solid #ebeef5; padding-left: 12px; }
.side-block { margin-bottom: 16px; }
.side-title { font-size: 12px; color: #909399; margin-bottom: 6px; font-weight: 600; }
.outline-item { font-size: 12px; color: #606266; padding: 3px 0; cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.outline-item:hover { color: #409eff; }
.outline-item.lv2 { padding-left: 10px; }
.outline-item.lv3 { padding-left: 20px; }
.outline-item.lv4 { padding-left: 30px; }
.outline-item.lv5 { padding-left: 40px; }
.outline-item.lv6 { padding-left: 50px; }
.side-link { font-size: 12px; color: #409eff; padding: 3px 0; cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.side-link:hover { text-decoration: underline; }
.side-link.muted { color: #606266; cursor: default; }
.side-link.muted:hover { text-decoration: none; }
</style>