<template>
  <div class="monitor" :class="{ 'monitor-tech': theme === 'tech' }" v-loading="loading">
    <!-- 工具栏 -->
    <div class="monitor-toolbar">
      <el-select v-model="instance" filterable placeholder="全部主机" style="width:230px" @change="onFilterChange">
        <el-option label="全部主机" value="all" />
        <el-option v-for="h in hosts" :key="h.instance" :label="`${h.hostname} (${h.ip})`" :value="h.instance" />
      </el-select>
      <el-radio-group v-model="range" size="small" @change="onQuickRangeChange">
        <el-radio-button value="1h">1 小时</el-radio-button>
        <el-radio-button value="6h">6 小时</el-radio-button>
        <el-radio-button value="24h">24 小时</el-radio-button>
        <el-radio-button value="7d">7 天</el-radio-button>
      </el-radio-group>
      <span class="tool-label">自定义</span>
      <el-date-picker
        v-model="customRange"
        type="datetimerange"
        size="small"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        :disabled-date="disableFuture"
        :default-time="[new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]"
        style="width:360px"
        @change="onCustomRangeChange"
      />
      <el-select v-model="refreshSec" size="small" style="width:130px">
        <el-option :value="0" label="不自动刷新" />
        <el-option :value="30" label="每 30 秒" />
        <el-option :value="60" label="每 1 分钟" />
        <el-option :value="300" label="每 5 分钟" />
      </el-select>
      <el-button type="primary" size="small" :loading="loading" @click="refreshAll">刷新</el-button>
      <el-button v-permission="'settings:source'" size="small" @click="configVisible = true">
        <el-icon style="margin-right:3px"><Setting /></el-icon>自定义面板
      </el-button>
      <span class="prom-state" :class="promState" :title="promStatus.binaryPath || promStatus.message || ''">
        <i class="dot"></i>{{ promStateText }}
      </span>
      <el-button v-if="promStatus.loaded && promStatus.mode === 'embedded' && !promStatus.healthy"
                 v-permission="'settings:source'" size="small" type="warning"
                 :loading="promBusy === 'start'" :disabled="!promStatus.canStart" @click="startPrometheus">
        一键启动 Prometheus
      </el-button>
      <span class="updated" v-if="updatedText">更新于 {{ updatedText }}<span v-if="stepText"> · 步长 {{ stepText }}</span></span>
      <span class="prom">{{ prometheusUrl }}</span>
    </div>

    <el-alert v-if="promStatus.loaded && promStatus.mode === 'embedded' && !promStatus.healthy"
              type="warning" :closable="false" show-icon style="margin-bottom:12px">
      <template #title>
        内置 Prometheus 未运行{{ promStatus.message ? '：' + promStatus.message : '，监控面板暂无实时数据' }}
        <span v-if="promStatus.archiveFound && !promStatus.binaryFound" class="prom-tip">（已检测到安装包，启动时会自动解压）</span>
      </template>
      <el-button v-permission="'settings:source'" size="small" type="primary"
                 :loading="promBusy === 'start'" :disabled="!promStatus.canStart" @click="startPrometheus">
        一键启动
      </el-button>
      <span class="prom-tip">也可以到 系统设置 → Prometheus 查看日志与配置。</span>
    </el-alert>

    <el-alert v-if="data.error" type="error" :closable="false" show-icon style="margin-bottom:12px"
              :title="'Prometheus 查询失败：' + data.error" />

    <!-- 概览卡片 -->
    <el-row :gutter="12" class="stat-row" v-if="cards.length">
      <el-col :span="4" v-for="c in cards" :key="c.key">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value" :style="{ color: c.color }">{{ c.value }}</div>
          <div class="stat-label">{{ c.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 图表 -->
    <el-row :gutter="12">
      <el-col v-if="chartOn('cpu')" :span="spanOf('cpu')">
        <el-card shadow="never" class="chart-card">
          <template #header><span>CPU 使用率 <span class="unit">(%)</span></span></template>
          <v-chart v-if="cpuOption" :option="cpuOption" autoresize class="chart" />
          <el-empty v-else description="暂无 CPU 数据" :image-size="60" />
        </el-card>
      </el-col>
      <el-col v-if="chartOn('memory')" :span="spanOf('memory')">
        <el-card shadow="never" class="chart-card">
          <template #header><span>内存使用率 <span class="unit">(%)</span></span></template>
          <v-chart v-if="memoryOption" :option="memoryOption" autoresize class="chart" />
          <el-empty v-else description="暂无内存数据" :image-size="60" />
        </el-card>
      </el-col>
      <el-col v-if="chartOn('disk')" :span="spanOf('disk')">
        <el-card shadow="never" class="chart-card">
          <template #header><span>根分区磁盘使用率 <span class="unit">(%)</span></span></template>
          <v-chart v-if="diskOption" :option="diskOption" autoresize class="chart" />
          <el-empty v-else description="暂无磁盘数据" :image-size="60" />
        </el-card>
      </el-col>
      <el-col v-if="chartOn('network')" :span="spanOf('network')">
        <el-card shadow="never" class="chart-card">
          <template #header><span>网络流量 <span class="unit">(bps)</span></span></template>
          <v-chart v-if="networkOption" :option="networkOption" autoresize class="chart" />
          <el-empty v-else description="暂无网络数据" :image-size="60" />
        </el-card>
      </el-col>
      <el-col v-if="chartOn('load')" :span="spanOf('load')">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <span>系统负载 <span class="unit">(1 / 5 / 15 分钟)</span></span>
          </template>
          <v-chart v-if="loadOption" :option="loadOption" autoresize class="chart load-chart" />
          <el-empty v-else description="暂无负载数据" :image-size="60" />
        </el-card>
      </el-col>

      <!-- 自定义重点指标 -->
      <el-col v-for="p in customPanels" :key="p.id || p.name" :span="p.span || 12">
        <el-card shadow="never" class="chart-card custom-card">
          <template #header>
            <div class="custom-head">
              <span>{{ p.name }} <span class="unit">({{ unitLabel(p.unit) }})</span></span>
              <el-tag v-if="p.latest != null" size="small" effect="plain">{{ fmtUnit(p.latest, p.unit) }}</el-tag>
            </div>
          </template>
          <v-chart v-if="customOption(p)" :option="customOption(p)" autoresize class="chart" />
          <el-alert v-else-if="p.error" type="error" :closable="false" show-icon :title="'查询失败：' + p.error" />
          <el-empty v-else description="暂无数据" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 主机状态 -->
    <el-card v-if="panelConfig.hostTable?.enabled !== false" shadow="never" class="host-card">
      <template #header>
        <div class="host-head">
          <span>主机状态 <el-tag size="small" type="info">{{ filteredHosts.length }}</el-tag></span>
          <el-input v-model="hostKeyword" size="small" placeholder="搜索主机/IP" clearable style="width:200px" />
        </div>
      </template>
      <el-table :data="pagedHosts" size="small" stripe style="width:100%">
        <el-table-column prop="hostname" label="主机名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="140" />
        <el-table-column prop="job" label="Job" width="110" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.up === true" type="success" size="small">在线</el-tag>
            <el-tag v-else-if="row.up === false" type="danger" size="small">离线</el-tag>
            <el-tag v-else type="info" size="small">未知</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="CPU" width="100" align="center">
          <template #default="{ row }"><span :class="pctClass(row.cpu)">{{ fmtPct(row.cpu) }}</span></template>
        </el-table-column>
        <el-table-column label="内存" width="100" align="center">
          <template #default="{ row }"><span :class="pctClass(row.memory)">{{ fmtPct(row.memory) }}</span></template>
        </el-table-column>
        <el-table-column label="磁盘" width="100" align="center">
          <template #default="{ row }"><span :class="pctClass(row.disk)">{{ fmtPct(row.disk) }}</span></template>
        </el-table-column>
        <el-table-column label="Load1" width="90" align="center">
          <template #default="{ row }">{{ row.load1 ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="运行时长" width="140">
          <template #default="{ row }">{{ uptimeText(row.uptime) }}</template>
        </el-table-column>
      </el-table>
      <div style="display:flex;justify-content:flex-end;margin-top:12px">
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="filteredHosts.length"
                       :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" />
      </div>
    </el-card>

    <MonitorPanelConfig
      v-model="configVisible"
      :config="panelConfig"
      :instance="instance"
      :dark="tech"
      @saved="onConfigSaved" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import MonitorPanelConfig from '@/components/MonitorPanelConfig.vue'

use([CanvasRenderer, LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent])

const PALETTE = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#8e44ad', '#00bcd4', '#ff9800',
  '#3f51b5', '#009688', '#c2185b', '#795548', '#607d8b', '#cddc39', '#ff5722', '#673ab7']
const TECH_PALETTE = ['#22d3ee', '#a78bfa', '#34d399', '#fbbf24', '#fb7185', '#60a5fa', '#f472b6', '#facc15',
  '#2dd4bf', '#c084fc', '#38bdf8', '#4ade80', '#f59e0b', '#e879f9', '#818cf8', '#5eead4']

const props = withDefaults(defineProps<{ theme?: 'tech' | 'normal' }>(), { theme: 'normal' })
const tech = computed(() => props.theme === 'tech')

const data = ref<any>({ summary: {}, hosts: [], panels: {}, error: null })
const loading = ref(false)
const route = useRoute()
const instance = ref('all')
const range = ref('1h')
const customRange = ref<any>([])
const refreshSec = ref(0)
const hostKeyword = ref('')
const page = ref(1)
const pageSize = ref(10)
const configVisible = ref(false)
let timer: any = null

/** 面板配置（组件显隐 + 自定义重点指标），由后端持久化 */
function defaultPanelConfig() {
  return {
    cards: { totalHosts: true, onlineHosts: true, offlineHosts: true, avgCpu: true, avgMemory: true, maxDisk: true },
    charts: {
      cpu: { enabled: true, span: 12 },
      memory: { enabled: true, span: 12 },
      disk: { enabled: true, span: 12 },
      network: { enabled: true, span: 12 },
      load: { enabled: true, span: 24 }
    },
    hostTable: { enabled: true },
    customMetrics: [] as any[]
  }
}
const panelConfig = ref<any>(defaultPanelConfig())
const customPanels = computed<any[]>(() => (data.value?.customPanels || []).filter((p: any) => p))

function chartOn(key: string) { return panelConfig.value?.charts?.[key]?.enabled !== false }

function spanOf(key: string) {
  const s = Number(panelConfig.value?.charts?.[key]?.span)
  if (s >= 6 && s <= 24) return s
  return key === 'load' ? 24 : 12
}

const hosts = computed(() => data.value?.hosts || [])
const prometheusUrl = computed(() => data.value?.prometheusUrl || '')

/* ===== 内置 Prometheus 状态 / 一键启动 ===== */
const promStatus = ref<any>({ loaded: false })
const promBusy = ref('')
const promState = computed(() => {
  const s = promStatus.value
  if (!s.loaded) return 'idle'
  if (s.mode !== 'embedded') return 'external'
  if (s.healthy) return 'ok'
  if (s.running) return 'warn'
  return 'off'
})
const promStateText = computed(() => {
  const s = promStatus.value
  if (!s.loaded) return 'Prometheus'
  if (s.mode !== 'embedded') return '外部 Prometheus'
  if (s.healthy) return 'Prometheus 运行中'
  if (s.running) return 'Prometheus 启动中'
  return 'Prometheus 未运行'
})
async function loadPromStatus() {
  try {
    const r = await request.get('/api/prometheus/status', { timeout: 15000 })
    promStatus.value = { ...(r.data || {}), loaded: true }
  } catch {
    promStatus.value = { loaded: true, healthy: false, running: false, mode: 'embedded' }
  }
}
async function startPrometheus() {
  promBusy.value = 'start'
  try {
    const r: any = await request.post('/api/prometheus/start', {}, { timeout: 120000 })
    if (r.data?.healthy) ElMessage.success(r.msg || 'Prometheus 已启动')
    else ElMessage.warning(r.data?.message || '启动未完成，请查看 系统设置 → Prometheus')
  } catch {
    /* 错误提示由 request 拦截器统一处理 */
  } finally {
    promBusy.value = ''
    await loadPromStatus()
    await load()
  }
}
async function refreshAll() {
  await Promise.all([load(), loadPromStatus()])
}
const updatedText = computed(() => {
  const t = data.value?.updatedAt
  return t ? new Date(t).toLocaleTimeString('zh-CN', { hour12: false }) : ''
})

const stepText = computed(() => {
  const s = Number(data.value?.step || 0)
  if (!s) return ''
  if (s < 60) return s + ' 秒'
  if (s < 3600) return Math.round(s / 60) + ' 分钟'
  if (s < 86400) return (s / 3600).toFixed(s % 3600 ? 1 : 0).replace('.0', '') + ' 小时'
  return (s / 86400).toFixed(s % 86400 ? 1 : 0).replace('.0', '') + ' 天'
})

const CARD_DEFS = [
  { key: 'totalHosts', label: '主机总数', color: '#409eff' },
  { key: 'onlineHosts', label: '在线主机', color: '#67c23a' },
  { key: 'offlineHosts', label: '离线主机', color: '#f56c6c' },
  { key: 'avgCpu', label: '平均 CPU', color: '#e6a23c' },
  { key: 'avgMemory', label: '平均内存', color: '#909399' },
  { key: 'maxDisk', label: '最高磁盘', color: '#8e44ad' }
]

const cards = computed(() => {
  const s = data.value?.summary || {}
  const valueOf = (key: string) => {
    if (key === 'totalHosts') return s.totalHosts ?? '-'
    if (key === 'onlineHosts') return s.onlineHosts ?? '-'
    if (key === 'offlineHosts') return s.offlineHosts ?? '-'
    if (key === 'avgCpu') return s.avgCpu == null ? '-' : s.avgCpu + '%'
    if (key === 'avgMemory') return s.avgMemory == null ? '-' : s.avgMemory + '%'
    if (key === 'maxDisk') return s.maxDisk == null ? '-' : s.maxDisk + '%'
    return '-'
  }
  const out: any[] = CARD_DEFS
    .filter(c => panelConfig.value?.cards?.[c.key] !== false)
    .map(c => ({ key: c.key, label: c.label, color: c.color, value: valueOf(c.key) }))
  // 自定义指标勾选“重点卡片”时，把最新值也放到概览
  for (const p of customPanels.value) {
    if (p.highlight) {
      const suffix = p.latestLabel === '合计' || p.latestLabel === '平均' ? `（${p.latestLabel}）` : ''
      out.push({ key: 'cm-' + (p.id || p.name), label: p.name + suffix, color: p.color || '#409eff',
        value: fmtUnit(p.latest, p.unit) })
    }
  }
  return out
})

const filteredHosts = computed(() => {
  const k = hostKeyword.value.trim().toLowerCase()
  if (!k) return hosts.value
  return hosts.value.filter((h: any) =>
    [h.hostname, h.ip, h.instance, h.job].some(v => (v || '').toLowerCase().includes(k)))
})

const pagedHosts = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredHosts.value.slice(start, start + pageSize.value)
})

watch(hostKeyword, () => { page.value = 1 })

function bpsText(v: number | null | undefined) {
  if (v == null) return '-'
  if (v >= 1e9) return (v / 1e9).toFixed(1) + ' Gbps'
  if (v >= 1e6) return (v / 1e6).toFixed(1) + ' Mbps'
  if (v >= 1e3) return (v / 1e3).toFixed(1) + ' Kbps'
  return Math.round(v) + ' bps'
}

function fmtTime(ts: number) {
  const d = new Date(ts)
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  const span = Number(data.value?.end || 0) - Number(data.value?.start || 0)
  if (span > 86400) return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hm}`
  return hm
}

function fmtPct(v: any) { return v == null ? '-' : v + '%' }
function pctClass(v: any) {
  if (v == null) return ''
  if (v >= 90) return 'pct danger'
  if (v >= 75) return 'pct warn'
  return 'pct ok'
}
function uptimeText(s: any) {
  if (s == null) return '-'
  const days = Math.floor(s / 86400)
  const hours = Math.floor((s % 86400) / 3600)
  if (days > 0) return `${days} 天 ${hours} 小时`
  const mins = Math.floor((s % 3600) / 60)
  return hours > 0 ? `${hours} 小时 ${mins} 分` : `${mins} 分钟`
}

function bytesText(v: number) {
  if (v >= 1024 ** 4) return (v / 1024 ** 4).toFixed(1) + ' TiB'
  if (v >= 1024 ** 3) return (v / 1024 ** 3).toFixed(1) + ' GiB'
  if (v >= 1024 ** 2) return (v / 1024 ** 2).toFixed(1) + ' MiB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KiB'
  return v.toFixed(0) + ' B'
}

function unitLabel(u: string) {
  return ({ pct: '%', bps: 'bps', bytes: 'Bytes', num: '数值', ms: 'ms', count: '数量' } as any)[u] || u || '数值'
}

function formatMetricValue(v: any, unit: string) {
  if (v == null) return '-'
  if (unit === 'pct') return v + '%'
  if (unit === 'bps') return bpsText(v)
  if (unit === 'bytes') return bytesText(v)
  if (unit === 'ms') return v + ' ms'
  return String(v)
}

function fmtUnit(v: any, unit: string) {
  if (v == null || !isFinite(Number(v))) return '-'
  const n = Number(v)
  if (unit === 'pct') return n.toFixed(1).replace(/\.0$/, '') + '%'
  if (unit === 'bps') return bpsText(n)
  if (unit === 'bytes') return bytesText(n)
  if (unit === 'ms') return n.toFixed(0) + ' ms'
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function chartOption(p: any, unit: string, type = 'line', color?: string) {
  if (!p || !p.series?.length || !p.times?.length) return null
  const dark = tech.value
  const palette = dark ? TECH_PALETTE : PALETTE
  const colors = color ? [color, ...palette.filter(c => c !== color)] : palette
  const isBar = type === 'bar'
  const isArea = type === 'area'
  return {
    color: colors,
    tooltip: {
      trigger: 'axis',
      confine: true,
      ...(dark ? {
        backgroundColor: 'rgba(7, 13, 27, .94)',
        borderColor: 'rgba(56, 189, 248, .35)',
        borderWidth: 1,
        textStyle: { color: '#e2e8f0', fontSize: 12 },
        extraCssText: 'backdrop-filter: blur(8px); border-radius: 10px; box-shadow: 0 12px 30px rgba(0,0,0,.45);'
      } : {}),
      valueFormatter: (v: any) => formatMetricValue(v, unit)
    },
    legend: { type: 'scroll', bottom: 0, textStyle: { fontSize: 11, color: dark ? '#9fb0d0' : '#606266' } },
    grid: { left: 56, right: dark ? 36 : 24, top: 18, bottom: 48 },
    xAxis: {
      type: 'category',
      data: (p.times || []).map(fmtTime),
      boundaryGap: isBar,
      axisLine: { lineStyle: { color: dark ? 'rgba(148,163,184,.22)' : '#e4e7ed' } },
      axisTick: { show: false },
      axisLabel: { fontSize: 10, hideOverlap: true, color: dark ? '#7f8fb3' : '#909399' }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: unit === 'pct' ? 100 : undefined,
      splitLine: { lineStyle: { color: dark ? 'rgba(148,163,184,.12)' : '#ebeef5', type: dark ? 'dashed' : 'solid' } },
      axisLabel: {
        color: dark ? '#7f8fb3' : '#909399',
        formatter: (v: number) => formatMetricValue(v, unit)
      }
    },
    series: (p.series || []).map((s: any) => ({
      name: s.name,
      type: isBar ? 'bar' : 'line',
      smooth: !isBar,
      showSymbol: false,
      connectNulls: true,
      barMaxWidth: 22,
      data: s.data,
      lineStyle: isBar ? undefined : (dark
        ? { width: 2, shadowBlur: 10, shadowColor: 'rgba(56,189,248,.35)' }
        : { width: 1.5 }),
      areaStyle: isArea ? { opacity: dark ? 0.10 : 0.06 } : undefined,
      emphasis: dark ? { focus: 'series', lineStyle: { width: 2.6 } } : undefined
    }))
  }
}

function lineOption(key: string, unit: string) {
  return chartOption(data.value?.panels?.[key], unit, 'area')
}

function customOption(p: any) {
  return chartOption(p, p?.unit || 'num', p?.type || 'line', p?.color)
}

const cpuOption = computed(() => lineOption('cpu', 'pct'))
const memoryOption = computed(() => lineOption('memory', 'pct'))
const diskOption = computed(() => lineOption('disk', 'pct'))
const networkOption = computed(() => lineOption('network', 'bps'))
const loadOption = computed(() => lineOption('load', 'num'))

async function loadPanelConfig() {
  try {
    const r = await request.get('/api/monitor/panel-config')
    if (r.data) panelConfig.value = r.data
  } catch { }
}

async function load() {
  loading.value = true
  try {
    const params: any = { range: range.value, instance: instance.value }
    if (range.value === 'custom' && Array.isArray(customRange.value) && customRange.value.length === 2) {
      params.start = Math.floor(new Date(customRange.value[0]).getTime() / 1000)
      params.end = Math.floor(new Date(customRange.value[1]).getTime() / 1000)
    }
    const r = await request.get('/api/monitor/overview', { params })
    data.value = r.data || { summary: {}, hosts: [], panels: {} }
    if (r.data?.panelConfig) panelConfig.value = r.data.panelConfig
    page.value = 1
  } finally {
    loading.value = false
  }
}

function onConfigSaved(cfg: any) {
  if (cfg) panelConfig.value = cfg
  load()
}

function onFilterChange() {
  page.value = 1
  load()
}

function onQuickRangeChange() {
  customRange.value = []
  page.value = 1
  load()
}

function disableFuture(date: Date) {
  return date.getTime() > Date.now()
}

function onCustomRangeChange(val: any) {
  if (!val || !Array.isArray(val) || val.length !== 2) {
    if (range.value === 'custom') {
      range.value = '1h'
      load()
    }
    return
  }
  const start = new Date(val[0]).getTime()
  const end = new Date(val[1]).getTime()
  const spanSec = (end - start) / 1000
  if (spanSec < 60) { ElMessage.warning('时间范围不能小于 1 分钟'); return }
  if (spanSec > 31 * 24 * 3600) { ElMessage.warning('自定义时间不能超过 31 天'); return }
  if (end > Date.now() + 60000) { ElMessage.warning('结束时间不能晚于当前时间'); return }
  range.value = 'custom'
  page.value = 1
  load()
}

function setupTimer() {
  if (timer) { clearInterval(timer); timer = null }
  if (refreshSec.value > 0) timer = setInterval(load, refreshSec.value * 1000)
}

watch(refreshSec, setupTimer)

/** AI 可能传主机名（如 node-104），转换成 Prometheus 的 instance */
function resolveInstance(v: string) {
  if (!v || v === 'all') return 'all'
  if (hosts.value.some((h: any) => h.instance === v)) return v
  const hit = hosts.value.find((h: any) => h.hostname === v || h.ip === v || h.instance.split(':')[0] === v)
  return hit ? hit.instance : 'all'
}

async function applyRouteQueryAndLoad() {
  const qr = route.query.range
  const qs = Number(route.query.start)
  const qe = Number(route.query.end)
  if (qs && qe) {
    customRange.value = [new Date(qs * 1000), new Date(qe * 1000)]
    range.value = 'custom'
  } else if (qr) {
    range.value = String(qr)
    customRange.value = []
  }
  const qi = route.query.instance
  if (qi) instance.value = String(qi)
  await load()
  if (qi) {
    const resolved = resolveInstance(String(qi))
    if (resolved !== instance.value) {
      instance.value = resolved
      await load()
    }
  }
}

watch(() => [route.query.instance, route.query.range, route.query.start, route.query.end], () => { applyRouteQueryAndLoad() })

onMounted(async () => {
  await loadPanelConfig()
  loadPromStatus()
  applyRouteQueryAndLoad()
  setupTimer()
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.monitor { min-height: 400px; }
.monitor-toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px; }
.monitor-toolbar .updated { color: #909399; font-size: 12px; }
.monitor-toolbar .tool-label { color: #909399; font-size: 12px; }
.monitor-toolbar .prom { margin-left: auto; color: #c0c4cc; font-size: 11px; }

/* 内置 Prometheus 状态 / 一键启动 */
.prom-state {
  display: inline-flex; align-items: center; gap: 6px; font-size: 12px;
  padding: 3px 10px; border-radius: 999px;
  border: 1px solid #e4e7ed; background: #fafafa; color: #909399;
}
.prom-state .dot { width: 7px; height: 7px; border-radius: 50%; background: #909399; }
.prom-state.ok { color: #529b2e; border-color: #d1eac4; background: #f0f9eb; }
.prom-state.ok .dot { background: #67c23a; box-shadow: 0 0 6px rgba(103, 194, 58, .7); }
.prom-state.warn { color: #b88230; border-color: #faecd8; background: #fdf6ec; }
.prom-state.warn .dot { background: #e6a23c; }
.prom-state.off { color: #c45656; border-color: #fde2e2; background: #fef0f0; }
.prom-state.off .dot { background: #f56c6c; box-shadow: 0 0 6px rgba(245, 108, 108, .6); }
.prom-tip { color: #909399; font-size: 12px; margin-left: 8px; }
.monitor-tech .prom-state { background: rgba(56, 189, 248, .08); border-color: rgba(56, 189, 248, .25); color: #9fb6d9; }
.monitor-tech .prom-state.ok { color: #22e6a4; border-color: rgba(34, 230, 164, .35); background: rgba(34, 230, 164, .08); }
.monitor-tech .prom-state.warn { color: #fbbf24; border-color: rgba(251, 191, 36, .35); background: rgba(251, 191, 36, .08); }
.monitor-tech .prom-state.off { color: #ff6b6b; border-color: rgba(255, 107, 107, .35); background: rgba(255, 107, 107, .08); }
.monitor-tech .prom-tip { color: #7e93ad; }
.monitor-tech :deep(.el-alert--warning) {
  background: rgba(251, 191, 36, .08); border: 1px solid rgba(251, 191, 36, .3); border-radius: 8px;
}
.monitor-tech :deep(.el-alert--warning .el-alert__title) { color: #fbbf24; }
.monitor-tech :deep(.el-alert--warning .el-alert__description) { color: #9fb6d9; }

.stat-row { margin-bottom: 4px; }
.stat-card { text-align: center; padding: 4px 0; }
.stat-value { font-size: 24px; font-weight: 700; line-height: 1.3; }
.stat-label { color: #909399; font-size: 12px; margin-top: 2px; }

.chart-card { margin-bottom: 12px; }
.chart-card :deep(.el-card__header) { padding: 10px 16px; font-size: 14px; font-weight: 600; color: #303133; }
.custom-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.custom-head .unit { color: #909399; font-size: 12px; font-weight: 400; }
.unit { color: #909399; font-size: 12px; font-weight: 400; }
.chart { height: 290px; width: 100%; }
.load-chart { height: 240px; }

.host-card { margin-top: 2px; }
.host-head { display: flex; align-items: center; justify-content: space-between; }
.pct { font-weight: 600; }
.pct.ok { color: #67c23a; }
.pct.warn { color: #e6a23c; }
.pct.danger { color: #f56c6c; }

/* ==================== 科技风（由仪表盘“界面风格”切换） ==================== */
.monitor-tech { color: #cbd5e1; }
.monitor-tech :deep(.el-card) {
  background: linear-gradient(160deg, rgba(15, 23, 42, .9), rgba(8, 13, 26, .95));
  border: 1px solid rgba(148, 163, 184, .14);
  box-shadow: 0 10px 28px rgba(2, 8, 20, .35);
}
.monitor-tech :deep(.el-card__header) {
  border-bottom: 1px solid rgba(148, 163, 184, .12);
  color: #e2e8f0;
}
.monitor-tech .stat-label,
.monitor-tech .unit,
.monitor-tech .monitor-toolbar .updated,
.monitor-tech .monitor-toolbar .tool-label,
.monitor-tech .hint { color: #64748b; }
.monitor-tech .monitor-toolbar .prom { color: #475569; }
.monitor-tech :deep(.el-input__wrapper),
.monitor-tech :deep(.el-select__wrapper) {
  background: rgba(15, 23, 42, .78);
  border-color: rgba(148, 163, 184, .2);
  box-shadow: none;
}
.monitor-tech :deep(.el-input__inner),
.monitor-tech :deep(.el-select__selected-item) { color: #cbd5e1; }
.monitor-tech :deep(.el-radio-button__inner) {
  background: rgba(15, 23, 42, .78);
  border-color: rgba(148, 163, 184, .2);
  color: #9fb0d0;
}
.monitor-tech :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: linear-gradient(135deg, #0ea5e9, #6366f1);
  border-color: transparent;
  color: #ffffff;
  box-shadow: 0 0 12px rgba(56, 189, 248, .3);
}
.monitor-tech :deep(.el-date-editor .el-range-input) { color: #cbd5e1; background: transparent; }
.monitor-tech :deep(.el-date-editor .el-range-separator) { color: #64748b; }
.monitor-tech :deep(.el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(56, 189, 248, .06);
  --el-table-border-color: rgba(148, 163, 184, .1);
  --el-table-text-color: #cbd5e1;
  --el-table-header-text-color: #94a3b8;
  background: transparent;
}
.monitor-tech :deep(.el-table th.el-table__cell),
.monitor-tech :deep(.el-table tr),
.monitor-tech :deep(.el-table td.el-table__cell) {
  background: transparent;
  border-color: rgba(148, 163, 184, .1);
}
.monitor-tech :deep(.el-table--striped .el-table__body tr.el-table__row--striped td.el-table__cell) {
  background: rgba(30, 41, 59, .35);
}
.monitor-tech :deep(.el-table__empty-text),
.monitor-tech :deep(.el-empty__description) { color: #64748b; }
.monitor-tech :deep(.el-pagination) { color: #94a3b8; }
.monitor-tech :deep(.el-pagination button),
.monitor-tech :deep(.el-pager li) { background: transparent; color: #94a3b8; }
.monitor-tech :deep(.el-pager li.is-active) { color: #22d3ee; }
.monitor-tech .monitor-toolbar :deep(.el-button--primary) {
  background: linear-gradient(135deg, #0ea5e9, #6366f1);
  border-color: transparent;
}
</style>