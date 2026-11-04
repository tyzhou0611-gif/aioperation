<template>
  <div class="cb">
    <div class="cb-head">
      <span class="cb-label">{{ block.label }}</span>
      <span class="cb-lang">{{ block.language }}</span>
      <el-button link size="small" @click="copy">复制</el-button>
      <el-button v-if="executable" link size="small" type="success" @click="tryExec">执行到服务器</el-button>
    </div>
    <pre class="cb-code">{{ block.code }}</pre>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CodeBlock, SuggestedServer } from '@/types'

const props = defineProps<{
  block: CodeBlock
  executable?: boolean
}>()
const emit = defineEmits<{ (e: 'execute', code: string, suggestedServers: SuggestedServer[]): void }>()

const DANGEROUS = ['rm -rf', 'rm -fr', 'mkfs', 'dd if=', 'shutdown', 'reboot']
const isDangerous = computed(() => DANGEROUS.some(d => (props.block.code || '').toLowerCase().includes(d)))

async function copy() {
  try { await navigator.clipboard.writeText(props.block.code); ElMessage.success('已复制') }
  catch { ElMessage.error('复制失败，请手动选择') }
}

async function tryExec() {
  if (isDangerous.value) {
    try {
      await ElMessageBox.prompt('该命令包含危险操作，请输入 CONFIRM 确认执行：', '危险命令二次确认', {
        inputValidator: (v) => v === 'CONFIRM' || '必须输入 CONFIRM', type: 'warning'
      })
    } catch { return }
  }
  emit('execute', props.block.code, props.block.suggestedServers || [])
}
</script>

<style scoped>
.cb { border: 1px solid #ebeef5; border-radius: 6px; overflow: hidden; margin: 8px 0; }
.cb-head { display: flex; align-items: center; gap: 8px; padding: 6px 10px; background: #f5f7fa; font-size: 12px; }
.cb-label { color: #606266; font-weight: 600; }
.cb-lang { color: #909399; background: #eef0f3; padding: 1px 6px; border-radius: 3px; }
.cb-head .el-button { margin-left: auto; }
.cb-code { margin: 0; padding: 10px 12px; background: #0d1117; color: #c9d1d9; font-family: 'Consolas', monospace; font-size: 12.5px; white-space: pre-wrap; word-break: break-all; }
</style>