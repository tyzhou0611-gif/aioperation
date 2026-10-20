<template>
  <!-- 悬浮按钮 -->
  <div v-show="!store.visible" class="ai-fab" @click="store.openWith()">
    <el-icon :size="26"><MagicStick /></el-icon>
  </div>

  <el-drawer v-model="store.visible" :with-header="false" size="760px" class="ai-drawer" destroy-on-close>
    <div class="copilot">
      <!-- 顶栏 -->
      <div class="cp-topbar">
        <div class="cp-title"><el-icon><MagicStick /></el-icon> AI 运维助手</div>
        <el-button size="small" type="primary" plain @click="createNew">＋ 新建会话</el-button>
      </div>

      <!-- AI 连接状态条 -->
      <div class="cp-status" :class="aiInfo.reachable ? 'ok' : 'warn'">
        <el-icon><component :is="aiInfo.reachable ? 'CircleCheck' : 'Warning'" /></el-icon>
        <span v-if="aiInfo.reachable">已连接 {{ providerLabel }}</span>
        <span v-else>{{ aiInfo.provider === 'none' ? '本地规则模式（未配置大模型）' : (providerLabel + ' 不可用，已降级本地规则') }}</span>
      </div>

      <div class="cp-body">
        <!-- 左栏：会话列表 -->
        <aside class="cp-sidebar">
          <div class="sb-head">
            <span>历史会话</span>
            <el-button link size="small" @click="clearAll">清空</el-button>
          </div>
          <div class="sb-list">
            <div v-for="s in store.sessions" :key="s.id"
                 :class="['sb-item', { active: s.id === store.sessionId }]"
                 @click="switchTo(s.id)">
              <div class="sb-info">
                <div class="sb-title">{{ s.title || '新对话' }}</div>
                <div class="sb-time">{{ fmtTime(s.updateTime) }}</div>
              </div>
              <div class="sb-actions" @click.stop>
                <el-icon title="重命名" @click="rename(s)"><Edit /></el-icon>
                <el-icon title="删除" @click="del(s)"><Delete /></el-icon>
              </div>
            </div>
            <div v-if="!store.sessions.length" class="sb-empty">
              <el-empty description="暂无历史会话" :image-size="60" />
            </div>
          </div>
        </aside>

        <!-- 右栏：对话区 -->
        <main class="cp-chat">
          <!-- 上下文资产卡片 -->
          <div v-if="store.context" class="cp-context">
            当前上下文：{{ store.context.name }}
            <el-tag size="small">{{ store.context.type }}</el-tag>
            <el-button link size="small" @click="store.clearContext()">清除</el-button>
          </div>

          <div class="cp-msgs" ref="bodyRef">
            <div v-if="!store.messages.length" class="cp-empty">
              <el-icon :size="40" color="#c0c4cc"><ChatDotRound /></el-icon>
              <p>你好，我是 AI 运维助手。<br/>试着问我：「kafka 消费延迟怎么排查」「重启 mysql」</p>
            </div>
            <MessageBubble v-for="m in store.messages" :key="m.id" :msg="m" :can-exec="true"
                           @execute="onExecute" @navigate="onNavigate" />
            <div v-if="store.loading" class="cp-loading">
              <el-icon class="is-loading"><Loading /></el-icon> AI 正在分析…
            </div>
          </div>

          <div class="cp-quick" v-if="quickTips.length">
            <el-tag v-for="t in quickTips" :key="t" size="small" effect="plain" class="quick-tag" @click="input = t">{{ t }}</el-tag>
          </div>

          <div class="cp-input">
            <el-input v-model="input" type="textarea" :rows="3" resize="none"
                      placeholder="描述你的运维需求，Ctrl+Enter 发送" @keydown="onKey" />
            <div class="cp-input-btns">
              <el-button size="small" text @click="store.messages = []">清空对话</el-button>
              <el-button type="primary" size="small" :loading="store.loading" @click="doSend">发送</el-button>
            </div>
          </div>
        </main>
      </div>
    </div>
  </el-drawer>

  <!-- 执行命令弹窗 -->
  <el-dialog v-model="execVisible" title="执行命令到服务器" width="560px" append-to-body>
    <div class="exec-cmd">
      <div class="exec-cmd-label">命令：</div>
      <pre class="exec-cmd-code">{{ execCommand }}</pre>
    </div>
    <div v-if="suggested.length" class="exec-suggest">
      <span class="esc-label">AI 建议：</span>
      <el-tag v-for="s in suggested" :key="s.id" size="small" effect="light" class="sug-tag"
              @click="execServerId = s.id">
        {{ s.name }} ({{ s.ip }}) <span v-if="s.reason">· {{ s.reason }}</span>
      </el-tag>
    </div>
    <el-form label-width="90px" style="margin-top:8px">
      <el-form-item label="目标服务器">
        <el-select v-model="execServerId" placeholder="选择服务器" style="width:100%">
          <el-option v-for="s in servers" :key="s.id" :label="s.hostname + ' (' + s.ip + ')'" :value="s.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="SSH用户"><span>{{ execServerInfo.sshUser || '-' }} @ {{ execServerInfo.ip || '未选' }}</span></el-form-item>
    </el-form>
    <div v-if="execOutput" class="exec-term">
      <div v-for="(line, i) in execOutput.split('\n')" :key="i">{{ line }}</div>
    </div>
    <template #footer>
      <el-button v-if="execOutput" type="warning" @click="sendResultToAi">发送结果给 AI</el-button>
      <el-button @click="execVisible = false">关闭</el-button>
      <el-button type="primary" :loading="execLoading" :disabled="!execServerId" @click="doExecute">执行</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick, ChatDotRound, Loading, Edit, Delete } from '@element-plus/icons-vue'
import { useCopilotStore } from '@/stores/copilot'
import { useRouter } from 'vue-router'
import { navigateByTarget } from '@/utils/routerHelper'
import request from '@/utils/request'
import MessageBubble from './MessageBubble.vue'
import type { NavigationHint, SuggestedServer } from '@/types'

const store = useCopilotStore()
const router = useRouter()
const input = ref('')
const bodyRef = ref<HTMLElement>()
const aiInfo = ref<any>({ provider: 'none', reachable: false })
const providerLabel = computed(() =>
  ({ hiagent: 'HiAgent', deepseek: 'DeepSeek', openai: 'OpenAI', qwen: '通义千问', none: '本地规则' } as any)[aiInfo.value.provider] || aiInfo.value.provider)
async function loadAiInfo() { try { const r = await request.get('/api/system/config/ai/status'); aiInfo.value = r.data || aiInfo.value } catch { } }
watch(() => store.visible, (v: boolean) => { if (v) { loadAiInfo(); store.loadSessions() } })
setInterval(loadAiInfo, 30000)
onMounted(async () => {
  const res = await request.get('/api/server/page', { params: { page: 1, pageSize: 500 } })
  servers.value = res.data.records || []
})

const servers = ref<any[]>([])
const execVisible = ref(false)
const execCommand = ref('')
const execServerId = ref<number | null>(null)
const execLoading = ref(false)
const execOutput = ref('')
const suggested = ref<SuggestedServer[]>([])
const execServerInfo = computed(() => servers.value.find((s: any) => s.id === execServerId.value) || {})

function fmtTime(t?: string) {
  if (!t) return ''
  const d = new Date(t)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }
  return `${d.getMonth() + 1}/${d.getDate()}`
}

function scrollBottom() { nextTick(() => { if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight }) }

async function doSend() {
  const t = input.value.trim(); if (!t) return
  input.value = ''
  await store.send(t); scrollBottom()
}
function onKey(e: KeyboardEvent) { if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); doSend() } }

function createNew() { store.newSession(); ElMessage.info('新会话已就绪，发送消息后自动保存') }
async function switchTo(id: number) { await store.selectSession(id); scrollBottom() }

function del(s: any) {
  ElMessageBox.confirm(`确定删除会话「${s.title || '新对话'}」？删除后不可恢复。`, '删除确认',
    { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' })
    .then(async () => { await store.removeSession(s.id); ElMessage.success('已删除') })
    .catch(() => {})
}

function rename(s: any) {
  ElMessageBox.prompt('输入新的会话标题', '重命名', {
    inputValue: s.title || '', inputValidator: (v) => !!v && v.trim().length > 0 || '标题不能为空'
  }).then(async ({ value }) => { await store.renameSession(s.id, value.trim()); ElMessage.success('已重命名') }).catch(() => {})
}

function clearAll() {
  ElMessageBox.confirm('确定清空当前用户所有历史会话？', '清空确认', { type: 'warning' })
    .then(async () => { await store.clearAllSessions(); ElMessage.success('已清空') }).catch(() => {})
}

function onNavigate(nav: NavigationHint) { if (navigateByTarget(router, nav)) store.close() }

function onExecute(code: string, ss: SuggestedServer[], label: string) {
  execCommand.value = code; execOutput.value = ''; suggested.value = ss || []
  if (ss && ss.length) {
    const hit = servers.value.find((s: any) => s.id === ss[0].id)
    execServerId.value = hit ? hit.id : (ss[0].id || servers.value[0]?.id || null)
  } else {
    execServerId.value = servers.value[0]?.id || null
  }
  execVisible.value = true
}

async function doExecute() {
  execLoading.value = true
  try {
    const r = await store.executeOnServer(execServerId.value!, execCommand.value)
    execOutput.value = (r && r.output) || '(无输出)'
  } catch (e: any) { execOutput.value = '执行失败：' + (e?.message || e) }
  finally { execLoading.value = false; ElMessage.success('执行完成') }
}

async function sendResultToAi() {
  const prompt = `以下是我执行命令的输出，请帮我分析：\n\`\`\`\n${execOutput.value}\n\`\`\`\n命令：${execCommand.value}`
  execVisible.value = false
  await store.send(prompt); scrollBottom()
}

const QUICK: Record<string, string[]> = {
  KAFKA: ['查看消费者组', '查看Topic列表', '查看Broker状态'],
  MYSQL: ['查看主从状态', '查看慢查询', '查看连接数'],
  REDIS: ['查看内存', '排查大Key'],
  NACOS: ['查看健康状态', '查看服务列表'],
  APP: ['查看日志', '重启应用', '查看端口占用', '查看JVM堆内存'],
  SERVER: ['查看磁盘', '查看内存', '查看CPU负载']
}
const quickTips = computed(() => { const c = store.context; return c ? (QUICK[c.type] || []) : [] })
</script>

<style scoped>
.ai-fab { position: fixed; right: 24px; bottom: 24px; width: 56px; height: 56px; border-radius: 50%;
  background: linear-gradient(135deg,#667eea,#764ba2); color:#fff; display:flex; align-items:center; justify-content:center;
  cursor:pointer; box-shadow:0 6px 20px rgba(102,126,234,.5); z-index:2000; animation: breathe 2.4s ease-in-out infinite; }
.ai-fab:hover { transform: scale(1.08); }
@keyframes breathe { 0%,100%{ box-shadow:0 6px 20px rgba(102,126,234,.5);} 50%{ box-shadow:0 6px 28px rgba(118,75,162,.85);} }

.copilot { display:flex; flex-direction:column; height:100%; }
.cp-topbar { display:flex; align-items:center; justify-content:space-between; padding:4px 0 8px; }
.cp-title { font-weight:700; font-size:15px; display:flex; align-items:center; gap:6px; }
.cp-status { display:flex; align-items:center; gap:6px; padding:6px 10px; border-radius:6px; font-size:13px; margin-bottom:8px; }
.cp-status.ok { background:#f0f9eb; color:#67c23a; }
.cp-status.warn { background:#fdf6ec; color:#e6a23c; }

.cp-body { flex:1; display:flex; overflow:hidden; }
.cp-sidebar { width:220px; border-right:1px solid #ebeef5; padding:8px; display:flex; flex-direction:column; }
.sb-head { display:flex; align-items:center; justify-content:space-between; font-weight:600; color:#606266; margin-bottom:6px; }
.sb-list { flex:1; overflow-y:auto; }
.sb-item { padding:8px 10px; border-radius:6px; cursor:pointer; display:flex; justify-content:space-between; align-items:center; position:relative; }
.sb-item:hover { background:#f5f7fa; }
.sb-item.active { background:#e6f0ff; border-left:3px solid #409eff; }
.sb-info { flex:1; min-width:0; }
.sb-title { font-size:13px; color:#303133; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.sb-time { font-size:11px; color:#909399; }
.sb-actions { display:none; gap:4px; color:#909399; }
.sb-item:hover .sb-actions { display:flex; }
.sb-actions .el-icon:hover { color:#409eff; }
.sb-empty { padding-top:12px; }

.cp-chat { flex:1; display:flex; flex-direction:column; min-width:0; padding:0 12px; }
.cp-context { margin:4px 0; padding:6px 10px; background:#f0f9eb; border:1px solid #e1f3d8; border-radius:6px; font-size:13px; display:flex; align-items:center; gap:6px; }
.cp-msgs { flex:1; overflow-y:auto; padding:6px 2px; }
.cp-empty { text-align:center; color:#909399; padding-top:40px; }
.cp-loading { color:#909399; font-size:13px; padding:6px 8px; }
.cp-quick { display:flex; flex-wrap:wrap; gap:6px; padding:4px 0; }
.quick-tag { cursor:pointer; }
.cp-input { border-top:1px solid #ebeef5; padding-top:8px; margin-top:8px; }
.cp-input-btns { display:flex; justify-content:space-between; align-items:center; margin-top:6px; }

.exec-cmd-label { color:#606266; font-size:13px; margin-bottom:4px; }
.exec-cmd-code { background:#0d1117; color:#c9d1d9; padding:10px; border-radius:6px; font-size:13px; white-space:pre-wrap; word-break:break-all; margin:0; }
.exec-suggest { margin-top:8px; display:flex; flex-wrap:wrap; gap:6px; align-items:center; }
.esc-label { font-size:12px; color:#909399; }
.sug-tag { cursor:pointer; }
.exec-term { margin-top:10px; background:#0d1117; color:#3ddc71; font-family:monospace; font-size:12.5px; padding:10px; border-radius:6px; max-height:220px; overflow-y:auto; white-space:pre-wrap; }
</style>