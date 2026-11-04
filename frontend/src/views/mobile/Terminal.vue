<template>
  <div class="term-page">
    <!-- 服务器选择 -->
    <div class="term-toolbar">
      <el-select v-model="serverId" filterable placeholder="选择服务器" class="srv-select" :disabled="connected || connecting" @change="onServerChange">
        <el-option v-for="s in servers" :key="s.id" :label="serverLabel(s)" :value="s.id" />
      </el-select>
      <button class="t-btn primary" :disabled="connected || connecting || !serverId" @click="connect">
        {{ connecting ? '连接中' : '连接' }}
      </button>
      <button class="t-btn" :disabled="!connected && !connecting" @click="disconnect()">断开</button>
    </div>

    <div class="term-status">
      <span class="m-dot" :class="connected ? 'online' : connecting ? 'warn' : 'offline'"></span>
      <span class="status-text">{{ statusText }}</span>
      <span class="font-ctrl">
        <button @click="changeFont(-1)">A-</button>
        <button @click="changeFont(1)">A+</button>
        <button @click="clearTerminal">清屏</button>
      </span>
    </div>

    <div ref="termRef" class="term-wrap" @click="focusTerm"></div>

    <!-- 手机快捷键盘 -->
    <div class="quick-keys">
      <button v-for="k in keys" :key="k.label" class="key" @click="sendKey(k.seq)">{{ k.label }}</button>
    </div>

    <div v-if="!servers.length" class="no-server">
      暂无服务器，请先到「更多 → 服务器」添加服务器并配置 SSH 凭据
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import request from '@/utils/request'
import { wsOrigin } from '@/utils/mobile'

const route = useRoute()
const servers = ref<any[]>([])
const serverId = ref<number | null>(null)
const currentServer = ref<any>(null)
const statusText = ref('未连接')
const connecting = ref(false)
const connected = ref(false)
const fontSize = ref(13)
const termRef = ref<HTMLElement>()

let term: Terminal | null = null
let fitAddon: FitAddon | null = null
let ws: WebSocket | null = null
let ready = false
let autoTried = false

const keys = [
  { label: 'Esc', seq: String.fromCharCode(27) },
  { label: 'Tab', seq: String.fromCharCode(9) },
  { label: 'Ctrl+C', seq: String.fromCharCode(3) },
  { label: 'Ctrl+D', seq: String.fromCharCode(4) },
  { label: 'Ctrl+Z', seq: String.fromCharCode(26) },
  { label: '↑', seq: String.fromCharCode(27) + '[A' },
  { label: '↓', seq: String.fromCharCode(27) + '[B' },
  { label: '←', seq: String.fromCharCode(27) + '[D' },
  { label: '→', seq: String.fromCharCode(27) + '[C' },
  { label: '|', seq: '|' },
  { label: '/', seq: '/' },
  { label: '-', seq: '-' },
  { label: '~', seq: '~' },
  { label: 'clear', seq: 'clear' + String.fromCharCode(10) }
]

function serverLabel(s: any) {
  const cred = s.sshPasswordSet ? '密码' : (s.sshKey ? '私钥' : '未配置凭据')
  return `${s.hostname || s.ip} (${s.sshUser || 'root'}@${s.ip}:${s.sshPort || 22}) · ${cred}`
}

async function loadServers() {
  try {
    const r = await request.get('/api/server/page', { params: { page: 1, pageSize: 500 } })
    servers.value = r.data?.records || []
  } catch {
    servers.value = []
  }
}

function initTerminal() {
  if (term || !termRef.value) return
  term = new Terminal({
    cursorBlink: true,
    fontSize: fontSize.value,
    fontFamily: 'Consolas, Monaco, "Cascadia Mono", "Courier New", monospace',
    scrollback: 3000,
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
  term.onData(data => {
    if (ready) send({ type: 'input', data })
  })
  fit()
  term.writeln('\x1b[36m选择服务器后点击「连接」建立 SSH 会话。\x1b[0m')
  term.writeln('\x1b[33m提示：点按终端区域可唤起手机键盘；下方提供 Esc/Tab/Ctrl 等快捷键。\x1b[0m')
}

function send(payload: any) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(payload))
}

function sendKey(seq: string) {
  if (ready) {
    send({ type: 'input', data: seq })
    term?.focus()
  } else {
    ElMessage.warning('请先连接服务器')
  }
}

function fit() {
  try { fitAddon?.fit() } catch { /* ignore */ }
  if (ready && term) send({ type: 'resize', cols: term.cols, rows: term.rows })
}

function focusTerm() {
  term?.focus()
}

function changeFont(delta: number) {
  fontSize.value = Math.min(20, Math.max(10, fontSize.value + delta))
  if (term) {
    term.options.fontSize = fontSize.value
    nextTick(() => fit())
  }
}

function clearTerminal() {
  term?.clear()
  if (ready) send({ type: 'input', data: 'clear\n' })
}

async function connect() {
  if (!serverId.value) { ElMessage.warning('请选择服务器'); return }
  disconnect(false)
  initTerminal()
  const id = serverId.value
  connecting.value = true
  statusText.value = '检查凭据'
  try {
    const r = await request.get(`/api/server/${id}`)
    const fresh = r.data
    if (fresh) {
      const idx = servers.value.findIndex(x => x.id === id)
      if (idx >= 0) servers.value[idx] = fresh
      currentServer.value = fresh
      const hasCred = fresh.sshPasswordSet || (fresh.sshKey && String(fresh.sshKey).trim())
      if (!hasCred) {
        connecting.value = false
        statusText.value = '未配置凭据'
        term?.reset()
        term?.writeln('\x1b[31m该服务器未配置 SSH 密码或私钥。\x1b[0m')
        term?.writeln('\x1b[33m请到「更多 → 服务器 → 编辑资产」填写 SSH 密码并保存。\x1b[0m')
        ElMessage.warning('该服务器未配置 SSH 凭据')
        return
      }
    }
  } catch { /* 继续尝试连接 */ }

  statusText.value = '连接中'
  term?.reset()
  term?.writeln('\x1b[36m正在建立 SSH 会话，请稍候…\x1b[0m')
  ready = false
  const token = localStorage.getItem('token') || ''
  const url = `${wsOrigin()}/ws/ssh?token=${encodeURIComponent(token)}&serverId=${id}`
  try {
    ws = new WebSocket(url)
  } catch (e: any) {
    connecting.value = false
    connected.value = false
    statusText.value = '连接失败'
    term?.writeln('\r\n\x1b[31m无法创建 WebSocket：' + (e?.message || e) + '\x1b[0m')
    return
  }
  ws.onopen = () => { if (term) send({ type: 'resize', cols: term.cols, rows: term.rows }) }
  ws.onmessage = (ev) => {
    let msg: any
    try { msg = JSON.parse(ev.data) } catch { return }
    if (msg.type === 'ready') {
      ready = true
      connecting.value = false
      connected.value = true
      statusText.value = '已连接'
      currentServer.value = msg.server || currentServer.value
      nextTick(() => { fit(); term?.focus() })
    } else if (msg.type === 'status') {
      if (msg.status === 'connecting') statusText.value = '连接中'
      else if (msg.status === 'closed') {
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
  ready = false
  try { ws?.close() } catch { /* ignore */ }
  ws = null
  connected.value = false
  connecting.value = false
  if (showMessage) statusText.value = '已断开'
}

function onServerChange(id: number) {
  const s = servers.value.find(x => x.id === id)
  currentServer.value = s || null
  const hasCred = s?.sshPasswordSet || (s?.sshKey && String(s.sshKey).trim())
  if (!hasCred) {
    ElMessage.warning('该服务器未配置 SSH 凭据，请先在资产中编辑')
    return
  }
  connect()
}

let vvHandler: (() => void) | null = null
let resizeHandler: (() => void) | null = null

onMounted(async () => {
  await loadServers()
  const qsid = Number(route.params.serverId || route.query.serverId)
  if (qsid && servers.value.some(s => s.id === qsid)) serverId.value = qsid
  else if (!qsid && servers.value.length === 1) serverId.value = servers.value[0].id
  currentServer.value = servers.value.find(s => s.id === serverId.value) || null

  await nextTick()
  initTerminal()
  setTimeout(() => {
    fit()
    if (serverId.value && !autoTried) {
      autoTried = true
      const s = servers.value.find(x => x.id === serverId.value)
      if (s?.sshPasswordSet || (s?.sshKey && String(s.sshKey).trim())) connect()
    }
  }, 400)

  resizeHandler = () => fit()
  window.addEventListener('resize', resizeHandler)
  if (window.visualViewport) {
    vvHandler = () => {
      fit()
      setTimeout(() => termRef.value?.scrollIntoView({ block: 'end' }), 60)
    }
    window.visualViewport.addEventListener('resize', vvHandler)
  }
})

watch(() => route.params.serverId, async (v) => {
  const id = Number(v)
  if (!id) return
  serverId.value = id
  currentServer.value = servers.value.find(s => s.id === id) || null
  await nextTick()
  fit()
})

onUnmounted(() => {
  disconnect(false)
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
  if (vvHandler && window.visualViewport) window.visualViewport.removeEventListener('resize', vvHandler)
  term?.dispose()
  term = null
})
</script>

<style scoped>
.term-page {
  height: 100%;
  min-height: calc(100dvh - 64px);
  display: flex;
  flex-direction: column;
  background: #0b1220;
  overflow: hidden;
}

.term-toolbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  background: #0f1b2d;
  border-bottom: 1px solid rgba(56, 189, 248, 0.14);
}

.srv-select {
  flex: 1;
  min-width: 0;
}

.srv-select :deep(.el-select__wrapper) {
  background: #13233a;
  box-shadow: 0 0 0 1px rgba(56, 189, 248, 0.2) inset;
}

.srv-select :deep(.el-select__selected-item) {
  color: #cfe3ff;
  font-size: 12px;
}

.t-btn {
  flex: 0 0 auto;
  height: 30px;
  padding: 0 10px;
  border-radius: 9px;
  border: 1px solid rgba(56, 189, 248, 0.26);
  background: rgba(56, 189, 248, 0.08);
  color: #7dd3fc;
  font-size: 11.5px;
  cursor: pointer;
}

.t-btn.primary {
  color: #fff;
  background: linear-gradient(135deg, #0ea5e9, #1677ff);
  border-color: transparent;
  font-weight: 600;
}

.t-btn:disabled { opacity: 0.4; }

.term-status {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: #0b1524;
  border-bottom: 1px solid rgba(56, 189, 248, 0.1);
  color: #9fb6d9;
  font-size: 11px;
}

.status-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.font-ctrl {
  display: flex;
  gap: 4px;
  flex: 0 0 auto;
}

.font-ctrl button {
  border: 1px solid rgba(148, 163, 184, 0.2);
  background: rgba(148, 163, 184, 0.1);
  color: #9fb6d9;
  font-size: 10.5px;
  padding: 2px 6px;
  border-radius: 6px;
  cursor: pointer;
}

.term-wrap {
  flex: 1;
  min-height: 0;
  padding: 6px 6px 2px;
  background: #0b1220;
  overflow: hidden;
  -webkit-overflow-scrolling: touch;
}

.term-wrap :deep(.xterm) { height: 100%; }
.term-wrap :deep(.xterm-viewport) { background: transparent !important; }

.quick-keys {
  flex: 0 0 auto;
  display: flex;
  gap: 5px;
  overflow-x: auto;
  padding: 7px 8px calc(7px + env(safe-area-inset-bottom, 0px));
  background: #0f1b2d;
  border-top: 1px solid rgba(56, 189, 248, 0.14);
  scrollbar-width: none;
}

.quick-keys::-webkit-scrollbar { display: none; }

.key {
  flex: 0 0 auto;
  min-width: 36px;
  height: 32px;
  padding: 0 9px;
  border-radius: 8px;
  border: 1px solid rgba(148, 163, 184, 0.24);
  background: rgba(148, 163, 184, 0.1);
  color: #cfe3ff;
  font-size: 11.5px;
  font-family: Consolas, monospace;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.key:active {
  background: rgba(56, 189, 248, 0.2);
  border-color: rgba(56, 189, 248, 0.5);
}

.no-server {
  padding: 10px;
  text-align: center;
  font-size: 11.5px;
  color: #fbbf24;
  background: #0f1b2d;
}
</style>