<template>
  <div class="dashboard" :class="{ 'tech-mode': styleMode === 'tech' }">
    <!-- 顶部标题 / HUD -->
    <div class="dash-head">
      <div class="dash-title">
        <h2>仪表盘</h2>
        <p class="dash-sub">{{ activeTab === 'echarts' ? 'AI OPS COMMAND CENTER · REAL-TIME OVERVIEW' : 'PROMETHEUS MONITORING · REAL-TIME OVERVIEW' }}</p>
      </div>
      <div class="dash-hud">
        <div class="style-switch">
          <span class="style-label">界面风格</span>
          <el-radio-group v-model="styleMode" size="small" @change="onStyleChange">
            <el-radio-button label="tech">科技风</el-radio-button>
            <el-radio-button label="normal">普通版</el-radio-button>
          </el-radio-group>
        </div>
        <span class="hud-live"><i class="hud-dot"></i>ONLINE</span>
        <span class="hud-clock">{{ nowText }}</span>
        <el-button v-if="activeTab === 'echarts'" class="hud-refresh" size="small" :loading="loadingDash" @click="loadDashboard">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </div>

    <!-- 两个 UI 始终保留，右上角可切换科技风 / 普通版 -->
    <el-tabs v-model="activeTab" class="dash-tabs">
      <el-tab-pane label="内置图表" name="echarts" />
      <el-tab-pane label="监控面板" name="panels" />
    </el-tabs>

    <!-- ====== 模式一：科技感内置图表 ====== -->
    <template v-if="activeTab === 'echarts'">
      <!-- 统计卡片 -->
      <div class="stats-grid">
        <div v-for="stat in statCards" :key="stat.key" class="tech-stat" :style="{ '--accent': stat.color }">
          <span class="stat-tag">{{ stat.tag }}</span>
          <div class="stat-top">
            <div class="stat-icon"><el-icon :size="20"><component :is="stat.icon" /></el-icon></div>
            <span class="stat-scanline"></span>
          </div>
          <div class="stat-value">{{ stat.value }}</div>
          <div class="stat-title">{{ stat.title }}<span>{{ stat.en }}</span></div>
          <div class="stat-line"></div>
        </div>
      </div>

      <!-- 图表区 -->
      <div class="charts-grid">
        <div class="tech-panel">
          <div class="panel-head">
            <div class="panel-title"><span class="panel-accent"></span>资源趋势<small>CPU / MEMORY · LAST 24H</small></div>
            <span class="panel-live"><i></i>LIVE</span>
          </div>
          <v-chart class="tech-chart" :option="lineOption" autoresize />
        </div>

        <div class="tech-panel">
          <div class="panel-head">
            <div class="panel-title"><span class="panel-accent"></span>主机状态<small>HOST STATUS · REAL-TIME</small></div>
            <span class="panel-live"><i></i>CLUSTER</span>
          </div>
          <v-chart class="tech-chart" :option="pieOption" autoresize />
        </div>
      </div>

      <!-- 快捷入口 -->
      <div class="tech-panel actions-panel">
        <div class="panel-head">
          <div class="panel-title"><span class="panel-accent"></span>快捷入口<small>QUICK ACCESS</small></div>
        </div>
        <div class="action-grid">
          <button class="tech-action" @click="router.push('/asset/server')">
            <el-icon><Monitor /></el-icon>
            <div><b>服务器管理</b><small>ASSET INVENTORY</small></div>
          </button>
          <button class="tech-action" @click="router.push('/alert/rule')">
            <el-icon><Warning /></el-icon>
            <div><b>告警规则</b><small>ALERT RULES</small></div>
          </button>
          <button class="tech-action" @click="router.push('/alert/history')">
            <el-icon><DataBoard /></el-icon>
            <div><b>告警历史</b><small>ALERT HISTORY</small></div>
          </button>
        </div>
      </div>

      <!-- 底部状态栏 -->
      <div class="tech-footer">
        <span class="live-dot"></span>
        <span>LIVE DATA</span>
        <span class="sep">//</span>
        <span>更新于 {{ lastUpdated || '--:--:--' }}</span>
        <span class="sep">//</span>
        <span>PROMETHEUS · 24H</span>
        <span class="footer-right">小维 AI · STANDING BY</span>
      </div>
    </template>

    <!-- ====== 模式二：原生监控面板（Prometheus 数据，无需 Grafana 登录） ====== -->
    <template v-if="activeTab === 'panels'">
      <MonitorPanels :theme="styleMode" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/utils/request'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent, MarkLineComponent } from 'echarts/components'
import { Monitor, DataBoard, Warning, CircleCheck, Refresh } from '@element-plus/icons-vue'
import MonitorPanels from './MonitorPanels.vue'
import { useUserStore } from '@/stores/user'

use([CanvasRenderer, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent, MarkLineComponent])

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

// 仪表盘界面风格：tech(科技风) / normal(普通版)，同时作用于「内置图表」和「监控面板」
type StyleMode = 'tech' | 'normal'
function normalizeStyle(v: any): StyleMode {
  return v === 'normal' ? 'normal' : 'tech'
}
const styleMode = ref<StyleMode>(normalizeStyle(userStore.userInfo?.dashboardStyle || localStorage.getItem('dashboard_style')))

const activeTab = ref(route.query.tab === 'panels' ? 'panels' : 'echarts')
watch(() => route.query.tab, (v) => {
  activeTab.value = v === 'panels' ? 'panels' : 'echarts'
})

async function onStyleChange(v: any) {
  const style = normalizeStyle(v)
  styleMode.value = style
  localStorage.setItem('dashboard_style', style)
  if (userStore.userInfo) {
    userStore.userInfo = { ...userStore.userInfo, dashboardStyle: style }
    localStorage.setItem('userInfo', JSON.stringify(userStore.userInfo))
  }
  try {
    await request.put('/api/auth/preferences', { dashboardStyle: style })
  } catch {
    /* 后端保存失败时本地偏好仍然生效 */
  }
}

// ==================== 顶部时钟 / 刷新 ====================
const nowText = ref('')
const lastUpdated = ref('')
const loadingDash = ref(false)
let clockTimer: any = null
let refreshTimer: any = null

function tickClock() {
  nowText.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

// ==================== 统计卡片（数字滚动） ====================
type StatKey = 'totalServers' | 'onlineCount' | 'offlineCount' | 'alertCount7d'
const animated = reactive<Record<StatKey, number>>({
  totalServers: 0,
  onlineCount: 0,
  offlineCount: 0,
  alertCount7d: 0
})
const statsLoaded = ref(false)
const animTokens: Record<string, number> = {}

function animateNumber(key: StatKey, target: any) {
  const to = Number(target)
  if (!Number.isFinite(to)) return
  const from = Number(animated[key] || 0)
  const token = (animTokens[key] = (animTokens[key] || 0) + 1)
  const start = performance.now()
  const duration = 700
  const step = (now: number) => {
    if (animTokens[key] !== token) return
    const p = Math.min(1, (now - start) / duration)
    const eased = 1 - Math.pow(1 - p, 3)
    animated[key] = Math.round(from + (to - from) * eased)
    if (p < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}

const statCards = computed(() => [
  { key: 'totalServers', title: '服务器总数', en: 'TOTAL HOSTS', tag: 'INVENTORY', value: statsLoaded.value ? animated.totalServers : '-', icon: Monitor, color: '#38bdf8' },
  { key: 'onlineCount', title: '在线数量', en: 'ACTIVE NODES', tag: 'ONLINE', value: statsLoaded.value ? animated.onlineCount : '-', icon: CircleCheck, color: '#34d399' },
  { key: 'offlineCount', title: '离线数量', en: 'OFFLINE NODES', tag: 'ALERT', value: statsLoaded.value ? animated.offlineCount : '-', icon: Warning, color: '#fb7185' },
  { key: 'alertCount7d', title: '近7天告警', en: 'ALERTS / 7D', tag: 'EVENTS', value: statsLoaded.value ? animated.alertCount7d : '-', icon: DataBoard, color: '#fbbf24' }
])

// ==================== 图表配置 ====================
const trendData = ref<any>(null)
const serverCounts = reactive({ online: 0, offline: 0, maintenance: 0, total: 0 })
const lineOption = computed(() => buildLineOption(trendData.value || { times: [], cpuUsage: [], memUsage: [] }, styleMode.value))
const pieOption = computed(() => buildPieOption(serverCounts.online, serverCounts.offline, serverCounts.maintenance, serverCounts.total, styleMode.value))

function techTooltip() {
  return {
    backgroundColor: 'rgba(7, 13, 27, .94)',
    borderColor: 'rgba(56, 189, 248, .35)',
    borderWidth: 1,
    padding: [8, 12],
    textStyle: { color: '#e2e8f0', fontSize: 12 },
    extraCssText: 'backdrop-filter: blur(8px); border-radius: 10px; box-shadow: 0 12px 30px rgba(0,0,0,.45);'
  }
}

function areaGradient(rgb: string) {
  return {
    type: 'linear',
    x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [
      { offset: 0, color: `rgba(${rgb}, .26)` },
      { offset: 1, color: `rgba(${rgb}, 0)` }
    ]
  }
}

function lineGradient(from: string, to: string) {
  return {
    type: 'linear',
    x: 0, y: 0, x2: 1, y2: 0,
    colorStops: [
      { offset: 0, color: from },
      { offset: 1, color: to }
    ]
  }
}

function buildLineOption(trend: any, style: StyleMode = 'tech') {
  const times = trend?.times || []
  const cpu = trend?.cpuUsage || []
  const mem = trend?.memUsage || []
  if (style === 'normal') {
    return {
      backgroundColor: 'transparent',
      color: ['#409eff', '#67c23a'],
      tooltip: { trigger: 'axis' },
      legend: { data: ['CPU', '内存'], bottom: 0 },
      grid: { left: 40, right: 20, bottom: 50, top: 20 },
      xAxis: {
        type: 'category',
        data: times,
        boundaryGap: false,
        axisLabel: { rotate: 45, fontSize: 11 }
      },
      yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
      series: [
        {
          name: 'CPU', type: 'line', smooth: true, data: cpu,
          areaStyle: { opacity: 0.15 }, itemStyle: { color: '#409eff' },
          markLine: { data: [{ yAxis: 90, label: { formatter: '告警线 90%', position: 'insideEndTop' } }] }
        },
        {
          name: '内存', type: 'line', smooth: true, data: mem,
          areaStyle: { opacity: 0.15 }, itemStyle: { color: '#67c23a' }
        }
      ]
    }
  }
  return {
    backgroundColor: 'transparent',
    color: ['#38bdf8', '#a78bfa'],
    tooltip: {
      ...techTooltip(),
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: 'rgba(56,189,248,.45)', type: 'dashed' } },
      valueFormatter: (v: any) => (v == null ? '-' : v + '%')
    },
    legend: {
      top: 0, right: 6,
      data: ['CPU', '内存'],
      icon: 'roundRect', itemWidth: 14, itemHeight: 6, itemGap: 16,
      textStyle: { color: '#9fb0d0', fontSize: 11 }
    },
    grid: { left: 48, right: 86, top: 40, bottom: 34 },
    xAxis: {
      type: 'category',
      data: times,
      boundaryGap: false,
      axisLine: { lineStyle: { color: 'rgba(148,163,184,.22)' } },
      axisTick: { show: false },
      axisLabel: { color: '#7f8fb3', fontSize: 10, hideOverlap: true }
    },
    yAxis: {
      type: 'value', min: 0, max: 100, splitNumber: 5,
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: '#7f8fb3', fontSize: 10, formatter: '{value}%' },
      splitLine: { lineStyle: { color: 'rgba(148,163,184,.12)', type: 'dashed' } }
    },
    series: [
      {
        name: 'CPU', type: 'line', smooth: 0.35, showSymbol: false, symbol: 'circle', symbolSize: 6,
        data: cpu,
        lineStyle: { width: 2.5, color: lineGradient('#22d3ee', '#38bdf8'), shadowBlur: 14, shadowColor: 'rgba(34,211,238,.5)' },
        itemStyle: { color: '#38bdf8' },
        areaStyle: { color: areaGradient('56,189,248') },
        emphasis: { focus: 'series', itemStyle: { shadowBlur: 18, shadowColor: 'rgba(56,189,248,.9)' } },
        endLabel: {
          show: true, distance: 8,
          formatter: (p: any) => `${p.seriesName} ${p.value == null ? '-' : p.value + '%'}`,
          color: '#67e8f9', fontSize: 10, fontWeight: 600,
          backgroundColor: 'rgba(34,211,238,.12)', borderColor: 'rgba(34,211,238,.4)', borderWidth: 1,
          borderRadius: 4, padding: [3, 6]
        },
        markLine: {
          silent: true, symbol: 'none',
          lineStyle: { color: 'rgba(251,113,133,.55)', type: 'dashed', width: 1 },
          label: { color: '#fb7185', fontSize: 10, formatter: '90%', position: 'insideEndTop' },
          data: [{ yAxis: 90 }]
        }
      },
      {
        name: '内存', type: 'line', smooth: 0.35, showSymbol: false, symbol: 'circle', symbolSize: 6,
        data: mem,
        lineStyle: { width: 2.5, color: lineGradient('#a78bfa', '#f472b6'), shadowBlur: 14, shadowColor: 'rgba(167,139,250,.48)' },
        itemStyle: { color: '#a78bfa' },
        areaStyle: { color: areaGradient('167,139,250') },
        emphasis: { focus: 'series', itemStyle: { shadowBlur: 18, shadowColor: 'rgba(167,139,250,.9)' } },
        endLabel: {
          show: true, distance: 8,
          formatter: (p: any) => `${p.seriesName} ${p.value == null ? '-' : p.value + '%'}`,
          color: '#d8b4fe', fontSize: 10, fontWeight: 600,
          backgroundColor: 'rgba(167,139,250,.14)', borderColor: 'rgba(167,139,250,.42)', borderWidth: 1,
          borderRadius: 4, padding: [3, 6]
        }
      }
    ]
  }
}

function buildPieOption(online: number, offline: number, maintenance: number, total: number, style: StyleMode = 'tech') {
  if (style === 'normal') {
    return {
      backgroundColor: 'transparent',
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0 },
      series: [{
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['50%', '40%'],
        data: [
          { value: online, name: '在线', itemStyle: { color: '#67c23a' } },
          { value: offline, name: '离线', itemStyle: { color: '#f56c6c' } },
          { value: maintenance, name: '维护', itemStyle: { color: '#e6a23c' } }
        ],
        label: { show: true, formatter: '{b}\n{d}%' }
      }]
    }
  }
  const onlineRate = total > 0 ? Math.round((online / total) * 1000) / 10 : 0
  const counts: Record<string, number> = { '在线': online, '离线': offline, '维护': maintenance }
  const data = [
    { value: online, name: '在线' },
    { value: offline, name: '离线' },
    { value: maintenance, name: '维护' }
  ]
  return {
    backgroundColor: 'transparent',
    color: ['#22d3ee', '#fb7185', '#fbbf24'],
    tooltip: {
      ...techTooltip(),
      trigger: 'item',
      formatter: '{b}: {c} 台 ({d}%)'
    },
    title: {
      text: onlineRate + '%',
      subtext: 'ONLINE RATE',
      left: 'center',
      top: '30%',
      textAlign: 'center',
      textStyle: { color: '#f1f5f9', fontSize: 30, fontWeight: 700, fontFamily: 'Consolas, monospace' },
      subtextStyle: { color: '#7f8fb3', fontSize: 10, letterSpacing: 2 }
    },
    legend: {
      bottom: 2, left: 'center',
      data: ['在线', '离线', '维护'],
      icon: 'circle', itemWidth: 8, itemHeight: 8, itemGap: 18,
      textStyle: { color: '#9fb0d0', fontSize: 11 },
      formatter: (name: string) => `${name}  ${counts[name] ?? 0}`
    },
    series: [{
      type: 'pie',
      radius: ['58%', '78%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      stillShowZeroSum: false,
      itemStyle: { borderColor: '#0b1224', borderWidth: 3, borderRadius: 6 },
      label: { show: false },
      labelLine: { show: false },
      emphasis: { scale: true, scaleSize: 7, itemStyle: { shadowBlur: 26, shadowColor: 'rgba(56,189,248,.35)' } },
      data: data
    }]
  }
}

// ==================== 数据加载 ====================
async function loadDashboard() {
  if (loadingDash.value) return
  loadingDash.value = true
  try {
    const results: any[] = await Promise.all([
      request.get('/api/dashboard/stats'),
      request.get('/api/dashboard/trend?hours=24'),
      request.get('/api/server/page?pageSize=100').catch(() => null)
    ])
    const s = results[0]?.data || {}
    animateNumber('totalServers', s.totalServers)
    animateNumber('onlineCount', s.onlineCount)
    animateNumber('offlineCount', s.offlineCount)
    animateNumber('alertCount7d', s.alertCount7d)
    statsLoaded.value = true

    trendData.value = results[1]?.data || null

    const servers = results[2]?.data?.records || []
    const listOnline = servers.filter((x: any) => x.status === 'ONLINE').length
    const listOffline = servers.filter((x: any) => x.status === 'OFFLINE').length
    const maintenance = servers.filter((x: any) => x.status === 'MAINTENANCE').length
    const total = Number(s.totalServers ?? (listOnline + listOffline + maintenance))
    const online = Number(s.onlineCount ?? listOnline)
    const offline = Number(s.offlineCount ?? listOffline)
    serverCounts.online = online
    serverCounts.offline = offline
    serverCounts.maintenance = maintenance
    serverCounts.total = total

    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (e) {
    /* 保持已有数据 */
  } finally {
    loadingDash.value = false
  }
}

onMounted(() => {
  tickClock()
  clockTimer = setInterval(tickClock, 1000)
  loadDashboard()
  refreshTimer = setInterval(() => {
    if (activeTab.value === 'echarts') loadDashboard()
  }, 60000)
})

onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<style scoped lang="scss">
.dashboard { position: relative; transition: background .3s ease, padding .3s ease, border-radius .3s ease; }

/* ==================== 暗色科技驾驶舱 ==================== */
.dashboard.tech-mode {
  padding: 22px 24px 14px;
  border-radius: 20px;
  overflow: hidden;
  color: #e2e8f0;
  background:
    radial-gradient(ellipse at 12% 0%, rgba(34, 211, 238, .14), transparent 45%),
    radial-gradient(ellipse at 88% 4%, rgba(167, 139, 250, .13), transparent 40%),
    linear-gradient(155deg, #0b1224 0%, #0e1729 46%, #080e1c 100%);
  box-shadow: 0 18px 50px rgba(2, 8, 20, .25);
}
.dashboard.tech-mode::before {
  content: '';
  position: absolute; inset: 0; pointer-events: none;
  background-image:
    linear-gradient(rgba(148, 163, 184, .07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, .07) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(circle at 50% 0%, #000 0%, transparent 78%);
  -webkit-mask-image: radial-gradient(circle at 50% 0%, #000 0%, transparent 78%);
  opacity: .7;
}
.dashboard.tech-mode::after {
  content: '';
  position: absolute; left: 0; right: 0; top: -30%; height: 30%;
  pointer-events: none;
  background: linear-gradient(180deg, transparent, rgba(56, 189, 248, .08), transparent);
  animation: tech-scan 9s linear infinite;
}
.dashboard.tech-mode > * { position: relative; z-index: 1; }

/* 顶部 */
.dash-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 10px; }
.dash-title h2 { margin: 0; font-size: 22px; color: #333; }
.tech-mode .dash-title h2 { font-size: 24px; color: #f1f5f9; letter-spacing: .5px; text-shadow: 0 0 24px rgba(56, 189, 248, .25); }
.dash-sub { margin: 4px 0 0; font-size: 10.5px; letter-spacing: 2px; color: #6b7a99; font-family: Consolas, monospace; }
.tech-mode .dash-sub { color: #5f7fa8; }
.dash-hud { display: flex; align-items: center; gap: 14px; }
.style-switch { display: flex; align-items: center; gap: 8px; }
.style-label { font-size: 11px; letter-spacing: 1px; color: #94a3b8; white-space: nowrap; }
.tech-mode .style-label { color: #64748b; }
.tech-mode .style-switch :deep(.el-radio-button__inner) {
  background: rgba(15, 23, 42, .78);
  border-color: rgba(148, 163, 184, .2);
  color: #9fb0d0;
}
.tech-mode .style-switch :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: linear-gradient(135deg, #0ea5e9, #6366f1);
  border-color: transparent;
  color: #ffffff;
  box-shadow: 0 0 12px rgba(56, 189, 248, .35);
}
.dashboard:not(.tech-mode) .hud-live { color: #67c23a; }
.dashboard:not(.tech-mode) .hud-dot { background: #67c23a; box-shadow: 0 0 10px #67c23a; }
.dashboard:not(.tech-mode) .hud-clock { color: #606266; }
.dashboard:not(.tech-mode) .hud-refresh { background: #ecf5ff; border-color: #b3d8ff; color: #409eff; }
.dashboard:not(.tech-mode) .hud-refresh:hover { background: #d9ecff; border-color: #409eff; color: #409eff; }
.hud-live { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; letter-spacing: 1.5px; color: #22d3ee; font-family: Consolas, monospace; }
.hud-dot { width: 7px; height: 7px; border-radius: 50%; background: #22d3ee; box-shadow: 0 0 10px #22d3ee; animation: tech-pulse 1.8s ease-in-out infinite; }
.hud-clock { font-family: Consolas, monospace; font-size: 13px; letter-spacing: 1px; color: #9fb0d0; font-variant-numeric: tabular-nums; }
.hud-refresh { background: rgba(56, 189, 248, .08); border-color: rgba(56, 189, 248, .35); color: #7dd3fc; }
.hud-refresh:hover { background: rgba(56, 189, 248, .16); border-color: #38bdf8; color: #e0f2fe; }

/* Tabs */
.dash-tabs { margin-bottom: 18px; }
.tech-mode :deep(.el-tabs__header) { margin-bottom: 18px; }
.tech-mode :deep(.el-tabs__item) { color: #7f8fb3; font-size: 13px; letter-spacing: .5px; }
.tech-mode :deep(.el-tabs__item:hover) { color: #7dd3fc; }
.tech-mode :deep(.el-tabs__item.is-active) { color: #e0f2fe; }
.tech-mode :deep(.el-tabs__active-bar) { height: 2px; background: linear-gradient(90deg, #22d3ee, #a78bfa); box-shadow: 0 0 10px rgba(34, 211, 238, .6); }
.tech-mode :deep(.el-tabs__nav-wrap::after) { height: 1px; background-color: rgba(148, 163, 184, .15); }
.tech-mode :deep(.el-tabs__content) { overflow: visible; }

/* 统计卡片 */
.stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 16px; }
.tech-stat {
  position: relative; overflow: hidden;
  padding: 14px 16px 12px;
  border-radius: 14px;
  background: linear-gradient(145deg, rgba(30, 41, 59, .75), rgba(15, 23, 42, .92));
  border: 1px solid rgba(148, 163, 184, .16);
  transition: transform .25s ease, border-color .25s ease, box-shadow .25s ease;
}
.tech-stat::before {
  content: ''; position: absolute; top: 0; left: 0; right: 0; height: 2px;
  background: linear-gradient(90deg, var(--accent), transparent);
  opacity: .85;
}
.tech-stat::after {
  content: ''; position: absolute; width: 150px; height: 150px; right: -55px; top: -62px;
  border-radius: 50%;
  background: radial-gradient(circle, var(--accent), transparent 70%);
  opacity: .10;
}
.tech-stat:hover {
  transform: translateY(-3px);
  border-color: var(--accent);
  box-shadow: 0 14px 34px rgba(2, 8, 20, .5), inset 0 0 24px rgba(56, 189, 248, .04);
}
.stat-tag { font-size: 9.5px; letter-spacing: 1.6px; color: #64748b; font-family: Consolas, monospace; }
.stat-top { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }
.stat-icon {
  width: 34px; height: 34px; border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
  color: var(--accent);
  background: linear-gradient(135deg, rgba(56, 189, 248, .12), rgba(167, 139, 250, .08));
  border: 1px solid rgba(148, 163, 184, .18);
  box-shadow: inset 0 0 12px rgba(56, 189, 248, .08);
}
.stat-scanline {
  width: 46px; height: 3px; border-radius: 99px;
  background: linear-gradient(90deg, transparent, var(--accent), transparent);
  opacity: .55;
}
.stat-value {
  margin-top: 8px;
  font-size: 30px; font-weight: 700; line-height: 1.1;
  color: #f8fafc;
  font-family: Consolas, 'DIN Alternate', monospace;
  letter-spacing: .5px;
  text-shadow: 0 0 18px rgba(56, 189, 248, .18);
}
.stat-title { margin-top: 3px; font-size: 12px; color: #94a3b8; }
.stat-title span { margin-left: 7px; font-size: 9px; letter-spacing: 1.2px; color: #475569; font-family: Consolas, monospace; }
.stat-line { margin-top: 10px; height: 2px; border-radius: 99px; background: linear-gradient(90deg, var(--accent), transparent); opacity: .45; }

/* 图表面板 */
.charts-grid { display: grid; grid-template-columns: 1.65fr 1fr; gap: 16px; margin-bottom: 16px; }
.tech-panel {
  position: relative; overflow: hidden;
  padding: 14px 16px 8px;
  border-radius: 16px;
  background: linear-gradient(160deg, rgba(15, 23, 42, .86), rgba(8, 13, 26, .95));
  border: 1px solid rgba(148, 163, 184, .14);
  box-shadow: 0 10px 28px rgba(2, 8, 20, .35);
}
.tech-panel::before {
  content: ''; position: absolute; inset: 0; pointer-events: none; border-radius: 16px;
  background:
    linear-gradient(#38bdf8, #38bdf8) left top / 18px 1px no-repeat,
    linear-gradient(#38bdf8, #38bdf8) left top / 1px 18px no-repeat,
    linear-gradient(#38bdf8, #38bdf8) right top / 18px 1px no-repeat,
    linear-gradient(#38bdf8, #38bdf8) right top / 1px 18px no-repeat,
    linear-gradient(#a78bfa, #a78bfa) left bottom / 18px 1px no-repeat,
    linear-gradient(#a78bfa, #a78bfa) left bottom / 1px 18px no-repeat,
    linear-gradient(#a78bfa, #a78bfa) right bottom / 18px 1px no-repeat,
    linear-gradient(#a78bfa, #a78bfa) right bottom / 1px 18px no-repeat;
  opacity: .4;
}
.tech-panel::after {
  content: ''; position: absolute; inset: 0; pointer-events: none;
  background: radial-gradient(circle at 100% 0%, rgba(56, 189, 248, .08), transparent 42%);
}
.panel-head { position: relative; z-index: 1; display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.panel-title { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; color: #e2e8f0; }
.panel-accent { width: 3px; height: 14px; border-radius: 2px; background: linear-gradient(#22d3ee, #a78bfa); box-shadow: 0 0 8px rgba(34, 211, 238, .7); }
.panel-title small { font-size: 9.5px; letter-spacing: 1.5px; color: #64748b; font-family: Consolas, monospace; font-weight: 400; }
.panel-live { display: inline-flex; align-items: center; gap: 5px; font-size: 10px; letter-spacing: 1.2px; color: #22d3ee; font-family: Consolas, monospace; }
.panel-live i { width: 6px; height: 6px; border-radius: 50%; background: #22d3ee; box-shadow: 0 0 8px #22d3ee; animation: tech-pulse 1.6s ease-in-out infinite; }
.tech-chart { position: relative; z-index: 1; height: 340px; width: 100%; }

/* 快捷入口 */
.actions-panel { padding-bottom: 16px; margin-bottom: 14px; }
.action-grid { position: relative; z-index: 1; display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-top: 10px; }
.tech-action {
  display: flex; align-items: center; gap: 12px; text-align: left;
  padding: 12px 14px; border-radius: 12px; cursor: pointer;
  color: #cbd5e1;
  background: rgba(15, 23, 42, .6);
  border: 1px solid rgba(148, 163, 184, .14);
  transition: all .2s ease;
}
.tech-action:hover {
  border-color: rgba(56, 189, 248, .55);
  background: rgba(56, 189, 248, .08);
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(2, 8, 20, .3);
}
.tech-action .el-icon { color: #22d3ee; font-size: 20px; }
.tech-action b { display: block; font-size: 13px; color: #e2e8f0; }
.tech-action small { display: block; margin-top: 2px; font-size: 9.5px; letter-spacing: 1.2px; color: #64748b; font-family: Consolas, monospace; }

/* 底部状态栏 */
.tech-footer {
  display: flex; align-items: center; gap: 8px;
  padding-top: 10px;
  border-top: 1px solid rgba(148, 163, 184, .12);
  font-size: 10.5px; letter-spacing: 1.2px; color: #64748b;
  font-family: Consolas, monospace;
}
.tech-footer .live-dot { width: 6px; height: 6px; border-radius: 50%; background: #22c55e; box-shadow: 0 0 8px #22c55e; animation: tech-pulse 1.8s ease-in-out infinite; }
.tech-footer .sep { color: #334155; }
.tech-footer .footer-right { margin-left: auto; color: #475569; }

/* ==================== 普通版（浅色） ==================== */
.dashboard:not(.tech-mode) .tech-stat {
  background: #ffffff;
  border: 1px solid #ebeef5;
  box-shadow: 0 2px 12px rgba(0, 0, 0, .04);
}
.dashboard:not(.tech-mode) .tech-stat::before {
  background: linear-gradient(90deg, var(--accent), transparent);
  opacity: .85;
}
.dashboard:not(.tech-mode) .tech-stat::after { display: none; }
.dashboard:not(.tech-mode) .tech-stat:hover {
  border-color: var(--accent);
  box-shadow: 0 8px 24px rgba(0, 0, 0, .08);
}
.dashboard:not(.tech-mode) .stat-tag { color: #909399; }
.dashboard:not(.tech-mode) .stat-icon {
  background: #f5f7fa;
  border-color: #ebeef5;
  box-shadow: none;
}
.dashboard:not(.tech-mode) .stat-value { color: #303133; text-shadow: none; }
.dashboard:not(.tech-mode) .stat-title { color: #909399; }
.dashboard:not(.tech-mode) .stat-title span { color: #c0c4cc; }
.dashboard:not(.tech-mode) .stat-line { opacity: .5; }
.dashboard:not(.tech-mode) .tech-panel {
  background: #ffffff;
  border: 1px solid #ebeef5;
  box-shadow: 0 2px 12px rgba(0, 0, 0, .04);
}
.dashboard:not(.tech-mode) .tech-panel::before,
.dashboard:not(.tech-mode) .tech-panel::after { display: none; }
.dashboard:not(.tech-mode) .panel-title { color: #303133; }
.dashboard:not(.tech-mode) .panel-title small { color: #c0c4cc; }
.dashboard:not(.tech-mode) .panel-live { color: #409eff; }
.dashboard:not(.tech-mode) .panel-live i { background: #409eff; box-shadow: 0 0 8px rgba(64, 158, 255, .55); }
.dashboard:not(.tech-mode) .tech-action {
  background: #ffffff;
  border-color: #ebeef5;
  color: #606266;
}
.dashboard:not(.tech-mode) .tech-action:hover {
  background: #f5f7fa;
  border-color: #c6e2ff;
  box-shadow: 0 8px 20px rgba(0, 0, 0, .06);
}
.dashboard:not(.tech-mode) .tech-action .el-icon { color: #409eff; }
.dashboard:not(.tech-mode) .tech-action b { color: #303133; }
.dashboard:not(.tech-mode) .tech-action small { color: #c0c4cc; }
.dashboard:not(.tech-mode) .tech-footer {
  border-top-color: #ebeef5;
  color: #909399;
}
.dashboard:not(.tech-mode) .tech-footer .sep { color: #dcdfe6; }
.dashboard:not(.tech-mode) .tech-footer .footer-right { color: #c0c4cc; }

@keyframes tech-scan {
  0% { transform: translateY(0); }
  100% { transform: translateY(460%); }
}
@keyframes tech-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: .35; }
}

/* 响应式 */
@media (max-width: 1280px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
  .charts-grid { grid-template-columns: 1fr; }
}
@media (max-width: 768px) {
  .stats-grid, .action-grid { grid-template-columns: 1fr; }
  .hud-live, .hud-clock, .hud-refresh, .style-label { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .dashboard.tech-mode::after, .hud-dot, .panel-live i, .tech-footer .live-dot { animation: none !important; }
}
</style>