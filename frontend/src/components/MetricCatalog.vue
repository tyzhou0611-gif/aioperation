<template>
  <div class="metric-catalog">
    <div class="cat-toolbar">
      <el-input v-model="keyword" placeholder="搜索编码/名称/说明" clearable style="width:260px" />
      <el-button @click="load">刷新</el-button>
      <el-button v-permission="'settings:source'" type="primary" @click="openCreate">新增指标</el-button>
      <span class="cat-count">{{ filtered.length }} / {{ rows.length }}</span>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
              title="指标注册中心：统一维护 Prometheus / HTTP JSON / Push 指标，面板、告警、诊断和 Copilot 共用同一份定义。" />

    <el-table :data="filtered" stripe size="small" style="width:100%" v-loading="loading">
      <el-table-column prop="code" label="编码" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <code class="code">{{ row.code }}</code>
          <el-tag v-if="row.builtin === 1" size="small" type="info" effect="plain" style="margin-left:6px">内置</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
      <el-table-column label="数据源" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="sourceTag(row.sourceType)" effect="plain">{{ sourceLabel(row.sourceType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="80" />
      <el-table-column label="标签" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ labelsText(row.labelsJson) }}</template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled === 1 ? 'success' : 'info'">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openPreview(row)">预览</el-button>
          <el-button v-permission="'settings:source'" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.builtin !== 1" v-permission="'settings:source'" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑 -->
    <el-dialog v-model="formVisible" :title="isEdit ? '编辑指标' : '新增指标'" width="820px" top="5vh"
               :close-on-click-modal="false" destroy-on-close>
      <el-form :model="form" label-width="110px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="指标编码" required>
              <el-input v-model="form.code" :disabled="isEdit && form.builtin === 1"
                        placeholder="如 node_cpu_usage / order_qps" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="指标名称" required>
              <el-input v-model="form.name" placeholder="如 订单 QPS" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="数据源" required>
              <el-select v-model="form.sourceType" style="width:100%">
                <el-option label="Prometheus（PromQL）" value="prometheus" />
                <el-option label="HTTP JSON（外部接口）" value="http_json" />
                <el-option label="Push（外部推送）" value="push" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="单位">
              <el-select v-model="form.unit" style="width:100%">
                <el-option label="百分比 %" value="pct" />
                <el-option label="比特/秒 bps" value="bps" />
                <el-option label="字节 Bytes" value="bytes" />
                <el-option label="数值" value="num" />
                <el-option label="毫秒 ms" value="ms" />
                <el-option label="数量" value="count" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="指标类型">
              <el-select v-model="form.valueType" style="width:100%">
                <el-option label="Gauge（瞬时值）" value="gauge" />
                <el-option label="Counter（累计值）" value="counter" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item v-if="form.sourceType === 'prometheus'" label="PromQL" required>
          <el-input v-model="form.query" type="textarea" :rows="4" maxlength="2000" show-word-limit
                    placeholder='例如：sum by(instance) (rate(node_cpu_seconds_total{mode="idle"}[15m]))' />
          <div class="hint">支持 $instance / $env / $project 等变量占位，查询时按 vars 替换</div>
        </el-form-item>

        <template v-else-if="form.sourceType === 'http_json'">
          <el-form-item label="接口地址" required>
            <el-input v-model="form.query" placeholder="https://api.example.com/metrics?app=order" />
          </el-form-item>
          <el-form-item label="请求配置" required>
            <el-input v-model="form.configJson" type="textarea" :rows="5"
                      placeholder='{"method":"GET","headers":{"Authorization":"Bearer xxx"},"jsonPath":"data.value"}' />
            <div class="hint">jsonPath 支持点路径与数组下标，如 data.items.0.value；当前值型指标，查询返回最新一次取值</div>
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="推送说明">
            <div class="push-hint">
              该指标由外部系统推送，保存后调用：
              <code>POST /api/metrics/push</code>
              <pre>{
  "metric": "{{ form.code || 'your_metric_code' }}",
  "labels": {"app": "order", "env": "prod"},
  "value": 123.4
}</pre>
            </div>
          </el-form-item>
        </template>

        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="标签">
              <el-input v-model="form.labelsText" placeholder="instance, app, env（逗号分隔）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="指标范围">
              <el-select v-model="form.scopeType" style="width:100%">
                <el-option label="全局" value="GLOBAL" />
                <el-option label="项目" value="PROJECT" />
                <el-option label="服务器" value="ASSET" />
                <el-option label="中间件" value="MIDDLEWARE" />
                <el-option label="应用" value="APP" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="范围 ID">
              <el-input-number v-model="form.scopeId" :min="1" :controls="false" style="width:100%" placeholder="可选" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="警告阈值">
              <el-input-number v-model="form.warning" :controls="false" style="width:100%" placeholder="可选" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="严重阈值">
              <el-input-number v-model="form.critical" :controls="false" style="width:100%" placeholder="可选" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button :loading="testing" @click="previewDraft">测试查询</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 查询预览 -->
    <el-dialog v-model="previewVisible" title="指标查询预览" width="880px" top="6vh" destroy-on-close>
      <div v-if="preview" class="preview-meta">
        <div class="meta-row"><span>指标</span><b>{{ preview.name }}（{{ preview.code }}）</b></div>
        <div class="meta-row"><span>数据源</span><el-tag size="small" effect="plain">{{ sourceLabel(preview.source) }}</el-tag></div>
        <div class="meta-row"><span>查询</span><code class="query">{{ preview.query }}</code></div>
        <div class="meta-row"><span>序列 / 最新值</span><b>{{ preview.seriesCount }} / {{ fmtUnit(preview.latest, preview.unit) }}
          <span v-if="preview.latestLabel" class="muted">（{{ preview.latestLabel }}）</span></b></div>
        <div class="meta-row"><span>耗时范围</span><span>{{ preview.step ? ('步长 ' + preview.step + 's') : '-' }}</span></div>
      </div>
      <el-alert v-if="preview && preview.error" type="error" :closable="false" show-icon
                :title="'查询失败：' + preview.error" style="margin-bottom:12px" />
      <v-chart v-if="previewOption" :option="previewOption" autoresize class="preview-chart" />
      <el-empty v-else-if="preview && !preview.error" description="暂无数据" :image-size="70" />
      <template #footer>
        <el-select v-model="previewRange" size="small" style="width:110px" @change="rerunPreview">
          <el-option label="1 小时" value="1h" />
          <el-option label="6 小时" value="6h" />
          <el-option label="24 小时" value="24h" />
        </el-select>
        <el-button size="small" :loading="previewLoading" @click="rerunPreview">重新查询</el-button>
        <el-button type="primary" @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent])

const rows = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const formVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const saving = ref(false)
const testing = ref(false)
const previewVisible = ref(false)
const previewLoading = ref(false)
const preview = ref<any>(null)
const previewDefinition = ref<any>(null)
const previewRange = ref('1h')

const form = reactive<any>({
  code: '', name: '', description: '', sourceType: 'prometheus', query: '', configJson: '',
  labelsText: 'instance', unit: 'pct', valueType: 'gauge', scopeType: 'GLOBAL', scopeId: null,
  warning: null, critical: null, enabled: true, builtin: 0
})

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return rows.value
  return rows.value.filter((r: any) =>
    [r.code, r.name, r.description].some(v => (v || '').toLowerCase().includes(k)))
})

async function load() {
  loading.value = true
  try {
    const r = await request.get('/api/metrics/definitions')
    rows.value = r.data || []
  } finally { loading.value = false }
}

function resetForm() {
  Object.assign(form, {
    code: '', name: '', description: '', sourceType: 'prometheus', query: '', configJson: '',
    labelsText: 'instance', unit: 'pct', valueType: 'gauge', scopeType: 'GLOBAL', scopeId: null,
    warning: null, critical: null, enabled: true, builtin: 0
  })
}

function openCreate() {
  isEdit.value = false
  editId.value = null
  resetForm()
  formVisible.value = true
}

function openEdit(row: any) {
  isEdit.value = true
  editId.value = row.id
  const thresholds = parseJson(row.thresholdsJson) || {}
  Object.assign(form, {
    code: row.code || '',
    name: row.name || '',
    description: row.description || '',
    sourceType: row.sourceType || 'prometheus',
    query: row.query || '',
    configJson: row.configJson || '',
    labelsText: labelsText(row.labelsJson),
    unit: row.unit || 'num',
    valueType: row.valueType || 'gauge',
    scopeType: row.scopeType || 'GLOBAL',
    scopeId: row.scopeId || null,
    warning: thresholds.warning ?? null,
    critical: thresholds.critical ?? null,
    enabled: row.enabled === 1,
    builtin: row.builtin || 0
  })
  formVisible.value = true
}

function buildPayload() {
  const labels = form.labelsText.split(',').map((s: string) => s.trim()).filter((s: string) => s.length > 0)
  const thresholds: any = {}
  if (form.warning != null && form.warning !== '') thresholds.warning = Number(form.warning)
  if (form.critical != null && form.critical !== '') thresholds.critical = Number(form.critical)
  return {
    code: form.code.trim(),
    name: form.name.trim(),
    description: form.description,
    sourceType: form.sourceType,
    query: form.query,
    configJson: form.configJson || null,
    labelsJson: JSON.stringify(labels),
    unit: form.unit,
    valueType: form.valueType,
    scopeType: form.scopeType,
    scopeId: form.scopeId || null,
    thresholdsJson: Object.keys(thresholds).length ? JSON.stringify(thresholds) : null,
    enabled: form.enabled ? 1 : 0
  }
}

async function save() {
  if (!form.code || !form.name) { ElMessage.warning('请填写指标编码和名称'); return }
  if (form.sourceType !== 'push' && !form.query) { ElMessage.warning('请填写查询语句或接口地址'); return }
  saving.value = true
  try {
    const payload = buildPayload()
    if (isEdit.value && editId.value != null) {
      await request.put(`/api/metrics/definitions/${editId.value}`, payload)
    } else {
      await request.post('/api/metrics/definitions', payload)
    }
    ElMessage.success('指标已保存')
    formVisible.value = false
    await load()
  } finally { saving.value = false }
}

async function remove(row: any) {
  try {
    await ElMessageBox.confirm(`确定删除指标「${row.name}」吗？`, '删除确认', { type: 'warning' })
  } catch { return }
  await request.delete(`/api/metrics/definitions/${row.id}`)
  ElMessage.success('已删除')
  load()
}

async function openPreview(row: any) {
  previewDefinition.value = { code: row.code }
  preview.value = null
  previewVisible.value = true
  await runPreview()
}

async function previewDraft() {
  previewDefinition.value = buildPayload()
  preview.value = null
  previewVisible.value = true
  await runPreview()
}

async function rerunPreview() {
  if (!previewVisible.value) return
  await runPreview()
}

async function runPreview() {
  previewLoading.value = true
  try {
    const d = previewDefinition.value || {}
    if (d.id || (d.code && !d.sourceType)) {
      const r = await request.post('/api/metrics/query', { code: d.code, range: previewRange.value, instance: 'all' })
      preview.value = r.data
    } else {
      const r = await request.post('/api/metrics/preview', {
        definition: d, range: previewRange.value, instance: 'all'
      })
      preview.value = r.data
    }
  } catch (e: any) {
    preview.value = { error: e?.message || '查询失败', seriesCount: 0 }
  } finally { previewLoading.value = false }
}

const previewOption = computed(() => {
  const p = preview.value
  if (!p || !p.series?.length || !p.times?.length) return null
  const palette = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#8e44ad', '#00bcd4', '#ff9800', '#3f51b5']
  const span = (p.times[p.times.length - 1] || 0) - (p.times[0] || 0)
  const fmt = (t: number) => {
    const d = new Date(t)
    const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
    return span > 86400000 ? `${d.getMonth() + 1}-${d.getDate()} ${hm}` : hm
  }
  return {
    color: palette,
    tooltip: { trigger: 'axis', valueFormatter: (v: any) => fmtUnit(v, p.unit) },
    legend: { type: 'scroll', bottom: 0, textStyle: { fontSize: 11 } },
    grid: { left: 60, right: 24, top: 18, bottom: 44 },
    xAxis: { type: 'category', data: p.times.map(fmt), boundaryGap: false,
      axisLabel: { fontSize: 10, hideOverlap: true } },
    yAxis: { type: 'value', min: 0, max: p.unit === 'pct' ? 100 : undefined,
      axisLabel: { formatter: (v: number) => fmtUnit(v, p.unit) } },
    series: p.series.map((s: any) => ({
      name: s.name, type: 'line', smooth: true, showSymbol: p.times.length < 3,
      connectNulls: true, data: s.data, areaStyle: { opacity: 0.06 }
    }))
  }
})

function parseJson(s: any) {
  if (!s) return null
  try { return typeof s === 'string' ? JSON.parse(s) : s } catch { return null }
}

function labelsText(raw: any) {
  const v = parseJson(raw)
  return Array.isArray(v) ? v.join(', ') : ''
}

function fmtTime(t: any) { return t ? String(t).replace('T', ' ').slice(0, 19) : '-' }

function sourceLabel(s: string) {
  return ({ prometheus: 'Prometheus', http_json: 'HTTP JSON', push: 'Push' } as any)[s] || s || '-'
}

function sourceTag(s: string): 'primary' | 'warning' | 'success' {
  return s === 'prometheus' ? 'primary' : s === 'http_json' ? 'warning' : 'success'
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

onMounted(load)
</script>

<style scoped>
.metric-catalog { min-height: 300px; }
.cat-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.cat-count { margin-left: auto; color: #909399; font-size: 12px; }
.code { font-family: Consolas, Monaco, monospace; color: #409eff; }
.hint { font-size: 12px; color: #909399; margin-top: 4px; line-height: 1.5; }
.push-hint { font-size: 12px; color: #606266; line-height: 1.7; }
.push-hint pre {
  margin: 6px 0 0; padding: 8px 10px; background: #f5f7fa; border-radius: 6px;
  font-family: Consolas, Monaco, monospace; font-size: 12px; white-space: pre-wrap;
}
.preview-meta { margin-bottom: 12px; }
.meta-row { display: flex; align-items: flex-start; gap: 10px; padding: 4px 0; font-size: 13px; }
.meta-row > span:first-child { width: 92px; color: #909399; flex-shrink: 0; }
.meta-row .query { word-break: break-all; color: #409eff; font-family: Consolas, Monaco, monospace; }
.muted { color: #909399; font-weight: 400; }
.preview-chart { height: 320px; width: 100%; }
</style>