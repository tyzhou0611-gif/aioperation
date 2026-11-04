<template>
  <div class="m-page">
    <div class="m-page-inner">
      <div class="tabs">
        <button class="tab" :class="{ active: tab === 'history' }" @click="switchTab('history')">告警历史</button>
        <button class="tab" :class="{ active: tab === 'rule' }" @click="switchTab('rule')">告警规则</button>
      </div>

      <!-- 告警历史 -->
      <template v-if="tab === 'history'">
        <div class="m-searchbar">
          <el-input v-model="keyword" placeholder="搜索 IP / 指标 / 消息" clearable :prefix-icon="Search" @keyup.enter="reload" />
          <el-button class="refresh-btn" :loading="loading" @click="reload"><el-icon><Refresh /></el-icon></el-button>
        </div>
        <div class="m-chip-row">
          <button v-for="l in levels" :key="l.value" class="m-chip" :class="{ active: level === l.value }" @click="setLevel(l.value)">{{ l.label }}</button>
        </div>

        <div v-if="loading && !list.length" class="m-list">
          <div v-for="i in 4" :key="i" class="m-skeleton" style="height:86px"></div>
        </div>

        <div v-else-if="list.length" class="m-list">
          <div v-for="a in list" :key="a.id" class="alert-card" :class="a.level === 'CRITICAL' ? 'critical' : 'warning'">
            <div class="alert-top">
              <span class="level-tag">
                <el-icon><WarningFilled /></el-icon>{{ a.level === 'CRITICAL' ? '严重' : '警告' }}
              </span>
              <span class="m-tag" :class="statusTag(a.status)">{{ a.status === 'UNREAD' ? '未读' : a.status === 'READ' ? '已读' : '已解决' }}</span>
              <span class="time">{{ timeAgo(a.createTime) }}</span>
            </div>
            <div class="alert-msg">{{ a.message || '-' }}</div>
            <div class="alert-meta">
              <span>{{ a.serverIp || '-' }}</span>
              <span class="dot-sep">·</span>
              <span>{{ metricLabel(a.metricName) }}</span>
              <span class="dot-sep">·</span>
              <span>触发 {{ a.actualValue }}% / 阈值 {{ a.comparator }}{{ a.threshold }}%</span>
            </div>
            <div class="alert-time">{{ fmtTime(a.createTime) }}</div>
          </div>

          <button class="load-more" :disabled="!hasMore || loading" @click="loadMore">
            {{ loading ? '加载中…' : hasMore ? '加载更多' : '没有更多了' }}
          </button>
        </div>

        <div v-else class="m-empty">暂无告警记录</div>
      </template>

      <!-- 告警规则 -->
      <template v-else>
        <div v-if="rulesLoading" class="m-list">
          <div v-for="i in 4" :key="i" class="m-skeleton"></div>
        </div>
        <div v-else-if="rules.length" class="m-list">
          <div v-for="r in rules" :key="r.id" class="rule-card">
            <div class="rule-top">
              <div class="rule-name">{{ r.ruleName }}</div>
              <el-switch
                :model-value="r.enabled"
                :disabled="!canEdit || toggling === r.id"
                @change="(v: any) => toggleRule(r, v)"
              />
            </div>
            <div class="rule-meta">
              <span class="m-tag" :class="r.level === 'CRITICAL' ? 'red' : 'orange'">{{ r.level === 'CRITICAL' ? '严重' : '警告' }}</span>
              <span class="m-tag blue">{{ metricLabel(r.metricName) }} {{ r.comparator }} {{ r.threshold }}%</span>
              <span class="m-tag" :class="r.enabled ? 'green' : 'gray'">{{ r.enabled ? '启用' : '禁用' }}</span>
            </div>
            <div v-if="r.notifyEmail" class="m-hint" style="margin-top:6px">通知：{{ r.notifyEmail }}</div>
          </div>
          <div class="m-hint" style="text-align:center;padding:6px">规则的新增 / 删除请前往桌面版操作</div>
        </div>
        <div v-else class="m-empty">暂无告警规则</div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { useUserStore } from '@/stores/user'
import { Search, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { fmtTime, timeAgo } from '@/utils/mobile'

const userStore = useUserStore()
const canEdit = computed(() => userStore.permissions.includes('*') || userStore.permissions.includes('alert:rule:edit'))

const tab = ref<'history' | 'rule'>('history')
const loading = ref(false)
const keyword = ref('')
const level = ref('')
const list = ref<any[]>([])
const page = ref(1)
const pageSize = 10
const total = ref(0)

const rulesLoading = ref(false)
const rules = ref<any[]>([])
const toggling = ref<number | null>(null)

const levels = [
  { label: '全部', value: '' },
  { label: '警告', value: 'WARNING' },
  { label: '严重', value: 'CRITICAL' }
]

const hasMore = computed(() => list.value.length < total.value)

function metricLabel(name: string) {
  return ({ CPU_USAGE: 'CPU', MEM_USAGE: '内存', DISK_USAGE: '磁盘' } as any)[name] || name || '-'
}

function statusTag(s: string) {
  return s === 'UNREAD' ? 'red' : s === 'READ' ? 'orange' : 'green'
}

async function load(reset = true) {
  if (loading.value) return
  loading.value = true
  if (reset) page.value = 1
  try {
    const r = await request.get('/api/alert/history/page', {
      params: { page: page.value, pageSize, keyword: keyword.value || undefined, level: level.value || undefined }
    })
    const rows = r.data?.records || []
    list.value = reset ? rows : [...list.value, ...rows]
    total.value = r.data?.total || 0
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

function setLevel(v: string) {
  level.value = v
  load(true)
}

async function loadRules() {
  rulesLoading.value = true
  try {
    const r = await request.get('/api/alert/rule/page', { params: { page: 1, pageSize: 100 } })
    rules.value = r.data?.records || []
  } finally {
    rulesLoading.value = false
  }
}

async function toggleRule(r: any, v: any) {
  toggling.value = r.id
  try {
    await request.put(`/api/alert/rule/${r.id}`, { ...r, enabled: v ? 1 : 0 })
    r.enabled = v ? 1 : 0
    ElMessage.success(v ? '规则已启用' : '规则已禁用')
  } catch {
    /* 错误提示由拦截器处理 */
  } finally {
    toggling.value = null
  }
}

function switchTab(t: 'history' | 'rule') {
  tab.value = t
  if (t === 'rule' && !rules.value.length) loadRules()
}

onMounted(() => load(true))
</script>

<style scoped>
.tabs {
  display: flex;
  padding: 3px;
  border-radius: 12px;
  background: #e9eef6;
}

.tab {
  flex: 1;
  border: none;
  background: transparent;
  padding: 8px;
  border-radius: 10px;
  color: #64748b;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.tab.active {
  background: #fff;
  color: var(--m-primary);
  box-shadow: 0 2px 8px rgba(15, 35, 70, 0.08);
}

.refresh-btn {
  flex: 0 0 auto;
  height: 32px;
  padding: 0 10px;
  border-radius: 10px;
}

.alert-card {
  position: relative;
  padding: 12px;
  border-radius: 15px;
  background: #fff;
  border: 1px solid var(--m-border);
  border-left: 3px solid var(--m-orange);
  box-shadow: var(--m-shadow);
}

.alert-card.critical {
  border-left-color: var(--m-red);
}

.alert-top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.level-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11.5px;
  font-weight: 700;
  color: #c77700;
}

.critical .level-tag {
  color: #d92d2d;
}

.time {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--m-muted);
}

.alert-msg {
  margin-top: 7px;
  font-size: 13.5px;
  line-height: 1.5;
  color: #1f2a37;
  word-break: break-word;
}

.alert-meta {
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--m-sub);
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}

.dot-sep {
  color: #cbd5e1;
}

.alert-time {
  margin-top: 4px;
  font-size: 10.5px;
  color: var(--m-muted);
  font-family: Consolas, monospace;
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

.load-more:disabled {
  color: var(--m-muted);
}

.rule-card {
  padding: 12px;
  border-radius: 15px;
  background: #fff;
  border: 1px solid var(--m-border);
  box-shadow: var(--m-shadow);
}

.rule-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.rule-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--m-text);
}

.rule-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}
</style>