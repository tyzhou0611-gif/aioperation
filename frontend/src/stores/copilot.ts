import { defineStore } from 'pinia'
import { ref } from 'vue'
import request from '@/utils/request'
import type { ChatMessage, ChatSession, ContextAsset, Plan } from '@/types'

export const useCopilotStore = defineStore('copilot', () => {
  const visible = ref(false)
  const context = ref<ContextAsset | null>(null)
  const sessionId = ref<number | null>(null)
  const messages = ref<ChatMessage[]>([])
  const sessions = ref<ChatSession[]>([])
  const loading = ref(false)
  const mode = ref<string>('RULE')   // 当前回答来源

  function openWith(ctx?: ContextAsset) {
    if (ctx) { context.value = ctx; sessionId.value = null; messages.value = [] }
    visible.value = true
    loadSessions()
  }
  function close() { visible.value = false }
  function clearContext() { context.value = null; sessionId.value = null; messages.value = [] }
  function newSession() { sessionId.value = null; messages.value = [] }

  async function loadSessions() {
    const res = await request.get('/api/copilot/sessions')
    sessions.value = res.data || []
  }

  async function selectSession(id: number) {
    sessionId.value = id
    const res = await request.get(`/api/copilot/sessions/${id}/messages`)
    messages.value = (res.data || []).map((m: any) => ({
      id: m.id, role: m.role, content: m.content, createTime: m.createTime, plan: m.plan || null
    }))
  }

  async function removeSession(id: number) {
    await request.delete(`/api/copilot/sessions/${id}`)
    sessions.value = sessions.value.filter(s => s.id !== id)
    if (sessionId.value === id) {
      const next = sessions.value[0]
      if (next) { await selectSession(next.id) }
      else { sessionId.value = null; messages.value = [] }
    }
  }

  async function renameSession(id: number, title: string) {
    await request.patch(`/api/copilot/sessions/${id}`, { title })
    const s = sessions.value.find(x => x.id === id)
    if (s) s.title = title
  }

  async function clearAllSessions() {
    await request.delete('/api/copilot/sessions')
    sessions.value = []
    sessionId.value = null
    messages.value = []
  }

  async function send(text: string) {
    if (!text.trim() || loading.value) return
    const userMsg: ChatMessage = { id: Date.now(), role: 'user', content: text, createTime: '' }
    messages.value.push(userMsg)
    loading.value = true
    try {
      const body: any = { message: text, sessionId: sessionId.value }
      if (context.value) body.contextAsset = { type: context.value.type, id: context.value.id }
      const res = await request.post('/api/copilot/chat', body)
      const plan: Plan = res.data
      sessionId.value = plan.sessionId
      mode.value = plan.mode || 'RULE'
      messages.value.push({
        id: Date.now() + 1, role: 'assistant',
        content: plan.title, createTime: '', plan
      })
      loadSessions()
    } finally {
      loading.value = false
    }
  }

  async function executeOnServer(serverId: number, command: string) {
    const res = await request.post('/api/copilot/execute', { serverId, command })
    return res.data  // { success, output }
  }

  return {
    visible, context, sessionId, messages, sessions, loading, mode,
    openWith, close, clearContext, newSession,
    loadSessions, selectSession, removeSession, renameSession, clearAllSessions, send, executeOnServer
  }
})