<template>
  <!-- 递归菜单组件 -->
  <template v-if="menu.children && menu.children.length > 0">
    <el-sub-menu :index="resolvePath(menu.path || '')">
      <template #title>
        <el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon>
        <span>{{ menu.name }}</span>
      </template>
      <MenuItem
        v-for="child in menu.children"
        :key="child.id"
        :menu="child"
        :base-path="resolvePath(menu.path || '')"
      />
    </el-sub-menu>
  </template>
  <template v-else>
    <el-menu-item v-if="menu.type <= 1" :index="resolvePath(menu.path || '')">
      <el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon>
      <span>{{ menu.name }}</span>
    </el-menu-item>
  </template>
</template>

<script setup lang="ts">
import type { MenuTreeNode } from '@/types'
import { useRouter } from 'vue-router'

const props = defineProps<{
  menu: MenuTreeNode
  basePath: string
}>()

const router = useRouter()

function resolvePath(path: string): string {
  if (!path) return props.basePath
  if (path.startsWith('/')) return path
  if (!props.basePath) return '/' + path
  return props.basePath + '/' + path
}
</script>