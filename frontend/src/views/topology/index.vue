<template>
  <div class="topo-wrap" ref="wrapRef">
    <div class="topo-grid-bg"></div>
    <div class="topo-scanline"></div>

    <div class="topo-toolbar">
      <div class="brand">
        <div class="brand-mark"><span></span></div>
        <div>
          <div class="topo-title">智能资产拓扑</div>
          <div class="topo-sub">ASSET TOPOLOGY · {{ selectedProjectName || 'ALL PROJECTS' }}</div>
        </div>
      </div>

      <div class="topo-stats">
        <div class="stat"><span>应用</span><b>{{ store.stats.appCount }}</b></div>
        <div class="stat"><span>中间件</span><b>{{ store.stats.middlewareCount }}</b></div>
        <div class="stat"><span>服务器</span><b>{{ store.stats.serverCount }}</b></div>
        <div class="stat abn"><span>异常</span><b>{{ store.stats.abnormalCount }}</b></div>
      </div>

      <div class="topo-tools">
        <el-select v-model="selectedProjectId" size="small" clearable placeholder="全部项目"
                   style="width:150px" @change="onProjectChange">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-radio-group v-model="view" size="small">
          <el-radio-button label="graph">图视图</el-radio-button>
          <el-radio-button label="table">表格</el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="layout" size="small" @change="onLayoutChange">
          <el-radio-button value="layered">分层</el-radio-button>
          <el-radio-button value="force">力导向</el-radio-button>
          <el-radio-button value="circular">环形</el-radio-button>
        </el-radio-group>
        <el-button size="small" @click="resetLayout">重排</el-button>
        <el-popover placement="bottom-end" :width="288" trigger="click" popper-class="topo-node-pop">
          <template #reference>
            <el-button size="small">节点样式</el-button>
          </template>
          <div class="ns-panel">
            <div class="ns-title">节点外观 <span>大小与形状比例可自由调整</span></div>
            <div class="ns-row">
              <label>节点大小</label>
              <el-slider v-model="nodeSize" :min="36" :max="120" :step="2" size="small"
                         :show-tooltip="false" @change="onNodeStyleChange" />
              <b>{{ nodeSize }}px</b>
            </div>
            <div class="ns-row">
              <label>形状比例</label>
              <el-slider v-model="nodeAspect" :min="0.7" :max="1.6" :step="0.05" size="small"
                         :show-tooltip="false" @change="onNodeStyleChange" />
              <b>{{ nodeAspect.toFixed(2) }}×</b>
            </div>
            <div class="ns-hint">
              <span :class="isRegularHex ? 'ok' : 'warn'">
                {{ isRegularHex ? '正六边形（推荐）' : '已自定义拉伸，不再是正六边形' }}
              </span>
              <el-button link type="primary" size="small" @click="resetNodeStyle">恢复默认</el-button>
            </div>
            <div class="ns-tip">分层布局下空间不足时会自动缩小，避免节点重叠。</div>
          </div>
        </el-popover>
        <el-tag v-if="layout === 'layered'" size="small" type="info" effect="plain">应用 ↑ · 中间件 · 服务器 ↓</el-tag>
        <el-tag v-if="layoutFallback" size="small" type="warning" effect="plain">节点较多 · 建议分层/环形布局</el-tag>
        <el-select v-model="filterCategory" size="small" style="width:110px" @change="onCategoryChange">
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
        <el-button size="small" @click="refreshGraph" :loading="store.loading">刷新</el-button>
        <el-button size="small" type="primary" @click="fullscreen">全屏</el-button>
      </div>
    </div>

    <!-- 图视图 -->
    <div v-show="view === 'graph'" class="topo-canvas" :class="{ editing: store.editMode }">
      <v-chart ref="chartRef" :option="option" autoresize class="topo-chart" @click="onChartClick" />

      <!-- 连线模式 HUD -->
      <div v-if="store.editMode" class="connect-hud">
        <span class="hud-badge">连线模式</span>
        <template v-if="store.connectFrom">
          <span class="hud-text">源节点 <b>{{ store.connectFrom.name }}</b> → 点击目标节点完成连线</span>
          <el-button link type="warning" size="small" @click="cancelConnect">取消</el-button>
        </template>
        <template v-else>
          <span class="hud-text">拖拽节点到目标节点即可连线，或点击源节点再点击目标</span>
        </template>
        <span class="hud-tip">Esc 取消 · 点击连线编辑/删除</span>
      </div>

      <!-- 拖拽连线预览 -->
      <svg v-if="drag.active" class="connect-layer">
        <defs>
          <marker id="connect-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" fill="#38bdf8" />
          </marker>
        </defs>
        <line :x1="drag.x1" :y1="drag.y1" :x2="drag.x2" :y2="drag.y2"
              :stroke="drag.hoverId ? '#22e6a4' : '#38bdf8'" stroke-width="2"
              stroke-dasharray="9 7" marker-end="url(#connect-arrow)" class="connect-line" />
        <circle :cx="drag.x1" :cy="drag.y1" r="6" fill="#38bdf8" opacity=".9" />
        <circle :cx="drag.x2" :cy="drag.y2" :r="drag.hoverId ? 8 : 4"
                :fill="drag.hoverId ? '#22e6a4' : '#38bdf8'" opacity=".85" />
      </svg>
      <div v-if="drag.active" class="drag-tip" :style="{ left: drag.x2 + 16 + 'px', top: drag.y2 + 14 + 'px' }">
        {{ drag.hoverId ? ('松开连接到 ' + drag.hoverName) : '拖到目标节点完成连线' }}
      </div>

      <!-- 连线操作菜单 -->
      <div v-if="edgeMenu.visible" class="edge-menu" :style="{ left: edgeMenu.x + 'px', top: edgeMenu.y + 'px' }"
           @click.stop @mousedown.stop>
        <div class="em-title">{{ edgeMenu.edge?.label || '关系' }}</div>
        <div class="em-sub">{{ edgeMenu.edge?.origin === 'auto' ? '自动推导（虚线）' : '手动配置（实线）' }}</div>
        <div class="em-item" @click="menuConfirmOrEdit">
          {{ edgeMenu.edge?.origin === 'auto' ? '确认转为手动关系' : '编辑关系' }}
        </div>
        <div v-if="edgeMenu.edge?.origin !== 'auto'" class="em-item danger" @click="menuDelete">删除关系</div>
        <div class="em-item muted" @click="closeEdgeMenu">取消</div>
      </div>

      <div class="corner tl"></div><div class="corner tr"></div>
      <div class="corner bl"></div><div class="corner br"></div>

      <div class="topo-legend">
        <div class="leg-title">NODE TYPES</div>
        <div class="leg-row"><span class="legend-ico server"></span>服务器</div>
        <div class="leg-row"><span class="legend-ico app"></span>应用</div>
        <div class="leg-row"><span class="legend-ico mw"></span>中间件 / 数据库</div>
        <div class="leg-sep"></div>
        <div class="leg-row small"><span class="dot online"></span>在线
          <span class="dot offline"></span>离线
          <span class="dot warn"></span>维护
        </div>
      </div>

      <transition name="panel-fade">
        <div v-if="selectedNode" class="node-panel">
          <div class="np-head">
            <div class="np-title">
              <span class="np-name">{{ selectedNode.name }}</span>
              <span class="np-sub">
                {{ categoryLabel(selectedNode) }}<template v-if="selectedNode.subType"> · {{ selectedNode.subType }}</template>
                <template v-if="nodeIp"> · {{ nodeIp }}<template v-if="selectedNode.port">:{{ selectedNode.port }}</template></template>
              </span>
            </div>
            <el-icon class="np-close" @click="closePanel"><Close /></el-icon>
          </div>
          <div class="np-tags">
            <el-tag effect="dark" size="small" :style="{ background: categoryColor(selectedNode), borderColor: categoryColor(selectedNode) }">
              {{ categoryLabel(selectedNode) }}
            </el-tag>
            <el-tag v-if="selectedNode.subType" size="small" effect="plain">{{ selectedNode.subType }}</el-tag>
            <el-tag size="small" effect="plain" :style="{ color: statusColor(selectedNode.status), borderColor: statusColor(selectedNode.status) }">
              {{ selectedNode.status || 'UNKNOWN' }}
            </el-tag>
            <EnvTag v-if="selectedNode.env" :env="selectedNode.env" effect="plain" />
          </div>

          <el-tabs v-model="panelTab" class="np-tabs">
            <el-tab-pane label="快捷操作" name="actions">
              <div class="np-actions">
                <button v-for="a in nodeActions" :key="a.key" class="np-action" :class="a.tone" @click="a.run()">
                  <el-icon class="na-ico"><component :is="a.icon" /></el-icon>
                  <span class="na-text">
                    <b>{{ a.label }}</b>
                    <small>{{ a.desc }}</small>
                  </span>
                  <el-icon class="na-arrow"><ArrowRight /></el-icon>
                </button>
              </div>
              <div v-if="!nodeActions.length" class="np-empty">当前账号对该资产没有可用的操作入口</div>
            </el-tab-pane>

            <el-tab-pane label="概览" name="overview">
              <div class="np-kv">
                <div v-for="(row, i) in nodeMetaRows" :key="i" class="np-kv-row">
                  <span class="k">{{ row.k }}</span>
                  <span class="v" :class="{ mono: row.mono }">{{ row.v }}</span>
                </div>
              </div>
              <div v-if="nodeIp" class="np-copy">
                <span class="k">IP</span>
                <code>{{ nodeIp }}</code>
                <el-button link type="primary" size="small" @click="copyText(nodeIp)">
                  <el-icon><CopyDocument /></el-icon>复制
                </el-button>
              </div>
            </el-tab-pane>

            <el-tab-pane :label="`关联链路 ${nodeRelations.length}`" name="relations">
              <div v-if="nodeRelations.length" class="np-rel-list">
                <div v-for="r in nodeRelations" :key="r.key" class="np-rel" @click="focusNode(r.otherId)">
                  <span class="rel-dir">{{ r.dir }}</span>
                  <span class="rel-name">{{ r.otherName }}</span>
                  <el-tag size="small" effect="plain" :type="r.origin === 'auto' ? 'info' : 'primary'">{{ r.label }}</el-tag>
                </div>
              </div>
              <div v-else class="np-empty">该资产暂未配置关联链路</div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </transition>

      <div v-if="emptyState" class="topo-empty">
        <el-empty :description="selectedProjectId
          ? '当前项目暂未关联资产，请到【资产管理 → 项目管理】分配机器/中间件/应用'
          : (filterCategory ? '当前筛选类型下暂无节点' : '暂无资产节点：请先在资产管理中添加服务器/中间件/应用')" />
      </div>
    </div>

    <!-- 表格视图 -->
    <div v-show="view === 'table'" class="topo-table"><RelationTable /></div>

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
import { ref, computed, onMounted, onUnmounted, reactive, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import {
  Close, ArrowRight, CopyDocument, Monitor, Promotion, MagicStick, Odometer, Position, Link, Document
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTopologyStore } from '@/stores/topology'
import { useUserStore } from '@/stores/user'
import request from '@/utils/request'
import RelationDialog from '@/components/RelationDialog.vue'
import RelationTable from '@/components/RelationTable.vue'
import EnvTag from '@/components/EnvTag.vue'
import type { TopoEdge, TopoNode, Project } from '@/types'

use([CanvasRenderer, GraphChart, TooltipComponent])

const store = useTopologyStore()
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const focusId = ref<string>('')
const wrapRef = ref<HTMLElement>()
const chartRef = ref<any>()
const view = ref<'graph' | 'table'>('graph')
const layout = ref<'layered' | 'force' | 'circular'>('layered')
const filterCategory = ref('')
const option = ref<any>({})
const emptyState = ref(false)
const projects = ref<Project[]>([])
const selectedProjectId = ref<number | null>(null)
const selectedNode = ref<TopoNode | null>(null)
const selectedProjectName = computed(() =>
  projects.value.find(p => p.id === selectedProjectId.value)?.name || '')
const panelTab = ref<'actions' | 'overview' | 'relations'>('actions')

/* ===== 节点详情面板：快捷操作 / 概览 / 关联链路 ===== */
interface NodeAction {
  key: string
  page: string        // 用于按菜单权限判断可见性
  label: string
  desc: string
  icon: any
  tone?: string
  run: () => void
}

/** 当前账号可访问的页面路径（后端下发的菜单树） */
const accessiblePaths = computed(() => {
  const set = new Set<string>()
  const walk = (list: any[]) => {
    for (const m of list || []) {
      if (m?.path) set.add(String(m.path))
      if (m?.children?.length) walk(m.children)
    }
  }
  walk(userStore.menus as any[])
  return set
})
function canVisit(path: string) {
  return userStore.isSuperAdmin || accessiblePaths.value.has(path)
}

function nodeNumericId(id: string) {
  const i = id.indexOf(':')
  return i >= 0 ? id.slice(i + 1) : id
}

/** 后端新增字段缺失时，从 detail 文本里兜底解析 IP（兼容旧服务端） */
const nodeIp = computed(() => {
  const n = selectedNode.value
  if (!n) return ''
  if (n.ip) return String(n.ip)
  const m = /(?:IP|地址)[:：]\s*([0-9a-zA-Z.\-]+)/.exec(n.detail || '')
  return m ? m[1] : ''
})

const nodeMetaRows = computed(() => {
  const n = selectedNode.value
  if (!n) return [] as Array<{ k: string; v: string; mono?: boolean }>
  const rows: Array<{ k: string; v: string; mono?: boolean }> = []
  for (const line of (n.detail || '').split('\n')) {
    const t = line.trim()
    if (!t) continue
    const i = t.indexOf(':')
    if (i > 0) rows.push({ k: t.slice(0, i).trim(), v: t.slice(i + 1).trim() })
    else rows.push({ k: '', v: t })
  }
  if (n.ip && !rows.some(r => r.k === 'IP')) rows.unshift({ k: 'IP', v: String(n.ip) })
  return rows
})

const nodeRelations = computed(() => {
  const n = selectedNode.value
  if (!n) return [] as any[]
  const out: any[] = []
  for (const e of store.edges) {
    const outgoing = e.source === n.id
    if (!outgoing && e.target !== n.id) continue
    const otherId = outgoing ? e.target : e.source
    const other = store.nodes.find(x => x.id === otherId)
    out.push({
      key: otherId + '|' + e.label + '|' + (e.origin || ''),
      dir: outgoing ? '→' : '←',
      otherId,
      otherName: other?.name || otherId,
      label: e.label,
      origin: e.origin || 'manual'
    })
  }
  return out
})

function focusNode(id: string) {
  const n = store.nodes.find(x => x.id === id)
  if (!n) return
  selectedNode.value = n
  render()
}

const nodeActions = computed<NodeAction[]>(() => {
  const n = selectedNode.value
  if (!n) return []
  const id = nodeNumericId(n.id)
  const ip = nodeIp.value
  // 监控实例：优先宿主服务器 IP，其次资产自身 IP（服务器/中间件都能按主机抓取指标）
  const hostIp = n.serverIp || ip
  const hostServerId = n.serverId ? String(n.serverId) : (n.category === 'SERVER' ? id : '')
  const list: NodeAction[] = []
  const monitor = () => {
    if (!hostIp || !canVisit('/dashboard')) return
    list.push({
      key: 'monitor', page: '/dashboard', label: '监控面板', tone: 'cyan',
      desc: `查看 ${hostIp} 的 CPU / 内存 / 磁盘 / 网络`,
      icon: Odometer,
      run: () => router.push({ path: '/dashboard', query: { tab: 'panels', instance: hostIp } })
    })
  }
  const ssh = () => {
    if (!hostServerId || !canVisit('/exec/ssh')) return
    list.push({
      key: 'ssh', page: '/exec/ssh', label: 'Web 终端', tone: 'green',
      desc: '浏览器内 SSH 登录（免输密码）',
      icon: Monitor,
      run: () => router.push({ path: '/exec/ssh', query: { serverId: hostServerId } })
    })
  }
  const diagnose = () => {
    if (!canVisit('/diagnosis')) return
    const type = n.category === 'SERVER' ? 'SERVER' : n.category === 'MIDDLEWARE' ? 'MIDDLEWARE' : 'APP'
    list.push({
      key: 'diag', page: '/diagnosis', label: 'AI 诊断', tone: 'purple',
      desc: '汇总指标与日志，生成排查建议',
      icon: MagicStick,
      run: () => router.push({ path: '/diagnosis', query: { targetType: type, targetId: id } })
    })
  }

  if (n.category === 'SERVER') {
    monitor()
    ssh()
    if (canVisit('/exec/task')) {
      list.push({
        key: 'exec', page: '/exec/task', label: '命令执行', tone: 'amber',
        desc: '对该主机批量下发命令 / 脚本',
        icon: Promotion,
        run: () => router.push({ path: '/exec/task', query: { serverId: id } })
      })
    }
    diagnose()
    if (canVisit('/asset/server')) {
      list.push({
        key: 'asset', page: '/asset/server', label: '服务器资产', tone: 'slate',
        desc: '查看 / 编辑资产信息与 SSH 凭据',
        icon: Document,
        run: () => router.push({ path: '/asset/server', query: { id } })
      })
    }
  } else if (n.category === 'MIDDLEWARE') {
    if (canVisit('/asset/middleware')) {
      list.push({
        key: 'detail', page: '/asset/middleware', label: '中间件详情', tone: 'purple',
        desc: '节点状态 / 集群信息 / 一键探测',
        icon: Link,
        run: () => router.push({ path: `/asset/middleware/${id}` })
      })
    }
    monitor()
    ssh()
    diagnose()
  } else {
    if (canVisit('/asset/app')) {
      list.push({
        key: 'app', page: '/asset/app', label: '应用详情', tone: 'green',
        desc: '查看应用信息、依赖与部署主机',
        icon: Position,
        run: () => router.push({ path: '/asset/app', query: { id } })
      })
    }
    monitor()
    ssh()
    diagnose()
  }
  return list
})

async function copyText(text: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制：' + text)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try { document.execCommand('copy'); ElMessage.success('已复制：' + text) } catch { ElMessage.warning('复制失败，请手动选择') }
    ta.remove()
  }
}

const dialogVisible = ref(false)
const dialogPreset = ref<any>(null)
const dialogRef = ref<any>()
const importVisible = ref(false)
const importText = ref('')

/* ===== 节点样式：大小 + 形状比例（比例 1.00 = 正六边形） ===== */
const NODE_STYLE_KEY = 'topo_node_style'
const DEFAULT_NODE_SIZE = 66
const nodeSize = ref(DEFAULT_NODE_SIZE)
const nodeAspect = ref(1)
const isRegularHex = computed(() => Math.abs(nodeAspect.value - 1) < 0.001)
function loadNodeStyle() {
  try {
    const s = JSON.parse(localStorage.getItem(NODE_STYLE_KEY) || '{}')
    const size = Number(s.size)
    const aspect = Number(s.aspect)
    if (size >= 36 && size <= 120) nodeSize.value = Math.round(size)
    if (aspect >= 0.7 && aspect <= 1.6) nodeAspect.value = Math.round(aspect * 20) / 20
  } catch { /* 无本地配置时使用默认值 */ }
}
function saveNodeStyle() {
  try { localStorage.setItem(NODE_STYLE_KEY, JSON.stringify({ size: nodeSize.value, aspect: nodeAspect.value })) } catch { /* ignore */ }
}
function onNodeStyleChange() {
  saveNodeStyle()
  resetLayout()
}
function resetNodeStyle() {
  nodeSize.value = DEFAULT_NODE_SIZE
  nodeAspect.value = 1
  saveNodeStyle()
  resetLayout()
}

/* ===== 连线交互状态 ===== */
const drag = reactive({
  active: false, sourceId: '', x1: 0, y1: 0, x2: 0, y2: 0,
  hoverId: '', hoverName: '', moved: false
})
const edgeMenu = reactive<{ visible: boolean; x: number; y: number; edge: TopoEdge | null }>({
  visible: false, x: 0, y: 0, edge: null
})
const layoutFallback = computed(() => layout.value === 'force' && store.nodes.length > 20 && store.edges.length === 0)
let suppressClick = false
let suppressTimer: any = null
let dragNodes: Array<{ id: string; name: string; x: number; y: number }> = []
const savedPositions: Record<string, [number, number]> = {}

/** 分层布局：应用在上、中间件居中、服务器在底部；每层内部按连线关系排序并自动换行 */
interface LayerBand { top: number; bottom: number }
const layerBands: Record<string, LayerBand> = {}
let lastLayeredKey = ''
let layerClampTimer: any = null
let layeredResizeObserver: ResizeObserver | null = null

const CATEGORY_LABEL: Record<string, string> = { SERVER: '服务器', MIDDLEWARE: '中间件', APP: '应用' }
const DB_TYPES = ['MYSQL', 'ORACLE', 'DAMENG', 'GREENPLUM', 'GBASE', 'MONGODB', 'POSTGRESQL']
const MQ_TYPES = ['KAFKA', 'RABBITMQ']
const REGISTRY_TYPES = ['NACOS', 'ZOOKEEPER']

function categoryLabel(n: TopoNode) { return CATEGORY_LABEL[n.category] || n.category }
function categoryColor(n: TopoNode) {
  if (n.category === 'SERVER') return '#38bdf8'
  if (n.category === 'APP') return '#34d399'
  const t = (n.subType || '').toUpperCase()
  if (DB_TYPES.includes(t)) return '#a78bfa'
  if (t === 'REDIS') return '#fb7185'
  if (MQ_TYPES.includes(t)) return '#f59e0b'
  if (REGISTRY_TYPES.includes(t)) return '#22d3ee'
  if (t === 'ELASTICSEARCH') return '#e879f9'
  return '#f59e0b'
}
function statusColor(status?: string | null) {
  const s = (status || '').toUpperCase()
  if (['ONLINE', 'RUNNING', 'CONNECTED'].includes(s)) return '#22e6a4'
  if (['OFFLINE', 'STOPPED', 'ERROR'].includes(s)) return '#ff4d6d'
  if (s === 'MAINTENANCE') return '#fbbf24'
  return '#64748b'
}
function badgeText(n: TopoNode) {
  if (n.category === 'SERVER') return 'SERVER'
  return (n.subType || n.category || '').toUpperCase().slice(0, 10)
}
function iconMarkup(n: TopoNode, c: string) {
  const t = (n.subType || '').toUpperCase()
  const g = `fill="none" stroke="${c}" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"`
  if (n.category === 'SERVER') {
    return `<g ${g}>
      <rect x="42" y="32" width="36" height="14" rx="3"/>
      <rect x="42" y="52" width="36" height="14" rx="3"/>
      <line x1="48" y1="39" x2="60" y2="39"/><circle cx="72" cy="39" r="1.6" fill="${c}" stroke="none"/>
      <line x1="48" y1="59" x2="60" y2="59"/><circle cx="72" cy="59" r="1.6" fill="${c}" stroke="none"/>
    </g>`
  }
  if (n.category === 'APP') {
    if (t === 'GATEWAY') {
      return `<g ${g}><path d="M60 28 L82 42 L60 56 L38 42 Z"/><path d="M42 64 H78"/><path d="M78 64 L70 57"/><path d="M78 64 L70 71"/></g>`
    }
    return `<g ${g}><rect x="38" y="30" width="44" height="40" rx="5"/><line x1="38" y1="42" x2="82" y2="42"/>
      <circle cx="45" cy="36" r="1.5" fill="${c}" stroke="none"/><circle cx="51" cy="36" r="1.5" fill="${c}" stroke="none"/>
      <path d="M45 55 L54 61 L45 67"/></g>`
  }
  if (DB_TYPES.includes(t)) {
    return `<g ${g}><ellipse cx="60" cy="34" rx="18" ry="6"/><path d="M42 34 V68 A18 6 0 0 0 78 68 V34"/><path d="M42 48 A18 6 0 0 0 78 48"/></g>`
  }
  if (t === 'REDIS') return `<g ${g}><path d="M64 26 L44 58 H56 L53 78 L78 44 H64 Z"/></g>`
  if (MQ_TYPES.includes(t)) {
    return `<g ${g}><rect x="40" y="33" width="40" height="8" rx="4"/><rect x="40" y="47" width="40" height="8" rx="4"/>
      <rect x="40" y="61" width="40" height="8" rx="4"/><path d="M30 37 H36"/><path d="M30 65 H36"/></g>`
  }
  if (REGISTRY_TYPES.includes(t)) {
    return `<g ${g}><path d="M60 27 L79 38 V61 L60 72 L41 61 V38 Z"/><circle cx="60" cy="49" r="4"/>
      <circle cx="60" cy="33" r="2.3"/><circle cx="43" cy="59" r="2.3"/><circle cx="77" cy="59" r="2.3"/>
      <path d="M60 35 V45 M44 58 L56 51 M76 58 L64 51"/></g>`
  }
  if (t === 'ELASTICSEARCH') return `<g ${g}><circle cx="57" cy="48" r="14"/><path d="M67 58 L78 69"/></g>`
  return `<g ${g}><path d="M60 27 L79 38 V61 L60 72 L41 61 V38 Z"/><path d="M60 39 V61 M50 44 L70 56 M70 44 L50 56"/></g>`
}

/** 生成内联 SVG 科技感图标，避免依赖外网图标库 */
function nodeSymbol(n: TopoNode) {
  const c = categoryColor(n)
  const st = statusColor(n.status)
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="120" height="120" viewBox="0 0 120 120">
    <defs><radialGradient id="bg" cx="50%" cy="42%" r="65%">
      <stop offset="0%" stop-color="${c}" stop-opacity="0.30"/>
      <stop offset="65%" stop-color="#07111f" stop-opacity="0.88"/>
      <stop offset="100%" stop-color="#050a14" stop-opacity="0.98"/>
    </radialGradient></defs>
    <polygon points="60,6 106.77,33 106.77,87 60,114 13.23,87 13.23,33" fill="url(#bg)" stroke="${c}" stroke-width="2" stroke-opacity="0.9"/>
    <polygon points="60,14 99.82,37 99.82,83 60,106 20.18,83 20.18,37" fill="none" stroke="${st}" stroke-width="1.3" stroke-opacity="0.85" stroke-dasharray="5 4"/>
    <circle cx="60" cy="56" r="29" fill="rgba(255,255,255,0.025)" stroke="${c}" stroke-opacity="0.18"/>
    ${iconMarkup(n, c)}
    <rect x="36" y="90" width="48" height="15" rx="7.5" fill="rgba(3,10,20,0.9)" stroke="${c}" stroke-opacity="0.55"/>
    <text x="60" y="101" text-anchor="middle" font-family="Consolas,monospace" font-size="8.5" letter-spacing="0.8" fill="${c}">${badgeText(n)}</text>
  </svg>`
  return 'image://data:image/svg+xml;charset=UTF-8,' + encodeURIComponent(svg)
}

function esc(s: any) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
function isFocus(nodeId: string) { return !!focusId.value && nodeId.endsWith(':' + focusId.value) }

/* ===== 分层布局：应用在上层、中间件在中层、服务器在底部 ===== */
function chartCanvasSize() {
  const chart = getChart()
  // 必须使用画布真实尺寸：之前把宽高下限抬到 680/520，窗口较小时布局会按更大的画布计算，
  // 导致最下面一行节点和标签被裁到画布外。
  const w = Math.max(240, Number(chart?.getWidth?.() || wrapRef.value?.clientWidth || 1200))
  const h = Math.max(200, Number(chart?.getHeight?.() || Math.max(300, (wrapRef.value?.clientHeight || 760) - 64)))
  return { w, h }
}

function layerIdOf(node: TopoNode | undefined) {
  return (node?.category || '').toUpperCase()
}

/**
 * ECharts graph 的 layout:'none' 会把数据坐标通过 View 坐标系“拉伸填满”视图矩形，
 * x/y 两个方向独立缩放，导致节点图标（正六边形）被纵向拉长、层级被拉散。
 * 这里把视图矩形设成数据包围盒本身，缩放变为 1:1，图标与坐标不再变形。
 */
function graphBox(data: any[]) {
  const { w, h } = chartCanvasSize()
  if (layout.value === 'layered' && data.length) {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
    for (const d of data) {
      if (!isFinite(d.x) || !isFinite(d.y)) continue
      minX = Math.min(minX, d.x); maxX = Math.max(maxX, d.x)
      minY = Math.min(minY, d.y); maxY = Math.max(maxY, d.y)
    }
    if (isFinite(minX) && isFinite(maxX) && isFinite(minY) && isFinite(maxY)) {
      // 与 ECharts createViewCoordSys 对零宽/零高的处理保持一致
      if (maxX - minX < 1) { minX -= 1; maxX += 1 }
      if (maxY - minY < 1) { minY -= 1; maxY += 1 }
      return { left: minX, top: minY, width: maxX - minX, height: maxY - minY }
    }
  }
  return { left: 26, top: 26, width: Math.max(1, w - 52), height: Math.max(1, h - 52) }
}

function computeLayeredLayout(nodes: TopoNode[], edges: TopoEdge[]) {
  const { w, h } = chartCanvasSize()
  const groups: Record<string, TopoNode[]> = {
    APP: nodes.filter(n => n.category === 'APP'),
    MIDDLEWARE: nodes.filter(n => n.category === 'MIDDLEWARE'),
    SERVER: nodes.filter(n => n.category === 'SERVER')
  }

  const adj: Record<string, string[]> = {}
  for (const e of edges) {
    if (!adj[e.source]) adj[e.source] = []
    if (!adj[e.target]) adj[e.target] = []
    adj[e.source].push(e.target)
    adj[e.target].push(e.source)
  }
  const indexOf = (list: TopoNode[]) => {
    const m: Record<string, number> = {}
    list.forEach((n, i) => { m[n.id] = i })
    return m
  }
  const neighborScore = (n: TopoNode, idx: Record<string, number>, fallback: Record<string, number>) => {
    const vals = (adj[n.id] || []).map(id => idx[id]).filter(v => v !== undefined && v !== null)
    if (!vals.length) return fallback[n.id] ?? 0
    return vals.reduce((a, b) => a + b, 0) / vals.length
  }
  for (let pass = 0; pass < 2; pass++) {
    const idxApp = indexOf(groups.APP)
    const idxMw = indexOf(groups.MIDDLEWARE)
    const idxSrv = indexOf(groups.SERVER)
    groups.APP.sort((a, b) => neighborScore(a, idxMw, idxApp) - neighborScore(b, idxMw, idxApp) || idxApp[a.id] - idxApp[b.id])
    groups.MIDDLEWARE.sort((a, b) => {
      const sa = (neighborScore(a, idxApp, idxMw) + neighborScore(a, idxSrv, idxMw)) / 2
      const sb = (neighborScore(b, idxApp, idxMw) + neighborScore(b, idxSrv, idxMw)) / 2
      return sa - sb || idxMw[a.id] - idxMw[b.id]
    })
    groups.SERVER.sort((a, b) => neighborScore(a, idxMw, idxSrv) - neighborScore(b, idxMw, idxSrv) || idxSrv[a.id] - idxSrv[b.id])
  }

  const present = ['APP', 'MIDDLEWARE', 'SERVER'].filter(k => groups[k].length > 0)
  const side = Math.max(30, w * 0.035)
  const availW = Math.max(240, w - side * 2)
  const topMargin = 30, bottomMargin = 30
  const availH = Math.max(240, h - topMargin - bottomMargin)
  const layerGap = 58
  const labelH = 20
  const aspect = Math.max(0.6, Math.min(1.8, nodeAspect.value || 1))
  /** 节点高度 = 宽度 × 形状比例；比例为 1 时即正六边形的方形外接框 */
  const nodeH = (nw: number) => Math.max(24, Math.round(nw * aspect))
  const baseSize = Math.max(36, Math.min(120, nodeSize.value || DEFAULT_NODE_SIZE))
  const baseRowGap = Math.max(104, nodeH(baseSize) + 38)

  // 每层先按宽度决定列数/行数；列宽至少 96px，避免标签横向挤在一起
  const rowsMap: Record<string, number> = {}
  const perRowMap: Record<string, number> = {}
  present.forEach(cat => {
    const total = groups[cat].length
    const perRow = Math.max(1, Math.min(total, Math.floor(availW / 72)))
    perRowMap[cat] = perRow
    rowsMap[cat] = Math.ceil(total / perRow)
  })
  const totalRows = present.reduce((sum, cat) => sum + Math.max(0, rowsMap[cat] - 1), 0)
  const fixedHeight = present.reduce((sum, cat) => sum + nodeH(baseSize) + labelH, 0)
      + layerGap * Math.max(0, present.length - 1)
  let size = baseSize
  let rowGap = baseRowGap
  if (totalRows > 0) {
    const maxGap = Math.max(48, (availH - fixedHeight) / totalRows)
    if (maxGap < rowGap) rowGap = maxGap
  }
  if (rowGap < nodeH(size) + labelH) size = Math.max(38, Math.floor((rowGap - labelH) / aspect))
  if (present.length * (nodeH(size) + labelH) + layerGap * Math.max(0, present.length - 1) > availH) {
    size = Math.max(34, Math.floor((availH - layerGap * Math.max(0, present.length - 1) - present.length * labelH) / (present.length * aspect)))
  }
  const blockHeights: Record<string, number> = {}
  present.forEach(cat => {
    blockHeights[cat] = (rowsMap[cat] - 1) * rowGap + nodeH(size) + labelH
  })
  const stackH = present.reduce((sum, cat) => sum + blockHeights[cat], 0) + layerGap * Math.max(0, present.length - 1)
  let cursor = topMargin + Math.max(0, (availH - stackH) / 2)

  delete layerBands.APP
  delete layerBands.MIDDLEWARE
  delete layerBands.SERVER
  const out: Record<string, [number, number]> = {}
  const metrics: Record<string, { size: number; height: number; labelWidth: number; fontSize: number; showLabel: boolean }> = {}
  present.forEach(cat => {
    const list = groups[cat]
    const total = list.length
    const rows = rowsMap[cat]
    const perRow = perRowMap[cat]
    const blockH = blockHeights[cat]
    layerBands[cat] = { top: cursor - 8, bottom: cursor + blockH + 8 }
    const slotW = availW / perRow
    metrics[cat] = {
      size,
      height: nodeH(size),
      labelWidth: Math.max(48, Math.min(120, Math.floor(slotW * 0.82))),
      fontSize: size < 40 ? 9 : size < 52 ? 10 : size < 62 ? 11 : 12,
      showLabel: slotW >= 48
    }
    list.forEach((n, i) => {
      const row = Math.floor(i / perRow)
      const col = i % perRow
      const countInRow = Math.min(perRow, total - row * perRow)
      const offset = ((perRow - countInRow) * slotW) / 2
      out[n.id] = [
        side + offset + slotW * (col + 0.5),
        cursor + nodeH(size) / 2 + row * rowGap
      ]
    })
    cursor += blockH + layerGap
  })
  return { positions: out, metrics }
}

function applyLayeredPositions(data: any[], nodes: TopoNode[], layered: { positions: Record<string, [number, number]> }) {
  const { w } = chartCanvasSize()
  const key = nodes.map(n => n.id).join('|') + '@' + Math.round(w) + 'x' + Math.round(chartCanvasSize().h)
  if (key !== lastLayeredKey) {
    Object.keys(savedPositions).forEach(k => delete savedPositions[k])
    lastLayeredKey = key
  }
  const nodeMap = new Map(nodes.map(n => [n.id, n]))
  data.forEach(item => {
    const node = nodeMap.get(item.id)
    const band = node ? layerBands[layerIdOf(node)] : undefined
    let pos = savedPositions[item.id]
    if (pos && band) {
      pos = [
        Math.min(Math.max(pos[0], 30), w - 30),
        Math.min(Math.max(pos[1], band.top + 26), band.bottom - 24)
      ]
    }
    if (!pos) pos = layered.positions[item.id]
    if (pos) {
      item.x = pos[0]
      item.y = pos[1]
      savedPositions[item.id] = pos
    }
  })
}

function scheduleLayerClamp() {
  if (layout.value !== 'layered') return
  clearTimeout(layerClampTimer)
  layerClampTimer = setTimeout(() => clampLayeredNodes(), 60)
}

/** 拖拽结束后把节点拉回所属层：越界节点恢复原位置，层内移动保留 */
function clampLayeredNodes() {
  if (layout.value !== 'layered') return
  try {
    const chart = getChart()
    const data = chart?.getModel?.().getSeriesByIndex(0)?.getData?.()
    const seriesData = option.value?.series?.[0]?.data || []
    if (!data || !seriesData.length) return
    const { w } = chartCanvasSize()
    let changed = false
    for (let i = 0; i < data.count(); i++) {
      const raw = data.getRawDataItem(i) || {}
      const id = raw.id || data.getId?.(i)
      const node = store.nodes.find(n => n.id === id)
      const band = node ? layerBands[layerIdOf(node)] : undefined
      const layoutPos = data.getItemLayout?.(i)
      if (!id || !band || !layoutPos || !isFinite(layoutPos[1])) continue
      const x = layoutPos[0]
      const y = layoutPos[1]
      const outOfBand = y < band.top + 24 || y > band.bottom - 24 || x < 30 || x > w - 30
      if (outOfBand) {
        const prev = savedPositions[id]
        const rx = prev ? prev[0] : Math.min(Math.max(x, 30), w - 30)
        const ry = prev ? prev[1] : Math.min(Math.max(y, band.top + 30), band.bottom - 28)
        savedPositions[id] = [rx, ry]
        const item = seriesData.find((d: any) => d.id === id)
        if (item) { item.x = rx; item.y = ry }
        changed = true
      } else {
        // 允许在同一层内调整位置，下一次渲染沿用
        savedPositions[id] = [x, y]
      }
    }
    if (changed) chart.setOption({ series: [{ data: seriesData }] }, { lazyUpdate: false })
  } catch { /* 拖拽夹紧失败不影响主流程 */ }
}

function render() {
  let nodes = store.nodes
  const cats = filterCategory.value ? [filterCategory.value] : ['SERVER', 'MIDDLEWARE', 'APP']
  nodes = nodes.filter(n => cats.includes(n.category))
  if (selectedNode.value && !nodes.some(n => n.id === selectedNode.value!.id)) selectedNode.value = null
  const keep = new Set(nodes.map(n => n.id))
  const edges = store.edges.filter(e => keep.has(e.source) && keep.has(e.target))
  // 有节点但还没连线时不要用空状态遮住节点，交给编辑模式 HUD 提示
  emptyState.value = nodes.length === 0
  const useForcePositions = layout.value === 'force'
  const layeredLayout = layout.value === 'layered' ? computeLayeredLayout(nodes, edges) : null

  const data = nodes.map(n => {
    const color = categoryColor(n)
    const isSource = store.connectFrom?.id === n.id
    const isHoverTarget = drag.active && drag.hoverId === n.id
    const active = isSource || isHoverTarget || selectedNode.value?.id === n.id || isFocus(n.id)
    const pos = useForcePositions ? savedPositions[n.id] : null
    const metric = layeredLayout?.metrics[n.category]
    const aspect = Math.max(0.6, Math.min(1.8, nodeAspect.value || 1))
    const categorySize = nodeSize.value - (n.category === 'MIDDLEWARE' ? 4 : n.category === 'APP' ? 6 : 0)
    const nodeW = Math.round((metric?.size || categorySize) + (isSource ? 4 : 0))
    const nodeH = Math.round(nodeW * aspect)
    const item: any = {
      id: n.id, name: n.name, category: n.category === 'SERVER' ? 0 : n.category === 'MIDDLEWARE' ? 1 : 2,
      symbol: nodeSymbol(n),
      symbolSize: [nodeW, nodeH],
      symbolKeepAspect: false,
      itemStyle: {
        borderColor: isSource ? '#ffd04b' : isHoverTarget ? '#22e6a4' : 'transparent',
        borderWidth: isSource || isHoverTarget ? 2 : 0,
        shadowBlur: active ? 28 : 14,
        shadowColor: isSource ? '#ffd04b' : isHoverTarget ? '#22e6a4' : color
      },
      label: {
        show: metric ? metric.showLabel : true, position: 'bottom', distance: metric && metric.size < 46 ? 3 : 5,
        formatter: isSource ? n.name + '  ⇢ 源' : n.name,
        color: isSource ? '#ffe08a' : '#e6f0ff', fontSize: metric?.fontSize || 12, fontWeight: 600,
        fontFamily: '"Microsoft YaHei", "PingFang SC", "Noto Sans SC", sans-serif',
        backgroundColor: 'rgba(4,11,22,.82)', borderColor: 'rgba(56,189,248,.28)', borderWidth: 1,
        borderRadius: 4, padding: metric && metric.fontSize <= 10 ? [2, 5] : [3, 7],
        width: metric?.labelWidth || 120, overflow: 'truncate', ellipsis: '…',
        textShadowColor: isSource ? 'rgba(255,208,75,.55)' : 'rgba(0,0,0,.75)', textShadowBlur: 3
      },
      _node: n, _detail: n.detail
    }
    // 关系增删后沿用上次布局位置，避免整图重新飞散
    if (pos) { item.x = pos[0]; item.y = pos[1] }
    return item
  })

  const links = edges.map(e => ({
    source: e.source, target: e.target,
    lineStyle: {
      color: e.origin === 'auto' ? 'rgba(100,116,139,.55)'
        : (e.lineStyle === 'gray' ? 'rgba(56,189,248,.35)' : 'rgba(79,172,254,.92)'),
      type: e.origin === 'auto' ? 'dashed' : (e.lineStyle === 'dashed' ? 'dashed' : 'solid'),
      curveness: 0.16,
      width: e.origin === 'auto' ? 1.1 : 2,
      opacity: e.origin === 'auto' ? 0.55 : 0.95,
      shadowBlur: e.origin === 'auto' ? 0 : 10,
      shadowColor: 'rgba(79,172,254,.75)'
    },
    _edge: e
  }))

  // 分层：应用在上、中间件居中、服务器在底部；区域固定，避免力导向全部漂在一起
  if (layeredLayout) applyLayeredPositions(data, nodes, layeredLayout)
  // 视图矩形与数据包围盒保持一致，避免 ECharts 非等比缩放把六边形拉长
  const box = graphBox(data)

  option.value = {
    backgroundColor: 'transparent',
    animationDuration: 700,
    animationDurationUpdate: 350,
    animationEasingUpdate: 'cubicOut',
    tooltip: {
      show: !store.editMode,
      trigger: 'item',
      backgroundColor: 'rgba(6,14,26,.94)',
      borderColor: 'rgba(79,172,254,.45)',
      borderWidth: 1,
      textStyle: { color: '#cfe3ff', fontSize: 12 },
      formatter: (p: any) => {
        if (p.dataType === 'node') {
          const n: TopoNode = p.data._node
          if (!n) return esc(p.name)
          return `<div style="min-width:180px">
            <div style="font-size:14px;font-weight:700;color:#e6f0ff;margin-bottom:6px">${esc(n.name)}</div>
            <div style="margin-bottom:6px">
              <span style="color:${categoryColor(n)}">${esc(categoryLabel(n))}</span>
              ${n.subType ? `<span style="color:#94a3b8"> · ${esc(n.subType)}</span>` : ''}
              <span style="color:${statusColor(n.status)}"> · ${esc(n.status || 'UNKNOWN')}</span>
            </div>
            <pre style="margin:0;font-size:12px;line-height:1.6;white-space:pre-wrap;color:#9fb6d9">${esc(n.detail)}</pre>
          </div>`
        }
        if (p.dataType === 'edge') {
          const e: TopoEdge = p.data._edge
          if (!e) return ''
          return `<div><b>${esc(e.label)}</b><br/><span style="color:#94a3b8">${e.origin === 'auto' ? '自动推导' : '手动配置'} · ${esc(e.relationType || '')}</span></div>`
        }
        return ''
      }
    },
    series: [{
      type: 'graph',
      layout: layout.value === 'layered' ? 'none' : layout.value,
      roam: true,
      draggable: !store.editMode,
      symbolKeepAspect: false,
      triggerLineEvent: true,
      cursor: store.editMode ? 'crosshair' : 'pointer',
      scaleLimit: { min: 0.4, max: 2.5 },
      categories: [{ name: 'SERVER' }, { name: 'MIDDLEWARE' }, { name: 'APP' }],
      data,
      links,
      ...box,
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 8,
      edgeLabel: { show: false },
      labelLayout: { hideOverlap: true },
      force: (() => {
        const n = nodes.length
        if (n > 60) return { repulsion: 160, edgeLength: [50, 100], gravity: 0.32, friction: 0.2 }
        if (n > 30) return { repulsion: 200, edgeLength: [70, 130], gravity: 0.25, friction: 0.2 }
        return { repulsion: 520, edgeLength: [100, 200], gravity: 0.08, friction: 0.2 }
      })(),
      emphasis: {
        focus: 'adjacency',
        blurScope: 'coordinateSystem',
        lineStyle: { width: 3.2, shadowBlur: 18, shadowColor: 'rgba(56,189,248,.9)' },
        edgeLabel: { show: true, fontSize: 11, color: '#cfe3ff' },
        label: { fontSize: 12, color: '#ffffff', textShadowBlur: 12, textShadowColor: '#38bdf8' }
      },
      lineStyle: { opacity: 0.9 }
    }]
  }
}

  ;(window as any).__topoDebug = { option: option.value, layout: layout.value }

function getChart(): any {
  const c: any = chartRef.value?.chart
  return c?.value ?? c
}

async function ensureChart(): Promise<any> {
  for (let i = 0; i < 20; i++) {
    const c = getChart()
    if (c) return c
    await new Promise(r => setTimeout(r, 100))
  }
  return null
}

function nodeCenter(dataIndex: number): [number, number] | null {
  try {
    const chart = getChart()
    const data = chart?.getModel?.().getSeriesByIndex(0)?.getData?.()
    const layout = data?.getItemLayout?.(dataIndex)
    if (layout && isFinite(layout[0])) return [layout[0], layout[1]]
  } catch { /* ignore */ }
  return null
}

/** 收集所有节点中心点，拖拽时用“就近吸附”判断目标，避免依赖 ECharts 的 hover 状态 */
function collectNodeCenters(): Array<{ id: string; name: string; x: number; y: number }> {
  const out: Array<{ id: string; name: string; x: number; y: number }> = []
  try {
    const chart = getChart()
    const data = chart?.getModel?.().getSeriesByIndex(0)?.getData?.()
    if (!data) return out
    for (let i = 0; i < data.count(); i++) {
      const raw = data.getRawDataItem(i) || {}
      const l = data.getItemLayout(i)
      if (l && isFinite(l[0])) out.push({ id: raw.id, name: raw.name, x: l[0], y: l[1] })
    }
  } catch { /* ignore */ }
  return out
}

function onChartClick(p: any) {
  if (suppressClick) { suppressClick = false; return }
  if (!p || !p.dataType) {
    closeEdgeMenu()
    if (store.connectFrom) { store.resetConnect(); ElMessage.info('已取消连线') }
    if (selectedNode.value) selectedNode.value = null
    render()
    return
  }
  if (p.dataType === 'node') {
    const node = store.nodes.find(n => n.id === p.data?.id)
    if (!node) return
    if (store.editMode) {
      closeEdgeMenu()
      if (!store.connectFrom) {
        if (node.category === 'SERVER') {
          ElMessage.warning('服务器只能作为目标：请从应用/中间件连到服务器')
          return
        }
        store.pickNode(node)
        render()
        ElMessage.info('已选源节点，请点击或拖拽到目标节点')
        return
      }
      if (store.connectFrom.id === node.id) {
        store.resetConnect(); render(); ElMessage.info('已取消连线'); return
      }
      openConnectDialog(store.connectFrom, node)
      return
    }
    closeEdgeMenu()
    selectedNode.value = node
    render()
    return
  }
  if (p.dataType === 'edge' && store.editMode) {
    const e: TopoEdge = p.data?._edge
    if (e) openEdgeMenu(e, p)
  }
}

/** 方向/自身校验：与后端 allowedRelationTypes 保持一致 */
function connectionError(src: TopoNode, tgt: TopoNode): string {
  if (src.id === tgt.id) return '不能连接自己'
  if (src.category === 'SERVER') return '服务器不能作为起点：请从应用/中间件连到服务器'
  const ok = (src.category === 'APP' && ['MIDDLEWARE', 'APP', 'SERVER'].includes(tgt.category))
    || (src.category === 'MIDDLEWARE' && ['MIDDLEWARE', 'SERVER'].includes(tgt.category))
  return ok ? '' : `不支持 ${categoryLabel(src)} → ${categoryLabel(tgt)} 的连线`
}

/** 拓扑节点 id 使用 MW/SRV 缩写，关系接口使用完整类型 */
function relationKey(nodeId: string): string {
  if (nodeId.startsWith('MW:')) return 'MIDDLEWARE:' + nodeId.slice(3)
  if (nodeId.startsWith('SRV:')) return 'SERVER:' + nodeId.slice(4)
  return nodeId
}

function openConnectDialog(src: TopoNode, tgt: TopoNode) {
  const err = connectionError(src, tgt)
  if (err) {
    ElMessage.warning(err)
    return
  }
  const sourceKey = relationKey(src.id)
  const targetKey = relationKey(tgt.id)
  const existing = store.edges.find(e => e.source === src.id && e.target === tgt.id)
  if (existing) {
    store.resetConnect(); render()
    if (existing.origin === 'auto') {
      ElMessageBox.confirm('该连线已存在（自动推导），是否确认转为手动关系？', '确认关系',
        { type: 'info', confirmButtonText: '确认转为手动' })
        .then(() => store.confirmEdge(existing))
        .then(() => { render(); ElMessage.success('已确认关系') })
        .catch(() => {})
      return
    }
    const rel = existing.relationId ? store.relations.find(r => r.id === existing.relationId) : null
    if (rel) {
      dialogRef.value?.setEditing(rel)
      dialogVisible.value = true
      ElMessage.info('该关系已存在，已打开编辑')
    } else {
      ElMessage.warning('该关系已存在')
    }
    return
  }
  dialogPreset.value = { sourceKey, targetKey }
  store.resetConnect()
  dialogRef.value?.setEditing?.({ id: null, sourceKey, targetKey })
  dialogVisible.value = true
  render()
}

/* ===== 拖拽连线 ===== */
function onChartMouseDown(p: any) {
  if (!store.editMode || view.value !== 'graph' || !p || p.dataType !== 'node') return
  if (p.data?.id === undefined) return
  const srcNode = store.nodes.find(n => n.id === p.data.id)
  if (srcNode?.category === 'SERVER') {
    ElMessage.warning('服务器只能作为目标：请从应用/中间件连到服务器')
    return
  }
  closeEdgeMenu()
  selectedNode.value = null
  if (store.connectFrom) { store.resetConnect(); render() }
  const evt = p.event || {}
  const center = nodeCenter(p.dataIndex)
  const x = center ? center[0] : Number(evt.offsetX || 0)
  const y = center ? center[1] : Number(evt.offsetY || 0)
  drag.active = true
  drag.sourceId = p.data.id
  drag.x1 = drag.x2 = x
  drag.y1 = drag.y2 = y
  drag.hoverId = ''; drag.hoverName = ''; drag.moved = false
  dragNodes = collectNodeCenters()
  window.addEventListener('mousemove', onWindowMouseMove)
  window.addEventListener('mouseup', onWindowMouseUp)
}

/** 更新拖拽终点并就近吸附目标节点（半径 42px） */
function updateDragPointer(x: number, y: number) {
  drag.x2 = x; drag.y2 = y
  if (Math.hypot(x - drag.x1, y - drag.y1) > 6) drag.moved = true
  let best: { id: string; name: string; x: number; y: number } | null = null
  let bestDist = 1e9
  for (const n of dragNodes) {
    if (n.id === drag.sourceId) continue
    const d = Math.hypot(n.x - x, n.y - y)
    if (d < bestDist) { bestDist = d; best = n }
  }
  if (best && bestDist <= 42) {
    drag.hoverId = best.id
    drag.hoverName = best.name
    drag.x2 = best.x
    drag.y2 = best.y
  } else {
    drag.hoverId = ''
    drag.hoverName = ''
  }
}

function onChartMouseMove(p: any) {
  if (!drag.active) return
  const evt = p?.event || {}
  const x = Number(evt.offsetX ?? drag.x2)
  const y = Number(evt.offsetY ?? drag.y2)
  updateDragPointer(x, y)
}

function onWindowMouseMove(e: MouseEvent) {
  if (!drag.active) return
  const canvas = wrapRef.value?.querySelector('.topo-chart') as HTMLElement | null
  if (!canvas) return
  const r = canvas.getBoundingClientRect()
  updateDragPointer(e.clientX - r.left, e.clientY - r.top)
}

function onChartMouseUp() {
  if (!drag.active) return
  finishDrag(drag.hoverId || '')
}

function onWindowMouseUp() {
  if (!drag.active) return
  finishDrag(drag.hoverId || '')
}

function finishDrag(targetId: string) {
  const srcId = drag.sourceId
  const moved = drag.moved
  cleanupDrag()
  if (!moved) return
  suppressClickFor(300)
  if (!targetId) { ElMessage.info('已取消连线'); return }
  const src = store.nodes.find(n => n.id === srcId)
  const tgt = store.nodes.find(n => n.id === targetId)
  if (src && tgt) openConnectDialog(src, tgt)
}

function cleanupDrag() {
  drag.active = false
  drag.hoverId = ''; drag.hoverName = ''; drag.moved = false
  dragNodes = []
  window.removeEventListener('mousemove', onWindowMouseMove)
  window.removeEventListener('mouseup', onWindowMouseUp)
}

function suppressClickFor(ms = 300) {
  suppressClick = true
  clearTimeout(suppressTimer)
  suppressTimer = setTimeout(() => { suppressClick = false }, ms)
}

function cancelConnect() {
  cleanupDrag()
  store.resetConnect()
  closeEdgeMenu()
  render()
}

/* ===== 连线操作菜单 ===== */
function openEdgeMenu(e: TopoEdge, p: any) {
  if (store.connectFrom) { store.resetConnect(); render() }
  selectedNode.value = null
  const wrap = wrapRef.value
  if (!wrap) return
  const rect = wrap.getBoundingClientRect()
  const native = p?.event?.event || p?.event
  let cx = native?.clientX
  let cy = native?.clientY
  if (cx == null || cy == null) {
    const canvas = wrap.querySelector('.topo-chart') as HTMLElement | null
    const cr = canvas?.getBoundingClientRect()
    cx = (cr?.left || rect.left) + Number(p?.event?.offsetX || 0)
    cy = (cr?.top || rect.top) + Number(p?.event?.offsetY || 0)
  }
  edgeMenu.edge = e
  edgeMenu.x = Math.max(8, Math.min(cx - rect.left, rect.width - 190))
  edgeMenu.y = Math.max(8, Math.min(cy - rect.top, rect.height - 180))
  edgeMenu.visible = true
  document.removeEventListener('click', onDocClickCloseMenu, true)
  setTimeout(() => document.addEventListener('click', onDocClickCloseMenu, true), 0)
}

function onDocClickCloseMenu(ev: MouseEvent) {
  const target = ev.target as HTMLElement | null
  if (target && target.closest('.edge-menu')) return
  closeEdgeMenu()
}

function closeEdgeMenu() {
  if (!edgeMenu.visible) return
  edgeMenu.visible = false
  edgeMenu.edge = null
  document.removeEventListener('click', onDocClickCloseMenu, true)
}

function menuConfirmOrEdit() {
  const e = edgeMenu.edge
  closeEdgeMenu()
  if (!e) return
  if (e.origin === 'auto') {
    store.confirmEdge(e)
      .then(() => { render(); ElMessage.success('已确认关系') })
      .catch(() => {})
    return
  }
  const rel = e.relationId ? store.relations.find(r => r.id === e.relationId) : null
  if (rel) {
    dialogRef.value?.setEditing(rel)
    dialogVisible.value = true
  } else {
    ElMessage.warning('关系数据未加载，请刷新后重试')
  }
}

function menuDelete() {
  const e = edgeMenu.edge
  closeEdgeMenu()
  if (!e?.relationId) return
  ElMessageBox.confirm(`确定删除「${e.label}」这条关系吗？`, '删除关系',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    .then(() => store.deleteRelation(e.relationId!))
    .then(() => { render(); ElMessage.success('已删除关系') })
    .catch(() => {})
}

/* ===== 布局位置保持 ===== */
function capturePositions() {
  if (layout.value !== 'force') return
  try {
    const chart = getChart()
    const data = chart?.getModel?.().getSeriesByIndex(0)?.getData?.()
    if (!data) return
    for (let i = 0; i < data.count(); i++) {
      const raw = data.getRawDataItem(i) || {}
      const id = raw.id || data.getId?.(i)
      const itemLayout = data.getItemLayout?.(i)
      if (id && itemLayout && isFinite(itemLayout[0])) savedPositions[id] = [itemLayout[0], itemLayout[1]]
    }
  } catch { /* ignore */ }
}

function onLayoutChange() {
  Object.keys(savedPositions).forEach(k => delete savedPositions[k])
  lastLayeredKey = ''
  closeEdgeMenu()
  render()
}

function resetLayout() {
  Object.keys(savedPositions).forEach(k => delete savedPositions[k])
  lastLayeredKey = ''
  render()
}

function onKeyDown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    if (drag.active) { cleanupDrag(); suppressClickFor(200); return }
    if (edgeMenu.visible) { closeEdgeMenu(); return }
    if (store.connectFrom) { store.resetConnect(); render(); return }
    if (selectedNode.value) { selectedNode.value = null; render() }
  }
}

async function attachChartEvents() {
  const chart = await ensureChart()
  if (!chart) return
  chart.off('mousedown', onChartMouseDown)
  chart.off('mousemove', onChartMouseMove)
  chart.off('mouseup', onChartMouseUp)
  chart.off('mouseup', scheduleLayerClamp)
  chart.off('finished', capturePositions)
  chart.on('mousedown', onChartMouseDown)
  chart.on('mousemove', onChartMouseMove)
  chart.on('mouseup', onChartMouseUp)
  chart.on('mouseup', scheduleLayerClamp)
  chart.on('finished', capturePositions)
}

function closePanel() { selectedNode.value = null; render() }
function onCategoryChange() { closeEdgeMenu(); render() }
function toggleEdit() {
  cleanupDrag(); closeEdgeMenu(); store.toggleEdit(); selectedNode.value = null; render()
}

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

function syncChartSize() {
  try {
    const chart = getChart()
    if (!chart) return
    chart.resize()
    lastLayeredKey = ''
    Object.keys(savedPositions).forEach(k => delete savedPositions[k])
    render()
  } catch { /* ignore */ }
}

function fullscreen() {
  const el = wrapRef.value as any
  if (!document.fullscreenElement) el?.requestFullscreen?.(); else document.exitFullscreen?.()
  // 全屏切换后容器尺寸变化较大，显式 resize，避免 canvas 被 CSS 拉伸导致节点/文字变形
  setTimeout(syncChartSize, 350)
}

function onFullscreenChange() { setTimeout(syncChartSize, 250) }

async function loadProjects() {
  const res = await request.get('/api/project/options')
  projects.value = res.data || []
}

async function onProjectChange(v: number | null | undefined) {
  cleanupDrag(); closeEdgeMenu(); store.resetConnect()
  selectedProjectId.value = v ? Number(v) : null
  selectedNode.value = null
  await store.loadGraph(selectedProjectId.value)
  render()
}

async function refreshGraph() {
  cleanupDrag(); closeEdgeMenu()
  await Promise.all([loadProjects(), store.loadGraph(selectedProjectId.value), store.loadRelations()])
  render()
}

watch([() => store.nodes, () => store.edges], () => { if (view.value === 'graph') render() })

onMounted(async () => {
  loadNodeStyle()
  if (route.query.focusId) focusId.value = String(route.query.focusId)
  const pid = route.query.projectId ? Number(route.query.projectId) : null
  selectedProjectId.value = pid && !Number.isNaN(pid) ? pid : null
  await Promise.all([loadProjects(), store.loadGraph(selectedProjectId.value), store.loadRelations()])
  // 默认分层布局：应用在上、中间件居中、服务器在底部
  render()
  await nextTick()
  await attachChartEvents()
  // 首次渲染时图表尺寸可能还没确定，尺寸变化后重新计算分层位置
  if (wrapRef.value && typeof ResizeObserver !== 'undefined') {
    layeredResizeObserver = new ResizeObserver(() => {
      if (layout.value !== 'layered') return
      clearTimeout(layerClampTimer)
      layerClampTimer = setTimeout(() => {
        try { getChart()?.resize() } catch { /* ignore */ }
        lastLayeredKey = ''
        Object.keys(savedPositions).forEach(k => delete savedPositions[k])
        render()
      }, 160)
    })
    layeredResizeObserver.observe(wrapRef.value)
  }
  window.addEventListener('keydown', onKeyDown)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  if (focusId.value) ElMessage({ message: '已高亮定位节点 ID=' + focusId.value, duration: 2500 })
  else if (selectedProjectId.value) ElMessage({ message: '已按项目筛选机器与链路', duration: 2500 })
  else ElMessage({ message: '科技大屏：拖拽节点即可连线；点击连线可编辑/删除；支持项目筛选与全屏', duration: 3000 })
})

onUnmounted(() => {
  cleanupDrag()
  closeEdgeMenu()
  clearTimeout(suppressTimer)
  clearTimeout(layerClampTimer)
  if (layeredResizeObserver) { layeredResizeObserver.disconnect(); layeredResizeObserver = null }
  window.removeEventListener('keydown', onKeyDown)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  const chart = getChart()
  if (chart) {
    chart.off('mousedown', onChartMouseDown)
    chart.off('mousemove', onChartMouseMove)
    chart.off('mouseup', onChartMouseUp)
    chart.off('mouseup', scheduleLayerClamp)
    chart.off('finished', capturePositions)
  }
})
</script>

<style scoped>
.topo-wrap {
  position: relative;
  height: calc(100vh - 40px);
  display: flex;
  flex-direction: column;
  border-radius: 10px;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 12%, rgba(56, 189, 248, .16), transparent 34%),
    radial-gradient(circle at 82% 82%, rgba(167, 139, 250, .13), transparent 40%),
    radial-gradient(circle at 50% 50%, #0b1526 0%, #060b15 70%);
  box-shadow: inset 0 0 80px rgba(2, 8, 20, .9);
}
.topo-grid-bg {
  position: absolute; inset: 0; pointer-events: none;
  background-image:
    linear-gradient(rgba(56, 189, 248, .055) 1px, transparent 1px),
    linear-gradient(90deg, rgba(56, 189, 248, .055) 1px, transparent 1px);
  background-size: 44px 44px;
  -webkit-mask-image: radial-gradient(circle at 50% 48%, #000 25%, transparent 88%);
  mask-image: radial-gradient(circle at 50% 48%, #000 25%, transparent 88%);
}
.topo-scanline {
  position: absolute; left: 0; right: 0; top: -160px; height: 160px; pointer-events: none;
  background: linear-gradient(180deg, transparent, rgba(56, 189, 248, .07), transparent);
  animation: scan 9s linear infinite;
}
@keyframes scan { 0% { transform: translateY(0); } 100% { transform: translateY(110vh); } }

.topo-toolbar {
  position: relative; z-index: 5;
  display: flex; align-items: center; gap: 16px; padding: 12px 18px; flex-wrap: wrap;
  background: linear-gradient(180deg, rgba(8, 18, 34, .92), rgba(8, 18, 34, .62));
  border-bottom: 1px solid rgba(56, 189, 248, .28);
  box-shadow: 0 6px 24px rgba(0, 0, 0, .35);
  backdrop-filter: blur(8px);
}
.brand { display: flex; align-items: center; gap: 10px; }
.brand-mark {
  width: 34px; height: 34px; position: relative;
  clip-path: polygon(50% 0, 93.3% 25%, 93.3% 75%, 50% 100%, 6.7% 75%, 6.7% 25%);
  background: linear-gradient(135deg, rgba(56,189,248,.95), rgba(167,139,250,.9));
  display: flex; align-items: center; justify-content: center;
  animation: brandPulse 2.8s ease-in-out infinite;
}
.brand-mark span {
  width: 14px; height: 14px; border-radius: 50%;
  background: #06111f; box-shadow: 0 0 12px #38bdf8;
}
@keyframes brandPulse { 0%,100% { filter: drop-shadow(0 0 8px rgba(56,189,248,.75)); } 50% { filter: drop-shadow(0 0 18px rgba(167,139,250,.95)); } }
.topo-title { color: #e6f0ff; font-weight: 800; font-size: 16px; letter-spacing: 1px; }
.topo-sub { color: #5b7ea6; font-size: 10px; letter-spacing: 1.4px; margin-top: 2px; font-family: Consolas, monospace; }
.topo-stats { display: flex; gap: 10px; color: #9fb6d9; font-size: 12px; flex-wrap: wrap; }
.stat {
  min-width: 76px; padding: 4px 12px; border-radius: 6px;
  background: rgba(56, 189, 248, .06);
  border: 1px solid rgba(56, 189, 248, .18);
  display: flex; align-items: baseline; gap: 8px;
}
.stat b { color: #38bdf8; font-size: 18px; font-family: Consolas, monospace; text-shadow: 0 0 10px rgba(56,189,248,.6); }
.stat.abn b { color: #ff6b6b; text-shadow: 0 0 10px rgba(255,107,107,.6); }
.topo-tools { margin-left: auto; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

.topo-toolbar :deep(.el-input__inner) {
  background: rgba(8, 20, 36, .85); color: #cfe3ff; border-color: rgba(56, 189, 248, .25);
}
.topo-toolbar :deep(.el-input__inner::placeholder) { color: #5b7ea6; }
.topo-toolbar :deep(.el-radio-button__inner) {
  background: rgba(8, 20, 36, .85); color: #9fb6d9; border-color: rgba(56, 189, 248, .25);
  box-shadow: none;
}
.topo-toolbar :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: linear-gradient(135deg, rgba(56,189,248,.85), rgba(79,172,254,.75));
  border-color: #38bdf8; color: #06111f; font-weight: 700;
  box-shadow: 0 0 14px rgba(56,189,248,.5);
}
.topo-toolbar :deep(.el-button) {
  background: rgba(8, 20, 36, .85); color: #9fb6d9; border-color: rgba(56, 189, 248, .25);
}
.topo-toolbar :deep(.el-button--primary) {
  background: linear-gradient(135deg, #38bdf8, #4facfe); border: none; color: #06111f; font-weight: 700;
  box-shadow: 0 0 14px rgba(56,189,248,.45);
}
.topo-toolbar :deep(.el-select-dropdown) { background: #0a1424; }

.edit-alert { border-radius: 0; background: rgba(245, 158, 11, .12); border-color: rgba(245, 158, 11, .3); color: #fbbf24; }

/* ===== 连线交互 ===== */
.connect-hud {
  position: absolute; top: 12px; left: 50%; transform: translateX(-50%); z-index: 6;
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap; max-width: calc(100% - 48px);
  padding: 7px 14px; border-radius: 999px;
  background: rgba(8, 20, 36, .9); border: 1px solid rgba(56, 189, 248, .32);
  color: #cfe3ff; font-size: 12px; backdrop-filter: blur(8px);
  box-shadow: 0 6px 20px rgba(0, 0, 0, .35), inset 0 0 16px rgba(56, 189, 248, .05);
  pointer-events: none;
}
.connect-hud .el-button { pointer-events: auto; }
.hud-badge {
  padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 700;
  color: #06111f; background: linear-gradient(135deg, #fbbf24, #f59e0b);
  box-shadow: 0 0 12px rgba(245, 158, 11, .5);
}
.hud-text b { color: #ffe08a; }
.hud-tip { color: #5b7ea6; font-size: 11px; padding-left: 10px; border-left: 1px solid rgba(56, 189, 248, .2); }
.connect-layer { position: absolute; inset: 0; width: 100%; height: 100%; z-index: 5; pointer-events: none; overflow: visible; }
.connect-line { animation: dashmove .8s linear infinite; }
@keyframes dashmove { to { stroke-dashoffset: -32; } }
.drag-tip {
  position: absolute; z-index: 7; pointer-events: none; white-space: nowrap;
  padding: 5px 9px; border-radius: 6px; font-size: 12px; color: #cfe3ff;
  background: rgba(6, 14, 26, .92); border: 1px solid rgba(56, 189, 248, .38);
  box-shadow: 0 4px 14px rgba(0, 0, 0, .45);
}
.edge-menu {
  position: absolute; z-index: 9; width: 176px; padding: 6px;
  border-radius: 8px; background: rgba(8, 18, 34, .97);
  border: 1px solid rgba(56, 189, 248, .36);
  box-shadow: 0 12px 32px rgba(0, 0, 0, .6); backdrop-filter: blur(8px);
  font-size: 12.5px;
}
.em-title { color: #e6f0ff; font-weight: 700; padding: 4px 8px 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.em-sub { color: #5b7ea6; font-size: 11px; padding: 0 8px 6px; border-bottom: 1px solid rgba(56, 189, 248, .16); margin-bottom: 4px; }
.em-item { padding: 7px 8px; border-radius: 5px; cursor: pointer; color: #cfe3ff; }
.em-item:hover { background: rgba(56, 189, 248, .13); }
.em-item.danger { color: #ff8a9b; }
.em-item.muted { color: #7e93ad; }
.topo-canvas.editing :deep(canvas) { cursor: crosshair !important; }

.topo-canvas { position: relative; flex: 1; min-height: 0; }
.topo-chart { height: 100%; width: 100%; }

.corner { position: absolute; width: 26px; height: 26px; pointer-events: none; border-color: rgba(56,189,248,.65); border-style: solid; }
.corner.tl { top: 10px; left: 10px; border-width: 2px 0 0 2px; }
.corner.tr { top: 10px; right: 10px; border-width: 2px 2px 0 0; }
.corner.bl { bottom: 10px; left: 10px; border-width: 0 0 2px 2px; }
.corner.br { bottom: 10px; right: 10px; border-width: 0 2px 2px 0; }

.topo-legend {
  position: absolute; left: 24px; bottom: 22px; z-index: 4;
  padding: 10px 14px; border-radius: 8px;
  background: rgba(6, 14, 26, .78);
  border: 1px solid rgba(56, 189, 248, .25);
  box-shadow: 0 0 24px rgba(0, 0, 0, .35), inset 0 0 18px rgba(56, 189, 248, .05);
  color: #9fb6d9; font-size: 12px; backdrop-filter: blur(6px);
  pointer-events: none;
}
.leg-title { color: #5b7ea6; font-size: 10px; letter-spacing: 1.4px; font-family: Consolas, monospace; margin-bottom: 6px; }
.leg-row { display: flex; align-items: center; gap: 8px; padding: 2px 0; }
.leg-sep { height: 1px; background: rgba(56, 189, 248, .15); margin: 6px 0; }
.leg-row.small { gap: 6px; }
.legend-ico { width: 14px; height: 14px; clip-path: polygon(50% 0, 93.3% 25%, 93.3% 75%, 50% 100%, 6.7% 75%, 6.7% 25%); }
.legend-ico.server { background: #38bdf8; box-shadow: 0 0 8px rgba(56,189,248,.7); }
.legend-ico.app { background: #34d399; box-shadow: 0 0 8px rgba(52,211,153,.7); }
.legend-ico.mw { background: #f59e0b; box-shadow: 0 0 8px rgba(245,158,11,.7); }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; margin-left: 6px; }
.dot.online { background: #22e6a4; box-shadow: 0 0 6px rgba(34,230,164,.8); }
.dot.offline { background: #ff4d6d; box-shadow: 0 0 6px rgba(255,77,109,.8); }
.dot.warn { background: #fbbf24; box-shadow: 0 0 6px rgba(251,191,36,.8); }

.node-panel {
  position: absolute; right: 16px; top: 12px; bottom: 12px; z-index: 4;
  width: 344px; max-width: calc(100% - 32px);
  display: flex; flex-direction: column;
  padding: 12px 14px 6px; border-radius: 10px;
  background: rgba(6, 14, 26, .92);
  border: 1px solid rgba(56, 189, 248, .3);
  box-shadow: 0 0 30px rgba(0, 0, 0, .5), inset 0 0 24px rgba(56, 189, 248, .05);
  backdrop-filter: blur(8px);
}
.np-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.np-title { display: flex; flex-direction: column; min-width: 0; }
.np-name { color: #e6f0ff; font-weight: 700; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.np-sub { color: #5b7ea6; font-size: 11px; margin-top: 3px; font-family: Consolas, monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.np-close { cursor: pointer; color: #5b7ea6; margin-top: 2px; }
.np-close:hover { color: #38bdf8; }
.np-tags { display: flex; gap: 6px; flex-wrap: wrap; margin: 8px 0 4px; }

.np-tabs { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.np-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.np-tabs :deep(.el-tabs__item) { color: #7e93ad; font-size: 12.5px; height: 30px; line-height: 30px; padding: 0 12px; }
.np-tabs :deep(.el-tabs__item.is-active) { color: #38bdf8; }
.np-tabs :deep(.el-tabs__item:hover) { color: #cfe3ff; }
.np-tabs :deep(.el-tabs__active-bar) { background: #38bdf8; }
.np-tabs :deep(.el-tabs__nav-wrap::after) { background-color: rgba(56, 189, 248, .15); height: 1px; }
.np-tabs :deep(.el-tabs__content) { flex: 1; overflow: auto; padding-right: 2px; }

.np-actions { display: flex; flex-direction: column; gap: 8px; padding-bottom: 6px; }
.np-action {
  display: flex; align-items: center; gap: 10px; width: 100%;
  padding: 9px 10px; border-radius: 8px; cursor: pointer; text-align: left;
  background: rgba(56, 189, 248, .06); border: 1px solid rgba(56, 189, 248, .18);
  color: #cfe3ff; font-family: inherit; transition: background .18s, border-color .18s, transform .18s;
}
.np-action:hover { background: rgba(56, 189, 248, .14); border-color: rgba(56, 189, 248, .45); transform: translateX(2px); }
.na-ico {
  width: 30px; height: 30px; flex: none; border-radius: 8px;
  display: flex; align-items: center; justify-content: center; font-size: 16px;
  color: #38bdf8; background: rgba(56, 189, 248, .12);
}
.np-action.green .na-ico { color: #34d399; background: rgba(52, 211, 153, .12); }
.np-action.purple .na-ico { color: #a78bfa; background: rgba(167, 139, 250, .14); }
.np-action.amber .na-ico { color: #f59e0b; background: rgba(245, 158, 11, .13); }
.np-action.slate .na-ico { color: #94a3b8; background: rgba(148, 163, 184, .12); }
.na-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.na-text b { font-size: 13px; font-weight: 600; }
.na-text small { color: #7e93ad; font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.na-arrow { color: #4b6584; font-size: 12px; flex: none; }

.np-empty { color: #5b7ea6; font-size: 12px; text-align: center; padding: 26px 10px; line-height: 1.7; }

.np-kv { display: flex; flex-direction: column; gap: 6px; }
.np-kv-row { display: flex; gap: 10px; font-size: 12.5px; line-height: 1.6; border-bottom: 1px dashed rgba(56, 189, 248, .1); padding-bottom: 5px; }
.np-kv-row .k { width: 56px; flex: none; color: #5b7ea6; }
.np-kv-row .v { color: #cfe3ff; word-break: break-all; }
.np-copy { display: flex; align-items: center; gap: 8px; margin-top: 12px; font-size: 12px; color: #9fb6d9; }
.np-copy .k { color: #5b7ea6; }
.np-copy code { color: #38bdf8; font-family: Consolas, monospace; background: rgba(56, 189, 248, .08); padding: 2px 8px; border-radius: 4px; }

.np-rel-list { display: flex; flex-direction: column; gap: 6px; padding-bottom: 6px; }
.np-rel {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 6px;
  background: rgba(56, 189, 248, .05); border: 1px solid rgba(56, 189, 248, .14);
  cursor: pointer; font-size: 12.5px; color: #cfe3ff;
}
.np-rel:hover { background: rgba(56, 189, 248, .12); border-color: rgba(56, 189, 248, .35); }
.rel-dir { color: #38bdf8; font-family: Consolas, monospace; }
.rel-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.np-rel :deep(.el-tag) { background: rgba(56, 189, 248, .08); border-color: rgba(56, 189, 248, .28); color: #9fb6d9; }
.np-rel :deep(.el-tag.el-tag--info) { background: rgba(144, 147, 153, .12); border-color: rgba(144, 147, 153, .3); color: #94a3b8; }
.panel-fade-enter-active, .panel-fade-leave-active { transition: opacity .2s, transform .2s; }
.panel-fade-enter-from, .panel-fade-leave-to { opacity: 0; transform: translateY(8px); }

.topo-table { flex: 1; overflow: auto; padding: 16px; position: relative; z-index: 2; }
.topo-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; pointer-events: none; }
</style>

<style>
/* 节点样式弹层（el-popover 传送到 body，不能使用 scoped） */
.topo-node-pop.el-popover.el-popper {
  background: rgba(8, 18, 34, .98);
  border: 1px solid rgba(56, 189, 248, .38);
  box-shadow: 0 12px 32px rgba(0, 0, 0, .55), inset 0 0 18px rgba(56, 189, 248, .06);
  color: #cfe3ff;
}
.topo-node-pop .el-popper__arrow::before {
  background: rgba(8, 18, 34, .98);
  border-color: rgba(56, 189, 248, .38);
}
.topo-node-pop .ns-title {
  color: #e6f0ff; font-weight: 700; font-size: 13px; margin-bottom: 10px;
  display: flex; align-items: baseline; gap: 8px;
}
.topo-node-pop .ns-title span { color: #5b7ea6; font-size: 11px; font-weight: 400; }
.topo-node-pop .ns-row { display: flex; align-items: center; gap: 10px; margin-bottom: 4px; }
.topo-node-pop .ns-row label { width: 54px; flex: none; color: #9fb6d9; font-size: 12px; }
.topo-node-pop .ns-row b {
  width: 52px; flex: none; text-align: right; color: #38bdf8; font-size: 12px;
  font-family: Consolas, monospace; text-shadow: 0 0 10px rgba(56, 189, 248, .5);
}
.topo-node-pop .ns-row .el-slider { flex: 1; --el-slider-main-bg-color: #38bdf8; --el-slider-runway-bg-color: rgba(56, 189, 248, .18); }
.topo-node-pop .ns-hint {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 8px; padding-top: 8px; border-top: 1px solid rgba(56, 189, 248, .16);
  font-size: 12px;
}
.topo-node-pop .ns-hint .ok { color: #22e6a4; }
.topo-node-pop .ns-hint .warn { color: #fbbf24; }
.topo-node-pop .ns-tip { margin-top: 6px; color: #5b7ea6; font-size: 11px; line-height: 1.5; }
</style>