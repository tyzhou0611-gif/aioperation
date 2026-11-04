<template>
  <div class="m-page">
    <div class="m-page-inner">
      <!-- 欢迎 / HUD -->
      <section class="m-hero">
        <div class="m-hero-top">
          <div>
            <div class="m-hero-title">{{ greeting }}，{{ userName }}</div>
            <div class="m-hero-sub">AI OPS · REAL-TIME COMMAND CENTER</div>
          </div>
          <span class="m-live"><i></i>ONLINE</span>
        </div>
        <div class="hero-nums">
          <div class="hero-num">
            <b>{{ onlineRate }}<em>%</em></b>
            <span>在线率</span>
          </div>
          <div class="hero-num">
            <b>{{ pct(overview?.summary?.avgCpu) }}</b>
            <span>平均 CPU</span>
          </div>
          <div class="hero-num">
            <b>{{ stats.alertCount7d ?? '-' }}</b>
            <span>近 7 天告警</span>
          </div>
        </div>
        <button class="hero-ai" @click="router.push('/m/ai')">
          <CopilotAvatar :size="30" />
          <span>让小维帮你排查</span>
          <el-icon><ArrowRight /></el-icon>
        </button>
      </section>

      <!-- 统计卡片 -->
      <section class="m-stat-grid">
        <div class="m-stat" style="--accent:#1677ff">
          <div class="m-stat-label">服务器总数</div>
          <div class="m-stat-value">{{ stats.totalServers ?? '-' }}</div>
          <div class="m-stat-foot">TOTAL HOSTS</div>
        </div>
        <div class="m-stat" style="--accent:#10b981">
          <div class="m-stat-label">在线</div>
          <div class="m-stat-value">{{ stats.onlineCount ?? '-' }}</div>
          <div class="m-stat-foot">ACTIVE NODES</div>
        </div>
        <div class="m-stat" style="--accent:#ef4444">
          <div class="m-stat-label">离线</div>
          <div class="m-stat-value">{{ stats.offlineCount ?? '-' }}</div>
          <div class="m-stat-foot">OFFLINE</div>
        </div>
        <div class="m-stat" style="--accent:#f59e0b">
          <div class="m-stat-label">近 7 天告警</div>
          <div class="m-stat-value">{{ stats.alertCount7d ?? '-' }}</div>
          <div class="m-stat-foot">ALERTS / 7D</div>
        </div>
      </section>

      <!-- 趋势 -->
      <section class="m-card">
        <div class="m-card-head">
          <div class="m-title">资源趋势 <span class="en">CPU / MEMORY</span></div>
          <div class="m-chip-row">
            <button v-for="r in ranges" :key="r.value" class="m-chip" :class="{ active: range === r.value }" @click="changeRange(r.value)">{{ r.label }}</button>
          </div>
        </div>
        <MiniLineChart v-if="trendPoints > 1" :times="trend.times || []" :series="trendSeries" :height="180" unit="%" />
        <div v-else class="m-empty">暂无趋势数据，请确认 Prometheus 已运行并采集到主机</div>
      </section>

      <!-- 快捷入口 -->
      <section class="m-card">
        <div class="m-card-head">
          <div class="m-title">快捷入口 <span class="en">QUICK ACCESS</span></div>
        </div>
        <div class="m-quick-grid">
          <button v-for="q in quickItems" :key="q.path" class="m-quick" :style="{ '--qc': q.color }" @click="router.push(q.path)">
            <span class="q-icon"><el-icon><component :is="q.icon" /></el-icon></span>
            <span class="q-label">{{ q.label }}</span>
          </button>
        </div>
      </section>

      <!-- 重点关注 -->
      <section v-if="attention.length" class="m-card">
        <div class="m-card-head">
          <div class="m-title">重点关注 <span class="en">ATTENTION</span></div>
          <span class="m-hint">{{ attention.length }} 项</span>
        </div>
        <div class="m-list">
          <div v-for="a in attention" :key="a.key" class="m-item" @click="router.push(a.path)">
            <span class="m-dot" :class="a.level"></span>
            <div class="m-item-main">
              <div class="m-item-title"><span class="name">{{ a.title }}</span></div>
              <div class="m-item-desc">{{ a.desc }}</div>
            </div>
            <div class="m-item-side">
              <el-icon color="#b6c2d1"><ArrowRight /></el-icon>
            </div>
          </div>
        </div>
      </section>

      <!-- 系统状态 -->
      <section class="m-card flat">
        <div class="row-line">
          <span class="m-hint">监控引擎</span>
          <span class="m-tag" :class="overview?.error ? 'orange' : 'green'">{{ overview?.error ? '数据异常' : 'Prometheus' }}</span>
        </div>
        <div class="row-line" style="margin-top:8px">
          <span class="m-hint">最后刷新 {{ lastUpdated || '--:--:--' }}</span>
          <button class="link-btn" @click="loadAll">刷新 <el-icon><Refresh /></el-icon></button>
        </div>
        <div v-if="overview?.error" class="m-hint" style="margin-top:8px;color:#c77700">{{ overview.error }}</div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/utils/request'
import { useUserStore } from '@/stores/user'
import CopilotAvatar from '@/components/CopilotAvatar.vue'
import MiniLineChart from '@/components/mobile/MiniLineChart.vue'
import {
  ArrowRight, Refresh, DataLine, Monitor, Cpu, Aim, Bell, Document, Collection, Grid
} from '@element-plus/icons-vue'
import { pct, timeAgo } from '@/utils/mobile'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const lastUpdated = ref('')
const stats = reactive<any>({ totalServers: null, onlineCount: null, offlineCount: null, alertCount7d: null })
const overview = ref<any>(null)
const trend = ref<any>({ times: [], cpuUsage: [], memUsage: [] })
const alerts = ref<any[]>([])
const offlineHosts = ref<any[]>([])
const range = ref(24)
const ranges = [
  { label: '6H', value: 6 },
  { label: '24H', value: 24 },
  { label: '3D', value: 72 }
]

const userName = computed(() => userStore.userInfo?.nickname || userStore.userInfo?.username || '运维同学')
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const onlineRate = computed(() => {
  const total = Number(stats.totalServers || 0)
  const online = Number(stats.onlineCount || 0)
  if (!total) return '0'
  return String(Math.round((online / total) * 1000) / 10)
})

const trendPoints = computed(() => (trend.value?.times || []).length)
const trendSeries = computed(() => [
  { name: 'CPU', data: trend.value?.cpuUsage || [], color: '#1677ff' },
  { name: '内存', data: trend.value?.memUsage || [], color: '#8b5cf6' }
])

const quickItems = [
  { label: '监控面板', path: '/m/monitor', icon: DataLine, color: '#1677ff' },
  { label: 'Web 终端', path: '/m/terminal', icon: Monitor, color: '#06b6d4' },
  { label: '命令执行', path: '/m/exec', icon: Cpu, color: '#8b5cf6' },
  { label: '智能诊断', path: '/m/diagnosis', icon: Aim, color: '#f59e0b' },
  { label: '告警历史', path: '/m/alerts', icon: Bell, color: '#ef4444' },
  { label: '日志查询', path: '/m/logs', icon: Document, color: '#10b981' },
  { label: '知识库', path: '/m/kb', icon: Collection, color: '#0ea5e9' },
  { label: '全部功能', path: '/m/more', icon: Grid, color: '#64748b' }
]

const attention = computed(() => {
  const list: any[] = []
  offlineHosts.value.slice(0, 4).forEach((s: any) => {
    list.push({
      key: 's' + s.id,
      level: 'offline',
      title: s.hostname || s.ip,
      desc: `${s.ip} · 服务器离线`,
      path: '/m/servers/' + s.id
    })
  })
  alerts.value.slice(0, 3).forEach((a: any) => {
    list.push({
      key: 'a' + a.id,
      level: a.level === 'CRITICAL' ? 'offline' : 'warn',
      title: a.message || '告警',
      desc: `${a.serverIp || '-'} · ${a.level === 'CRITICAL' ? '严重' : '警告'} · ${timeAgo(a.createTime)}`,
      path: '/m/alerts'
    })
  })
  return list
})

async function loadStats() {
  const r = await request.get('/api/dashboard/stats')
  Object.assign(stats, r.data || {})
}

async function loadTrend() {
  const r = await request.get('/api/dashboard/trend', { params: { hours: range.value } })
  trend.value = r.data || { times: [], cpuUsage: [], memUsage: [] }
}

async function loadOverview() {
  try {
    const r = await request.get('/api/monitor/overview', { params: { range: '1h', instance: 'all' } })
    overview.value = r.data || null
  } catch {
    overview.value = null
  }
}

async function loadOfflineHosts() {
  try {
    const r = await request.get('/api/server/page', { params: { page: 1, pageSize: 200 } })
    const rows = r.data?.records || []
    offlineHosts.value = rows.filter((s: any) => s.status === 'OFFLINE')
  } catch {
    offlineHosts.value = []
  }
}

async function loadAlerts() {
  try {
    const r = await request.get('/api/alert/history/page', { params: { page: 1, pageSize: 5 } })
    alerts.value = r.data?.records || []
  } catch {
    alerts.value = []
  }
}

async function loadAll() {
  if (loading.value) return
  loading.value = true
  try {
    await Promise.allSettled([loadStats(), loadTrend(), loadOverview(), loadOfflineHosts(), loadAlerts()])
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } finally {
    loading.value = false
  }
}

function changeRange(v: number) {
  range.value = v
  loadTrend()
}

let timer: any = null
onMounted(() => {
  loadAll()
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') loadAll()
  }, 60000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.hero-nums {
  display: flex;
  gap: 18px;
  margin-top: 16px;
}

.hero-num {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.hero-num b {
  font-size: 22px;
  line-height: 1.1;
  color: #f1f5f9;
  font-family: Consolas, 'DIN Alternate', monospace;
  letter-spacing: 0.5px;
}

.hero-num b em {
  font-style: normal;
  font-size: 11px;
  color: #67e8f9;
  margin-left: 1px;
}

.hero-num span {
  font-size: 10px;
  letter-spacing: 1px;
  color: #7f9bc4;
}

.hero-ai {
  margin-top: 16px;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 12px;
  border: 1px solid rgba(56, 189, 248, 0.28);
  background: rgba(56, 189, 248, 0.1);
  color: #cbe9ff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.hero-ai:active {
  background: rgba(56, 189, 248, 0.18);
}

.hero-ai .el-icon {
  margin-left: auto;
  color: #67e8f9;
}

.row-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.link-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: none;
  background: transparent;
  color: var(--m-primary);
  font-size: 12px;
  padding: 4px;
  cursor: pointer;
}

.m-title .en {
  margin-left: 2px;
}
</style>