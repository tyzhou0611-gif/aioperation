<template>
  <div class="m-page">
    <div class="m-page-inner">
      <div class="tabs">
        <button class="tab" :class="{ active: tab === 'exec' }" @click="tab = 'exec'">命令执行</button>
        <button class="tab" :class="{ active: tab === 'history' }" @click="openHistory">执行历史</button>
      </div>

      <!-- ============ 执行 ============ -->
      <template v-if="tab === 'exec'">
        <section class="m-card">
          <div class="m-card-head">
            <div class="m-title">目标主机 <span class="en">{{ selected.length }} SELECTED</span></div>
            <button class="link-btn" @click="hostPicker = true">选择</button>
          </div>
          <button class="target-box" @click="hostPicker = true">
            <template v-if="selectedHosts.length">
              <span v-for="s in selectedHosts.slice(0, 3)" :key="s.id" class="m-tag blue">{{ s.hostname || s.ip }}</span>
              <span v-if="selectedHosts.length > 3" class="m-tag gray">+{{ selectedHosts.length - 3 }}</span>
            </template>
            <span v-else class="placeholder">点击选择目标主机（可多选）</span>
            <el-icon class="chev"><ArrowRight /></el-icon>
          </button>

          <div class="m-card-head" style="margin-top:14px;margin-bottom:8px">
            <div class="m-title">命令模板 <span class="en">TEMPLATE</span></div>
            <span class="m-hint">可修改后执行</span>
          </div>
          <div class="m-chip-row">
            <button v-for="t in templates.slice(0, 12)" :key="t.id" class="m-chip" :class="{ active: templateId === t.id }" @click="applyTemplate(t)">
              {{ t.name }}{{ t.dangerous ? ' ⚠' : '' }}
            </button>
          </div>

          <div v-if="templateParams.length" class="param-row">
            <el-input v-for="p in templateParams" :key="p" v-model="paramValues[p]" :placeholder="p" size="small" style="flex:1;min-width:96px" />
            <el-button size="small" type="primary" plain @click="fillParams">应用参数</el-button>
          </div>

          <el-input
            v-model="command"
            type="textarea"
            :rows="4"
            class="cmd-input"
            placeholder="输入 Shell 命令，例如：df -h；支持 {param} 占位符"
          />

          <div class="run-row">
            <label class="dry-toggle">
              <el-switch v-model="dryRun" size="small" />
              <span>Dry-run 预演</span>
            </label>
            <span v-if="lastRisk" class="m-tag" :class="lastRisk.riskLevel === 'HIGH' ? 'red' : lastRisk.riskLevel === 'MEDIUM' ? 'orange' : 'green'">
              风险 {{ lastRisk.riskLevel }}
            </span>
          </div>

          <div v-if="lastRisk && lastRisk.riskLevel !== 'LOW'" class="risk-box" :class="lastRisk.blocked ? 'blocked' : ''">
            {{ lastRisk.message }}
          </div>

          <div class="run-btns">
            <button v-if="canRun" class="run-btn" :disabled="!selected.length || !command.trim() || running" @click="startRun()">
              <el-icon><CaretRight /></el-icon>{{ running ? '执行中…' : '执行命令' }}
            </button>
            <button v-if="running" class="kill-btn" @click="killRun">终止</button>
            <span v-if="!canRun" class="m-hint">当前角色没有命令执行权限</span>
          </div>
        </section>

        <!-- 执行结果 -->
        <section v-if="results.length" class="m-card">
          <div class="m-card-head">
            <div class="m-title">执行结果 <span class="en">{{ successCount }} OK / {{ failCount }} FAIL</span></div>
            <span v-if="runId" class="m-hint">#{{ runId }}</span>
          </div>
          <div class="result-list">
            <div v-for="r in results" :key="r.serverId" class="result-card" :class="r.status.toLowerCase()">
              <div class="res-top" @click="r.expanded = !r.expanded">
                <span class="m-dot" :class="resDot(r.status)"></span>
                <div class="res-main">
                  <div class="res-name">{{ r.hostname || r.ip }}</div>
                  <div class="m-hint">{{ r.ip }} · {{ statusText(r.status) }}<template v-if="r.exitCode != null"> · exit {{ r.exitCode }}</template><template v-if="r.durationMs != null"> · {{ r.durationMs }}ms</template></div>
                </div>
                <el-icon class="chev" :class="{ open: r.expanded }"><ArrowRight /></el-icon>
              </div>
              <div v-if="r.expanded" class="res-output">
                <pre>{{ r.stdout || '' }}<template v-if="r.stderr">\n[stderr]\n{{ r.stderr }}</template><template v-if="r.error">\n[error]\n{{ r.error }}</template><template v-if="!r.stdout && !r.stderr && !r.error && r.status === 'RUNNING'">执行中…</template></pre>
                <button class="copy-btn" @click="copyOutput(r)">复制输出</button>
              </div>
            </div>
          </div>
        </section>

        <div v-else class="m-empty">选择主机并输入命令，结果会实时显示</div>
      </template>

      <!-- ============ 历史 ============ -->
      <template v-else>
        <div class="m-searchbar">
          <el-input v-model="historyKeyword" placeholder="搜索命令 / 操作人" clearable :prefix-icon="Search" @keyup.enter="loadHistory" />
          <el-button class="refresh-btn" :loading="historyLoading" @click="loadHistory"><el-icon><Refresh /></el-icon></el-button>
        </div>
        <div v-if="history.length" class="m-list">
          <div v-for="h in history" :key="h.id" class="history-card" @click="openHistoryDetail(h.id)">
            <div class="h-top">
              <span class="m-tag" :class="h.dryRun === 1 ? 'gray' : h.failCount > 0 ? 'orange' : 'green'">
                {{ h.dryRun === 1 ? 'DRY-RUN' : h.failCount > 0 ? '部分失败' : '成功' }}
              </span>
              <span class="h-time">{{ fmtTime(h.startTime || h.createTime) }}</span>
              <span class="m-hint" style="margin-left:auto">{{ h.operator }}</span>
            </div>
            <div class="h-cmd">{{ h.command }}</div>
            <div class="m-hint">{{ h.successCount }}/{{ h.serverCount }} 台成功 · {{ h.durationMs || 0 }} ms</div>
          </div>
          <button class="load-more" :disabled="historyLoading || !historyHasMore" @click="loadHistoryMore">
            {{ historyLoading ? '加载中…' : historyHasMore ? '加载更多' : '没有更多了' }}
          </button>
        </div>
        <div v-else class="m-empty">暂无执行历史</div>
      </template>

      <!-- 主机选择 -->
      <el-drawer v-model="hostPicker" direction="btt" size="76%" title="选择目标主机">
        <div class="picker">
          <div class="picker-bar">
            <el-input v-model="hostKeyword" placeholder="搜索主机 / IP" clearable :prefix-icon="Search" size="small" style="flex:1" />
            <el-button size="small" @click="selectFiltered">全选</el-button>
            <el-button size="small" @click="selected = []">清空</el-button>
          </div>
          <div class="picker-list">
            <div v-for="s in filteredServers" :key="s.id" class="picker-item" @click="toggleServer(s.id)">
              <el-checkbox :model-value="selected.includes(s.id)" @change="(v: any) => setSelected(s.id, v)" @click.stop />
              <span class="m-dot" :class="statusClass(s.status)"></span>
              <div class="picker-main">
                <div class="picker-name">{{ s.hostname || s.ip }}</div>
                <div class="m-hint">{{ s.ip }} · {{ envLabel(s.environment) }} · {{ statusLabel(s.status) }}</div>
              </div>
            </div>
            <div v-if="!filteredServers.length" class="m-empty">暂无匹配主机</div>
          </div>
          <button class="picker-ok" @click="hostPicker = false">确定（{{ selected.length }} 台）</button>
        </div>
      </el-drawer>

      <!-- 历史详情 -->
      <el-drawer v-model="historyDetailVisible" direction="btt" size="88%" title="执行详情">
        <div v-if="historyDetail" class="detail">
          <div class="detail-meta">
            <span>#{{ historyDetail.id }}</span>
            <span>{{ fmtTime(historyDetail.startTime) }}</span>
            <span>{{ historyDetail.operator }}</span>
            <span>{{ historyDetail.successCount }} 成功 / {{ historyDetail.failCount }} 失败</span>
          </div>
          <pre class="detail-cmd">{{ historyDetail.command }}</pre>
          <div v-for="h in historyDetail.hosts || []" :key="h.id" class="detail-host">
            <div class="dh-top">
              <span class="m-dot" :class="h.success === 1 ? 'online' : 'offline'"></span>
              <b>{{ h.hostname || h.ip }}</b>
              <span class="m-hint">{{ h.ip }} · exit {{ h.exitCode }}</span>
            </div>
            <pre class="dh-output">{{ h.stdout || '' }}<template v-if="h.stderr">\n[stderr]\n{{ h.stderr }}</template><template v-if="h.errorMsg">\n[error]\n{{ h.errorMsg }}</template></pre>
          </div>
        </div>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { useUserStore } from '@/stores/user'
import {
  ArrowRight, Search, Refresh, CaretRight
} from '@element-plus/icons-vue'
import {
  fmtTime, envLabel, statusClass, statusLabel, wsOrigin, copyText
} from '@/utils/mobile'

interface Row {
  serverId: number
  hostname: string
  ip: string
  status: string
  exitCode: number | null
  durationMs: number | null
  stdout: string
  stderr: string
  error: string | null
  expanded: boolean
}

const route = useRoute()
const userStore = useUserStore()
const canRun = computed(() => userStore.permissions.includes('*') || userStore.permissions.includes('exec:run'))

const tab = ref<'exec' | 'history'>('exec')
const servers = ref<any[]>([])
const selected = ref<number[]>([])
const hostPicker = ref(false)
const hostKeyword = ref('')

const templates = ref<any[]>([])
const templateId = ref<number | null>(null)
const templateParams = ref<string[]>([])
const paramValues = reactive<Record<string, string>>({})
const command = ref('')
const dryRun = ref(false)
const running = ref(false)
const results = ref<Row[]>([])
const runId = ref<number | null>(null)
const lastRisk = ref<any>(null)
const lastPreviewToken = ref('')

let activeWs: WebSocket | null = null
let activeRequestId = ''
let completed = false

const history = ref<any[]>([])
const historyLoading = ref(false)
const historyPage = ref(1)
const historyPageSize = 10
const historyTotal = ref(0)
const historyKeyword = ref('')
const historyDetailVisible = ref(false)
const historyDetail = ref<any>(null)

const selectedHosts = computed(() => servers.value.filter(s => selected.value.includes(s.id)))
const filteredServers = computed(() => {
  const k = hostKeyword.value.trim().toLowerCase()
  if (!k) return servers.value
  return servers.value.filter(s => [s.hostname, s.ip, s.environment].some(v => String(v || '').toLowerCase().includes(k)))
})
const successCount = computed(() => results.value.filter(r => r.status === 'SUCCESS').length)
const failCount = computed(() => results.value.filter(r => r.status === 'FAILED' || r.status === 'KILLED').length)
const historyHasMore = computed(() => history.value.length < historyTotal.value)

function statusText(s: string) {
  return ({ PENDING: '等待', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败', KILLED: '已终止' } as any)[s] || s
}

function resDot(s: string) {
  return s === 'SUCCESS' ? 'online' : s === 'FAILED' || s === 'KILLED' ? 'offline' : 'warn'
}

async function loadServers() {
  const r = await request.get('/api/server/page', { params: { page: 1, pageSize: 500 } })
  servers.value = r.data?.records || []
  const sid = Number(route.query.serverId)
  if (sid && servers.value.some(s => s.id === sid)) selected.value = [sid]
}

async function loadTemplates() {
  try {
    const r = await request.get('/api/exec/templates', { params: { targetType: 'SERVER' } })
    templates.value = r.data || []
  } catch { templates.value = [] }
}

function applyTemplate(t: any) {
  templateId.value = t.id
  command.value = t.command
  const names = [...new Set([...String(t.command).matchAll(/\{([A-Za-z0-9_]+)\}/g)].map(m => m[1]))]
  templateParams.value = names
  names.forEach(n => { if (paramValues[n] === undefined) paramValues[n] = '' })
}

function fillParams() {
  let c = command.value
  templateParams.value.forEach(p => {
    if (paramValues[p]) c = c.split(`{${p}}`).join(paramValues[p])
  })
  command.value = c
  ElMessage.success('参数已替换')
}

function toggleServer(id: number) {
  if (selected.value.includes(id)) selected.value = selected.value.filter(x => x !== id)
  else selected.value = [...selected.value, id]
}

function setSelected(id: number, v: any) {
  if (v) { if (!selected.value.includes(id)) selected.value = [...selected.value, id] }
  else selected.value = selected.value.filter(x => x !== id)
}

function selectFiltered() {
  selected.value = [...new Set([...selected.value, ...filteredServers.value.map(s => s.id)])]
}

function uid() {
  return 'r' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

function makeRow(s: any): Row {
  return {
    serverId: s.id, hostname: s.hostname || s.ip, ip: s.ip,
    status: 'PENDING', exitCode: null, durationMs: null,
    stdout: '', stderr: '', error: null, expanded: false
  }
}

async function startRun(opts: any = {}) {
  if (running.value) return
  const ids: number[] = opts.ids || selected.value
  const cmd: string = (opts.command || command.value).trim()
  if (!ids.length) { ElMessage.warning('请选择目标主机'); return }
  if (!cmd) { ElMessage.warning('请输入命令'); return }

  let risk: any = null
  try {
    const r = await request.post('/api/exec/check', { command: cmd, serverIds: ids })
    risk = r.data || {}
    lastRisk.value = risk
  } catch { risk = null }

  if (risk?.blocked) { ElMessage.error(risk.message || '命令被拦截'); return }

  let confirmDangerous = !!opts.confirmDangerous
  let dry = opts.dryRun !== undefined ? !!opts.dryRun : dryRun.value
  let previewToken = opts.previewToken || lastPreviewToken.value || ''

  if (risk?.requiresConfirm && !confirmDangerous) {
    try {
      await ElMessageBox.confirm(risk.message || '该命令具有破坏性，确定执行？', '高危命令确认', { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' })
      confirmDangerous = true
    } catch { return }
  }
  if (risk?.dryRunRequired && !dry && !previewToken) {
    try {
      await ElMessageBox.confirm('目标包含生产环境，高危命令必须先 dry-run 预演。是否先预演？', '生产环境护栏', { type: 'warning', confirmButtonText: '先预演', cancelButtonText: '取消' })
      dry = true
    } catch { return }
  }

  if (opts.ids) selected.value = ids
  await startStream(ids, cmd, dry, confirmDangerous, previewToken)
}

async function startStream(ids: number[], cmd: string, dry: boolean, confirmDangerous: boolean, previewToken: string) {
  const picked = servers.value.filter(s => ids.includes(s.id))
  results.value = picked.map(makeRow)
  results.value.forEach(r => { r.expanded = true })
  running.value = true
  completed = false
  runId.value = null
  lastPreviewToken.value = ''
  activeRequestId = uid()

  const token = localStorage.getItem('token') || ''
  let opened = false
  try {
    const ws = new WebSocket(`${wsOrigin()}/ws/exec?token=${encodeURIComponent(token)}`)
    activeWs = ws
    ws.onopen = () => {
      opened = true
      ws.send(JSON.stringify({
        action: 'start', requestId: activeRequestId, serverIds: ids,
        command: cmd, dryRun: dry, confirmDangerous, previewToken
      }))
    }
    ws.onmessage = ev => {
      try { handleWsMessage(JSON.parse(ev.data), { ids, cmd, dry, confirmDangerous, previewToken }) } catch { /* ignore */ }
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
        ElMessage.error('连接中断，请查看执行历史')
      }
    }
  } catch {
    runViaRest(ids, cmd, dry, confirmDangerous, previewToken)
  }
}

function handleWsMessage(d: any, ctx: any) {
  if (d.type === 'run_start') {
    runId.value = d.runId
    if (d.risk) lastRisk.value = d.risk
    if (d.previewToken) lastPreviewToken.value = d.previewToken
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
    row.status = d.killed ? 'KILLED' : (d.success ? 'SUCCESS' : 'FAILED')
    if (d.error) row.error = d.error
    if (ctx.dry && !row.stdout) row.stdout = '[DRY-RUN] 未实际执行，仅预览命令'
    return
  }
  if (d.type === 'run_end') {
    running.value = false
    completed = true
    runId.value = d.runId || runId.value
    if (d.previewToken) lastPreviewToken.value = d.previewToken
    try { activeWs?.close() } catch { /* ignore */ }
    activeWs = null
    if (ctx.dry && lastPreviewToken.value) {
      ElMessageBox.confirm('Dry-run 预演完成，是否执行真实命令？', '预演完成', { type: 'info', confirmButtonText: '执行', cancelButtonText: '稍后' })
        .then(() => startRun({ ids: ctx.ids, command: ctx.cmd, dryRun: false, confirmDangerous: true, previewToken: lastPreviewToken.value }))
        .catch(() => { /* ignore */ })
    }
    return
  }
  if (d.type === 'error') {
    running.value = false
    completed = true
    if (d.risk) lastRisk.value = d.risk
    ElMessage.error(d.message || '执行被拒绝')
    try { activeWs?.close() } catch { /* ignore */ }
    activeWs = null
    return
  }
  if (d.type === 'kill_ack') {
    ElMessage.warning('已终止执行')
  }
}

async function runViaRest(ids: number[], cmd: string, dry: boolean, confirmDangerous: boolean, previewToken: string) {
  running.value = true
  try {
    const r = await request.post('/api/exec/batch', { serverIds: ids, command: cmd, dryRun: dry, confirmDangerous, previewToken })
    const d = r.data || {}
    if (d.rejected) {
      running.value = false
      if (d.risk) lastRisk.value = d.risk
      ElMessage.error(d.message || '执行被拒绝')
      return
    }
    runId.value = d.runId
    if (d.previewToken) lastPreviewToken.value = d.previewToken
    results.value = (d.results || []).map((x: any) => ({
      serverId: x.serverId, hostname: x.hostname, ip: x.ip,
      status: x.killed ? 'KILLED' : (x.success ? 'SUCCESS' : 'FAILED'),
      exitCode: x.exitCode, durationMs: x.durationMs,
      stdout: x.stdout || (d.dryRun ? '[DRY-RUN] 未实际执行，仅预览命令' : ''),
      stderr: x.stderr || '', error: x.errorMessage || null, expanded: true
    }))
    running.value = false
    completed = true
    if (d.dryRun && d.previewToken) {
      ElMessageBox.confirm('Dry-run 预演完成，是否执行真实命令？', '预演完成', { type: 'info', confirmButtonText: '执行', cancelButtonText: '稍后' })
        .then(() => startRun({ ids, command: cmd, dryRun: false, confirmDangerous: true, previewToken: d.previewToken }))
        .catch(() => { /* ignore */ })
    }
  } catch (e: any) {
    running.value = false
    ElMessage.error(e?.message || '执行失败')
  }
}

function killRun() {
  if (!activeWs || activeWs.readyState !== WebSocket.OPEN) return
  activeWs.send(JSON.stringify({ action: 'kill', requestId: activeRequestId }))
  ElMessage.info('已发送终止请求')
}

async function copyOutput(r: Row) {
  try {
    await copyText(r.stdout || r.stderr || r.error || '')
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败')
  }
}

async function openHistory() {
  tab.value = 'history'
  historyPage.value = 1
  await loadHistory(true)
}

async function loadHistory(reset = false) {
  if (historyLoading.value) return
  if (reset) historyPage.value = 1
  historyLoading.value = true
  try {
    const r = await request.get('/api/exec/history', {
      params: { page: historyPage.value, pageSize: historyPageSize, keyword: historyKeyword.value || undefined }
    })
    const rows = r.data?.records || []
    history.value = reset ? rows : [...history.value, ...rows]
    historyTotal.value = r.data?.total || 0
  } finally {
    historyLoading.value = false
  }
}

function loadHistoryMore() {
  if (historyLoading.value || !historyHasMore.value) return
  historyPage.value += 1
  loadHistory(false)
}

async function openHistoryDetail(id: number) {
  try {
    const r = await request.get(`/api/exec/history/${id}`)
    historyDetail.value = r.data
    historyDetailVisible.value = true
  } catch { /* ignore */ }
}

onMounted(async () => {
  await Promise.allSettled([loadServers(), loadTemplates()])
})

onUnmounted(() => {
  if (activeWs) { try { activeWs.close() } catch { /* ignore */ } }
})
</script>

<style scoped>
.tabs {
  display: flex;
  padding: 3px;
  border-radius: 12px;
  background: #e9eef6;
}

.tab {
  flex: 1;
  border: none;
  background: transparent;
  padding: 8px;
  border-radius: 10px;
  color: #64748b;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.tab.active {
  background: #fff;
  color: var(--m-primary);
  box-shadow: 0 2px 8px rgba(15, 35, 70, 0.08);
}

.link-btn {
  border: none;
  background: transparent;
  color: var(--m-primary);
  font-size: 12.5px;
  cursor: pointer;
}

.target-box {
  width: 100%;
  min-height: 42px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 9px 11px;
  border-radius: 11px;
  border: 1px dashed #cdd8e6;
  background: #f8fafd;
  cursor: pointer;
  text-align: left;
}

.target-box .placeholder {
  font-size: 12px;
  color: var(--m-muted);
}

.target-box .chev {
  margin-left: auto;
  color: #b6c2d1;
}

.param-row {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}

.cmd-input {
  margin-top: 10px;
}

.cmd-input :deep(.el-textarea__inner) {
  font-family: Consolas, Monaco, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  border-radius: 10px;
}

.run-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.dry-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #64748b;
}

.risk-box {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 9px;
  font-size: 11.5px;
  line-height: 1.5;
  color: #b26a00;
  background: #fff7e8;
  border: 1px solid #ffe1b8;
}

.risk-box.blocked {
  color: #d92d2d;
  background: #fdecec;
  border-color: #f8caca;
}

.run-btns {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.run-btn {
  flex: 1;
  height: 42px;
  border: none;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  background: linear-gradient(135deg, #1677ff, #06b6d4);
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.26);
  cursor: pointer;
}

.run-btn:disabled { opacity: 0.45; box-shadow: none; }

.kill-btn {
  flex: 0 0 auto;
  padding: 0 16px;
  height: 42px;
  border-radius: 12px;
  border: 1px solid #f3b6b6;
  background: #fdecec;
  color: #d92d2d;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.result-list { display: flex; flex-direction: column; gap: 7px; }

.result-card {
  border-radius: 11px;
  border: 1px solid var(--m-border);
  background: #f8fafd;
  overflow: hidden;
}

.result-card.success { border-color: #c8eedd; background: #f4fcf8; }
.result-card.failed, .result-card.killed { border-color: #f8caca; background: #fef7f7; }
.result-card.running { border-color: #cfe3ff; background: #f5f9ff; }

.res-top {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  cursor: pointer;
}

.res-main { flex: 1; min-width: 0; }

.res-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #334155;
}

.res-top .chev {
  color: #b6c2d1;
  transition: transform 0.2s;
}

.res-top .chev.open { transform: rotate(90deg); }

.res-output { padding: 0 8px 8px; }

.res-output pre {
  margin: 0;
  padding: 9px;
  border-radius: 8px;
  background: #0f172a;
  color: #cfe3ff;
  font-size: 11px;
  line-height: 1.55;
  font-family: Consolas, Monaco, monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
}

.copy-btn {
  margin-top: 6px;
  width: 100%;
  padding: 6px;
  border-radius: 8px;
  border: 1px solid var(--m-border);
  background: #fff;
  color: var(--m-primary);
  font-size: 11.5px;
  cursor: pointer;
}

.history-card {
  padding: 10px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid var(--m-border);
  box-shadow: var(--m-shadow);
  cursor: pointer;
}

.h-top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.h-time {
  font-size: 10.5px;
  color: var(--m-muted);
}

.h-cmd {
  margin: 6px 0 3px;
  padding: 6px 8px;
  border-radius: 7px;
  background: #0f172a;
  color: #cfe3ff;
  font-size: 11px;
  font-family: Consolas, monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.load-more {
  width: 100%;
  padding: 10px;
  border-radius: 12px;
  border: 1px dashed var(--m-border);
  background: #fff;
  color: var(--m-primary);
  font-size: 12.5px;
  cursor: pointer;
}

.load-more:disabled { color: var(--m-muted); }

.picker { display: flex; flex-direction: column; height: 100%; }

.picker-bar {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 8px;
}

.picker-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.picker-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border-radius: 10px;
  border-bottom: 1px solid #f1f5f9;
  cursor: pointer;
}

.picker-main { flex: 1; min-width: 0; }

.picker-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #334155;
}

.picker-ok {
  margin-top: 8px;
  width: 100%;
  height: 40px;
  border: none;
  border-radius: 11px;
  color: #fff;
  font-size: 13.5px;
  font-weight: 600;
  background: linear-gradient(135deg, #1677ff, #0ea5e9);
  cursor: pointer;
}

.detail { padding: 2px 2px 12px; }

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11.5px;
  color: var(--m-sub);
}

.detail-cmd {
  margin: 8px 0;
  padding: 8px;
  border-radius: 8px;
  background: #0f172a;
  color: #cfe3ff;
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-all;
}

.detail-host {
  margin-top: 8px;
  padding: 8px;
  border-radius: 10px;
  background: #f8fafd;
  border: 1px solid #eef2f7;
}

.dh-top {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #334155;
}

.dh-output {
  margin: 6px 0 0;
  padding: 8px;
  border-radius: 7px;
  background: #0f172a;
  color: #cfe3ff;
  font-size: 10.5px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
}
</style>