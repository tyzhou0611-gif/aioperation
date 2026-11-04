<template>
  <div class="embedded">
    <div class="embed-hint" :class="{ strong: hint }">
      <el-icon><InfoFilled /></el-icon>
      <div class="hint-body">
        <div v-if="hint">智能大屏建议横屏浏览；复杂拖拽与编辑请使用桌面版。</div>
        <div v-else>该模块复用桌面版页面，表格支持左右滑动；复杂操作建议切换桌面版。</div>
        <button @click="openDesktop">在桌面版打开</button>
      </div>
    </div>

    <component :is="view" v-if="view" />
    <div v-else class="m-empty">页面加载失败：未找到对应模块</div>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, markRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { InfoFilled } from '@element-plus/icons-vue'
import { setViewMode } from '@/utils/device'

// 相对 Embedded.vue 的路径：../ = src/views
const viewModules = import.meta.glob('../**/*.vue')

const route = useRoute()
const router = useRouter()

const viewKey = computed(() => String(route.meta.view || ''))
const view = computed(() => {
  const key = viewKey.value ? `../${viewKey.value}.vue` : ''
  const loader = key ? (viewModules[key] as any) : null
  if (!loader) return null
  // import.meta.glob 返回的是 () => Promise<模块>，需要包成异步组件，
  // 否则 <component :is> 会把 Promise 当成组件渲染成 [object Promise]
  return markRaw(defineAsyncComponent(() => loader()))
})
const hint = computed(() => route.meta.embeddedHint === true)

function openDesktop() {
  setViewMode('desktop')
  const p = route.path.replace(/^\/m/, '') || '/dashboard'
  const map: Record<string, string> = {
    '/alert-rule': '/alert/rule',
    '/elk': '/asset/middleware/elk',
    '/servers': '/asset/server'
  }
  router.push(map[p] || p)
}
</script>

<style scoped>
.embedded {
  width: 100%;
  max-width: 100%;
  padding: 8px;
  box-sizing: border-box;
  overflow-x: auto;
}

.embed-hint {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 8px;
  padding: 9px 11px;
  border-radius: 11px;
  background: #fff7e8;
  border: 1px solid #ffe1b8;
  color: #b26a00;
  font-size: 11.5px;
  line-height: 1.5;
}

.embed-hint button {
  margin-top: 5px;
  border: 1px solid #f5c97a;
  background: #fff;
  color: #b26a00;
  padding: 4px 9px;
  border-radius: 7px;
  font-size: 11px;
  cursor: pointer;
}

.hint-body {
  min-width: 0;
}

.embed-hint.strong {
  background: #fff3e0;
}

/* 桌面页面嵌入手机时压缩标题与卡片留白 */
.embedded :deep(h2) {
  font-size: 18px !important;
  margin: 4px 0 10px !important;
}

.embedded :deep(.el-card) {
  border-radius: 12px;
}

.embedded :deep(.el-card__body) {
  padding: 10px;
}
</style>