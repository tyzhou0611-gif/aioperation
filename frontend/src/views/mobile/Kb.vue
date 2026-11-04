<template>
  <div class="m-page">
    <div class="m-page-inner">
      <!-- 知识库列表 -->
      <template v-if="!currentKb">
        <div class="m-searchbar">
          <el-input v-model="kbKeyword" placeholder="搜索知识库" clearable :prefix-icon="Search" />
          <el-button class="refresh-btn" :loading="kbLoading" @click="loadKbs"><el-icon><Refresh /></el-icon></el-button>
        </div>
        <div v-if="kbLoading && !kbs.length" class="m-list">
          <div v-for="i in 4" :key="i" class="m-skeleton"></div>
        </div>
        <div v-else-if="filteredKbs.length" class="m-list">
          <div v-for="k in filteredKbs" :key="k.id" class="kb-card" @click="openKb(k)">
            <div class="kb-icon"><el-icon :size="20"><Collection /></el-icon></div>
            <div class="kb-main">
              <div class="kb-name">{{ k.name }}</div>
              <div class="m-hint desc">{{ k.description || '暂无描述' }}</div>
              <div class="kb-meta">
                <span class="m-tag blue">{{ k.docCount || 0 }} 篇文档</span>
                <span class="m-tag gray">{{ k.embeddingModel || '默认检索' }}</span>
              </div>
            </div>
            <el-icon class="chev"><ArrowRight /></el-icon>
          </div>
        </div>
        <div v-else class="m-empty">暂无知识库</div>
      </template>

      <!-- 知识库内容 -->
      <template v-else>
        <div class="kb-head">
          <button class="back-chip" @click="backToList"><el-icon><ArrowLeft /></el-icon>{{ currentKb.name }}</button>
          <span class="m-hint">{{ docs.length }} 篇</span>
        </div>

        <template v-if="!currentDoc">
          <div class="m-searchbar" style="padding-top:0">
            <el-input v-model="query" placeholder="检索知识库内容" clearable :prefix-icon="Search" @keyup.enter="doSearch" />
            <el-button type="primary" size="small" :loading="searching" @click="doSearch">检索</el-button>
          </div>

          <div v-if="searchResults" class="m-card">
            <div class="m-card-head">
              <div class="m-title">检索结果 <span class="en">{{ searchResults.length }}</span></div>
              <button class="link-btn" @click="clearSearch">清空</button>
            </div>
            <div v-if="searchResults.length" class="chunk-list">
              <div v-for="(c, i) in searchResults" :key="i" class="chunk">
                <div class="chunk-head">{{ c.headingPath || '片段 ' + (i + 1) }}</div>
                <div class="chunk-text">{{ c.content }}</div>
              </div>
            </div>
            <div v-else class="m-empty">没有检索到相关内容</div>
          </div>

          <div v-if="docsLoading" class="m-list">
            <div v-for="i in 4" :key="i" class="m-skeleton"></div>
          </div>
          <div v-else-if="docs.length" class="m-list">
            <div v-for="d in docs" :key="d.id" class="doc-card" @click="openDoc(d.id)">
              <div class="doc-main">
                <div class="doc-title">{{ d.title }}</div>
                <div class="m-hint doc-path">{{ d.fileName }}</div>
              </div>
              <div class="doc-side">
                <span class="m-tag" :class="d.source === 'OBSIDIAN' ? 'orange' : d.source === 'MANUAL' ? 'green' : 'gray'">
                  {{ d.source === 'OBSIDIAN' ? 'Obsidian' : d.source === 'MANUAL' ? '页面创建' : '上传' }}
                </span>
                <el-icon class="chev"><ArrowRight /></el-icon>
              </div>
            </div>
          </div>
          <div v-else class="m-empty">暂无文档，请到桌面版上传 / 导入</div>
        </template>

        <!-- 文档预览 -->
        <template v-else>
          <div class="doc-head-bar">
            <button class="back-chip" @click="closeDoc"><el-icon><ArrowLeft /></el-icon>返回文档列表</button>
          </div>
          <div v-if="docLoading" class="m-card"><div class="m-skeleton" style="height:220px"></div></div>
          <div v-else class="m-card doc-preview-wrap">
            <DocPreview :doc="currentDoc" @open="openDoc" />
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import DocPreview from '@/components/DocPreview.vue'
import { Search, Refresh, Collection, ArrowRight, ArrowLeft } from '@element-plus/icons-vue'

const route = useRoute()
const kbs = ref<any[]>([])
const kbKeyword = ref('')
const kbLoading = ref(false)
const currentKb = ref<any>(null)
const docs = ref<any[]>([])
const docsLoading = ref(false)
const query = ref('')
const searching = ref(false)
const searchResults = ref<any[] | null>(null)
const currentDoc = ref<any>(null)
const docLoading = ref(false)

const filteredKbs = computed(() => {
  const k = kbKeyword.value.trim().toLowerCase()
  if (!k) return kbs.value
  return kbs.value.filter(x => [x.name, x.description].some(v => String(v || '').toLowerCase().includes(k)))
})

async function loadKbs() {
  kbLoading.value = true
  try {
    const r = await request.get('/api/kb')
    kbs.value = r.data || []
  } finally {
    kbLoading.value = false
  }
}

async function openKb(kb: any) {
  currentKb.value = kb
  currentDoc.value = null
  searchResults.value = null
  await loadDocs()
}

async function loadDocs() {
  if (!currentKb.value) return
  docsLoading.value = true
  try {
    const r = await request.get(`/api/kb/${currentKb.value.id}/docs`)
    docs.value = r.data || []
  } finally {
    docsLoading.value = false
  }
}

async function openDoc(docId: number) {
  docLoading.value = true
  currentDoc.value = null
  try {
    const r = await request.get(`/api/kb/doc/${docId}/content`)
    currentDoc.value = r.data || null
    window.scrollTo({ top: 0, behavior: 'smooth' })
  } finally {
    docLoading.value = false
  }
}

function closeDoc() {
  currentDoc.value = null
}

function backToList() {
  currentKb.value = null
  currentDoc.value = null
  docs.value = []
  searchResults.value = null
}

async function doSearch() {
  const q = query.value.trim()
  if (!q || !currentKb.value) return
  searching.value = true
  try {
    const r = await request.post(`/api/kb/${currentKb.value.id}/retrieve`, { query: q, topK: 8 })
    searchResults.value = r.data || []
  } finally {
    searching.value = false
  }
}

function clearSearch() {
  searchResults.value = null
  query.value = ''
}

onMounted(async () => {
  await loadKbs()
  const kbId = Number(route.query.kbId)
  if (kbId) {
    const kb = kbs.value.find(x => Number(x.id) === kbId)
    if (kb) {
      await openKb(kb)
      const docId = Number(route.query.docId)
      if (docId) openDoc(docId)
    }
  }
})
</script>

<style scoped>
.refresh-btn {
  flex: 0 0 auto;
  height: 32px;
  padding: 0 10px;
  border-radius: 10px;
}

.kb-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 15px;
  background: #fff;
  border: 1px solid var(--m-border);
  box-shadow: var(--m-shadow);
  cursor: pointer;
}

.kb-icon {
  width: 42px;
  height: 42px;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #1677ff;
  background: #e8f1ff;
  flex: 0 0 auto;
}

.kb-main {
  flex: 1;
  min-width: 0;
}

.kb-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--m-text);
}

.desc {
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-meta {
  display: flex;
  gap: 5px;
  margin-top: 6px;
}

.chev {
  color: #c3cedb;
  font-size: 14px;
}

.kb-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.back-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: none;
  background: transparent;
  color: var(--m-primary);
  font-size: 14px;
  font-weight: 600;
  padding: 2px 0;
  cursor: pointer;
  min-width: 0;
}

.doc-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 12px;
  border-radius: 13px;
  background: #fff;
  border: 1px solid var(--m-border);
  box-shadow: var(--m-shadow);
  cursor: pointer;
}

.doc-main {
  flex: 1;
  min-width: 0;
}

.doc-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #1f2a37;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-path {
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-side {
  display: flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
}

.doc-head-bar {
  display: flex;
  align-items: center;
}

.doc-preview-wrap {
  padding: 10px;
  overflow-x: hidden;
}

.chunk-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.chunk {
  padding: 10px;
  border-radius: 11px;
  background: #f8fafd;
  border: 1px solid #eef2f7;
}

.chunk-head {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--m-primary);
  margin-bottom: 4px;
}

.chunk-text {
  font-size: 12px;
  line-height: 1.6;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 180px;
  overflow-y: auto;
}

.link-btn {
  border: none;
  background: transparent;
  color: var(--m-primary);
  font-size: 12px;
  cursor: pointer;
}
</style>