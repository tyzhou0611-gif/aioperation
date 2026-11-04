<template>
  <div class="m-page">
    <div class="m-page-inner">
      <div class="m-searchbar">
        <el-input v-model="keyword" placeholder="搜索主机名 / IP / 标签" clearable :prefix-icon="Search" @keyup.enter="refresh" />
        <el-button class="refresh-btn" :loading="loading" @click="refresh">
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>

      <div class="m-chip-row">
        <button v-for="f in statusFilters" :key="f.value" class="m-chip" :class="{ active: statusFilter === f.value }" @click="statusFilter = f.value">
          {{ f.label }}<template v-if="f.value"> {{ f.count }}</template>
        </button>
      </div>
      <div class="m-chip-row">
        <button v-for="f in envFilters" :key="f.value" class="m-chip" :class="{ active: envFilter === f.value }" @click="envFilter = f.value">{{ f.label }}</button>
        <button class="m-chip" @click="router.push('/m/asset/server')">资产编辑</button>
      </div>

      <div class="list-head">
        <span class="m-hint">共 {{ filtered.length }} 台 · 实时指标 {{ metricHosts.length }} 台</span>
        <span v-if="lastUpdated" class="m-hint">{{ lastUpdated }}</span>
      </div>

      <div v-if="loading && !servers.length" class="m-list">
        <div v-for="i in 5" :key="i" class="m-skeleton"></div>
      </div>

      <div v-else-if="filtered.length" class="m-list">
        <div v-for="s in filtered" :key="s.id" class="srv-card" @click="open(s)">
          <div class="srv-top">
            <span class="m-dot" :class="statusClass(s.status)"></span>
            <div class="srv-title">
              <div class="name">{{ s.hostname || s.ip }}</div>
              <div class="m-item-desc">
                <span>{{ s.ip }}:{{ s.sshPort || 22 }}</span>
                <span v-if="s.os">· {{ s.os }}</span>
              </div>
            </div>
            <div class="srv-right">
              <span class="m-tag" :class="envTagClass(s.environment)">{{ envLabel(s.environment) }}</span>
              <span class="m-tag" :class="statusTag(s.status)">{{ statusLabel(s.status) }}</span>
            </div>
          </div>

          <div v-if="metricOf(s)" class="srv-metrics">
            <div class="metric">
              <div class="metric-top"><span>CPU</span><b :style="{ color: pctColor(metricOf(s).cpu) }">{{ pct(metricOf(s).cpu) }}</b></div>
              <div class="m-progress"><i :style="{ width: barWidth(metricOf(s).cpu), background: pctColor(metricOf(s).cpu) }"></i></div>
            </div>
            <div class="metric">
              <div class="metric-top"><span>内存</span><b :style="{ color: pctColor(metricOf(s).memory) }">{{ pct(metricOf(s).memory) }}</b></div>
              <div class="m-progress"><i :style="{ width: barWidth(metricOf(s).memory), background: pctColor(metricOf(s).memory) }"></i></div>
            </div>
            <div class="metric">
              <div class="metric-top"><span>磁盘</span><b :style="{ color: pctColor(metricOf(s).disk) }">{{ pct(metricOf(s).disk) }}</b></div>
              <div class="m-progress"><i :style="{ width: barWidth(metricOf(s).disk), background: pctColor(metricOf(s).disk) }"></i></div>
            </div>
          </div>

          <div class="srv-meta">
            <span v-if="(s.projectNames || []).length" class="m-tag blue">{{ s.projectNames.join(' / ') }}</span>
            <span v-for="t in rowTags(s).slice(0, 3)" :key="t" class="m-tag gray">{{ t }}</span>
            <span class="m-tag" :class="s.sshPasswordSet || s.sshKey ? 'green' : 'orange'">{{ s.sshPasswordSet ? '密码' : s.sshKey ? '私钥' : '未配置凭据' }}</span>
          </div>

          <div class="srv-actions" @click.stop>
            <button class="act" :disabled="!s.sshPasswordSet && !s.sshKey" @click="router.push('/m/terminal/' + s.id)">
              <el-icon><Monitor /></el-icon>终端
            </button>
            <button class="act" @click="router.push('/m/exec?serverId=' + s.id)">
              <el-icon><Cpu /></el-icon>命令
            </button>
            <button class="act" @click="router.push('/m/diagnosis?targetType=SERVER&targetId=' + s.id)">
              <el-icon><Aim /></el-icon>诊断
            </button>
            <button class="act" @click="copyIp(s.ip)">
              <el-icon><CopyDocument /></el-icon>复制IP
            </button>
          </div>
        </div>
      </div>

      <div v-else class="m-empty">
        没有匹配的服务器
        <div style="margin-top:12px">
          <el-button size="small" @click="router.push('/m/asset/server')">去添加 / 编辑服务器</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import {
  Search, Refresh, Monitor, Cpu, Aim, CopyDocument
} from '@element-plus/icons-vue'
import {
  statusClass, statusLabel, statusTag, envLabel, envTagClass, pct, pctColor, copyText
} from '@/utils/mobile'
import { parseTags } from '@/utils/tags'

const router = useRouter()
const loading = ref(false)
const keyword = ref('')
const statusFilter = ref('')
const envFilter = ref('')
const servers = ref<any[]>([])
const metricHosts = ref<any[]>([])
const lastUpdated = ref('')

const rowTags = (row: any) => parseTags(row?.tags)

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return servers.value.filter(s => {
    if (statusFilter.value && s.status !== statusFilter.value) return false
    if (envFilter.value && String(s.environment || '').toLowerCase() !== envFilter.value) return false
    if (!k) return true
    return [s.hostname, s.ip, s.os, s.tags, (s.projectNames || []).join(' ')]
      .some(v => String(v || '').toLowerCase().includes(k))
  })
})

const statusFilters = computed(() => [
  { label: '全部', value: '', count: servers.value.length },
  { label: '在线', value: 'ONLINE', count: servers.value.filter(s => s.status === 'ONLINE').length },
  { label: '离线', value: 'OFFLINE', count: servers.value.filter(s => s.status === 'OFFLINE').length },
  { label: '维护', value: 'MAINTENANCE', count: servers.value.filter(s => s.status === 'MAINTENANCE').length }
])

const envFilters = [
  { label: '全部环境', value: '' },
  { label: '生产', value: 'production' },
  { label: '预发', value: 'staging' },
  { label: '测试', value: 'test' },
  { label: '开发', value: 'dev' }
]

function metricOf(s: any) {
  const ip = s?.ip
  if (!ip) return null
  return metricHosts.value.find((h: any) => h.ip === ip || String(h.instance || '').startsWith(ip + ':')) || null
}

function barWidth(v: any) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '0%'
  return `${Math.max(0, Math.min(100, n))}%`
}

async function loadServers() {
  const r = await request.get('/api/server/page', { params: { page: 1, pageSize: 500 } })
  servers.value = r.data?.records || []
}

async function loadMetrics() {
  try {
    const r = await request.get('/api/monitor/overview', { params: { range: '1h', instance: 'all' } })
    metricHosts.value = r.data?.hosts || []
  } catch {
    metricHosts.value = []
  }
}

async function refresh() {
  if (loading.value) return
  loading.value = true
  try {
    await Promise.allSettled([loadServers(), loadMetrics()])
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } finally {
    loading.value = false
  }
}

function open(s: any) {
  router.push('/m/servers/' + s.id)
}

async function copyIp(ip: string) {
  try {
    await copyText(ip)
    ElMessage.success('已复制 ' + ip)
  } catch {
    ElMessage.warning('复制失败，请长按选择')
  }
}

let timer: any = null
onMounted(() => {
  refresh()
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') refresh()
  }, 60000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.refresh-btn {
  flex: 0 0 auto;
  height: 32px;
  padding: 0 10px;
  border-radius: 10px;
}

.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 2px 4px;
}

.srv-card {
  padding: 12px;
  border-radius: 16px;
  background: #fff;
  border: 1px solid var(--m-border);
  box-shadow: var(--m-shadow);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.srv-card:active {
  background: #f8fbff;
}

.srv-top {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.srv-top .m-dot {
  margin-top: 6px;
}

.srv-title {
  flex: 1;
  min-width: 0;
}

.srv-title .name {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--m-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.srv-right {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.srv-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
  padding: 9px 10px;
  border-radius: 11px;
  background: #f8fafd;
}

.metric-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 10.5px;
  color: var(--m-muted);
  margin-bottom: 4px;
}

.metric-top b {
  font-size: 11.5px;
  font-family: Consolas, monospace;
}

.srv-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
  margin-top: 9px;
}

.srv-actions {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px dashed #eef2f7;
}

.act {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border: 1px solid var(--m-border);
  background: #fff;
  color: #475569;
  font-size: 11.5px;
  height: 30px;
  border-radius: 9px;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.act:active {
  background: var(--m-primary-soft);
  color: var(--m-primary);
}

.act:disabled {
  opacity: 0.45;
}
</style>