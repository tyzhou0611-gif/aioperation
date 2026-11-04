<template>
  <div class="ct-card">
    <span class="ct-halftone"></span>
    <span class="ct-spark s1">✦</span>
    <span class="ct-spark s2">✧</span>
    <span class="ct-spark s3">✦</span>

    <div class="ct-head">
      <div class="ct-avatar-wrap">
        <span class="ct-avatar-ring"></span>
        <CopilotAvatar :size="42" state="thinking" />
      </div>
      <div class="ct-title-wrap">
        <div class="ct-title">
          <span>小维正在思考</span>
          <span class="ct-dots"><i></i><i></i><i></i></span>
          <span class="ct-elapsed">{{ elapsedText }}</span>
        </div>
        <div class="ct-sub">
          {{ context ? `正在分析：${context.name || context.type}` : '正在理解你的运维问题' }}
        </div>
      </div>
      <div class="ct-wave" aria-hidden="true">
        <span v-for="n in 5" :key="n" :style="{ animationDelay: (n * 0.11) + 's' }"></span>
      </div>
    </div>

    <div class="ct-timeline">
      <div v-for="(s, i) in STAGES" :key="i" :class="['ct-step', { done: i < activeIndex, active: i === activeIndex }]">
        <div class="ct-node">
          <el-icon v-if="i < activeIndex"><Check /></el-icon>
          <el-icon v-else><component :is="s.icon" /></el-icon>
        </div>
        <div class="ct-step-body">
          <div class="ct-step-label">{{ s.label }}</div>
          <div class="ct-step-hint">{{ i < activeIndex ? s.doneHint : s.hint }}</div>
          <div v-if="i === activeIndex" class="ct-progress"><span></span></div>
        </div>
      </div>
    </div>

    <div class="ct-foot">
      <span class="ct-foot-dot"></span>
      思考过程会随回答一并展示
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Check, ChatDotRound, Coin, Collection, Cpu, List } from '@element-plus/icons-vue'
import CopilotAvatar from './CopilotAvatar.vue'
import type { ContextAsset } from '@/types'

const props = defineProps<{ context?: ContextAsset | null }>()

const STAGES = [
  { label: '理解问题与意图', hint: '分析你的诉求和当前所在页面', doneHint: '已理解问题与目标', icon: ChatDotRound },
  { label: '读取资产上下文', hint: props.context ? `当前资产：${props.context.name || props.context.type}` : '未指定资产，按通用运维问题分析', doneHint: props.context ? `已读取：${props.context.name || props.context.type}` : '已切换为通用运维分析', icon: Coin },
  { label: '检索知识库与历史方案', hint: '匹配相关文档、会话与内置模板', doneHint: '已完成知识匹配', icon: Collection },
  { label: '大模型推理', hint: '生成排查思路与可执行命令', doneHint: '已生成排查方案', icon: Cpu },
  { label: '整理执行方案', hint: '校验风险、跳转路径与执行建议', doneHint: '方案整理完成', icon: List }
]

// 仅用于展示节奏：不阻塞请求，回答返回后整个卡片会被替换为最终消息
const THRESHOLDS = [700, 1600, 2800, 4200]
const elapsed = ref(0)
let timer: any = null
const startedAt = Date.now()

const activeIndex = computed(() => {
  let idx = 0
  for (let i = 0; i < THRESHOLDS.length; i++) {
    if (elapsed.value >= THRESHOLDS[i]) idx = i + 1
  }
  return Math.min(idx, STAGES.length - 1)
})
const elapsedText = computed(() => (elapsed.value / 1000).toFixed(1) + 's')

onMounted(() => {
  timer = setInterval(() => { elapsed.value = Date.now() - startedAt }, 120)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.ct-card {
  position: relative; margin: 10px 2px; padding: 14px 16px 10px;
  border-radius: 18px; overflow: hidden;
  background: linear-gradient(145deg, #ffffff 0%, #f6f8ff 55%, #f2f0ff 100%);
  border: 1px solid #e3e8ff;
  box-shadow: 0 8px 26px rgba(102, 126, 234, .12), inset 0 1px 0 rgba(255, 255, 255, .9);
  font-size: 13px; color: #4b5563;
}
.ct-halftone {
  position: absolute; inset: 0; pointer-events: none; opacity: .35;
  background-image: radial-gradient(#c9d4ff 1px, transparent 1.2px);
  background-size: 11px 11px;
  mask-image: linear-gradient(120deg, transparent 40%, #000 100%);
  -webkit-mask-image: linear-gradient(120deg, transparent 40%, #000 100%);
}
.ct-spark { position: absolute; color: #9aa8ff; pointer-events: none; animation: ct-twinkle 2.2s ease-in-out infinite; }
.ct-spark.s1 { right: 18px; top: 14px; font-size: 14px; }
.ct-spark.s2 { right: 54px; top: 34px; font-size: 10px; color: #e49cff; animation-delay: .7s; }
.ct-spark.s3 { right: 30px; bottom: 40px; font-size: 8px; color: #ff9ec4; animation-delay: 1.3s; }

.ct-head { position: relative; display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.ct-avatar-wrap { position: relative; width: 46px; height: 46px; display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.ct-avatar-ring {
  position: absolute; inset: -2px; border-radius: 50%;
  background: conic-gradient(from 0deg, rgba(125, 134, 247, .0), rgba(125, 134, 247, .65), rgba(165, 101, 232, .65), rgba(125, 134, 247, 0));
  animation: ct-spin 3.2s linear infinite; filter: blur(.4px);
}
.ct-avatar-wrap :deep(.copilot-avatar) { position: relative; z-index: 1; }
.ct-title-wrap { flex: 1; min-width: 0; }
.ct-title { display: flex; align-items: baseline; gap: 6px; font-weight: 700; color: #303133; font-size: 14px; }
.ct-sub { margin-top: 2px; color: #8a94b8; font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ct-elapsed { margin-left: auto; font-size: 11px; color: #a0a8c8; font-weight: 500; font-variant-numeric: tabular-nums; }
.ct-dots { display: inline-flex; gap: 3px; }
.ct-dots i { width: 5px; height: 5px; border-radius: 50%; background: #7d86f7; animation: ct-bounce 1.1s ease-in-out infinite; }
.ct-dots i:nth-child(2) { animation-delay: .15s; background: #9a7cf0; }
.ct-dots i:nth-child(3) { animation-delay: .3s; background: #c78ce8; }
.ct-wave { display: flex; align-items: center; gap: 2.5px; height: 20px; padding-right: 4px; }
.ct-wave span { width: 3px; height: 6px; border-radius: 2px; background: linear-gradient(#9db2ff, #c78ce8); animation: ct-wave 1s ease-in-out infinite; }
.ct-wave span:nth-child(2n) { background: linear-gradient(#c78ce8, #ff9ec4); }

.ct-timeline { position: relative; margin-left: 4px; }
.ct-step { position: relative; display: flex; gap: 10px; padding-bottom: 9px; }
.ct-step:not(:last-child)::before {
  content: ''; position: absolute; left: 12px; top: 26px; bottom: 0; width: 2px;
  background: linear-gradient(#e1e6ff, #eef1ff);
}
.ct-step.done::before { background: linear-gradient(#8fb6ff, #c9b6ff); }
.ct-node {
  position: relative; z-index: 1; width: 26px; height: 26px; border-radius: 50%; flex: 0 0 auto;
  display: flex; align-items: center; justify-content: center; font-size: 13px;
  color: #a6afcf; background: #eef1fb; border: 1px solid #e1e6f7; transition: all .25s;
}
.ct-step.done .ct-node { color: #fff; background: linear-gradient(135deg, #7d9bff, #a565e8); border-color: transparent; box-shadow: 0 2px 8px rgba(125, 134, 247, .35); }
.ct-step.active .ct-node { color: #fff; background: linear-gradient(135deg, #6f8cff, #b06cf0); border-color: transparent; box-shadow: 0 0 0 4px rgba(125, 134, 247, .16), 0 3px 10px rgba(125, 134, 247, .4); animation: ct-pulse 1.6s ease-in-out infinite; }
.ct-step-body { flex: 1; min-width: 0; padding-top: 2px; }
.ct-step-label { font-size: 12.5px; color: #8b93ad; font-weight: 600; transition: color .25s; }
.ct-step.done .ct-step-label { color: #5e6a8f; }
.ct-step.active .ct-step-label { color: #4b57c9; }
.ct-step-hint { font-size: 11.5px; color: #aab1c8; margin-top: 1px; }
.ct-step.active .ct-step-hint { color: #7a86ad; }
.ct-progress { margin-top: 6px; height: 3px; border-radius: 99px; background: #e7ebff; overflow: hidden; }
.ct-progress span { display: block; height: 100%; width: 45%; border-radius: 99px; background: linear-gradient(90deg, #7d86f7, #c78ce8, #7d86f7); background-size: 200% 100%; animation: ct-shimmer 1.2s linear infinite; }
.ct-foot { display: flex; align-items: center; gap: 6px; padding: 4px 0 0 38px; color: #a6aecb; font-size: 11px; }
.ct-foot-dot { width: 5px; height: 5px; border-radius: 50%; background: #c7d0ff; box-shadow: 0 0 0 3px rgba(199, 208, 255, .25); }

@keyframes ct-bounce { 0%, 100% { transform: translateY(0); opacity: .45; } 50% { transform: translateY(-3px); opacity: 1; } }
@keyframes ct-wave { 0%, 100% { height: 5px; opacity: .5; } 50% { height: 17px; opacity: 1; } }
@keyframes ct-spin { to { transform: rotate(360deg); } }
@keyframes ct-pulse { 0%, 100% { box-shadow: 0 0 0 4px rgba(125, 134, 247, .12), 0 3px 10px rgba(125, 134, 247, .35); } 50% { box-shadow: 0 0 0 7px rgba(125, 134, 247, .05), 0 3px 14px rgba(125, 134, 247, .55); } }
@keyframes ct-twinkle { 0%, 100% { opacity: .25; transform: scale(.85); } 50% { opacity: 1; transform: scale(1.15); } }
@keyframes ct-shimmer { from { transform: translateX(-60%); } to { transform: translateX(180%); } }

@media (prefers-reduced-motion: reduce) {
  .ct-spark, .ct-avatar-ring, .ct-dots i, .ct-wave span, .ct-step.active .ct-node, .ct-progress span { animation: none !important; }
}
</style>