<template>
  <div class="dashboard">
    <div class="page-heading">
      <div>
        <div class="eyebrow">OVERVIEW / REAL-TIME STATUS</div>
        <h2>运行总览</h2>
        <p>集中掌握基础设施运行状态与平台健康情况</p>
      </div>
      <div class="heading-right">
        <span class="live-indicator"><i></i> LIVE MONITORING</span>
        <span class="update-time">最近更新 {{ currentTime }}</span>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="dashboard-tabs">
      <el-tab-pane label="运行态势" name="echarts" />
      <el-tab-pane label="Grafana 看板" name="grafana" :disabled="!grafanaEnabled" />
    </el-tabs>

    <template v-if="activeTab === 'echarts'">
      <div class="section-heading">
        <span>核心指标</span>
        <small>CORE METRICS</small>
      </div>
      <el-row :gutter="14" class="stats-row">
        <el-col :xs="24" :sm="12" :lg="6" v-for="(stat, index) in stats" :key="stat.title">
          <el-card shadow="never" class="metric-card" :class="'metric-' + index">
            <div class="metric-topline">
              <span class="metric-label">{{ stat.title }}</span>
              <span class="metric-icon"><el-icon><component :is="stat.icon" /></el-icon></span>
            </div>
            <div class="metric-value">{{ stat.value }}</div>
            <div class="metric-foot">
              <span class="metric-foot-dot"></span>
              <span>{{ stat.footnote }}</span>
            </div>
            <div class="metric-decoration"></div>
          </el-card>
        </el-col>
      </el-row>

      <div class="section-heading chart-section-heading">
        <span>资源运行趋势</span>
        <small>RESOURCE TELEMETRY · LAST 24 HOURS</small>
      </div>
      <el-row :gutter="14" class="chart-row">
        <el-col :xs="24" :xl="16">
          <el-card shadow="never" class="chart-card">
            <template #header>
              <div class="card-heading">
                <div><strong>CPU / 内存使用率</strong><small>主机资源利用率变化趋势</small></div>
                <span class="chart-unit">UTILIZATION / %</span>
              </div>
            </template>
            <v-chart :option="lineOption" style="height: 310px; width: 100%" autoresize />
          </el-card>
        </el-col>
        <el-col :xs="24" :xl="8">
          <el-card shadow="never" class="chart-card">
            <template #header>
              <div class="card-heading">
                <div><strong>服务器状态分布</strong><small>当前纳管主机健康概况</small></div>
                <span class="chart-unit">HOST STATUS</span>
              </div>
            </template>
            <v-chart :option="pieOption" style="height: 310px; width: 100%" autoresize />
          </el-card>
        </el-col>
      </el-row>

      <div class="section-heading quick-heading">
        <span>常用运维入口</span>
        <small>QUICK ACCESS</small>
      </div>
      <div class="quick-access">
        <button class="quick-link" @click="router.push('/asset/server')">
          <span class="quick-icon blue"><el-icon><Monitor /></el-icon></span>
          <span><strong>服务器管理</strong><small>资产清单与主机状态</small></span>
          <el-icon class="quick-arrow"><ArrowRight /></el-icon>
        </button>
        <button class="quick-link" @click="router.push('/alert/rule')">
          <span class="quick-icon amber"><el-icon><Warning /></el-icon></span>
          <span><strong>告警规则</strong><small>阈值配置与通知策略</small></span>
          <el-icon class="quick-arrow"><ArrowRight /></el-icon>
        </button>
        <button class="quick-link" @click="router.push('/alert/history')">
          <span class="quick-icon red"><el-icon><Bell /></el-icon></span>
          <span><strong>告警历史</strong><small>事件追踪与处理记录</small></span>
          <el-icon class="quick-arrow"><ArrowRight /></el-icon>
        </button>
      </div>
    </template>

    <template v-if="activeTab === 'grafana' && grafanaEnabled">
      <GrafanaDashboard :url="grafanaUrl" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/utils/request'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { Monitor, DataBoard, Warning, CircleCheck, Bell, ArrowRight } from '@element-plus/icons-vue'
import GrafanaDashboard from './GrafanaDashboard.vue'

use([CanvasRenderer, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const router = useRouter()
const activeTab = ref('echarts')
const grafanaEnabled = import.meta.env.VITE_GRAFANA_ENABLED === 'true'
const grafanaUrl = (import.meta.env.VITE_GRAFANA_URL as string) || '/api/grafana'
const currentTime = ref(new Date().toLocaleTimeString('zh-CN', { hour12: false }))
let clockTimer: ReturnType<typeof setInterval> | undefined

const stats = ref([
  { title: '服务器总数', value: '-', icon: Monitor, footnote: '当前纳管资产' },
  { title: '在线数量', value: '-', icon: CircleCheck, footnote: '运行状态正常' },
  { title: '离线数量', value: '-', icon: Warning, footnote: '需要关注的主机' },
  { title: '近 7 天告警', value: '-', icon: DataBoard, footnote: '近七日累计事件' }
])
const lineOption = ref<Record<string, any>>({})
const pieOption = ref<Record<string, any>>({})

onMounted(async () => {
  clockTimer = setInterval(() => {
    currentTime.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  }, 1000)

  try {
    const statsRes = await request.get('/api/dashboard/stats')
    if (statsRes.data) {
      stats.value = [
        { title: '服务器总数', value: statsRes.data.totalServers, icon: Monitor, footnote: '当前纳管资产' },
        { title: '在线数量', value: statsRes.data.onlineCount, icon: CircleCheck, footnote: '运行状态正常' },
        { title: '离线数量', value: statsRes.data.offlineCount, icon: Warning, footnote: '需要关注的主机' },
        { title: '近 7 天告警', value: statsRes.data.alertCount7d, icon: DataBoard, footnote: '近七日累计事件' }
      ]
    }

    const trendRes = await request.get('/api/dashboard/trend?hours=24')
    if (trendRes.data) {
      lineOption.value = {
        backgroundColor: 'transparent',
        color: ['#48c5ed', '#8b9cff'],
        tooltip: {
          trigger: 'axis',
          backgroundColor: '#17283d',
          borderColor: 'rgba(119,177,211,.25)',
          textStyle: { color: '#e5f0fa' },
          axisPointer: { lineStyle: { color: 'rgba(91,194,231,.35)' } }
        },
        legend: { data: ['CPU使用率', '内存使用率'], bottom: 0, textStyle: { color: '#91a7bd', fontSize: 11 }, itemWidth: 14, itemHeight: 7 },
        grid: { left: 42, right: 18, bottom: 48, top: 18 },
        xAxis: {
          type: 'category', data: trendRes.data.times, boundaryGap: false,
          axisLine: { lineStyle: { color: 'rgba(143,169,194,.2)' } },
          axisTick: { show: false },
          axisLabel: { color: '#8198af', rotate: 35, fontSize: 10 }
        },
        yAxis: {
          type: 'value', min: 0, max: 100,
          axisLabel: { color: '#8198af', formatter: '{value}%', fontSize: 10 },
          splitLine: { lineStyle: { color: 'rgba(143,169,194,.10)', type: 'dashed' } }
        },
        series: [
          {
            name: 'CPU使用率', type: 'line', smooth: true, showSymbol: false,
            data: trendRes.data.cpuUsage, areaStyle: { opacity: 0.10 },
            lineStyle: { width: 2.5 }, itemStyle: { color: '#48c5ed' },
            markLine: { symbol: 'none', lineStyle: { color: '#e9a85a', type: 'dashed' }, label: { color: '#e9b76f', formatter: '告警线 90%' }, data: [{ yAxis: 90 }] }
          },
          {
            name: '内存使用率', type: 'line', smooth: true, showSymbol: false,
            data: trendRes.data.memUsage, areaStyle: { opacity: 0.07 },
            lineStyle: { width: 2.5 }, itemStyle: { color: '#8b9cff' },
            markLine: { symbol: 'none', lineStyle: { color: '#e9a85a', type: 'dashed' }, label: { color: '#e9b76f', formatter: '告警线 90%' }, data: [{ yAxis: 90 }] }
          }
        ]
      }
    }

    const serverRes = await request.get('/api/server/page?pageSize=100')
    if (serverRes.data?.records) {
      const servers = serverRes.data.records
      const online = servers.filter((s: any) => s.status === 'ONLINE').length
      const offline = servers.filter((s: any) => s.status === 'OFFLINE').length
      const maintenance = servers.filter((s: any) => s.status === 'MAINTENANCE').length
      pieOption.value = {
        backgroundColor: 'transparent',
        color: ['#35c99a', '#f07882', '#eab45f'],
        tooltip: { trigger: 'item', backgroundColor: '#17283d', borderColor: 'rgba(119,177,211,.25)', textStyle: { color: '#e5f0fa' }, formatter: '{b}: {c} ({d}%)' },
        legend: { bottom: 2, textStyle: { color: '#91a7bd', fontSize: 11 }, itemWidth: 9, itemHeight: 9 },
        series: [{
          type: 'pie', radius: ['48%', '70%'], center: ['50%', '43%'],
          itemStyle: { borderColor: '#142338', borderWidth: 3 },
          data: [
            { value: online, name: '在线' },
            { value: offline, name: '离线' },
            { value: maintenance, name: '维护' }
          ],
          label: { color: '#b9c9d9', fontSize: 11, formatter: '{b}\n{d}%' },
          labelLine: { lineStyle: { color: 'rgba(143,169,194,.35)' } }
        }]
      }
    }
  } catch (e) {
    // 数据接口与原有实现保持一致；接口异常时保留页面结构
  }
})

onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<style scoped lang="scss">
.page-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 20px; margin-bottom: 21px; }
.eyebrow { color: #5e9fbd; font-size: 9px; font-weight: 700; letter-spacing: 1.5px; margin-bottom: 8px; }
.page-heading h2 { margin: 0; color: #edf6ff; font-size: 23px; font-weight: 650; }
.page-heading p { margin: 7px 0 0; color: #8197ad; font-size: 12px; }
.heading-right { display: flex; flex-direction: column; align-items: flex-end; gap: 7px; }
.live-indicator { display: flex; align-items: center; gap: 7px; color: #63d3b0; font-size: 9px; font-weight: 700; letter-spacing: 1px; }
.live-indicator i { width: 6px; height: 6px; border-radius: 50%; background: #35c99a; box-shadow: 0 0 9px rgba(53,201,154,.65); }
.update-time { color: #6f849a; font-size: 10px; font-variant-numeric: tabular-nums; }
.dashboard-tabs { margin-bottom: 19px; }
.section-heading { display: flex; align-items: center; gap: 10px; margin: 17px 0 12px; color: #dce8f4; font-size: 13px; font-weight: 650; }
.section-heading small { color: #607991; font-size: 9px; font-weight: 600; letter-spacing: 1px; }
.stats-row { margin-bottom: 8px; }
.metric-card { position: relative; overflow: hidden; min-height: 132px; }
.metric-card :deep(.el-card__body) { padding: 17px 18px; position: relative; z-index: 1; }
.metric-topline { display: flex; justify-content: space-between; align-items: center; }
.metric-label { color: #91a7bd; font-size: 11px; }
.metric-icon { width: 31px; height: 31px; display: grid; place-items: center; border-radius: 9px; color: #68ccef; background: rgba(66,185,232,.12); font-size: 16px; }
.metric-1 .metric-icon { color: #53d5ad; background: rgba(53,201,154,.12); }
.metric-2 .metric-icon { color: #f08b93; background: rgba(240,120,130,.12); }
.metric-3 .metric-icon { color: #edbd70; background: rgba(240,180,93,.12); }
.metric-value { margin-top: 9px; color: #f0f7ff; font-size: 29px; line-height: 1.1; font-weight: 700; font-variant-numeric: tabular-nums; }
.metric-foot { display: flex; align-items: center; gap: 7px; margin-top: 9px; color: #71879d; font-size: 10px; }
.metric-foot-dot { width: 4px; height: 4px; border-radius: 50%; background: #42b9e8; }
.metric-1 .metric-foot-dot { background: #35c99a; }
.metric-2 .metric-foot-dot { background: #f07882; }
.metric-3 .metric-foot-dot { background: #f0b45d; }
.metric-decoration { position: absolute; right: -24px; bottom: -43px; width: 120px; height: 120px; border: 1px solid rgba(80,177,216,.08); border-radius: 50%; box-shadow: 0 0 0 15px rgba(80,177,216,.025), 0 0 0 31px rgba(80,177,216,.018); }
.chart-section-heading { margin-top: 23px; }
.chart-row { margin-bottom: 8px; }
.chart-card { height: 100%; }
.chart-card :deep(.el-card__header) { padding: 14px 18px; }
.chart-card :deep(.el-card__body) { padding: 10px 12px 7px; }
.card-heading { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.card-heading > div { display: flex; flex-direction: column; gap: 4px; }
.card-heading strong { color: #dce8f4; font-size: 12px; font-weight: 650; }
.card-heading small { color: #748ba2; font-size: 10px; font-weight: 400; }
.chart-unit { color: #5d7892; font-size: 8px; letter-spacing: 1px; }
.quick-heading { margin-top: 22px; }
.quick-access { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.quick-link { display: flex; align-items: center; gap: 12px; min-width: 0; padding: 14px; text-align: left; color: inherit; cursor: pointer; background: rgba(20,35,54,.8); border: 1px solid rgba(142,174,204,.14); border-radius: 10px; transition: border-color .2s, transform .2s, background .2s; }
.quick-link:hover { transform: translateY(-2px); border-color: rgba(66,185,232,.42); background: rgba(28,49,72,.95); }
.quick-icon { width: 34px; height: 34px; display: grid; place-items: center; flex: 0 0 34px; border-radius: 9px; font-size: 16px; }
.quick-icon.blue { color: #66c9eb; background: rgba(66,185,232,.12); }
.quick-icon.amber { color: #eab76a; background: rgba(240,180,93,.12); }
.quick-icon.red { color: #ed8a93; background: rgba(240,120,130,.12); }
.quick-link > span:nth-child(2) { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.quick-link strong { color: #dce8f4; font-size: 11px; font-weight: 650; }
.quick-link small { color: #788ea5; font-size: 9px; }
.quick-arrow { margin-left: auto; color: #5c7892; font-size: 13px; }
@media (max-width: 900px) { .quick-access { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .page-heading { align-items: flex-start; flex-direction: column; } .heading-right { align-items: flex-start; } .section-heading small { display: none; } }
</style>