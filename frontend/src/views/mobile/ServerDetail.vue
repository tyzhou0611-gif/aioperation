<template>
  <div class="m-page">
    <div v-if="loading && !server" class="m-page-inner">
      <div class="m-skeleton" style="height:88px"></div>
      <div class="m-skeleton" style="height:180px"></div>
      <div class="m-skeleton" style="height:120px"></div>
    </div>

    <div v-else-if="server" class="m-page-inner">
      <!-- 基本信息 -->
      <section class="m-hero">
        <div class="m-hero-top">
          <div style="min-width:0">
            <div class="m-hero-title name">{{ server.hostname || server.ip }}</div>
            <div class="m-hero-sub">{{ server.ip }}:{{ server.sshPort || 22 }} · {{ server.os || '未知系统' }}</div>
          </div>
          <span class="m-tag" :class="statusTag(server.status)">{{ statusLabel(server.status) }}</span>
        </div>
        <div class="hero-tags">
          <span class="m-tag" :class="envTagClass(server.environment)">{{ envLabel(server.environment) }}</span>
          <span class="m-tag gray">{{ server.statusSource ? '来源 ' + server.statusSource : '未探测' }}</span>
          <span class="m-tag gray">最后探测 {{ timeAgo(server.lastProbeTime) }}</span>
        </div>
        <div class="hero-metrics">
          <div><b :style="{ color: pctColor(host?.cpu) }">{{ pct(host?.cpu) }}</b><span>CPU</span></div>
          <div><b :style="{ color: pctColor(host?.memory) }">{{ pct(host?.memory) }}</b><span>内存</span></div>
          <div><b :style="{ color: pctColor(host?.disk) }">{{ pct(host?.disk) }}</b><span>磁盘</span></div>
          <div><b>{{ host?.load1 != null ? host.load1 : '-' }}</b><span>Load1</span></div>
        </div>
      </section>

      <!-- 操作 -->
      <section class="m-card">
        <div class="m-card-head">
          <div class="m-title">快捷操作 <span class="en">ACTIONS</span></div>
        </div>
        <div class="action-grid">
          <button class="action" :disabled="!server.sshPasswordSet && !server.sshKey" @click="router.push('/m/terminal/' + server.id)">
            <el-icon><Monitor /></el-icon><span>Web 终端</span>
          </button>
          <button class="action" @click="router.push('/m/exec?serverId=' + server.id)">
            <el-icon><Cpu /></el-icon><span>命令执行</span>
          </button>
          <button class="action" @click="router.push('/m/diagnosis?targetType=SERVER&targetId=' + server.id)">
            <el-icon><Aim /></el-icon><span>AI 诊断</span>
          </button>
          <button class="action" @click="router.push('/m/monitor?instance=' + encodeURIComponent(host?.instance || server.ip))">
            <el-icon><DataLine /></el-icon><span>监控面板</span>
          </button>
          <button class="action" @click="router.push('/m/asset/server?id=' + server.id)">
            <el-icon><Edit /></el-icon><span>编辑资产</span>
          </button>
          <button class="action" @click="copyIp">
            <el-icon><CopyDocument /></el-icon><span>复制 IP</span>
          </button>
        </div>
      </section>

      <!-- 监控趋势 -->
      <section class="m-card">
        <div class="m-card-head">
          <div class="m-title">实时趋势 <span class="en">METRICS</span></div>
          <div class="m-chip-row">
            <button v-for="r in ranges" :key="r.value" class="m-chip" :class="{ active: range === r.value }" @click="changeRange(r.value)">{{ r.label }}</button>
          </div>
        </div>
        <div class="metric-tabs">
          <button v-for="t in metricTabs" :key="t.key" class="m-chip" :class="{ active: chartTab === t.key }" @click="chartTab = t.key">{{ t.label }}</button>
        </div>
        <MiniLineChart v-if="chartSeries.length && chartTimes.length" :times="chartTimes" :series="chartSeries" :height="170" unit="%" />
        <div v-else class="m-empty">暂无监控数据（需安装 node_exporter 并被 Prometheus 采集）</div>
        <div class="m-hint" style="margin-top:4px">运行时长 {{ uptimeText(host?.uptime) }}</div>
      </section>

      <!-- 服务器信息 -->
      <section class="m-card">
        <div class="m-card-head">
          <div class="m-title">服务器信息 <span class="en">INFO</span></div>
        </div>
        <div class="m-kv">
          <span class="k">IP 地址</span><span class="v">{{ server.ip }}</span>
          <span class="k">主机名</span><span class="v">{{ server.hostname || '-' }}</span>
          <span class="k">操作系统</span><span class="v">{{ server.os || '-' }}</span>
          <span class="k">CPU</span><span class="v">{{ server.cpu || '-' }}</span>
          <span class="k">内存</span><span class="v">{{ server.memory || '-' }}</span>
          <span class="k">磁盘</span><span class="v">{{ server.disk || '-' }}</span>
          <span class="k">SSH 账号</span><span class="v">{{ server.sshUser || 'root' }} · {{ server.sshPasswordSet ? '密码' : server.sshKey ? '私钥' : '未配置' }}</span>
          <span class="k">所属项目</span><span class="v">{{ (server.projectNames || []).join('、') || '未分配' }}</span>
          <span class="k">标签</span>
          <span class="v">
            <span v-for="t in tags" :key="t" class="m-tag gray" style="margin-right:4px">{{ t }}</span>
            <span v-if="!tags.length">-</span>
          </span>
        </div>
      </section>

      <!-- 最近命令 -->
      <section class="m-card">
        <div class="m-card-head">
          <div class="m-title">最近执行 <span class="en">RECENT RUNS</span></div>
          <button class="link-btn" @click="router.push('/m/exec')">全部 <el-icon><ArrowRight /></el-icon></button>
        </div>
        <div v-if="history.length" class="m-list">
          <div v-for="h in history" :key="h.id" class="history-item" @click="router.push('/m/exec')">
            <div class="cmd">{{ h.command }}</div>
            <div class="m-item-desc">
              <span>{{ fmtTime(h.startTime || h.createTime) }}</span>
              <span>· {{ h.operator }}</span>
              <span class="m-tag" :class="h.failCount > 0 ? 'orange' : 'green'">{{ h.failCount > 0 ? '部分失败' : '成功' }}</span>
            </div>
          </div>
        </div>
        <div v-else class="m-empty">暂无执行记录</div>
      </section>
    </div>

    <div v-else class="m-empty">
      未找到该服务器
      <div style="margin-top:12px"><el-button size="small" @click="router.push('/m/servers')">返回主机列表</el-button></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import MiniLineChart from '@/components/mobile/MiniLineChart.vue'
import {
  Monitor, Cpu, Aim, DataLine, Edit, CopyDocument, ArrowRight
} from '@element-plus/icons-vue'
import {
  statusLabel, statusTag, envLabel, envTagClass, pct, pctColor, uptimeText, fmtTime, timeAgo, copyText
} from '@/utils/mobile'
import { parseTags } from '@/utils/tags'

const route = useRoute()
const router = useRouter()
const serverId = computed(() => Number(route.params.id))

const loading = ref(false)
const server = ref<any>(null)
const host = ref<any>(null)
const overview = ref<any>(null)
const history = ref<any[]>([])
const range = ref('1h')
const chartTab = ref<'cpu' | 'memory' | 'disk'>('cpu')

const ranges = [
  { label: '1H', value: '1h' },
  { label: '6H', value: '6h' },
  { label: '24H', value: '24h' }
]
const metricTabs = [
  { key: 'cpu', label: 'CPU' },
  { key: 'memory', label: '内存' },
  { key: 'disk', label: '磁盘' }
] as const

const tags = computed(() => parseTags(server.value?.tags))
const chartTimes = computed(() => overview.value?.panels?.[chartTab.value]?.times || [])
const chartSeries = computed(() => {
  const list = overview.value?.panels?.[chartTab.value]?.series || []
  const colors = ['#1677ff', '#8b5cf6', '#06b6d4', '#f59e0b']
  const label = chartTab.value === 'cpu' ? 'CPU' : chartTab.value === 'memory' ? '内存' : '磁盘'
  return list.slice(0, 4).map((s: any, i: number) => ({
    name: i === 0 ? label : s.name,
    data: s.data,
    color: colors[i % colors.length]
  }))
})

async function loadServer() {
  const r = await request.get(`/api/server/${serverId.value}`)
  server.value = r.data || null
}

async function loadMonitor() {
  try {
    const all = await request.get('/api/monitor/overview', { params: { range: '1h', instance: 'all' } })
    const hosts = all.data?.hosts || []
    const s = server.value
    host.value = hosts.find((h: any) => h.ip === s?.ip || String(h.instance || '').startsWith((s?.ip || '') + ':')) || null
    if (host.value?.instance) {
      const r = await request.get('/api/monitor/overview', { params: { range: range.value, instance: host.value.instance } })
      overview.value = r.data || null
    } else {
      overview.value = null
    }
  } catch {
    overview.value = null
  }
}

async function loadHistory() {
  try {
    const r = await request.get('/api/exec/history', { params: { page: 1, pageSize: 5, serverId: serverId.value } })
    history.value = r.data?.records || []
  } catch {
    history.value = []
  }
}

async function loadAll() {
  if (loading.value) return
  loading.value = true
  try {
    await loadServer()
    await Promise.allSettled([loadMonitor(), loadHistory()])
  } finally {
    loading.value = false
  }
}

async function changeRange(v: string) {
  range.value = v
  await loadMonitor()
}

async function copyIp() {
  const ip = server.value?.ip
  if (!ip) return
  try {
    await copyText(ip)
    ElMessage.success('已复制 ' + ip)
  } catch {
    ElMessage.warning('复制失败')
  }
}

let timer: any = null
onMounted(() => {
  loadAll()
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') loadMonitor()
  }, 60000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 10px;
}

.hero-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid rgba(148, 163, 184, 0.16);
}

.hero-metrics > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.hero-metrics b {
  font-size: 16px;
  font-family: Consolas, monospace;
  color: #f1f5f9;
}

.hero-metrics span {
  font-size: 10px;
  letter-spacing: 1px;
  color: #7f9bc4;
}

.action-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.action {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 11px 4px;
  border-radius: 12px;
  border: 1px solid var(--m-border);
  background: #f9fbfe;
  color: #475569;
  font-size: 11.5px;
  cursor: pointer;
}

.action .el-icon {
  font-size: 18px;
  color: var(--m-primary);
}

.action:active {
  background: var(--m-primary-soft);
}

.action:disabled {
  opacity: 0.45;
}

.metric-tabs {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
}

.history-item {
  padding: 10px;
  border-radius: 11px;
  background: #f8fafd;
  border: 1px solid #eef2f7;
}

.history-item .cmd {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: #334155;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.link-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: none;
  background: transparent;
  color: var(--m-primary);
  font-size: 12px;
  padding: 2px;
  cursor: pointer;
}
</style>