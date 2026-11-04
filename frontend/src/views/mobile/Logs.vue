<template>
  <div class="m-page">
    <div class="m-page-inner">
      <div class="filter-card">
        <el-select v-model="serverId" filterable clearable placeholder="全部服务器" style="width:100%" @change="reload">
          <el-option v-for="s in servers" :key="s.id" :label="`${s.hostname || s.ip} (${s.ip})`" :value="s.id" />
        </el-select>
        <div class="kw-row">
          <el-input v-model="keyword" placeholder="关键词搜索" clearable :prefix-icon="Search" @keyup.enter="reload" />
          <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
        </div>
      </div>

      <div v-if="loaded && !logs.length" class="m-card flat empty-guide">
        <el-icon :size="30"><Document /></el-icon>
        <div class="guide-title">暂无日志数据</div>
        <div class="m-hint">请在【系统设置 → 日志源】接入 Loki / Elasticsearch 后查看真实日志。</div>
      </div>

      <div v-else class="m-list">
        <div v-for="l in logs" :key="l.id" class="log-card" :class="String(l.level || '').toLowerCase()" @click="open(l)">
          <div class="log-top">
            <span class="level" :class="String(l.level || '').toLowerCase()">{{ l.level }}</span>
            <span class="m-tag gray">{{ l.source || '-' }}</span>
            <span class="ip">{{ l.serverIp || '-' }}</span>
            <span class="time">{{ fmtTime(l.createTime, false) }}</span>
          </div>
          <div class="msg" v-html="highlight(l.message)"></div>
        </div>

        <div v-if="logs.length" class="load-more-wrap">
          <button class="load-more" :disabled="loading || !hasMore" @click="loadMore">
            {{ loading ? '加载中…' : hasMore ? '加载更多' : '没有更多了' }}
          </button>
        </div>
      </div>

      <!-- 详情 -->
      <el-drawer v-model="detailVisible" direction="btt" size="58%" title="日志详情">
        <div v-if="current" class="detail">
          <div class="detail-meta">
            <span class="level" :class="String(current.level || '').toLowerCase()">{{ current.level }}</span>
            <span class="m-tag gray">{{ current.source || '-' }}</span>
            <span class="m-hint">{{ fmtTime(current.createTime) }}</span>
          </div>
          <div class="detail-msg">{{ current.message }}</div>
          <button class="copy-btn" @click="copyLog">复制日志内容</button>
          <div class="m-hint" style="margin-top:8px">上下文前后 N 行需对接 Loki / ES 后提供。</div>
        </div>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { Search, Document } from '@element-plus/icons-vue'
import { fmtTime, copyText } from '@/utils/mobile'

const route = useRoute()
const servers = ref<any[]>([])
const logs = ref<any[]>([])
const serverId = ref<number | null>(null)
const keyword = ref('')
const page = ref(1)
const size = 20
const total = ref(0)
const loading = ref(false)
const loaded = ref(false)
const detailVisible = ref(false)
const current = ref<any>(null)

const hasMore = computed(() => logs.value.length < total.value)

async function loadServers() {
  try {
    const r = await request.get('/api/server/page', { params: { page: 1, pageSize: 200 } })
    servers.value = r.data?.records || []
  } catch { servers.value = [] }
}

async function load(reset = true) {
  if (loading.value) return
  loading.value = true
  if (reset) page.value = 1
  try {
    const r = await request.get('/api/log/search', {
      params: { page: page.value, size, serverId: serverId.value || undefined, keyword: keyword.value || undefined }
    })
    const rows = r.data?.records || []
    logs.value = reset ? rows : [...logs.value, ...rows]
    total.value = r.data?.total || 0
    loaded.value = true
  } finally {
    loading.value = false
  }
}

function reload() { load(true) }

function loadMore() {
  if (loading.value || !hasMore.value) return
  page.value += 1
  load(false)
}

function escapeHtml(s: string) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function highlight(msg: string) {
  if (!msg) return ''
  const kw = keyword.value.trim()
  let html = escapeHtml(msg)
  if (kw) {
    const re = new RegExp('(' + kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'gi')
    html = html.replace(re, '<mark class="hl">$1</mark>')
  }
  return html
}

function open(l: any) {
  current.value = l
  detailVisible.value = true
}

async function copyLog() {
  try {
    await copyText(current.value?.message || '')
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败')
  }
}

onMounted(() => {
  if (route.query.serverId) serverId.value = Number(route.query.serverId)
  if (route.query.keyword) keyword.value = String(route.query.keyword)
  loadServers()
  load(true)
})
</script>

<style scoped>
.filter-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border-radius: 14px;
  background: #fff;
  border: 1px solid var(--m-border);
  box-shadow: var(--m-shadow);
}

.kw-row {
  display: flex;
  gap: 8px;
}

.kw-row .el-input {
  flex: 1;
}

.log-card {
  padding: 10px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid var(--m-border);
  border-left: 3px solid #cbd5e1;
  box-shadow: var(--m-shadow);
  cursor: pointer;
}

.log-card.error { border-left-color: #ef4444; }
.log-card.warn { border-left-color: #f59e0b; }
.log-card.info { border-left-color: #1677ff; }
.log-card.debug { border-left-color: #94a3b8; }

.log-top {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}

.level {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 5px;
  background: #f1f5f9;
  color: #64748b;
  font-family: Consolas, monospace;
}

.level.error { background: #fdecec; color: #d92d2d; }
.level.warn { background: #fff4e5; color: #c77700; }
.level.info { background: #e8f1ff; color: #1677ff; }

.ip {
  font-size: 10.5px;
  color: var(--m-sub);
  font-family: Consolas, monospace;
}

.time {
  margin-left: auto;
  font-size: 10px;
  color: var(--m-muted);
}

.msg {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.55;
  color: #334155;
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.msg :deep(.hl) {
  background: #ffd54f;
  padding: 0 1px;
  border-radius: 2px;
}

.load-more-wrap {
  padding: 4px 0;
}

.load-more {
  width: 100%;
  padding: 10px;
  border-radius: 12px;
  border: 1px dashed var(--m-border);
  background: #fff;
  color: var(--m-primary);
  font-size: 12.5px;
  cursor: pointer;
}

.load-more:disabled { color: var(--m-muted); }

.empty-guide {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: var(--m-muted);
  padding: 26px 16px;
  text-align: center;
}

.guide-title {
  font-size: 14px;
  font-weight: 600;
  color: #475569;
}

.detail {
  padding: 2px 4px;
}

.detail-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.detail-msg {
  margin-top: 10px;
  padding: 10px;
  border-radius: 11px;
  background: #0f172a;
  color: #cfe3ff;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 40vh;
  overflow-y: auto;
  font-family: Consolas, Monaco, monospace;
}

.copy-btn {
  margin-top: 12px;
  width: 100%;
  padding: 10px;
  border-radius: 11px;
  border: 1px solid var(--m-border);
  background: #f8fafd;
  color: var(--m-primary);
  font-size: 13px;
  cursor: pointer;
}
</style>