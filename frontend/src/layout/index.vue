<template>
  <div class="app-wrapper">
    <!-- 侧边栏（桌面端） -->
    <div v-if="!narrow" class="sidebar-container">
      <SidebarMenu :menus="userMenus" />
    </div>

    <!-- 主内容区 -->
    <div class="main-container">
      <div class="navbar">
        <div class="navbar-left">
          <el-button v-if="narrow" class="nav-menu-btn" text @click="mobileMenuOpen = true">
            <el-icon :size="20"><Menu /></el-icon>
          </el-button>
          <span v-if="narrow" class="nav-page-title">{{ route.meta.title || 'AI-Ops' }}</span>
        </div>
        <div class="navbar-right">
          <el-button v-if="narrow" type="primary" size="small" plain @click="goMobile">
            手机版
          </el-button>
          <el-button type="primary" size="small" plain @click="copilotStore.openWith()">
            <CopilotAvatar :size="18" style="margin-right:6px;vertical-align:middle" />AI 运维助手
          </el-button>
          <el-icon class="nav-bell"><Bell /></el-icon>
          <span class="username">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
          <el-button type="danger" size="small" @click="handleLogout">退出登录</el-button>
        </div>
      </div>
      <div class="app-main">
        <router-view />
      </div>
    </div>

    <!-- 窄屏菜单抽屉 -->
    <el-drawer v-model="mobileMenuOpen" direction="ltr" size="248px" :with-header="false" class="mobile-menu-drawer">
      <SidebarMenu :menus="userMenus" @select="mobileMenuOpen = false" />
    </el-drawer>

    <!-- 全局 AI 助手（右下角悬浮 + 抽屉） -->
    <AiCopilot />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useCopilotStore } from '@/stores/copilot'
import SidebarMenu from './SidebarMenu.vue'
import AiCopilot from '@/components/AiCopilot.vue'
import CopilotAvatar from '@/components/CopilotAvatar.vue'
import { Bell, Menu } from '@element-plus/icons-vue'
import { setViewMode } from '@/utils/device'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const copilotStore = useCopilotStore()

const narrow = ref(false)
const mobileMenuOpen = ref(false)
let mq: MediaQueryList | null = null

// 系统设置始终排在侧边栏最后（不受后端 sort 影响）
const userMenus = computed(() => {
  const list = [...userStore.menus]
  const idx = list.findIndex(m => m.path === '/settings' || m.name === '系统设置')
  if (idx >= 0) {
    const [settings] = list.splice(idx, 1)
    list.push(settings)
  }
  return list
})

function handleLogout() {
  userStore.logout()
}

function goMobile() {
  setViewMode('mobile')
  mobileMenuOpen.value = false
  router.push('/m/home')
}

function syncNarrow(e?: MediaQueryListEvent) {
  narrow.value = e ? e.matches : !!mq?.matches
  if (!narrow.value) mobileMenuOpen.value = false
}

onMounted(() => {
  mq = window.matchMedia('(max-width: 820px)')
  syncNarrow()
  if (mq.addEventListener) mq.addEventListener('change', syncNarrow)
  else (mq as any).addListener(syncNarrow)
})

onUnmounted(() => {
  if (!mq) return
  if (mq.removeEventListener) mq.removeEventListener('change', syncNarrow)
  else (mq as any).removeListener(syncNarrow)
})
</script>

<style scoped>
.navbar-left {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.nav-page-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-menu-btn {
  padding: 6px;
  color: #303133;
}

.nav-bell {
  color: #606266;
}

:deep(.mobile-menu-drawer .el-drawer__body) {
  padding: 0;
  background: #304156;
  height: 100%;
}

@media (max-width: 820px) {
  .navbar {
    padding: 0 12px;
    justify-content: space-between;
  }

  .navbar-right {
    gap: 8px;
  }

  .navbar-right .username,
  .navbar-right .nav-bell {
    display: none;
  }

  .navbar-right .el-button {
    margin-left: 0;
  }

  .app-main {
    padding: 10px;
  }
}
</style>