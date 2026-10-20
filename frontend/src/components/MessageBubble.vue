<template>
  <!-- 用户气泡 -->
  <div v-if="msg.role === 'user'" class="row user">
    <div class="bubble user-bubble">{{ msg.content }}</div>
  </div>

  <!-- AI 气泡 -->
  <div v-else class="row ai">
    <div class="bubble ai-bubble">
      <template v-if="msg.plan">
        <div class="p-title">
          {{ msg.plan.title }}
          <el-tag size="small" :type="msg.plan.mode === 'LLM' ? 'success' : 'info'" style="margin-left:6px">
            {{ msg.plan.mode === 'LLM' ? '大模型' : '规则库' }}
          </el-tag>
        </div>
        <div class="p-summary" v-if="msg.plan.summary">{{ msg.plan.summary }}</div>

        <ol class="p-steps" v-if="msg.plan.steps && msg.plan.steps.length">
          <li v-for="(s, i) in msg.plan.steps" :key="i">{{ s }}</li>
        </ol>

        <CodeBlock
          v-for="(c, i) in (msg.plan.codeBlocks || [])" :key="i"
          :block="c" :executable="canExec"
          @execute="(code, ss) => $emit('execute', code, ss, c.label)" />

        <el-alert v-if="msg.plan.riskTip" :type="riskType" :closable="false" show-icon
                  :title="msg.plan.riskTip" style="margin-top:6px" />

        <!-- 智能跳转按钮 -->
        <div v-if="msg.plan.navigation && isKnownTarget(msg.plan.navigation.target)"
             class="navigation-btn" @click="$emit('navigate', msg.plan.navigation)">
          <el-icon><Position /></el-icon>
          <span>{{ msg.plan.navigation.label }}</span>
          <el-tooltip v-if="msg.plan.navigation.reason || msg.plan.navigation.hasWarning" placement="top">
            <template #content>
              {{ msg.plan.navigation.hasWarning ? ('⚠ ' + (msg.plan.navigation.warningMessage || '')) : msg.plan.navigation.reason }}
            </template>
            <el-icon class="nav-info" :class="{ warn: msg.plan.navigation.hasWarning }"><InfoFilled /></el-icon>
          </el-tooltip>
        </div>
      </template>
      <div v-else>{{ msg.content }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import CodeBlock from './CodeBlock.vue'
import { isKnownTarget } from '@/utils/routerHelper'
import type { ChatMessage, NavigationHint, SuggestedServer } from '@/types'

const props = defineProps<{ msg: ChatMessage; canExec?: boolean }>()
defineEmits<{
  (e: 'execute', code: string, suggestedServers: SuggestedServer[], label: string): void
  (e: 'navigate', nav: NavigationHint): void
}>()

const riskType = computed(() => {
  const r = props.msg.plan?.riskLevel
  return r === 'HIGH' ? 'error' : r === 'MEDIUM' ? 'warning' : 'info'
})
</script>

<style scoped>
.navigation-btn {
  display: inline-flex; align-items: center; gap: 6px; margin-top: 10px;
  padding: 7px 16px; border-radius: 999px; cursor: pointer;
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
  color: #fff; font-size: 13px; font-weight: 600;
  box-shadow: 0 2px 10px rgba(79, 172, 254, .4); transition: all .2s;
}
.navigation-btn:hover { transform: translateY(-1px); box-shadow: 0 4px 14px rgba(79, 172, 254, .6); }
.nav-info { font-size: 14px; opacity: .85; }
.nav-info.warn { color: #ffe08a; }
</style>

<style scoped>
.row { display: flex; margin: 8px 0; }
.row.user { justify-content: flex-end; }
.row.ai { justify-content: flex-start; }
.bubble { max-width: 88%; padding: 10px 12px; border-radius: 10px; font-size: 14px; line-height: 1.6; }
.user-bubble { background: #409eff; color: #fff; border-bottom-right-radius: 2px; }
.ai-bubble { background: #fff; border: 1px solid #ebeef5; border-bottom-left-radius: 2px; box-shadow: 0 1px 3px rgba(0,0,0,.04); }
.p-title { font-weight: 700; color: #303133; margin-bottom: 4px; }
.p-summary { color: #606266; margin-bottom: 6px; }
.p-steps { margin: 4px 0; padding-left: 20px; color: #303133; }
.p-steps li { margin: 3px 0; }
</style>