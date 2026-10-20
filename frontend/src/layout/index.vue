<template>
  <div class="app-wrapper">
    <!-- 侧边栏 -->
    <div class="sidebar-container">
      <div class="sidebar-logo">AI-Ops</div>
      <el-menu
        :default-active="activeMenu"
        :collapse="false"
        :unique-opened="true"
        router
        text-color="#bfcbd9"
        active-text-color="#409eff"
        background-color="#304156"
      >
        <!-- 静态菜单：仪表盘 -->
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>

        <!-- 动态菜单：从用户信息中获取 -->
        <template v-for="menu in userMenus" :key="menu.id">
          <MenuItem :menu="menu" :base-path="''" />
        </template>
      </el-menu>
    </div>

    <!-- 主内容区 -->
    <div class="main-container">
      <div class="navbar">
        <div class="navbar-right">
          <el-button type="primary" size="small" plain @click="copilotStore.openWith()">
            <el-icon style="margin-right:4px"><MagicStick /></el-icon>AI 运维助手
          </el-button>
          <el-icon><Bell /></el-icon>
          <span class="username">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
          <el-button type="danger" size="small" @click="handleLogout">退出登录</el-button>
        </div>
      </div>
      <div class="app-main">
        <router-view />
      </div>
    </div>
    <!-- 全局 AI 助手（右下角悬浮 + 抽屉） -->
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
import { Bell, Odometer, MagicStick } from '@element-plus/icons-vue'

const route = useRoute()
const userStore = useUserStore()
const copilotStore = useCopilotStore()

const activeMenu = computed(() => route.path)

const userMenus = computed(() => userStore.menus)

function handleLogout() {
  userStore.logout()
}
</script>