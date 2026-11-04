<template>
  <div class="m-page">
    <div class="m-page-inner">
      <!-- 筛选 -->
      <div class="filter-bar">
        <el-select v-model="instance" filterable placeholder="全部主机" class="host-select" @change="onInstanceChange">
          <el-option label="全部主机" value="all" />
          <el-option v-for="h in hosts" :key="h.instance" :label="`${h.hostname} (${h.ip})`" :value="h.instance" />
        </el-select>
        <el-button class="refresh-btn" :loading="loading" @click="load"><el-icon><Refresh /></el-icon></el-button>
      </div>
      <div class="m-chip-row">
        <button v-for="r in ranges" :key="r.value" class="m-chip" :class="{ active: range === r.value }" @click="setRange(r.value)">{{ r.label }}</button>
      </div>

      <div v-if="data.error" class="error-box">
        <el-icon><Warning /></el-icon>
        <span>{{ data.error }}</span>
      </div>

      <!-- 概览 -->
      <section class="m-stat-grid">
        <div class="m-stat" style="--accent:#1677ff">
          <div class="m-stat-label">主机数</div>
          <div class="m-stat-value">{{ summary.totalHosts ?? '-' }}</div>
          <div class="m-stat-foot">{{ selectedHost ? '单主机视图' : 'ALL HOSTS' }}</div>
        </div>
        <div class="m-stat" style="--accent:#10b981">
          <div class="m-stat-label">在线</div>
          <div class="m-stat-value">{{ summary.onlineHosts ?? '-' }}</div>
          <div class="m-stat-foot">ONLINE</div>
        </div>
        <div class="m-stat" style="--accent:#f59e0b">
          <div class="m-stat-label">平均 CPU</div>
          <div class="m-stat-value">{{ pct(summary.avgCpu) }}</div>
          <div class="m-stat-foot">AVG CPU</div>
        </div>
        <div class="m-stat" style="--accent:#8b5cf6">
          <div class="m-stat-label">平均内存</div>
          <div class="m-stat-value">{{ pct(summary.avgMemory) }}</div>
          <div class="m-stat-foot">AVG MEMORY</div>
        </div>
        <div class="m-stat" style="--accent:#ef4444">
          <div class="m-stat-label">最高磁盘</div>
          <div class="m-stat-value">{{ pct(summary.maxDisk) }}</div>
          <div class="m-stat-foot">MAX DISK</div>
        </div>
        <div class="m-stat" style="--accent:#06b6d4">
          <div class="m-stat-label">离线</div>
          <div class="m-stat-value">{{ summary.offlineHosts ?? '-' }}</div>
          <div class="m-stat-foot">OFFLINE</div>
        </div>
      </section>

      <!-- 图表 -->
      <section class="m-card">
        <div class="m-card-head">
          <div class="m-title">{{ chartTitle }} <span class="en">{{ chartEn }}</span></div>
          <span class="m-live"><i></i>LIVE</span>
        </div>
        <div class="m-chip-row" style="margin-bottom:8px">
          <button v-for="t in chartTabs" :key="t.key" class="m-chip" :class="{ active: chartTab === t.key }" @click="chartTab = t.key">{{ t.label }}</button>
        </div>
        <MiniLineChart v-if="chart.times.length && chart.series.length" :times="chart.times" :series="chart.series" :height="190" unit="%" />
        <div v-else class="m-empty">暂无数据</div>
        <div v-if="updatedText" class="m-hint" style="margin-top:4px">更新于 {{ updatedText }} · 步长 {{ stepText || '-' }}</div>
      </section>

      <!-- 主机状态 -->
      <section class="m-card">
        <div class="m-card-head">
          <div class="m-title">主机状态 <span class="en">HOSTS</span></div>
          <span class="m-hint">{{ filteredHosts.length }} 台</span>
        </div>
        <div class="m-searchbar" style="padding-top:0">
          <el-input v-model="hostKeyword" placeholder="搜索主机 / IP" clearable :prefix-icon="Search" />
        </div>
        <div v-if="filteredHosts.length" class="host-list">
          <div v-for="h in filteredHosts" :key="h.instance" class="host-row" :class="{ active: instance === h.instance }" @click="pickHost(h)">
            <span class="m-dot" :class="h.up === true ? 'online' : h.up === false ? 'offline' : 'warn'"></span>
            <div class="host-main">
              <div class="host-name">{{ h.hostname }} <span class="ip">{{ h.ip }}</span></div>
              <div class="bars">
                <div class="bar"><span>CPU</span><div class="m-progress"><i :style="{ width: barWidth(h.cpu), background: pctColor(h.cpu) }"></i></div><b>{{ pct(h.cpu) }}</b></div>
                <div class="bar"><span>内存</span><div class="m-progress"><i :style="{ width: barWidth(h.memory), background: pctColor(h.memory) }"></i></div><b>{{ pct(h.memory) }}</b></div>
                <div class="bar"><span>磁盘</span><div class="m-progress"><i :style="{ width: barWidth(h.disk), background: pctColor(h.disk) }"></i></div><b>{{ pct(h.disk) }}</b></div>
              </div>
            </div>
            <el-icon class="chev"><ArrowRight /></el-icon>
          </div>
        </div>
        <div v-else class="m-empty">暂无主机监控数据</div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import MiniLineChart from '@/components/mobile/MiniLineChart.vue'
import { Refresh, Search, Warning, ArrowRight } from '@element-plus/icons-vue'
import { pct, pctColor } from '@/utils/mobile'
import type { MiniSeries } from '@/components/mobile/MiniLineChart.vue'

const route = useRoute()
const loading = ref(false)
const data = ref<any>({ summary: {}, hosts: [], panels: {}, error: null })
const instance = ref('all')
const range = ref('1h')
const hostKeyword = ref('')
const chartTab = ref<'cpu' | 'memory' | 'disk'>('cpu')

const ranges = [
  { label: '1 小时', value: '1h' },
  { label: '6 小时', value: '6h' },
  { label: '24 小时', value: '24h' },
  { label: '7 天', value: '7d' }
]

const chartTabs = [
  { key: 'cpu', label: 'CPU' },
  { key: 'memory', label: '内存' },
  { key: 'disk', label: '磁盘' }
] as const

const summary = computed(() => data.value?.summary || {})
const hosts = computed<any[]>(() => data.value?.hosts || [])
const selectedHost = computed(() => instance.value === 'all' ? null : hosts.value.find(h => h.instance === instance.value) || null)
const filteredHosts = computed(() => {
  const k = hostKeyword.value.trim().toLowerCase()
  if (!k) return hosts.value
  return hosts.value.filter(h => [h.hostname, h.ip, h.instance, h.job].some(v => String(v || '').toLowerCase().includes(k)))
})
const updatedText = computed(() => data.value?.updatedAt ? new Date(Number(data.value.updatedAt)).toLocaleTimeString('zh-CN', { hour12: false }) : '')
const stepText = computed(() => {
  const s = Number(data.value?.step)
  if (!Number.isFinite(s) || s <= 0) return ''
  return s >= 60 ? `${Math.round(s / 60)}m` : `${s}s`
})

const chartTitle = computed(() => ({ cpu: 'CPU 使用率', memory: '内存使用率', disk: '磁盘使用率' } as any)[chartTab.value])
const chartEn = computed(() => ({ cpu: 'CPU', memory: 'MEMORY', disk: 'DISK' } as any)[chartTab.value])

const chart = computed<{ times: (string | number)[]; series: MiniSeries[] }>(() => {
  const panel = data.value?.panels?.[chartTab.value]
  const times = panel?.times || []
  const list = panel?.series || []
  if (instance.value === 'all' && list.length > 1) {
    // 全部主机：按时间点求均值，手机屏幕只展示一条聚合曲线
    const values = times.map((_: any, idx: number) => {
      let sum = 0
      let n = 0
      for (const s of list) {
        const v = s?.data?.[idx]
        if (v != null && Number.isFinite(Number(v))) { sum += Number(v); n++ }
      }
      return n ? Math.round((sum / n) * 10) / 10 : null
    })
    return { times, series: [{ name: '全集群均值', data: values, color: '#1677ff' }] }
  }
  const colors = ['#1677ff', '#8b5cf6', '#06b6d4', '#f59e0b']
  return { times, series: list.slice(0, 4).map((s: any, i: number) => ({ name: s.name, data: s.data, color: colors[i % colors.length] })) }
})

function barWidth(v: any) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '0%'
  return `${Math.max(0, Math.min(100, n))}%`
}

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const q = String(route.query.instance || '')
    const r = await request.get('/api/monitor/overview', { params: { range: range.value, instance: instance.value } })
    data.value = r.data || { summary: {}, hosts: [], panels: {}, error: null }
    // 首次进入带 instance 参数（IP 或 ip:port）时，匹配到真实 instance 后再查一次单主机数据
    if (q && instance.value === 'all') {
      const list = data.value?.hosts || []
      const h = list.find((x: any) => x.instance === q || x.ip === q || String(x.instance).startsWith(q + ':'))
      if (h) {
        instance.value = h.instance
        const single = await request.get('/api/monitor/overview', { params: { range: range.value, instance: h.instance } })
        data.value = single.data || data.value
      }
    }
  } catch {
    data.value = { summary: {}, hosts: [], panels: {}, error: '监控数据加载失败' }
  } finally {
    loading.value = false
  }
}

function setRange(v: string) {
  range.value = v
  load()
}

function onInstanceChange() {
  load()
}

async function pickHost(h: any) {
  instance.value = instance.value === h.instance ? 'all' : h.instance
  await load()
}

watch(() => route.query.instance, (v) => {
  if (!v) return
  const h = hosts.value.find(x => x.instance === String(v) || x.ip === String(v) || String(x.instance).startsWith(String(v) + ':'))
  if (h && instance.value !== h.instance) {
    instance.value = h.instance
    load()
  }
})

let timer: any = null
onMounted(() => {
  load()
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') load()
  }, 60000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.host-select {
  flex: 1;
}

.host-select :deep(.el-select__wrapper) {
  border-radius: 12px;
  background: #fff;
}

.refresh-btn {
  flex: 0 0 auto;
  height: 32px;
  padding: 0 10px;
  border-radius: 10px;
}

.error-box {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 10px 12px;
  border-radius: 12px;
  background: #fff7e8;
  border: 1px solid #ffe1b8;
  color: #b26a00;
  font-size: 12px;
  line-height: 1.5;
}

.host-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.host-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 8px;
  border-radius: 11px;
  background: #f8fafd;
  border: 1px solid transparent;
  cursor: pointer;
}

.host-row.active {
  border-color: #b7d5ff;
  background: #f2f8ff;
}

.host-main {
  flex: 1;
  min-width: 0;
}

.host-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #334155;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.host-name .ip {
  margin-left: 5px;
  font-size: 10.5px;
  font-weight: 400;
  color: var(--m-muted);
  font-family: Consolas, monospace;
}

.bars {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 7px;
  margin-top: 5px;
}

.bar {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 9.5px;
  color: var(--m-muted);
}

.bar .m-progress {
  flex: 1;
  height: 4px;
}

.bar b {
  font-size: 9.5px;
  font-family: Consolas, monospace;
  color: #64748b;
  min-width: 30px;
  text-align: right;
}

.chev {
  color: #c3cedb;
  font-size: 13px;
}
</style>