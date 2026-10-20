<template>
  <div class="topo-wrap" ref="wrapRef">
    <div class="topo-toolbar">
      <div class="topo-title">🛰 智能资产拓扑</div>
      <div class="topo-stats">
        <span>应用 <b>{{ store.stats.appCount }}</b></span>
        <span>中间件 <b>{{ store.stats.middlewareCount }}</b></span>
        <span>服务器 <b>{{ store.stats.serverCount }}</b></span>
        <span class="abn">异常 <b>{{ store.stats.abnormalCount }}</b></span>
      </div>
      <div class="topo-tools">
        <el-radio-group v-model="view" size="small">
          <el-radio-button label="graph">图视图</el-radio-button>
          <el-radio-button label="table">表格视图</el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="layout" size="small" @change="render">
          <el-radio-button value="force">力导向</el-radio-button>
          <el-radio-button value="circular">环形</el-radio-button>
        </el-radio-group>
        <el-select v-model="filterCategory" size="small" style="width:110px" @change="render">
          <el-option label="全部类型" value="" />
          <el-option label="仅服务器" value="SERVER" />
          <el-option label="仅中间件" value="MIDDLEWARE" />
          <el-option label="仅应用" value="APP" />
        </el-select>
        <el-button size="small" :type="store.editMode ? 'warning' : 'default'" @click="toggleEdit">
          {{ store.editMode ? '退出编辑' : '编辑模式' }}
        </el-button>
        <el-button size="small" @click="exportJson">导出</el-button>
        <el-button size="small" @click="importVisible = true">导入</el-button>
        <el-button size="small" @click="store.loadGraph()">刷新</el-button>
        <el-button size="small" type="primary" @click="fullscreen">全屏</el-button>
      </div>
    </div>

    <el-alert v-if="store.editMode && view === 'graph'" type="warning" :closable="false" show-icon
              :title="hint" style="border-radius:0" />

    <!-- 图视图 -->
    <div v-show="view === 'graph'" class="topo-canvas">
      <v-chart :option="option" autoresize style="height:100%;width:100%" @click="onChartClick" />
    </div>
    <!-- 表格视图 -->
    <div v-show="view === 'table'" class="topo-table"><RelationTable /></div>
    <div v-if="emptyState && view === 'graph'" class="topo-empty">
      <el-empty description="暂无拓扑关系，点击右上角【编辑模式】连线，或用【表格视图】新增关系" />
    </div>

    <RelationDialog ref="dialogRef" v-model="dialogVisible" :preset="dialogPreset" />

    <!-- 导入 -->
    <el-dialog v-model="importVisible" title="导入关系(JSON)" width="520px" append-to-body>
      <el-input v-model="importText" type="textarea" :rows="10" placeholder="粘贴关系JSON数组：[{sourceType,sourceId,targetType,targetId,relationType,label}]" />
      <template #footer>
        <el-button @click="importVisible=false">取消</el-button>
        <el-button type="primary" @click="doImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTopologyStore } from '@/stores/topology'
import RelationDialog from '@/components/RelationDialog.vue'
import RelationTable from '@/components/RelationTable.vue'
import type { TopoEdge } from '@/types'

use([CanvasRenderer, GraphChart, TooltipComponent])

const store = useTopologyStore()
const route = useRoute()
const focusId = ref<string>('')
const wrapRef = ref<HTMLElement>()
const view = ref<'graph' | 'table'>('graph')
const layout = ref<'force' | 'circular'>('force')
const filterCategory = ref('')
const option = ref<any>({})
const emptyState = ref(false)

const dialogVisible = ref(false)
const dialogPreset = ref<any>(null)
const dialogRef = ref<any>()
const importVisible = ref(false)
const importText = ref('')

const hint = computed(() => store.connectFrom
  ? `已选源：${store.connectFrom.name} → 请点击目标节点完成连线`
  : '编辑模式：点击源节点再点击目标节点即可连线；点击连线可编辑/删除；点击自动(虚线)边可确认')

const COLOR: Record<string, string> = { SERVER: '#409eff', MIDDLEWARE: '#e6a23c', APP: '#67c23a' }
const SYMBOL: Record<string, string> = { SERVER: 'rect', MIDDLEWARE: 'circle', APP: 'circle' }
function isFocus(nodeId: string) { return !!focusId.value && nodeId.endsWith(':' + focusId.value) }

function render() {
  let nodes = store.nodes
  const cats = filterCategory.value ? [filterCategory.value] : ['SERVER', 'MIDDLEWARE', 'APP']
  nodes = nodes.filter(n => cats.includes(n.category))
  const keep = new Set(nodes.map(n => n.id))
  const edges = store.edges.filter(e => keep.has(e.source) && keep.has(e.target))
  emptyState.value = nodes.length === 0 || edges.length === 0

  const data = nodes.map(n => ({
    id: n.id, name: n.name, symbolSize: n.symbolSize || 40, category: n.category,
    symbol: SYMBOL[n.category] || 'circle',
    itemStyle: {
      color: COLOR[n.category],
      shadowBlur: (store.connectFrom?.id === n.id || isFocus(n.id)) ? 26 : 10,
      shadowColor: (store.connectFrom?.id === n.id || isFocus(n.id)) ? '#ffd04b' : COLOR[n.category],
      borderColor: store.connectFrom?.id === n.id ? '#f56c6c' : (isFocus(n.id) ? '#ffd04b' : 'transparent'),
      borderWidth: (store.connectFrom?.id === n.id || isFocus(n.id)) ? 3 : 0
    },
    label: { show: true, position: 'right', color: '#cfe3ff', fontSize: 12 },
    _detail: n.detail
  }))
  const links = edges.map(e => ({
    source: e.source, target: e.target,
    lineStyle: {
      color: e.origin === 'auto' ? '#5a6b82' : (e.lineStyle === 'gray' ? '#3a4a5a' : '#4facfe'),
      type: e.origin === 'auto' ? 'dashed' : (e.lineStyle === 'dashed' ? 'dashed' : 'solid'),
      curveness: 0.12, width: e.origin === 'auto' ? 1.2 : 2.2, opacity: e.origin === 'auto' ? 0.5 : 0.95
    },
    label: { show: true, formatter: e.label + (e.origin === 'auto' ? '' : ''), fontSize: 10, color: e.origin === 'auto' ? '#7f9cc4' : '#cfe3ff' },
    _edge: e
  }))
  option.value = {
    backgroundColor: 'transparent',
    tooltip: { formatter: (p: any) => p.data._detail ? `${p.name}<br/><pre style="margin:0;font-size:12px;white-space:pre-wrap">${p.data._detail}</pre>` : p.name },
    series: [{ type: 'graph', layout: layout.value, roam: true, draggable: true, data, links,
      top: 30, bottom: 30, left: 30, right: 30, edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 8,
      force: { repulsion: 420, edgeLength: 150, gravity: 0.08 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 3 } }, lineStyle: { opacity: .85 } }]
  }
}

function onChartClick(p: any) {
  if (p.dataType === 'node' && store.editMode) {
    const node = store.nodes.find(n => n.id === p.data.id)
    if (!node) return
    if (!store.connectFrom) { store.pickNode(node); render(); ElMessage.info('已选源节点，请点击目标节点'); return }
    if (store.connectFrom.id === node.id) { store.resetConnect(); render(); return }
    dialogPreset.value = { sourceKey: store.connectFrom.id, targetKey: node.id }
    store.resetConnect()
    dialogRef.value?.setEditing?.({ id: null })
    dialogVisible.value = true
    return
  }
  if (p.dataType === 'edge' && store.editMode) {
    const e: TopoEdge = p.data._edge
    if (!e) return
    onEdge(e)
  }
}

function onEdge(e: TopoEdge) {
  if (e.origin === 'auto') {
    ElMessageBox.confirm(`确认这条自动推导的「${e.label}」关系？确认后将转为实线持久保存`, '确认关系', { type: 'info', confirmButtonText: '确认' })
      .then(() => store.confirmEdge(e).then(() => { render(); ElMessage.success('已确认') })).catch(() => {})
    return
  }
  ElMessageBox.confirm(`对该「${e.label}」关系执行操作？`, '编辑关系',
    { confirmButtonText: '编辑', cancelButtonText: '删除', distinguishCancelAndClose: true, type: 'warning' })
    .then(() => {
      const rel = store.relations.find(r => r.id === e.relationId)
      if (rel) { dialogRef.value?.setEditing(rel); dialogVisible.value = true }
    })
    .then(() => render())
    .catch((action: any) => {
      if (action === 'cancel') {
        ElMessageBox.confirm('确定删除该关系？', '删除', { type: 'warning' })
          .then(() => store.deleteRelation(e.relationId!).then(() => { render(); ElMessage.success('已删除') })).catch(() => {})
      }
    })
}

function toggleEdit() { store.toggleEdit(); render() }

function exportJson() {
  const arr = store.relations.map(r => ({ sourceType: r.sourceType, sourceId: r.sourceId, targetType: r.targetType, targetId: r.targetId, relationType: r.relationType, label: r.label, description: r.description }))
  const blob = new Blob([JSON.stringify(arr, null, 2)], { type: 'application/json' })
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'topology-relations.json'; a.click()
}

async function doImport() {
  try {
    const arr = JSON.parse(importText.value)
    let ok = 0
    for (const it of arr) { await store.createRelation({ ...it, lineStyle: 'solid' }); ok++ }
    render(); importVisible.value = false; importText.value = ''
    ElMessage.success(`导入成功 ${ok} 条`)
  } catch (e: any) { ElMessage.error('JSON 解析失败：' + (e?.message || e)) }
}

function fullscreen() {
  const el = wrapRef.value as any
  if (!document.fullscreenElement) el?.requestFullscreen?.(); else document.exitFullscreen?.()
}

onMounted(async () => {
  if (route.query.focusId) focusId.value = String(route.query.focusId)
  await Promise.all([store.loadGraph(), store.loadRelations()])
  render()
  if (focusId.value) ElMessage({ message: '已高亮定位节点 ID=' + focusId.value, duration: 2500 })
  else ElMessage({ message: '提示：可开启【编辑模式】点击两个节点连线；或切到【表格视图】管理关系', duration: 3000 })
})
</script>

<style scoped>
.topo-wrap { height: calc(100vh - 40px); display: flex; flex-direction: column; background: radial-gradient(circle at 30% 20%, #12203a 0%, #0b1020 70%); border-radius: 8px; overflow: hidden; }
.topo-toolbar { display: flex; align-items: center; gap: 14px; padding: 12px 16px; flex-wrap: wrap; background: rgba(255,255,255,0.03); border-bottom: 1px solid rgba(255,255,255,0.08); }
.topo-title { color: #e6f0ff; font-weight: 700; font-size: 16px; }
.topo-stats { display: flex; gap: 14px; color: #9fb6d9; font-size: 13px; }
.topo-stats b { color: #4facfe; font-size: 15px; }
.topo-stats .abn b { color: #ff6b6b; }
.topo-tools { margin-left: auto; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.topo-canvas { flex: 1; }
.topo-table { flex: 1; overflow: auto; padding: 16px; }
.topo-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; pointer-events: none; }
</style>
