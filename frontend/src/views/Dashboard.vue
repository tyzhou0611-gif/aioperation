<template>
  <div class="dashboard">
    <h2>仪表盘</h2>

    <!-- 模式切换 Tabs -->
    <el-tabs v-model="activeTab" style="margin-bottom:16px">
      <el-tab-pane label="📊 内置图表" name="echarts" />
      <el-tab-pane label="📈 Grafana 看板" name="grafana" :disabled="!grafanaEnabled" />
    </el-tabs>

    <!-- ====== 模式一：ECharts 内置图表（默认） ====== -->
    <template v-if="activeTab === 'echarts'">
      <!-- 统计卡片 -->
      <el-row :gutter="16" class="stats-row">
        <el-col :span="6" v-for="stat in stats" :key="stat.title">
          <el-card shadow="hover">
            <div class="stat-item">
              <el-icon :size="40" :color="stat.color">
                <component :is="stat.icon" />
              </el-icon>
              <div>
                <div class="stat-value">{{ stat.value }}</div>
                <div class="stat-label">{{ stat.title }}</div>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- CPU / 内存趋势图 -->
      <el-row :gutter="16" class="chart-row">
        <el-col :span="16">
          <el-card>
            <template #header><span>CPU / 内存使用率趋势（过去24小时）</span></template>
            <v-chart :option="lineOption" style="height: 340px" autoresize />
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card>
            <template #header><span>服务器状态分布</span></template>
            <v-chart :option="pieOption" style="height: 340px" autoresize />
          </el-card>
        </el-col>
      </el-row>

      <!-- 快捷操作 -->
      <el-card class="action-card">
        <template #header><span>快捷操作</span></template>
        <div style="display: flex; gap: 12px; flex-wrap: wrap">
          <el-button type="primary" @click="router.push('/asset/server')">服务器管理</el-button>
          <el-button type="warning" @click="router.push('/alert/rule')">告警规则</el-button>
          <el-button type="danger" @click="router.push('/alert/history')">告警历史</el-button>
        </div>
      </el-card>
    </template>

    <!-- ====== 模式二：Grafana 看板 iframe ====== -->
    <template v-if="activeTab === 'grafana' && grafanaEnabled">
      <GrafanaDashboard :url="grafanaUrl" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import request from '@/utils/request'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { Monitor, DataBoard, Warning, CircleCheck } from '@element-plus/icons-vue'
import GrafanaDashboard from './GrafanaDashboard.vue'

use([CanvasRenderer, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const router = useRouter()
const userStore = useUserStore()

// Tab 切换 & Grafana 开关
const activeTab = ref('echarts')
const grafanaEnabled = import.meta.env.VITE_GRAFANA_ENABLED === 'true'
const grafanaUrl = (import.meta.env.VITE_GRAFANA_URL as string) || '/api/grafana'

const stats = ref([
  { title: '服务器总数', value: '-', icon: Monitor, color: '#409eff' },
  { title: '在线数量', value: '-', icon: CircleCheck, color: '#67c23a' },
  { title: '离线数量', value: '-', icon: Warning, color: '#f56c6c' },
  { title: '近7天告警', value: '-', icon: DataBoard, color: '#e6a23c' }
])

const lineOption = ref({})
const pieOption = ref({})

onMounted(async () => {
  try {
    const statsRes = await request.get('/api/dashboard/stats')
    if (statsRes.data) {
      stats.value = [
        { title: '服务器总数', value: statsRes.data.totalServers, icon: Monitor, color: '#409eff' },
        { title: '在线数量', value: statsRes.data.onlineCount, icon: CircleCheck, color: '#67c23a' },
        { title: '离线数量', value: statsRes.data.offlineCount, icon: Warning, color: '#f56c6c' },
        { title: '近7天告警', value: statsRes.data.alertCount7d, icon: DataBoard, color: '#e6a23c' }
      ]
    }

    const trendRes = await request.get('/api/dashboard/trend?hours=24')
    if (trendRes.data) {
      lineOption.value = {
        tooltip: { trigger: 'axis' },
        legend: { data: ['CPU使用率', '内存使用率'], bottom: 0 },
        grid: { left: 40, right: 20, bottom: 50, top: 20 },
        xAxis: {
          type: 'category',
          data: trendRes.data.times,
          boundaryGap: false,
          axisLabel: { rotate: 45, fontSize: 11 }
        },
        yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
        series: [
          {
            name: 'CPU使用率', type: 'line', smooth: true,
            data: trendRes.data.cpuUsage, areaStyle: { opacity: 0.15 },
            itemStyle: { color: '#409eff' },
            markLine: { data: [{ yAxis: 90, label: { formatter: '告警线 90%' } }] }
          },
          {
            name: '内存使用率', type: 'line', smooth: true,
            data: trendRes.data.memUsage, areaStyle: { opacity: 0.15 },
            itemStyle: { color: '#67c23a' },
            markLine: { data: [{ yAxis: 90, label: { formatter: '告警线 90%' } }] }
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
        tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
        legend: { bottom: 0 },
        series: [{
          type: 'pie', radius: ['40%', '70%'], center: ['50%', '40%'],
          data: [
            { value: online, name: '在线', itemStyle: { color: '#67c23a' } },
            { value: offline, name: '离线', itemStyle: { color: '#f56c6c' } },
            { value: maintenance, name: '维护', itemStyle: { color: '#e6a23c' } }
          ],
          label: { show: true, formatter: '{b}\n{d}%' }
        }]
      }
    }
  } catch (e) { /* silent */ }
})
</script>

<style scoped lang="scss">
.dashboard h2 { margin: 0 0 8px; font-size: 22px; color: #333; }
.stats-row { margin-bottom: 20px; }
.stat-item { display: flex; align-items: center; gap: 16px; }
.stat-value { font-size: 28px; font-weight: 700; color: #333; line-height: 1.2; }
.stat-label { font-size: 13px; color: #999; }
.chart-row { margin-bottom: 20px; }
.action-card { margin-bottom: 20px; }
</style>