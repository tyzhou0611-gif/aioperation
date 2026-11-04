<template>
  <el-dialog
    :model-value="modelValue"
    title="监控面板配置"
    width="900px"
    top="6vh"
    class="monitor-cfg-dialog"
    :class="{ dark }"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="onVisibleChange">
    <el-tabs v-model="tab">
      <!-- 组件显隐 -->
      <el-tab-pane label="组件显示" name="components">
        <div class="cfg-section">
          <div class="cfg-title">概览卡片</div>
          <div class="cfg-checks">
            <el-checkbox v-for="c in CARD_DEFS" :key="c.key" v-model="draft.cards[c.key]">{{ c.label }}</el-checkbox>
          </div>
        </div>

        <div class="cfg-section">
          <div class="cfg-title">图表组件</div>
          <div v-for="c in CHART_DEFS" :key="c.key" class="cfg-row">
            <el-checkbox v-model="draft.charts[c.key].enabled">{{ c.label }}</el-checkbox>
            <el-select v-model="draft.charts[c.key].span" size="small" style="width:120px"
                       :disabled="!draft.charts[c.key].enabled">
              <el-option label="四分之一" :value="6" />
              <el-option label="三分之一" :value="8" />
              <el-option label="半宽" :value="12" />
              <el-option label="三分之二" :value="16" />
              <el-option label="全宽" :value="24" />
            </el-select>
          </div>
        </div>

        <div class="cfg-section">
          <div class="cfg-title">主机状态</div>
          <el-checkbox v-model="draft.hostTable.enabled">显示主机状态表</el-checkbox>
        </div>
      </el-tab-pane>

      <!-- 自定义重点指标 -->
      <el-tab-pane label="重点指标（开发）" name="metrics">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
                  title="开发功能：用 PromQL 自定义重点指标，保存后直接出现在监控面板；勾选“重点卡片”会在顶部概览卡片显示最新值。" />

        <div class="preset-row">
          <el-select v-model="catalogCode" placeholder="从指标目录导入" style="width:300px" filterable clearable>
            <el-option v-for="c in catalogMetrics" :key="c.code" :label="`${c.name} (${c.code})`" :value="c.code" />
          </el-select>
          <el-button @click="addFromCatalog" :disabled="!catalogCode || full">导入指标</el-button>
          <span class="limit">仅 Prometheus 类型</span>
        </div>

        <div class="preset-row">
          <el-select v-model="presetIndex" placeholder="从预置指标添加" style="width:260px" clearable>
            <el-option v-for="(p, i) in PRESETS" :key="p.name" :label="p.name" :value="i" />
          </el-select>
          <el-button @click="addPreset" :disabled="presetIndex == null || full">添加预置指标</el-button>
          <el-button type="primary" plain @click="addBlank" :disabled="full">添加空白指标</el-button>
          <span class="limit">{{ draft.customMetrics.length }} / 20</span>
        </div>

        <div v-if="!draft.customMetrics.length" class="empty-metrics">
          <el-empty description="还没有自定义指标，可从上方预置列表添加，或添加空白指标自行编写 PromQL" :image-size="60" />
        </div>

        <el-card v-for="(m, i) in draft.customMetrics" :key="m.id || i" shadow="never" class="metric-card">
          <div class="metric-head">
            <el-input v-model="m.name" placeholder="指标名称（如：TCP 连接数）" style="width:220px" maxlength="40" />
            <el-tag size="small" effect="plain">{{ unitLabel(m.unit) }}</el-tag>
            <span class="grow"></span>
            <el-switch v-model="m.highlight" active-text="重点卡片" />
            <el-switch v-model="m.enabled" active-text="启用" />
            <el-button text type="danger" @click="remove(i)">删除</el-button>
          </div>

          <el-input v-model="m.query" type="textarea" :rows="2" maxlength="500" show-word-limit
                    placeholder='PromQL，例如 node_load1{instance="$instance"}；支持 $instance 占位' />

          <div class="metric-row">
            <span class="lbl">单位</span>
            <el-select v-model="m.unit" size="small" style="width:110px">
              <el-option label="百分比 %" value="pct" />
              <el-option label="比特/秒 bps" value="bps" />
              <el-option label="字节 bytes" value="bytes" />
              <el-option label="数值" value="num" />
              <el-option label="毫秒 ms" value="ms" />
              <el-option label="数量" value="count" />
            </el-select>
            <span class="lbl">图形</span>
            <el-select v-model="m.type" size="small" style="width:100px">
              <el-option label="折线" value="line" />
              <el-option label="面积" value="area" />
              <el-option label="柱状" value="bar" />
            </el-select>
            <span class="lbl">宽度</span>
            <el-select v-model="m.span" size="small" style="width:110px">
              <el-option label="四分之一" :value="6" />
              <el-option label="三分之一" :value="8" />
              <el-option label="半宽" :value="12" />
              <el-option label="三分之二" :value="16" />
              <el-option label="全宽" :value="24" />
            </el-select>
            <el-color-picker v-model="m.color" size="small" />
            <span class="grow"></span>
            <el-button size="small" :loading="testingId === (m.id || String(i))" @click="test(m, i)">试运行</el-button>
          </div>

          <div v-if="m._test" class="test-ok">
            查询成功：{{ m._test.series }} 条序列，最新值 {{ fmtUnit(m._test.latest, m.unit) }}
            <span v-if="m._test.label">（{{ m._test.label }}）</span>
          </div>
          <el-alert v-if="m._testError" type="error" :closable="false" show-icon class="test-err"
                    :title="'查询失败：' + m._testError" />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <div class="cfg-footer">
        <el-button @click="reset">恢复默认</el-button>
        <span class="grow"></span>
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存并应用</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

const props = withDefaults(defineProps<{
  modelValue: boolean
  config: any
  instance?: string
  dark?: boolean
}>(), { instance: 'all', dark: false })

const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved', cfg: any): void }>()

const CARD_DEFS = [
  { key: 'totalHosts', label: '主机总数' },
  { key: 'onlineHosts', label: '在线主机' },
  { key: 'offlineHosts', label: '离线主机' },
  { key: 'avgCpu', label: '平均 CPU' },
  { key: 'avgMemory', label: '平均内存' },
  { key: 'maxDisk', label: '最高磁盘' }
]

const CHART_DEFS = [
  { key: 'cpu', label: 'CPU 使用率' },
  { key: 'memory', label: '内存使用率' },
  { key: 'disk', label: '根分区磁盘使用率' },
  { key: 'network', label: '网络流量' },
  { key: 'load', label: '系统负载' }
]

const PRESETS: any[] = [
  { name: 'CPU 使用率', query: '100 - (avg by(instance) (rate(node_cpu_seconds_total{mode="idle"}[15m])) * 100)', unit: 'pct', type: 'line', span: 12, color: '#409eff', highlight: true },
  { name: '内存使用率', query: '(1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100', unit: 'pct', type: 'line', span: 12, color: '#67c23a', highlight: true },
  { name: '根分区使用率', query: 'max by(instance) ((1 - node_filesystem_avail_bytes{mountpoint="/",fstype!~"tmpfs|overlay"} / node_filesystem_size_bytes{mountpoint="/",fstype!~"tmpfs|overlay"}) * 100)', unit: 'pct', type: 'line', span: 12, color: '#e6a23c', highlight: true },
  { name: '系统负载 (1m)', query: 'node_load1', unit: 'num', type: 'line', span: 12, color: '#f56c6c', highlight: false },
  { name: 'TCP 连接数', query: 'sum(node_netstat_Tcp_CurrEstab)', unit: 'count', type: 'line', span: 12, color: '#8e44ad', highlight: true },
  { name: '运行中进程数', query: 'sum(node_procs_running)', unit: 'count', type: 'line', span: 12, color: '#00bcd4', highlight: false },
  { name: '打开文件描述符', query: 'sum(node_filefd_allocated)', unit: 'count', type: 'line', span: 12, color: '#ff9800', highlight: false },
  { name: '磁盘读取速率', query: 'sum(rate(node_disk_read_bytes_total[15m]))', unit: 'bytes', type: 'area', span: 12, color: '#3f51b5', highlight: false },
  { name: '磁盘写入速率', query: 'sum(rate(node_disk_written_bytes_total[15m]))', unit: 'bytes', type: 'area', span: 12, color: '#009688', highlight: false },
  { name: '网络接收速率', query: 'sum(rate(node_network_receive_bytes_total{device!="lo"}[15m]))*8', unit: 'bps', type: 'area', span: 12, color: '#c2185b', highlight: false },
  { name: '网络发送速率', query: 'sum(rate(node_network_transmit_bytes_total{device!="lo"}[15m]))*8', unit: 'bps', type: 'area', span: 12, color: '#795548', highlight: false },
  { name: 'CPU iowait', query: 'avg by(instance)(rate(node_cpu_seconds_total{mode="iowait"}[15m]))*100', unit: 'pct', type: 'line', span: 12, color: '#607d8b', highlight: false },
  { name: 'inode 使用率', query: 'max by(instance) ((1 - node_filesystem_files_free{mountpoint="/"} / node_filesystem_files{mountpoint="/"}) * 100)', unit: 'pct', type: 'line', span: 12, color: '#cddc39', highlight: false }
]

function defaultConfig() {
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

const tab = ref('components')
const presetIndex = ref<number | null>(null)
const catalogCode = ref('')
const catalogMetrics = ref<any[]>([])
const saving = ref(false)
const testingId = ref('')
const draft = reactive<any>(defaultConfig())

const full = computed(() => draft.customMetrics.length >= 20)

function clone(v: any) { return JSON.parse(JSON.stringify(v == null ? null : v)) }

function loadFromProps() {
  const d = defaultConfig()
  const c = props.config || {}
  Object.assign(d.cards, c.cards || {})
  for (const key of Object.keys(d.charts)) {
    Object.assign((d.charts as any)[key], (c.charts || {})[key] || {})
  }
  Object.assign(d.hostTable, c.hostTable || {})
  d.customMetrics = Array.isArray(c.customMetrics) ? clone(c.customMetrics) : []
  Object.assign(draft, clone(d))
}

watch(() => props.modelValue, (v) => { if (v) { loadFromProps(); loadCatalog() } })
watch(() => props.config, () => { if (props.modelValue) loadFromProps() }, { deep: true })

function onVisibleChange(v: boolean) { emit('update:modelValue', v) }

async function loadCatalog() {
  try {
    const r = await request.get('/api/metrics/catalog')
    catalogMetrics.value = (r.data || []).filter((x: any) => x.sourceType === 'prometheus' && x.enabled !== 0)
  } catch { catalogMetrics.value = [] }
}

function addFromCatalog() {
  if (!catalogCode.value || full.value) return
  const c = catalogMetrics.value.find((x: any) => x.code === catalogCode.value)
  if (!c) return
  draft.customMetrics.push({
    id: 'cm-' + Date.now().toString(36),
    name: c.name,
    query: c.query,
    unit: c.unit || 'num',
    type: 'line',
    span: 12,
    color: '#409eff',
    highlight: false,
    enabled: true,
    description: c.description || ('来自指标目录: ' + c.code)
  })
  catalogCode.value = ''
}

function reset() {
  Object.assign(draft, defaultConfig())
  ElMessage.info('已恢复默认配置，点击“保存并应用”后生效')
}

function addPreset() {
  if (presetIndex.value == null || full.value) return
  const p = clone(PRESETS[presetIndex.value])
  p.id = 'cm-' + Date.now().toString(36)
  p.enabled = true
  draft.customMetrics.push(p)
  presetIndex.value = null
}

function addBlank() {
  if (full.value) return
  draft.customMetrics.push({
    id: 'cm-' + Date.now().toString(36),
    name: '', query: '', unit: 'num', type: 'line', span: 12,
    color: '#409eff', highlight: false, enabled: true, description: ''
  })
}

function remove(i: number) {
  draft.customMetrics.splice(i, 1)
}

async function test(m: any, i: number) {
  m._test = null
  m._testError = ''
  if (!m.query || !String(m.query).trim()) { m._testError = '请先填写 PromQL 查询语句'; return }
  testingId.value = m.id || String(i)
  try {
    const r = await request.post('/api/monitor/metric-test', {
      query: m.query,
      unit: m.unit,
      instance: props.instance || 'all',
      range: '1h'
    }, { timeout: 60000 })
    const d = r.data || {}
    if (d.error) m._testError = d.error
    else m._test = { series: d.seriesCount || 0, latest: d.latest, label: d.latestLabel }
  } catch (e: any) {
    m._testError = e?.message || '测试请求失败'
  } finally {
    testingId.value = ''
  }
}

function clean(m: any) {
  const out: any = {}
  for (const k of ['id', 'name', 'query', 'unit', 'type', 'span', 'color', 'highlight', 'enabled', 'description']) {
    out[k] = m[k]
  }
  return out
}

async function save() {
  for (let i = 0; i < draft.customMetrics.length; i++) {
    const m = draft.customMetrics[i]
    if (!m.name || !String(m.name).trim()) { ElMessage.warning(`第 ${i + 1} 个指标缺少名称`); return }
    if (!m.query || !String(m.query).trim()) { ElMessage.warning(`指标“${m.name}”缺少 PromQL 查询语句`); return }
  }
  saving.value = true
  try {
    const payload = {
      cards: clone(draft.cards),
      charts: clone(draft.charts),
      hostTable: clone(draft.hostTable),
      customMetrics: draft.customMetrics.map(clean)
    }
    const r = await request.put('/api/monitor/panel-config', payload)
    ElMessage.success('监控面板配置已保存')
    emit('saved', r.data || payload)
    emit('update:modelValue', false)
  } catch (e: any) {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    saving.value = false
  }
}

function unitLabel(u: string) {
  return ({ pct: '%', bps: 'bps', bytes: 'Bytes', num: '数值', ms: 'ms', count: '数量' } as any)[u] || u || '数值'
}

function fmtUnit(v: any, u: string) {
  if (v == null) return '-'
  const n = Number(v)
  if (!isFinite(n)) return '-'
  if (u === 'pct') return n.toFixed(1).replace(/\.0$/, '') + '%'
  if (u === 'bps') return bpsText(n)
  if (u === 'bytes') return bytesText(n)
  if (u === 'ms') return n.toFixed(0) + ' ms'
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function bpsText(v: number) {
  if (v >= 1e9) return (v / 1e9).toFixed(1) + ' Gbps'
  if (v >= 1e6) return (v / 1e6).toFixed(1) + ' Mbps'
  if (v >= 1e3) return (v / 1e3).toFixed(1) + ' Kbps'
  return v.toFixed(0) + ' bps'
}

function bytesText(v: number) {
  if (v >= 1024 ** 4) return (v / 1024 ** 4).toFixed(1) + ' TiB'
  if (v >= 1024 ** 3) return (v / 1024 ** 3).toFixed(1) + ' GiB'
  if (v >= 1024 ** 2) return (v / 1024 ** 2).toFixed(1) + ' MiB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KiB'
  return v.toFixed(0) + ' B'
}
</script>

<style scoped>
.cfg-section { margin-bottom: 18px; }
.cfg-title { font-size: 13px; font-weight: 600; color: #303133; margin-bottom: 8px; }
.cfg-checks { display: flex; flex-wrap: wrap; gap: 8px 22px; }
.cfg-row { display: flex; align-items: center; gap: 12px; padding: 4px 0; }
.preset-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.preset-row .limit { color: #909399; font-size: 12px; margin-left: auto; }
.empty-metrics { padding: 6px 0 10px; }
.metric-card { margin-bottom: 12px; border-radius: 8px; }
.metric-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.metric-head .grow, .metric-row .grow, .cfg-footer .grow { flex: 1; }
.metric-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
.metric-row .lbl { font-size: 12px; color: #909399; }
.test-ok { margin-top: 8px; font-size: 12px; color: #529b2e; }
.test-err { margin-top: 8px; }
.cfg-footer { display: flex; align-items: center; width: 100%; }

/* 科技风弹窗 */
:global(.monitor-cfg-dialog.dark .el-dialog) {
  background: linear-gradient(160deg, #0f172a, #080d1a);
  border: 1px solid rgba(148, 163, 184, .18);
  color: #cbd5e1;
}
:global(.monitor-cfg-dialog.dark .el-dialog__title) { color: #e2e8f0; }
:global(.monitor-cfg-dialog.dark .cfg-title) { color: #cbd5e1; }
:global(.monitor-cfg-dialog.dark .cfg-row .el-checkbox__label),
:global(.monitor-cfg-dialog.dark .cfg-checks .el-checkbox__label) { color: #cbd5e1; }
:global(.monitor-cfg-dialog.dark .metric-card) {
  background: rgba(15, 23, 42, .72);
  border-color: rgba(148, 163, 184, .16);
}
:global(.monitor-cfg-dialog.dark .el-textarea__inner),
:global(.monitor-cfg-dialog.dark .el-input__wrapper),
:global(.monitor-cfg-dialog.dark .el-select__wrapper) {
  background: rgba(8, 13, 26, .85);
  box-shadow: 0 0 0 1px rgba(148, 163, 184, .18) inset;
  color: #cbd5e1;
}
:global(.monitor-cfg-dialog.dark .el-textarea__inner) { color: #cbd5e1; }
:global(.monitor-cfg-dialog.dark .el-dialog__footer) { border-top-color: rgba(148, 163, 184, .12); }
</style>