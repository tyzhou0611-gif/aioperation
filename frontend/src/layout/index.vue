<template>
  <div class="app-wrapper">
    <aside class="sidebar-container">
      <div class="sidebar-brand">
        <div class="brand-mark"><el-icon><Monitor /></el-icon></div>
        <div class="brand-copy">
          <strong>AI<span>OPS</span></strong>
          <small>INTELLIGENT OPERATIONS</small>
        </div>
      </div>

      <div class="sidebar-section-label">WORKSPACE</div>
      <el-menu
        :default-active="activeMenu"
        :collapse="false"
        :unique-opened="true"
        router
        class="ops-menu"
      >
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>运行总览</span>
        </el-menu-item>
        <template v-for="menu in userMenus" :key="menu.id">
          <MenuItem :menu="menu" :base-path="''" />
        </template>
      </el-menu>

      <div class="sidebar-footer">
        <span class="status-pulse"></span>
        <span>运维平台运行正常</span>
        <small>v1.0.0</small>
      </div>
    </aside>

    <section class="main-container">
      <header class="navbar">
        <div class="navbar-context">
          <span class="context-kicker">AI OPERATIONS CENTER</span>
          <span class="context-divider">/</span>
          <strong>{{ route.meta.title || '运行总览' }}</strong>
        </div>
        <div class="navbar-right">
          <div class="system-state"><span class="status-pulse"></span>系统在线</div>
          <el-tooltip content="AI 运维助手" placement="bottom">
            <el-button class="copilot-button" @click="copilotStore.openWith()">
              <el-icon><MagicStick /></el-icon>
              <span>AI 助手</span>
            </el-button>
          </el-tooltip>
          <el-tooltip content="告警中心" placement="bottom">
            <button class="icon-action" aria-label="告警中心">
              <el-icon><Bell /></el-icon>
              <i></i>
            </button>
          </el-tooltip>
          <div class="user-profile">
            <div class="user-avatar">{{ userInitial }}</div>
            <div class="user-meta">
              <strong>{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</strong>
              <small>平台运维</small>
            </div>
          </div>
          <el-button class="logout-button" text @click="handleLogout">退出</el-button>
        </div>
      </header>

      <main class="app-main">
        <router-view />
      </main>
    </section>
    <AiCopilot />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useCopilotStore } from '@/stores/copilot'
import MenuItem from './MenuItem.vue'
import AiCopilot from '@/components/AiCopilot.vue'
import { Bell, MagicStick, Monitor, Odometer } from '@element-plus/icons-vue'

const route = useRoute()
const userStore = useUserStore()
const copilotStore = useCopilotStore()
const activeMenu = computed(() => route.path)
const userMenus = computed(() => userStore.menus)
const userInitial = computed(() => (userStore.userInfo?.nickname || userStore.userInfo?.username || 'U').slice(0, 1).toUpperCase())

function handleLogout() {
  userStore.logout()
}
</script>