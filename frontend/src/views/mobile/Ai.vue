<template>
  <div class="ai-page">
    <!-- 状态条 -->
    <div class="ai-bar">
      <div class="ai-status" :class="aiInfo.reachable ? 'ok' : 'warn'">
        <i></i>
        <span>{{ aiInfo.reachable ? `小维在线 · ${providerLabel}` : (aiInfo.provider === 'none' ? '本地规则模式' : '大模型不可用 · 规则降级') }}</span>
      </div>
      <div class="ai-bar-actions">
        <button @click="sessionsVisible = true"><el-icon><Clock /></el-icon>历史</button>
        <button @click="newSession"><el-icon><Plus /></el-icon>新会话</button>
      </div>
    </div>

    <!-- 对话区 -->
    <div ref="bodyRef" class="ai-msgs">
      <div v-if="store.context" class="context-chip">
        上下文：{{ store.context.name }}
        <button @click="store.clearContext()">×</button>
      </div>

      <div v-if="!store.messages.length" class="ai-empty">
        <CopilotAvatar :size="108" :state="store.loading ? 'thinking' : 'idle'" />
        <div class="hello">在的！我是小维</div>
        <div class="hello-sub">可以直接问我故障排查、命令写法、变更影响，也能让我带你去对应页面。</div>
        <div class="prompt-list">
          <button v-for="p in prompts" :key="p" @click="send(p)">{{ p }}</button>
        </div>
      </div>

      <MessageBubble
        v-for="m in store.messages"
        :key="m.id"
        :msg="m"
        :can-exec="false"
        @navigate="onNavigate"
        @execute="() => {}"
      />
      <CopilotThinking v-if="store.loading" :context="store.context" />
    </div>

    <!-- 快捷问题 -->
    <div v-if="store.messages.length" class="ai-quick">
      <button v-for="p in prompts.slice(0, 3)" :key="p" @click="send(p)">{{ p }}</button>
    </div>

    <!-- 输入区 -->
    <div class="ai-input-bar">
      <div class="ai-input">
        <el-input
          v-model="input"
          type="textarea"
          :rows="1"
          :autosize="{ minRows: 1, maxRows: 4 }"
          resize="none"
          placeholder="描述你的运维问题…"
          @keydown.enter.exact.prevent="onEnter"
        />
        <button class="send-btn" :disabled="!input.trim() || store.loading" @click="doSend">
          <el-icon :size="18"><Promotion /></el-icon>
        </button>
      </div>
      <div class="input-hint">按 Enter 发送 · 回答由 {{ providerLabel }} 生成，请核对后再执行</div>
    </div>

    <!-- 历史会话 -->
    <el-drawer v-model="sessionsVisible" direction="btt" size="68%" title="历史会话" class="ai-sessions-drawer">
      <div class="session-list">
        <div v-for="s in store.sessions" :key="s.id" class="session-item" :class="{ active: s.id === store.sessionId }" @click="selectSession(s.id)">
          <div class="session-main">
            <div class="session-title">{{ s.title || '新对话' }}</div>
            <div class="m-hint">{{ fmtTime(s.updateTime, false) }} · {{ s.messageCount || 0 }} 条</div>
          </div>
          <el-icon class="del" @click.stop="delSession(s.id)"><Delete /></el-icon>
        </div>
        <div v-if="!store.sessions.length" class="m-empty">暂无历史会话</div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { useCopilotStore } from '@/stores/copilot'
import MessageBubble from '@/components/MessageBubble.vue'
import CopilotAvatar from '@/components/CopilotAvatar.vue'
import CopilotThinking from '@/components/CopilotThinking.vue'
import { Clock, Plus, Promotion, Delete } from '@element-plus/icons-vue'
import { navigateByTarget } from '@/utils/routerHelper'
import { fmtTime } from '@/utils/mobile'
import type { NavigationHint } from '@/types'

const router = useRouter()
const store = useCopilotStore()
const input = ref('')
const bodyRef = ref<HTMLElement>()
const sessionsVisible = ref(false)
const aiInfo = ref<any>({ provider: 'none', reachable: false })

const providerLabel = computed(() =>
  ({ hiagent: 'HiAgent', anthropic: 'Anthropic', claude: 'Anthropic', deepseek: 'DeepSeek', openai: 'OpenAI', qwen: '通义千问', none: '本地规则' } as any)[aiInfo.value.provider] || aiInfo.value.provider || 'AI')

const prompts = [
  '帮我看看当前有哪些主机离线',
  '最近有没有严重告警？',
  '如何排查 kafka 消费延迟',
  '给出一份服务器巡检命令'
]

async function loadAiInfo() {
  try {
    const r = await request.get('/api/system/config/ai/status')
    aiInfo.value = r.data || aiInfo.value
  } catch { /* ignore */ }
}

function scrollBottom() {
  nextTick(() => {
    if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
  })
}

async function send(text: string) {
  const t = text.trim()
  if (!t || store.loading) return
  await store.send(t)
  scrollBottom()
}

async function doSend() {
  const t = input.value.trim()
  if (!t || store.loading) return
  input.value = ''
  await send(t)
}

function onEnter(e: KeyboardEvent) {
  if (e.isComposing) return
  doSend()
}

function newSession() {
  store.newSession()
  input.value = ''
  scrollBottom()
}

async function selectSession(id: number) {
  await store.selectSession(id)
  sessionsVisible.value = false
  scrollBottom()
}

async function delSession(id: number) {
  try {
    await ElMessageBox.confirm('删除该会话？', '提示', { type: 'warning' })
    await store.removeSession(id)
    ElMessage.success('已删除')
  } catch { /* cancel */ }
}

function onNavigate(nav: NavigationHint) {
  if (!navigateByTarget(router, nav)) ElMessage.warning('暂不支持跳转到该页面')
}

watch(() => store.messages.length, scrollBottom)
watch(() => store.loading, scrollBottom)
watch(sessionsVisible, v => { if (v) store.loadSessions() })

onMounted(() => {
  loadAiInfo()
  store.loadSessions()
  scrollBottom()
})
</script>

<style scoped>
.ai-page {
  height: 100%;
  min-height: calc(100dvh - 64px);
  max-height: 100dvh;
  display: flex;
  flex-direction: column;
  background: var(--m-bg);
  overflow: hidden;
}

.ai-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  background: #fff;
  border-bottom: 1px solid var(--m-border);
}

.ai-status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: #64748b;
  min-width: 0;
}

.ai-status i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--m-orange);
  box-shadow: 0 0 6px rgba(245, 158, 11, 0.6);
  flex: 0 0 auto;
}

.ai-status.ok i {
  background: var(--m-green);
  box-shadow: 0 0 6px rgba(16, 185, 129, 0.6);
}

.ai-status span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-bar-actions {
  display: flex;
  gap: 4px;
  flex: 0 0 auto;
}

.ai-bar-actions button {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: 1px solid var(--m-border);
  background: #f8fafd;
  color: #475569;
  font-size: 11.5px;
  padding: 5px 8px;
  border-radius: 8px;
  cursor: pointer;
}

.ai-msgs {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 12px 6px;
  -webkit-overflow-scrolling: touch;
}

.context-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  padding: 4px 9px;
  border-radius: 999px;
  background: #e8f1ff;
  color: #1677ff;
  font-size: 11px;
}

.context-chip button {
  border: none;
  background: transparent;
  color: #1677ff;
  font-size: 13px;
  cursor: pointer;
}

.ai-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 22px 8px 12px;
  text-align: center;
}

.hello {
  margin-top: 4px;
  font-size: 18px;
  font-weight: 700;
  color: var(--m-text);
}

.hello-sub {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--m-sub);
  max-width: 300px;
}

.prompt-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  max-width: 340px;
  margin-top: 16px;
}

.prompt-list button {
  width: 100%;
  text-align: left;
  padding: 10px 12px;
  border-radius: 11px;
  border: 1px solid var(--m-border);
  background: #fff;
  color: #334155;
  font-size: 12.5px;
  box-shadow: var(--m-shadow);
  cursor: pointer;
}

.prompt-list button:active {
  background: #f8fbff;
  border-color: #b7d5ff;
}

.ai-quick {
  flex: 0 0 auto;
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding: 6px 12px;
  background: var(--m-bg);
  scrollbar-width: none;
}

.ai-quick::-webkit-scrollbar { display: none; }

.ai-quick button {
  flex: 0 0 auto;
  border: 1px solid var(--m-border);
  background: #fff;
  color: #64748b;
  font-size: 11px;
  padding: 5px 9px;
  border-radius: 999px;
  cursor: pointer;
}

.ai-input-bar {
  flex: 0 0 auto;
  padding: 8px 10px calc(8px + env(safe-area-inset-bottom, 0px));
  background: #fff;
  border-top: 1px solid var(--m-border);
}

.ai-input {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.ai-input :deep(.el-textarea__inner) {
  border-radius: 12px;
  padding: 8px 11px;
  font-size: 13.5px;
  line-height: 1.5;
  background: #f7f9fc;
  box-shadow: 0 0 0 1px var(--m-border) inset;
}

.send-btn {
  flex: 0 0 auto;
  width: 38px;
  height: 38px;
  border-radius: 12px;
  border: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: linear-gradient(135deg, #1677ff, #06b6d4);
  box-shadow: 0 6px 14px rgba(22, 119, 255, 0.3);
  cursor: pointer;
}

.send-btn:disabled {
  opacity: 0.45;
  box-shadow: none;
}

.input-hint {
  margin-top: 4px;
  text-align: center;
  font-size: 9.5px;
  color: var(--m-muted);
}

.session-list {
  padding: 0 4px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px;
  border-radius: 12px;
  border: 1px solid var(--m-border);
  background: #fff;
  margin-bottom: 8px;
  cursor: pointer;
}

.session-item.active {
  border-color: #b7d5ff;
  background: #f5f9ff;
}

.session-main {
  flex: 1;
  min-width: 0;
}

.session-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--m-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-item .del {
  color: #b6c2d1;
  font-size: 16px;
}
</style>