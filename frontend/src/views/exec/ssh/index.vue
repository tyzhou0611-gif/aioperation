<template>
  <div class="ssh-page">
    <div class="ssh-head">
      <div>
        <h2>Web SSH 终端</h2>
        <div class="sub">交互式远程终端 · 类似 Xshell · 支持任意 shell 命令</div>
      </div>
      <div class="ssh-tools">
        <el-select v-model="serverId" filterable placeholder="选择服务器" style="width:320px" :disabled="connected || connecting"
                   @visible-change="onServerSelectVisible">
          <el-option v-for="s in servers" :key="s.id" :label="serverLabel(s)" :value="s.id" />
        </el-select>
        <el-button :loading="refreshingServers" :disabled="connected || connecting" @click="refreshServers">刷新</el-button>
        <el-button type="primary" :loading="connecting" :disabled="connected || !serverId" @click="connect">连接</el-button>
        <el-button :disabled="!connected && !connecting" @click="disconnect">断开</el-button>
        <el-button @click="clearTerminal">清屏</el-button>
        <el-select v-model="fontSize" style="width:100px" @change="applyFontSize">
          <el-option :value="12" label="12 px" />
          <el-option :value="14" label="14 px" />
          <el-option :value="16" label="16 px" />
          <el-option :value="18" label="18 px" />
        </el-select>
        <el-button @click="toggleFullscreen">全屏</el-button>
      </div>
    </div>

    <el-alert type="warning" :closable="false" show-icon class="ssh-alert"
              title="交互式终端会绕过命令安全校验，请谨慎操作；每次会话都会记录审计日志。" />

    <div class="term-card" ref="wrapRef">
      <div class="term-bar">
        <div class="term-info">
          <span class="dot" :class="statusClass"></span>
          <span v-if="currentServer">{{ currentServer.hostname || currentServer.ip }} · {{ currentServer.user }}@{{ currentServer.ip }}:{{ currentServer.port }}</span>
          <span v-else>未连接</span>
          <el-tag size="small" :type="statusTagType">{{ statusText }}</el-tag>
        </div>
        <div class="term-actions">
          <span class="hint" v-if="!servers.length">暂无服务器，请先到资产管理添加</span>
          <el-button link size="small" @click="router.push('/asset/server')">服务器管理</el-button>
        </div>
      </div>
      <div class="term-wrap" ref="termRef"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import request from '@/utils/request'

const route = useRoute()
const router = useRouter()
const servers = ref<any[]>([])
const serverId = ref<number | null>(null)
const currentServer = ref<any>(null)
const connecting = ref(false)
const connected = ref(false)
const refreshingServers = ref(false)
const statusText = ref('未连接')
const fontSize = ref(14)
const termRef = ref<HTMLElement>()
const wrapRef = ref<HTMLElement>()

let term: Terminal | null = null
let fitAddon: FitAddon | null = null
let ws: WebSocket | null = null
let resizeObserver: ResizeObserver | null = null
let ready = false

const statusClass = computed(() => connected.value ? 'ok' : connecting.value ? 'warn' : 'idle')
const statusTagType = computed(() => connected.value ? 'success' : connecting.value ? 'warning' : 'info')

function serverLabel(s: any) {
  const cred = s.sshPasswordSet ? '密码' : (s.sshKey ? '私钥' : '未配置凭据')
  return `${s.hostname || s.ip} (${s.sshUser || 'root'}@${s.ip}:${s.sshPort || 22}) · ${cred}`
}

async function connect() {
  if (!serverId.value) { ElMessage.warning('请选择服务器'); return }
  disconnect(false)
  initTerminalIfNeeded()
  const id = serverId.value
  connecting.value = true
  statusText.value = '检查凭据'
  // 连接前刷新服务器详情，避免在其它页面刚配置密码后这里仍显示/使用旧状态
  try {
    const r = await request.get(`/api/server/${id}`)
    const fresh = r.data
    if (fresh) {
      const idx = servers.value.findIndex((x: any) => x.id === id)
      if (idx >= 0) servers.value[idx] = fresh
      currentServer.value = fresh
      const hasCred = fresh.sshPasswordSet || (fresh.sshKey && String(fresh.sshKey).trim())
      if (!hasCred) {
        connecting.value = false
        statusText.value = '未配置凭据'
        term?.reset()
        term?.writeln('\x1b[31m该服务器未配置 SSH 密码或私钥。\x1b[0m')
        term?.writeln('\x1b[33m请到「资产管理 → 服务器列表 → 编辑」填写 SSH 密码/私钥并保存。\x1b[0m')
        ElMessage.warning('该服务器未配置 SSH 密码或私钥')
        return
      }
    }
  } catch {
    /* 详情读取失败时继续尝试连接，由后端返回具体错误 */
  }
  statusText.value = '连接中'
  term?.reset()
  term?.writeln('\x1b[36m正在建立 SSH 会话，请稍候…\x1b[0m')
  ready = false
  const token = localStorage.getItem('token') || ''
  const base = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')
  let url: string
  if (base) {
    const u = new URL(base)
    const proto = u.protocol === 'https:' ? 'wss' : 'ws'
    url = `${proto}://${u.host}/ws/ssh?token=${encodeURIComponent(token)}&serverId=${id}`
  } else {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    url = `${proto}://${location.host}/ws/ssh?token=${encodeURIComponent(token)}&serverId=${id}`
  }
  try {
    ws = new WebSocket(url)
  } catch (e: any) {
    connecting.value = false
    connected.value = false
    statusText.value = '连接失败'
    term?.writeln('\r\n\x1b[31m无法创建 WebSocket 连接：' + (e?.message || e) + '\x1b[0m')
    return
  }
  ws.onopen = () => {
    sendResize()
  }
  ws.onmessage = (ev) => {
    let msg: any
    try { msg = JSON.parse(ev.data) } catch { return }
    if (msg.type === 'ready') {
      ready = true
      connecting.value = false
      connected.value = true
      statusText.value = '已连接'
      currentServer.value = msg.server || null
      nextTick(() => { fit(); sendResize(); term?.focus() })
    } else if (msg.type === 'status') {
      if (msg.status === 'connecting') {
        statusText.value = '连接中'
      } else if (msg.status === 'closed') {
        connected.value = false
        connecting.value = false
        ready = false
        statusText.value = '已断开'
        if (msg.message) term?.writeln('\r\n\x1b[33m' + msg.message + '\x1b[0m')
      }
    } else if (msg.type === 'output') {
      term?.write(msg.data || '')
    } else if (msg.type === 'error') {
      connecting.value = false
      connected.value = false
      ready = false
      statusText.value = '连接失败'
      term?.writeln('\r\n\x1b[31m' + (msg.message || '连接失败') + '\x1b[0m')
    }
  }
  ws.onclose = () => {
    if (connected.value) term?.writeln('\r\n\x1b[33m连接已关闭\x1b[0m')
    connected.value = false
    connecting.value = false
    ready = false
    if (statusText.value === '连接中') statusText.value = '连接失败'
    ws = null
  }
  ws.onerror = () => {
    if (!connected.value) {
      statusText.value = '连接失败'
      term?.writeln('\r\n\x1b[31mWebSocket 连接失败，请检查登录状态或网络\x1b[0m')
    }
    connecting.value = false
  }
}

function disconnect(showMessage = true) {
  commonDisconnect()
  if (showMessage) statusText.value = '已断开'
}

function commonDisconnect() {
  ready = false
  try { ws?.close() } catch { /* ignore */ }
  ws = null
  connected.value = false
  connecting.value = false
}

function send(payload: any) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(payload))
}

function sendResize() {
  if (!term) return
  send({ type: 'resize', cols: term.cols, rows: term.rows })
}

function fit() {
  try { fitAddon?.fit() } catch { /* ignore */ }
  sendResize()
}

function initTerminalIfNeeded() {
  if (term || !termRef.value) return
  term = new Terminal({
    cursorBlink: true,
    fontSize: fontSize.value,
    fontFamily: 'Consolas, Monaco, "Cascadia Mono", "Courier New", monospace',
    scrollback: 5000,
    convertEol: false,
    theme: {
      background: '#0b1220',
      foreground: '#cfe3ff',
      cursor: '#38bdf8',
      selectionBackground: 'rgba(56,189,248,.25)',
      black: '#0b1220', red: '#ff6b81', green: '#22e6a4', yellow: '#fbbf24',
      blue: '#4facfe', magenta: '#a78bfa', cyan: '#22d3ee', white: '#dbeafe',
      brightBlack: '#5b7ea6', brightRed: '#ff8a9b', brightGreen: '#5cf2c0', brightYellow: '#ffd04b',
      brightBlue: '#7cc7ff', brightMagenta: '#c4b5fd', brightCyan: '#67e8f9', brightWhite: '#ffffff'
    }
  })
  fitAddon = new FitAddon()
  term.loadAddon(fitAddon)
  term.open(termRef.value)
  term.onData((data) => {
    if (ready) send({ type: 'input', data })
  })
  term.onResize(() => {
    if (ready) sendResize()
  })
  fit()
  term.writeln('\x1b[36m选择服务器后点击「连接」建立交互式 SSH 会话。\x1b[0m')
  resizeObserver = new ResizeObserver(() => fit())
  if (wrapRef.value) resizeObserver.observe(wrapRef.value)
}

function applyFontSize() {
  if (term) {
    term.options.fontSize = fontSize.value
    nextTick(() => fit())
  }
}

function clearTerminal() {
  term?.clear()
}

async function toggleFullscreen() {
  const el = wrapRef.value as any
  try {
    if (!document.fullscreenElement) await el?.requestFullscreen?.()
    else await document.exitFullscreen?.()
  } catch { /* ignore */ }
  setTimeout(fit, 350)
}

async function loadServers() {
  const keep = serverId.value
  const res = await request.get('/api/server/page', { params: { page: 1, pageSize: 500 } })
  servers.value = res.data.records || []
  if (keep && servers.value.some((s: any) => s.id === keep)) {
    serverId.value = keep
  } else if (servers.value.length) {
    serverId.value = servers.value[0].id
  } else {
    serverId.value = null
  }
}

async function refreshServers() {
  refreshingServers.value = true
  try {
    await loadServers()
  } finally {
    refreshingServers.value = false
  }
}

function onServerSelectVisible(visible: boolean) {
  if (visible && !connected.value && !connecting.value) refreshServers()
}

onMounted(async () => {
  await loadServers()
  // 支持大屏/资产页跳转：/exec/ssh?serverId=1
  const qsid = Number(route.query.serverId)
  if (qsid && servers.value.some((s: any) => s.id === qsid)) serverId.value = qsid
  const qauto = String(route.query.connect || '') === '1'
  await nextTick()
  initTerminalIfNeeded()
  setTimeout(fit, 300)
  if (qauto && qsid && serverId.value === qsid) {
    // 交由用户点击连接（WebSocket 需要用户上下文），只做提示
    ElMessage.info('已选择服务器，点击「连接」即可进入终端')
  }
  window.addEventListener('resize', fit)
})

onUnmounted(() => {
  commonDisconnect()
  resizeObserver?.disconnect()
  window.removeEventListener('resize', fit)
  term?.dispose()
  term = null
})
</script>

<style scoped>
.ssh-page { height: calc(100vh - 120px); min-height: 520px; display: flex; flex-direction: column; }
.ssh-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 10px; }
.ssh-head h2 { margin: 0; font-size: 22px; color: #303133; }
.ssh-head .sub { color: #909399; font-size: 13px; margin-top: 4px; }
.ssh-tools { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ssh-alert { margin-bottom: 10px; }
.term-card { flex: 1; min-height: 0; display: flex; flex-direction: column; background: #0b1220; border-radius: 8px; overflow: hidden; border: 1px solid #1e3a5f; }
.term-bar { height: 38px; flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; padding: 0 12px; color: #9fb6d9; font-size: 12px; background: linear-gradient(180deg, #0f1b2d, #0b1524); border-bottom: 1px solid rgba(56,189,248,.16); }
.term-info { display: flex; align-items: center; gap: 8px; min-width: 0; }
.term-info span:not(.dot) { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.term-actions { display: flex; align-items: center; gap: 8px; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: #64748b; }
.dot.ok { background: #22e6a4; box-shadow: 0 0 8px rgba(34,230,164,.8); }
.dot.warn { background: #fbbf24; box-shadow: 0 0 8px rgba(251,191,36,.8); }
.term-wrap { flex: 1; min-height: 0; padding: 8px 10px; background: #0b1220; }
.term-wrap :deep(.xterm) { height: 100%; }
.term-wrap :deep(.xterm-viewport) { background: transparent !important; }
.hint { color: #909399; font-size: 12px; }
</style>