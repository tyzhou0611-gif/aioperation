<template>
  <el-drawer :model-value="modelValue" :title="'一键命令 · ' + targetName" size="560px"
             @update:model-value="close" @close="reset">
    <!-- 目标信息确认 -->
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:14px">
      <template #title>
        目标：{{ targetName }}
        <span v-if="targetMeta">（{{ targetMeta }}）</span>
        · 经宿主服务器 SSH 执行
      </template>
    </el-alert>

    <!-- 预设命令模板 -->
    <div class="section-title">预设命令</div>
    <div class="cmd-grid">
      <el-card v-for="t in templates" :key="t.id" shadow="hover" class="cmd-card"
               :class="{ danger: t.dangerous === 1 }" @click="runTemplate(t)">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <strong>{{ t.name }}</strong>
          <el-tag v-if="t.dangerous === 1" type="danger" size="small">危险</el-tag>
        </div>
        <div class="cmd-code">{{ t.command }}</div>
        <div class="cmd-desc">{{ t.description }}</div>
      </el-card>
      <el-empty v-if="!templates.length" description="该类型暂无预设命令" :image-size="60" />
    </div>

    <!-- 自定义命令 -->
    <div class="section-title" style="margin-top:16px">自定义命令（管理员）</div>
    <el-input v-model="customCommand" type="textarea" :rows="2" placeholder="输入要执行的 Shell 命令" />
    <el-checkbox v-model="riskAck" style="margin-top:8px">我确认已了解风险，允许执行自定义命令</el-checkbox>
    <div style="margin-top:10px">
      <el-button type="primary" :disabled="!canExecCustom" :loading="running" @click="runCustom">执行自定义命令</el-button>
    </div>

    <!-- 终端输出 -->
    <div class="section-title" style="margin-top:18px">执行结果</div>
    <div class="terminal">
      <div class="term-line" v-for="(line, i) in termLines" :key="i">{{ line }}</div>
      <div v-if="running" class="term-cursor">▋ 执行中…</div>
      <div v-if="!termLines.length && !running" class="term-hint">$ 等待执行…</div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CommandTemplate } from '@/types'

const props = defineProps<{
  modelValue: boolean
  targetType: string        // SERVER / MIDDLEWARE / APP
  targetId: number | null
  targetName: string
  middlewareType?: string
  targetMeta?: string
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const templates = ref<CommandTemplate[]>([])
const customCommand = ref('')
const riskAck = ref(false)
const running = ref(false)
const termLines = ref<string[]>([])

const BLACKLIST = ['rm -rf', 'rm -fr', 'mkfs', 'dd if=', 'shutdown', 'reboot', 'chmod -R 777 /']
const isBlacklisted = (c: string) => BLACKLIST.some(b => (c || '').toLowerCase().includes(b))
const canExecCustom = computed(() => riskAck.value && !!customCommand.value.trim() && !isBlacklisted(customCommand.value))

async function loadTemplates() {
  const res = await request.get('/api/exec/templates', {
    params: { targetType: props.targetType, middlewareType: props.middlewareType || undefined }
  })
  templates.value = res.data || []
}

watch(() => props.modelValue, (v) => { if (v) loadTemplates() })

async function runTemplate(t: CommandTemplate) {
  if (isBlacklisted(t.command)) { ElMessage.error('该命令被安全策略拦截'); return }
  if (t.dangerous === 1) {
    try { await ElMessageBox.confirm(`命令「${t.name}」为危险操作，确认执行？`, '二次确认', { type: 'warning' }) }
    catch { return }
  }
  submit(t.command)
}

function runCustom() { submit(customCommand.value) }

async function submit(command: string) {
  running.value = true
  termLines.value = [`$ ${command}`, `# 目标: ${props.targetName}`, `# 提交中…`]
  const res = await request.post('/api/exec/quick', {
    targetType: props.targetType, targetId: props.targetId, command
  })
  const task = res.data
  if (task && task.status !== 'RUNNING') {
    termLines.value = [`$ ${command}`, `> ${task.output || task.status}`]
    running.value = false
    return
  }
  poll(task.taskId, command)
}

function poll(taskId: string, command: string) {
  const timer = setInterval(async () => {
    const res = await request.get(`/api/exec/quick/${taskId}`)
    const t = res.data
    if (t.status === 'RUNNING') return
    clearInterval(timer)
    const lines = [`$ ${command}`, `# 目标: ${props.targetName} (${t.targetIp || 'N/A'})`]
    lines.push(...(t.output || '').split('\n'))
    lines.push(`\n# 结果: ${t.status}`)
    termLines.value = lines
    running.value = false
  }, 900)
}

function reset() { customCommand.value = ''; riskAck.value = false; termLines.value = [] }
function close() { emit('update:modelValue', false) }
</script>

<style scoped>
.section-title { font-weight: 600; color: #333; margin-bottom: 8px; }
.cmd-grid { display: flex; flex-wrap: wrap; gap: 10px; }
.cmd-card { width: calc(50% - 5px); cursor: pointer; }
.cmd-card.danger { border-color: #f56c6c; }
.cmd-code { font-family: monospace; font-size: 12px; color: #67c23a; background: #0f1419; padding: 4px 6px; border-radius: 4px; margin: 6px 0; word-break: break-all; }
.cmd-desc { font-size: 12px; color: #999; }
.terminal { background: #0d1117; color: #3ddc71; font-family: 'Consolas', monospace; font-size: 13px; padding: 12px; border-radius: 8px; min-height: 160px; max-height: 320px; overflow-y: auto; white-space: pre-wrap; }
.term-line { line-height: 1.5; }
.term-cursor { color: #3ddc71; animation: blink 1s infinite; }
.term-hint { color: #555; }
@keyframes blink { 50% { opacity: 0; } }
</style>