<template>
  <div class="m-app">
    <!-- 顶部栏 -->
    <header class="m-header">
      <div class="m-header-left">
        <button v-if="showBack" class="m-icon-btn" aria-label="返回" @click="goBack">
          <el-icon :size="20"><ArrowLeft /></el-icon>
        </button>
        <div class="m-header-title">
          <span class="m-header-main">{{ title }}</span>
          <span v-if="!showBack" class="m-header-sub">AI-OPS · MOBILE</span>
        </div>
      </div>
      <div class="m-header-right">
        <button v-if="route.path !== '/m/ai'" class="m-icon-btn ai" aria-label="AI 助手" @click="router.push('/m/ai')">
          <CopilotAvatar :size="26" />
        </button>
        <button class="m-user-btn" aria-label="我的" @click="router.push('/m/more')">
          {{ userInitial }}
        </button>
      </div>
    </header>

    <!-- 页面内容 -->
    <main class="m-main" :class="{ 'no-tabbar': hideTabbar }">
      <router-view v-slot="{ Component }">
        <transition name="m-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <!-- 底部导航 -->
    <nav v-if="!hideTabbar" class="m-tabbar">
      <button v-for="t in tabs" :key="t.path" class="m-tab" :class="{ active: isActive(t) }" @click="onTab(t)">
        <el-icon :size="20"><component :is="t.icon" /></el-icon>
        <span>{{ t.label }}</span>
      </button>
    </nav>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Bell, ChatDotRound, Grid, HomeFilled, Monitor } from '@element-plus/icons-vue'
import CopilotAvatar from '@/components/CopilotAvatar.vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const tabs = [
  { path: '/m/home', label: '总览', icon: HomeFilled, match: ['/m/home'] },
  { path: '/m/servers', label: '主机', icon: Monitor, match: ['/m/servers'] },
  { path: '/m/alerts', label: '告警', icon: Bell, match: ['/m/alerts'] },
  { path: '/m/ai', label: 'AI', icon: ChatDotRound, match: ['/m/ai'] },
  { path: '/m/more', label: '更多', icon: Grid, match: [] }
]

const title = computed(() => String(route.meta.title || 'AI-Ops'))
const hideTabbar = computed(() => route.meta.hideTabbar === true)
const rootPaths = ['/m/home', '/m/servers', '/m/alerts', '/m/more']
const showBack = computed(() => route.path === '/m/ai' || !rootPaths.includes(route.path))
const userInitial = computed(() => {
  const name = userStore.userInfo?.nickname || userStore.userInfo?.username || 'A'
  return String(name).trim().slice(0, 1).toUpperCase()
})

function isActive(t: { path: string; match: string[] }) {
  if (t.path === '/m/more') {
    return !['/m/home', '/m/servers', '/m/alerts', '/m/ai'].some(p => route.path === p || route.path.startsWith(p + '/'))
  }
  return route.path === t.path || route.path.startsWith(t.path + '/')
}

function onTab(t: { path: string }) {
  if (route.path !== t.path) router.push(t.path)
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/m/home')
}
</script>

<style scoped>
.m-app {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  background: var(--m-bg);
}

.m-header {
  position: sticky;
  top: 0;
  z-index: 50;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: calc(8px + env(safe-area-inset-top, 0px)) 12px 8px;
  color: #e2e8f0;
  background:
    radial-gradient(ellipse at 10% 0%, rgba(34, 211, 238, 0.2), transparent 46%),
    radial-gradient(ellipse at 92% 0%, rgba(139, 92, 246, 0.2), transparent 44%),
    linear-gradient(150deg, #0b1224, #101a30 60%, #0a1322);
  box-shadow: 0 6px 18px rgba(5, 12, 26, 0.2);
}

.m-header-left {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.m-icon-btn {
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 11px;
  background: rgba(148, 163, 184, 0.12);
  color: #cbd5e1;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.m-icon-btn:active { background: rgba(148, 163, 184, 0.24); }

.m-icon-btn.ai {
  overflow: hidden;
  background: rgba(56, 189, 248, 0.1);
  border: 1px solid rgba(56, 189, 248, 0.28);
}

.m-header-title {
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}

.m-header-main {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.m-header-sub {
  margin-top: 3px;
  font-size: 9px;
  letter-spacing: 1.8px;
  color: #7f9bc4;
  font-family: Consolas, monospace;
}

.m-header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.m-user-btn {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  border: 1px solid rgba(56, 189, 248, 0.3);
  background: linear-gradient(135deg, rgba(34, 211, 238, 0.2), rgba(139, 92, 246, 0.22));
  color: #e0f2fe;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
}

.m-main {
  flex: 1;
  min-height: 0;
  padding-bottom: calc(66px + env(safe-area-inset-bottom, 0px));
}

.m-main.no-tabbar {
  padding-bottom: env(safe-area-inset-bottom, 0px);
}

.m-tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 60;
  display: flex;
  align-items: stretch;
  justify-content: space-around;
  padding: 6px 4px calc(6px + env(safe-area-inset-bottom, 0px));
  background: rgba(255, 255, 255, 0.96);
  border-top: 1px solid #e8edf5;
  box-shadow: 0 -8px 24px rgba(15, 35, 70, 0.07);
  backdrop-filter: blur(14px);
}

.m-tab {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border: none;
  background: transparent;
  color: #8b98ab;
  font-size: 10.5px;
  padding: 4px 0 2px;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: color 0.15s ease;
}

.m-tab.active {
  color: var(--m-primary);
  font-weight: 600;
}

.m-tab.active .el-icon {
  filter: drop-shadow(0 0 6px rgba(22, 119, 255, 0.35));
}

.m-fade-enter-active,
.m-fade-leave-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
}

.m-fade-enter-from {
  opacity: 0;
  transform: translateY(4px);
}

.m-fade-leave-to {
  opacity: 0;
}

@media (min-width: 821px) {
  .m-app {
    max-width: 780px;
    margin: 0 auto;
    box-shadow: 0 0 0 1px #e8edf5, 0 24px 60px rgba(15, 35, 70, 0.08);
  }

  .m-tabbar {
    max-width: 780px;
    left: 50%;
    transform: translateX(-50%);
  }
}
</style>