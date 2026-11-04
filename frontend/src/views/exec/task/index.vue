<template>
  <div class="exec-page">
    <div class="page-head">
      <div>
        <h2>命令执行</h2>
        <div class="sub">多机并发 · 实时流式输出 · 危险命令护栏</div>
      </div>
      <el-tag v-if="running" type="warning" effect="dark">执行中…</el-tag>
    </div>

    <el-card shadow="never">
      <div class="section-label">目标主机 <span class="hint">已选 {{ selected.length }} 台</span></div>
      <div class="filter-row">
        <el-input v-model="serverKeyword" size="small" placeholder="搜索主机名/IP" clearable style="width:230px" />
        <el-select v-model="envFilter" size="small" clearable placeholder="全部环境" style="width:140px">
          <el-option v-for="e in envOptions" :key="e.value" :label="e.label" :value="e.value">
            <EnvTag :env="e.value" />
          </el-option>
        </el-select>
        <el-button size="small" @click="selectFiltered">全选当前</el-button>
        <el-button size="small" @click="selected = []">清空</el-button>
      </div>
      <el-select v-model="selected" multiple filterable collapse-tags placeholder="选择目标主机（支持按环境/关键字过滤）" style="width:100%">
        <el-option-group v-for="g in groupedServers" :key="g.env" :label="g.label">
          <el-option v-for="s in g.servers" :key="s.id"
                     :label="`${s.hostname || s.ip} (${s.ip}) · ${s.status || 'UNKNOWN'}`" :value="s.id" />
        </el-option-group>
      </el-select>

      <div class="section-label" style="margin-top:18px">命令</div>
      <div class="template-row">
        <el-select v-model="templateId" size="small" clearable filterable placeholder="选择命令模板" style="width:260px" @change="onTemplateChange">
          <el-option v-for="t in templates" :key="t.id" :label="t.name" :value="t.id">
            <span>{{ t.name }}</span>
            <span class="hint" style="margin-left:8px">{{ t.description }}</span>
          </el-option>
        </el-select>
        <el-button v-permission="'exec:template:save'" size="small" @click="saveTemplateVisible = true">保存为模板</el-button>
        <template v-if="templateParams.length">
          <el-input v-for="p in templateParams" :key="p" v-model="paramValues[p]" size="small"
                    :placeholder="p" style="width:130px" />
          <el-button size="small" type="primary" plain @click="applyTemplate">应用参数</el-button>
        </template>
      </div>
      <el-input v-model="command" type="textarea" :rows="4" class="cmd-input"
                placeholder="输入 Shell 命令，例如：df -h；支持 {param} 占位符模板" />
      <div class="run-row">
        <el-checkbox v-model="dryRun">Dry-run 预演</el-checkbox>
        <el-button v-permission="'exec:run'" type="primary" :loading="running" :disabled="!selected.length || !command.trim()" @click="startRun()">执行</el-button>
        <el-button v-permission="'exec:run'" v-if="running" type="danger" plain @click="killRun">终止</el-button>
        <el-button v-permission="'exec:run'" v-if="finished && failedIds.length" type="warning" plain @click="retryFailed">重试失败主机 ({{ failedIds.length }})</el-button>
        <el-tag v-if="lastRisk" size="small" :type="riskTagType(lastRisk)">风险：{{ lastRisk.riskLevel }}</el-tag>
        <span v-if="lastRisk && lastRisk.production" class="hint">包含生产环境主机</span>
      </div>
      <el-alert v-if="lastRisk && lastRisk.riskLevel !== 'LOW'" :type="lastRisk.blocked ? 'error' : 'warning'"
                :closable="false" show-icon style="margin-top:8px" :title="lastRisk.message" />
    </el-card>

    <el-card shadow="never" class="result-card">
      <el-tabs v-model="tab" @tab-change="onTabChange">
        <el-tab-pane label="执行结果" name="result">
          <div v-if="results.length">
            <div class="result-bar">
              <span>运行 #{{ runId || '-' }} · {{ successCount }} 成功 / {{ failCount }} 失败 · {{ lastDuration }} ms</span>
              <div v-if="results.length > 1" class="diff-selects">
                <span class="hint">Diff：</span>
                <el-select v-model="diffA" size="small" style="width:160px">
                  <el-option v-for="r in results" :key="r.serverId" :label="r.hostname || r.ip" :value="r.serverId" />
                </el-select>
                <span class="hint">↔</span>
                <el-select v-model="diffB" size="small" style="width:160px">
                  <el-option v-for="r in results" :key="r.serverId" :label="r.hostname || r.ip" :value="r.serverId" />
                </el-select>
              </div>
            </div>

            <el-table :data="results" row-key="serverId" stripe size="small" style="width:100%">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="row-detail">
                    <el-tabs v-model="rowTabs[row.serverId]">
                      <el-tab-pane label="原始输出" name="raw">
                        <pre class="output-box">{{ row.stdout || '' }}<template v-if="row.stderr">\n[stderr]\n{{ row.stderr }}</template><template v-if="row.error">\n[error]\n{{ row.error }}</template></pre>
                      </el-tab-pane>
                      <el-tab-pane v-if="jsonOf(row)" label="JSON" name="json">
                        <pre class="output-box">{{ jsonOf(row) }}</pre>
                      </el-tab-pane>
                      <el-tab-pane v-if="tableOf(row)" label="表格" name="table">
                        <el-table :data="tableOf(row)!.rows" size="small" border max-height="360">
                          <el-table-column v-for="(h, i) in tableOf(row)!.headers" :key="i" :prop="'c' + i" :label="h" min-width="100" show-overflow-tooltip />
                        </el-table>
                      </el-tab-pane>
                    </el-tabs>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="主机" min-width="190">
                <template #default="{ row }">{{ row.hostname || '-' }} <span class="hint">{{ row.ip }}</span></template>
              </el-table-column>
              <el-table-column prop="environment" label="环境" width="100">
                <template #default="{ row }"><EnvTag :env="row.environment" /></template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="退出码" width="90" align="center">
                <template #default="{ row }">{{ row.exitCode == null ? '-' : row.exitCode }}</template>
              </el-table-column>
              <el-table-column label="耗时" width="100" align="right">
                <template #default="{ row }">{{ row.durationMs == null ? '-' : row.durationMs + ' ms' }}</template>
              </el-table-column>
              <el-table-column label="输出摘要" min-width="280" show-overflow-tooltip>
                <template #default="{ row }">{{ summaryOf(row) }}</template>
              </el-table-column>
            </el-table>

            <div v-if="diffLines.length" class="diff-box">
              <div class="diff-title">Diff：{{ hostName(diffA) }} ↔ {{ hostName(diffB) }}</div>
              <div v-for="(d, i) in diffLines" :key="i" :class="['diff-line', d.type]">
                <span class="sign">{{ d.type === 'add' ? '+' : d.type === 'del' ? '-' : ' ' }}</span>{{ d.text }}
              </div>
            </div>
          </div>
          <el-empty v-else description="选择主机并执行命令，结果将在这里实时聚合" />
        </el-tab-pane>

        <el-tab-pane label="执行历史" name="history">
          <div class="filter-row" style="margin-bottom:12px">
            <el-input v-model="historyKeyword" size="small" placeholder="命令/操作人关键字" clearable style="width:220px" @keyup.enter="loadHistory" />
            <el-select v-model="historyServerId" size="small" clearable filterable placeholder="主机" style="width:190px">
              <el-option v-for="s in servers" :key="s.id" :label="`${s.hostname || s.ip} (${s.ip})`" :value="s.id" />
            </el-select>
            <el-date-picker v-model="historyRange" type="datetimerange" size="small" style="width:360px"
                            start-placeholder="开始时间" end-placeholder="结束时间"
                            value-format="YYYY-MM-DD HH:mm:ss" />
            <el-button size="small" type="primary" @click="loadHistory">查询</el-button>
          </div>
          <el-table :data="history" size="small" stripe style="width:100%" v-loading="historyLoading">
            <el-table-column prop="startTime" label="时间" width="165">
              <template #default="{ row }">{{ fmtTime(row.startTime || row.createTime) }}</template>
            </el-table-column>
            <el-table-column prop="operator" label="操作人" width="100" />
            <el-table-column prop="command" label="命令" min-width="260" show-overflow-tooltip />
            <el-table-column label="主机" width="90" align="center">
              <template #default="{ row }">{{ row.successCount }}/{{ row.serverCount }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="90" align="right">
              <template #default="{ row }">{{ row.durationMs == null ? '-' : row.durationMs + ' ms' }}</template>
            </el-table-column>
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag :type="row.dryRun === 1 ? 'info' : row.failCount > 0 ? 'warning' : 'success'" size="small">
                  {{ row.dryRun === 1 ? 'DRY-RUN' : (row.failCount > 0 ? '部分失败' : '成功') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openHistoryDetail(row.id)">详情</el-button>
                <el-button link type="success" size="small" @click="exportHistory(row.id)">导出</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div style="display:flex;justify-content:flex-end;margin-top:12px">
            <el-pagination v-model:current-page="historyPage" :page-size="historyPageSize" :total="historyTotal"
                           layout="total, prev, pager, next" @current-change="loadHistory" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 保存模板 -->
    <el-dialog v-model="saveTemplateVisible" title="保存为命令模板" width="520px">
      <el-form label-width="90px">
        <el-form-item label="模板名称"><el-input v-model="templateForm.name" placeholder="如：重启 Nginx" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="templateForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="命令"><el-input v-model="templateForm.command" type="textarea" :rows="4" /></el-form-item>
      </el-form>
      <div class="hint">支持 {param} 占位符，例如：systemctl restart {service}</div>
      <template #footer>
        <el-button @click="saveTemplateVisible = false">取消</el-button>
        <el-button type="primary" @click="saveTemplate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 历史详情 -->
    <el-dialog v-model="historyDetailVisible" title="执行详情" width="920px" top="6vh">
      <div v-if="historyDetail">
        <div class="detail-meta">
          <span>#{{ historyDetail.id }}</span>
          <span>{{ fmtTime(historyDetail.startTime) }}</span>
          <span>操作人：{{ historyDetail.operator }}</span>
          <span>{{ historyDetail.successCount }} 成功 / {{ historyDetail.failCount }} 失败</span>
        </div>
        <pre class="cmd-echo">{{ historyDetail.command }}</pre>
        <el-table :data="historyDetail.hosts || []" size="small" stripe>
          <el-table-column type="expand">
            <template #default="{ row }">
              <pre class="output-box">{{ row.stdout || '' }}<template v-if="row.stderr">\n[stderr]\n{{ row.stderr }}</template><template v-if="row.errorMsg">\n[error]\n{{ row.errorMsg }}</template></pre>
            </template>
          </el-table-column>
          <el-table-column prop="hostname" label="主机" min-width="150" />
          <el-table-column prop="ip" label="IP" width="140" />
          <el-table-column prop="environment" label="环境" width="100">
                <template #default="{ row }"><EnvTag :env="row.environment" /></template>
              </el-table-column>
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="row.success === 1 ? 'success' : 'danger'" size="small">{{ row.success === 1 ? '成功' : '失败' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="exitCode" label="退出码" width="90" align="center" />
          <el-table-column prop="durationMs" label="耗时(ms)" width="100" align="right" />
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import EnvTag from '@/components/EnvTag.vue'

interface ServerItem { id: number; hostname: string; ip: string; environment: string; status: string }
interface Row {
  serverId: number; hostname: string; ip: string; environment: string
  status: string; exitCode: number | null; durationMs: number | null
  stdout: string; stderr: string; error: string | null; killed: boolean
}

const route = useRoute()
const servers = ref<ServerItem[]>([])
const selected = ref<number[]>([])
const serverKeyword = ref('')
const envFilter = ref('')
const command = ref('')
const dryRun = ref(false)
const running = ref(false)
const finished = ref(false)
const results = ref<Row[]>([])
const rowTabs = reactive<Record<number, string>>({})
const runId = ref<number | null>(null)
const lastDuration = ref(0)
const lastRisk = ref<any>(null)
const lastPreviewToken = ref('')
const requestId = ref('')
const tab = ref('result')
const diffA = ref<number | null>(null)
const diffB = ref<number | null>(null)

const templates = ref<any[]>([])
const templateId = ref<number | null>(null)
const templateParams = ref<string[]>([])
const paramValues = reactive<Record<string, string>>({})
const saveTemplateVisible = ref(false)
const templateForm = reactive({ name: '', description: '', command: '' })

const history = ref<any[]>([])
const historyTotal = ref(0)
const historyPage = ref(1)
const historyPageSize = 10
const historyKeyword = ref('')
const historyServerId = ref<number | null>(null)
const historyRange = ref<string[] | null>(null)
const historyLoading = ref(false)
const historyDetailVisible = ref(false)
const historyDetail = ref<any>(null)

let activeWs: WebSocket | null = null
let activeRequestId = ''

const ENVS: Record<string, string> = { production: '生产', staging: '预发', test: '测试', dev: '开发' }
const envOptions = computed(() => {
  const set = new Set<string>()
  servers.value.forEach(s => set.add(s.environment || 'production'))
  return [...set].map(v => ({ value: v, label: ENVS[v] || v }))
})

const filteredServers = computed(() => {
  const k = serverKeyword.value.trim().toLowerCase()
  return servers.value.filter(s => {
    if (envFilter.value && s.environment !== envFilter.value) return false
    if (!k) return true
    return [s.hostname, s.ip, s.environment].some(v => (v || '').toLowerCase().includes(k))
  })
})

const groupedServers = computed(() => {
  const map = new Map<string, ServerItem[]>()
  filteredServers.value.forEach(s => {
    const env = s.environment || 'production'
    if (!map.has(env)) map.set(env, [])
    map.get(env)!.push(s)
  })
  return [...map.entries()].map(([env, list]) => ({ env, label: `${ENVS[env] || env} (${list.length})`, servers: list }))
})

const successCount = computed(() => results.value.filter(r => r.status === 'SUCCESS').length)
const failCount = computed(() => results.value.filter(r => r.status === 'FAILED' || r.status === 'KILLED').length)
const failedIds = computed(() => results.value.filter(r => r.status === 'FAILED' || r.status === 'KILLED').map(r => r.serverId))
const diffLines = computed(() => {
  if (!diffA.value || !diffB.value || diffA.value === diffB.value) return []
  const a = results.value.find(r => r.serverId === diffA.value)
  const b = results.value.find(r => r.serverId === diffB.value)
  if (!a || !b) return []
  return lineDiff(a.stdout || '', b.stdout || '')
})

function statusLabel(s: string) {
  return { PENDING: '等待', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败', KILLED: '已终止' }[s] || s
}
function statusTag(s: string) {
  return s === 'SUCCESS' ? 'success' : s === 'FAILED' ? 'danger' : s === 'KILLED' ? 'warning' : 'info'
}
function riskTagType(r: any) {
  return r?.blocked ? 'danger' : r?.riskLevel === 'HIGH' ? 'warning' : 'success'
}
function fmtTime(t?: string) { return t ? t.replace('T', ' ').substring(0, 19) : '-' }
function hostName(id: number | null) {
  const r = results.value.find(x => x.serverId === id)
  return r ? (r.hostname || r.ip) : '-'
}

function uid() {
  return 'r' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

function makeRow(s: ServerItem, status = 'PENDING'): Row {
  rowTabs[s.id] = 'raw'
  return {
    serverId: s.id, hostname: s.hostname, ip: s.ip, environment: s.environment || 'production',
    status, exitCode: null, durationMs: null, stdout: '', stderr: '', error: null, killed: false
  }
}

function summaryOf(row: Row) {
  const text = (row.stdout || row.stderr || row.error || '').trim()
  if (!text) return row.status === 'RUNNING' ? '执行中…' : '-'
  const line = text.split('\n').find(l => l.trim()) || ''
  return line.slice(0, 160)
}

function jsonOf(row: Row): string | null {
  const t = (row.stdout || '').trim()
  if (!t || (!t.startsWith('{') && !t.startsWith('['))) return null
  try { return JSON.stringify(JSON.parse(t), null, 2) } catch { return null }
}

function tableOf(row: Row): { headers: string[]; rows: any[] } | null {
  const text = (row.stdout || '').trim()
  if (!text) return null
  const lines = text.split('\n').map(l => l.replace(/\r$/, '')).filter(l => l.trim())
  if (lines.length < 2) return null
  const split = (l: string) => {
    let parts = l.trim().split(/\s{2,}|\t+/).filter(Boolean)
    if (parts.length < 2) parts = l.trim().split(/\s+/)
    return parts
  }
  const all = lines.map(split)
  const maxCols = Math.max(...all.map(r => r.length))
  if (maxCols < 2) return null
  const header = all[0].concat(Array(Math.max(0, maxCols - all[0].length)).fill(''))
  const rows = all.slice(1).map(parts => {
    const o: Record<string, string> = {}
    for (let i = 0; i < maxCols; i++) o['c' + i] = parts[i] || ''
    return o
  })
  return { headers: header, rows }
}

function lineDiff(a: string, b: string) {
  const A = a.split('\n').slice(0, 400)
  const B = b.split('\n').slice(0, 400)
  const n = A.length, m = B.length
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out: { type: string; text: string }[] = []
  let i = 0, j = 0
  while (i < n && j < m) {
    if (A[i] === B[j]) { out.push({ type: 'same', text: A[i] }); i++; j++ }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { out.push({ type: 'del', text: A[i] }); i++ }
    else { out.push({ type: 'add', text: B[j] }); j++ }
  }
  while (i < n) { out.push({ type: 'del', text: A[i++] }) }
  while (j < m) { out.push({ type: 'add', text: B[j++] }) }
  return out
}

async function loadServers() {
  const r = await request.get('/api/server/page', { params: { page: 1, pageSize: 500 } })
  servers.value = r.data?.records || []
  const sid = Number(route.query.serverId)
  if (sid && servers.value.some(s => s.id === sid)) selected.value = [sid]
}

async function loadTemplates() {
  const r = await request.get('/api/exec/templates', { params: { targetType: 'SERVER' } })
  templates.value = r.data || []
}

function onTemplateChange(id: number | null) {
  const t = templates.value.find(x => x.id === id)
  if (!t) { templateParams.value = []; return }
  command.value = t.command
  const names = [...new Set([...String(t.command).matchAll(/\{([A-Za-z0-9_]+)\}/g)].map(m => m[1]))]
  templateParams.value = names as string[]
  names.forEach(n => { if (paramValues[n] === undefined) paramValues[n] = '' })
  if (!names.length) ElMessage.success('已应用模板：' + t.name)
}

function applyTemplate() {
  let c = command.value
  templateParams.value.forEach(p => {
    const v = paramValues[p]
    if (v) c = c.split(`{${p}}`).join(v)
  })
  command.value = c
  ElMessage.success('参数已替换')
}

async function saveTemplate() {
  if (!templateForm.name.trim() || !templateForm.command.trim()) { ElMessage.warning('请填写模板名称和命令'); return }
  await request.post('/api/exec/templates', {
    name: templateForm.name.trim(),
    description: templateForm.description,
    command: templateForm.command,
    targetType: 'SERVER'
  })
  ElMessage.success('模板已保存')
  saveTemplateVisible.value = false
  templateForm.name = ''; templateForm.description = ''
  await loadTemplates()
}

function selectFiltered() {
  selected.value = [...new Set([...selected.value, ...filteredServers.value.map(s => s.id)])]
}

function killRun() {
  if (!activeWs || activeWs.readyState !== WebSocket.OPEN) return
  activeWs.send(JSON.stringify({ action: 'kill', requestId: activeRequestId }))
  ElMessage.info('已发送终止请求')
}

async function startRun(opts: any = {}) {
  if (running.value) return
  const ids: number[] = opts.ids || selected.value
  const cmd: string = command.value.trim()
  if (!ids.length) { ElMessage.warning('请选择目标主机'); return }
  if (!cmd) { ElMessage.warning('请输入命令'); return }

  let risk: any = null
  try {
    const c = await request.post('/api/exec/check', { command: cmd, serverIds: ids })
    risk = c.data || {}
  } catch { risk = null }
  if (risk) lastRisk.value = risk
  if (risk?.blocked) { ElMessage.error(risk.message || '命令被拦截'); return }

  let confirmDangerous = !!opts.confirmDangerous
  let dry = opts.dryRun !== undefined ? !!opts.dryRun : dryRun.value
  let previewToken = opts.previewToken || lastPreviewToken.value || ''

  if (risk?.requiresConfirm && !confirmDangerous) {
    try {
      await ElMessageBox.confirm(risk.message || '该命令具有破坏性，确定执行？', '高危命令确认',
        { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' })
      confirmDangerous = true
    } catch { return }
  }
  if (risk?.dryRunRequired && !dry && !previewToken) {
    try {
      await ElMessageBox.confirm('目标包含生产环境，高危命令必须先 dry-run 预演。是否先预演？', '生产环境护栏',
        { type: 'warning', confirmButtonText: '先预演', cancelButtonText: '取消' })
      dry = true
    } catch { return }
  }

  if (opts.ids) selected.value = ids
  await startStream(ids, cmd, dry, confirmDangerous, previewToken)
}

async function startStream(ids: number[], cmd: string, dry: boolean, confirmDangerous: boolean, previewToken: string) {
  const picked = servers.value.filter(s => ids.includes(s.id))
  results.value = picked.map(s => makeRow(s))
  rowTabsInit()
  running.value = true; finished.value = false
  runId.value = null; lastDuration.value = 0
  lastPreviewToken.value = ''
  requestId.value = uid()
  activeRequestId = requestId.value
  diffA.value = picked[0]?.id || null
  diffB.value = picked[1]?.id || null

  const token = localStorage.getItem('token') || ''
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  let opened = false
  let completed = false
  try {
    const ws = new WebSocket(`${proto}://${location.host}/ws/exec?token=${encodeURIComponent(token)}`)
    activeWs = ws
    ws.onopen = () => {
      opened = true
      ws.send(JSON.stringify({
        action: 'start', requestId: requestId.value, serverIds: ids,
        command: cmd, dryRun: dry, confirmDangerous, previewToken
      }))
    }
    ws.onmessage = (ev) => {
      try {
        const d = JSON.parse(ev.data)
        handleWsMessage(d, { ids, cmd, dry, confirmDangerous, previewToken, setCompleted: (v: boolean) => { completed = v } })
      } catch { }
    }
    ws.onerror = () => {
      if (!opened && !completed) {
        activeWs = null
        runViaRest(ids, cmd, dry, confirmDangerous, previewToken)
      }
    }
    ws.onclose = () => {
      if (!completed && opened && running.value) {
        running.value = false
        ElMessage.error('WebSocket 连接中断，请重试或查看执行历史')
      }
    }
  } catch {
    runViaRest(ids, cmd, dry, confirmDangerous, previewToken)
  }
}

function rowTabsInit() {
  results.value.forEach(r => { rowTabs[r.serverId] = 'raw' })
}

function handleWsMessage(d: any, ctx: any) {
  if (d.type === 'run_start') {
    runId.value = d.runId
    if (d.risk) lastRisk.value = d.risk
    if (d.previewToken) lastPreviewToken.value = d.previewToken
    if (Array.isArray(d.hosts)) {
      d.hosts.forEach((h: any) => {
        const row = results.value.find(r => r.serverId === h.serverId)
        if (row) { row.hostname = h.hostname || row.hostname; row.ip = h.ip || row.ip; row.environment = h.environment || row.environment }
      })
    }
    return
  }
  if (d.type === 'host_start') {
    const row = results.value.find(r => r.serverId === d.serverId)
    if (row) row.status = 'RUNNING'
    return
  }
  if (d.type === 'host_output') {
    const row = results.value.find(r => r.serverId === d.serverId)
    if (!row) return
    if (d.stream === 'stderr') row.stderr += d.data
    else row.stdout += d.data
    return
  }
  if (d.type === 'host_end') {
    const row = results.value.find(r => r.serverId === d.serverId)
    if (!row) return
    row.exitCode = d.exitCode
    row.durationMs = d.durationMs
    row.killed = !!d.killed
    row.status = d.killed ? 'KILLED' : (d.success ? 'SUCCESS' : 'FAILED')
    if (d.error) row.error = d.error
    if (ctx.dry && !row.stdout) row.stdout = '[DRY-RUN] 未实际执行，仅预览命令'
    return
  }
  if (d.type === 'run_end') {
    running.value = false
    finished.value = true
    runId.value = d.runId || runId.value
    lastDuration.value = d.durationMs || 0
    if (d.previewToken) lastPreviewToken.value = d.previewToken
    ctx.setCompleted(true)
    if (activeWs) { try { activeWs.close() } catch { } activeWs = null }
    if (tab.value === 'history') loadHistory()
    if (ctx.dry && lastPreviewToken.value) {
      ElMessageBox.confirm('Dry-run 预演完成，是否执行真实命令？', '预演完成', {
        type: 'info', confirmButtonText: '执行', cancelButtonText: '稍后'
      }).then(() => startRun({ ids: ctx.ids, dryRun: false, confirmDangerous: true, previewToken: lastPreviewToken.value }))
        .catch(() => { })
    }
    return
  }
  if (d.type === 'error') {
    running.value = false
    finished.value = false
    ctx.setCompleted(true)
    if (d.risk) lastRisk.value = d.risk
    ElMessage.error(d.message || '执行被拒绝')
    if (activeWs) { try { activeWs.close() } catch { } activeWs = null }
    return
  }
  if (d.type === 'kill_ack') {
    ElMessage.warning('已终止执行')
  }
}

async function runViaRest(ids: number[], cmd: string, dry: boolean, confirmDangerous: boolean, previewToken: string) {
  running.value = true
  try {
    const r = await request.post('/api/exec/batch', {
      serverIds: ids, command: cmd, dryRun: dry,
      confirmDangerous, previewToken
    })
    const d = r.data || {}
    if (d.rejected) {
      running.value = false
      if (d.risk) lastRisk.value = d.risk
      ElMessage.error(d.message || '执行被拒绝')
      return
    }
    runId.value = d.runId
    lastDuration.value = Math.max(0, ...(d.results || []).map((x: any) => x.durationMs || 0))
    if (d.previewToken) lastPreviewToken.value = d.previewToken
    results.value = (d.results || []).map((x: any) => ({
      serverId: x.serverId, hostname: x.hostname, ip: x.ip, environment: x.environment,
      status: x.killed ? 'KILLED' : (x.success ? 'SUCCESS' : 'FAILED'),
      exitCode: x.exitCode, durationMs: x.durationMs,
      stdout: x.stdout || (d.dryRun ? '[DRY-RUN] 未实际执行，仅预览命令' : ''),
      stderr: x.stderr || '', error: x.errorMessage || null, killed: !!x.killed
    }))
    rowTabsInit()
    diffA.value = results.value[0]?.serverId || null
    diffB.value = results.value[1]?.serverId || null
    running.value = false
    finished.value = true
    if (tab.value === 'history') loadHistory()
    if (d.dryRun && d.previewToken) {
      ElMessageBox.confirm('Dry-run 预演完成，是否执行真实命令？', '预演完成', {
        type: 'info', confirmButtonText: '执行', cancelButtonText: '稍后'
      }).then(() => startRun({ ids, dryRun: false, confirmDangerous: true, previewToken: d.previewToken }))
        .catch(() => { })
    }
  } catch (e: any) {
    running.value = false
    ElMessage.error(e?.message || '执行失败')
  }
}

function retryFailed() {
  if (!failedIds.value.length) return
  startRun({
    ids: failedIds.value,
    dryRun: false,
    confirmDangerous: true,
    previewToken: lastPreviewToken.value || ''
  })
}

function onTabChange(name: any) {
  if (name === 'history') loadHistory()
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const r = await request.get('/api/exec/history', {
      params: {
        page: historyPage.value, pageSize: historyPageSize,
        keyword: historyKeyword.value || undefined,
        serverId: historyServerId.value || undefined,
        start: historyRange.value?.[0] || undefined,
        end: historyRange.value?.[1] || undefined
      }
    })
    history.value = r.data?.records || []
    historyTotal.value = r.data?.total || 0
  } finally { historyLoading.value = false }
}

async function openHistoryDetail(id: number) {
  const r = await request.get(`/api/exec/history/${id}`)
  historyDetail.value = r.data
  historyDetailVisible.value = true
}

async function exportHistory(id: number) {
  const blob: any = await request.get(`/api/exec/history/${id}/export`, { responseType: 'blob' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = `exec-run-${id}.csv`; a.click()
  URL.revokeObjectURL(url)
}

watch(saveTemplateVisible, (v) => { if (v) templateForm.command = command.value })
watch(results, () => {
  if (!diffA.value && results.value[0]) diffA.value = results.value[0].serverId
  if (!diffB.value && results.value[1]) diffB.value = results.value[1].serverId
})

onMounted(async () => {
  await Promise.all([loadServers(), loadTemplates()])
})

onUnmounted(() => {
  if (activeWs) { try { activeWs.close() } catch { } }
})
</script>

<style scoped>
.exec-page { padding: 0 2px; }
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.page-head h2 { margin: 0; font-size: 22px; color: #333; }
.page-head .sub { color: #909399; font-size: 13px; margin-top: 4px; }
.section-label { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 8px; }
.hint { color: #909399; font-size: 12px; }
.filter-row, .template-row, .run-row, .result-bar, .diff-selects { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.template-row { margin-bottom: 10px; }
.cmd-input :deep(.el-textarea__inner) { font-family: Consolas, Monaco, monospace; font-size: 13px; line-height: 1.6; }
.run-row { margin-top: 10px; }
.result-card { margin-top: 14px; }
.result-bar { justify-content: space-between; margin-bottom: 10px; color: #606266; font-size: 13px; }
.row-detail { padding: 8px 12px; }
.output-box { margin: 0; padding: 10px; background: #0d1117; color: #c9d1d9; border-radius: 6px; font-size: 12px; line-height: 1.55; max-height: 380px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.cmd-echo { background: #0d1117; color: #c9d1d9; padding: 10px; border-radius: 6px; font-size: 12px; white-space: pre-wrap; }
.diff-box { margin-top: 14px; border: 1px solid #ebeef5; border-radius: 6px; overflow: hidden; max-height: 360px; overflow-y: auto; }
.diff-title { padding: 8px 12px; background: #f5f7fa; font-size: 12px; color: #606266; font-weight: 600; }
.diff-line { display: flex; font-family: Consolas, Monaco, monospace; font-size: 12px; padding: 1px 10px; white-space: pre-wrap; }
.diff-line.same { color: #606266; }
.diff-line.add { background: #e8f5e9; color: #2e7d32; }
.diff-line.del { background: #ffebee; color: #c62828; }
.diff-line .sign { width: 16px; flex: 0 0 16px; user-select: none; }
.detail-meta { display: flex; gap: 16px; flex-wrap: wrap; color: #606266; font-size: 13px; margin-bottom: 8px; }
</style>