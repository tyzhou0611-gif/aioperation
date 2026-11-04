<template>
  <div class="sidebar-menu">
    <div class="sidebar-logo">AI-Ops</div>
    <el-menu
      :default-active="activeMenu"
      :collapse="false"
      :unique-opened="true"
      router
      text-color="#bfcbd9"
      active-text-color="#409eff"
      background-color="#304156"
      @select="onSelect"
    >
      <el-menu-item index="/dashboard">
        <el-icon><Odometer /></el-icon>
        <span>仪表盘</span>
      </el-menu-item>

      <template v-for="menu in menus" :key="menu.id">
        <MenuItem :menu="menu" :base-path="''" />
      </template>
    </el-menu>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import MenuItem from './MenuItem.vue'
import { Odometer } from '@element-plus/icons-vue'
import type { MenuTreeNode } from '@/types'

const props = defineProps<{ menus: MenuTreeNode[] }>()
const emit = defineEmits<{ (e: 'select', path: string): void }>()

const route = useRoute()

const activeMenu = computed(() => {
  const path = route.path
  if (/^\/asset\/middleware\/\d+$/.test(path)) return '/asset/middleware'
  if (/^\/asset\/middleware\/elk/.test(path)) return '/asset/middleware/elk'
  if (path === '/asset/middleware' || path === '/asset/middleware-dir') return '/asset/middleware'
  return path
})

function onSelect(path: string) {
  emit('select', path)
}
</script>

<style scoped>
.sidebar-menu {
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
}

.sidebar-logo {
  height: 55px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  background: rgba(0, 0, 0, 0.2);
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  letter-spacing: 1px;
}

.el-menu {
  border-right: none;
  background: transparent !important;
}

:deep(.el-menu-item),
:deep(.el-sub-menu__title) {
  color: #bfcbd9 !important;
}

:deep(.el-menu-item:hover),
:deep(.el-sub-menu__title:hover) {
  background: rgba(255, 255, 255, 0.05) !important;
}

:deep(.el-menu-item.is-active) {
  color: #409eff !important;
  background: rgba(64, 158, 255, 0.15) !important;
}

:deep(.el-sub-menu .el-menu) {
  background: rgba(0, 0, 0, 0.12) !important;
}
</style>