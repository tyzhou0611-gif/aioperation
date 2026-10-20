<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">菜单管理</h2>
    <el-card>
      <el-table :data="rows" v-loading="loading" stripe row-key="id" :tree-props="{ children: 'children' }" style="width:100%">
        <el-table-column prop="name" label="菜单名称" min-width="160" />
        <el-table-column prop="path" label="路由路径" width="180" />
        <el-table-column prop="component" label="组件" width="200" show-overflow-tooltip />
        <el-table-column prop="permission" label="权限标识" width="180" />
        <el-table-column prop="type" label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="typeColor(row.type)" size="small">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="70" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const loading = ref(false)
const rows = ref<any[]>([])

function typeLabel(t: number) {
  return t === 0 ? '目录' : t === 1 ? '菜单' : '按钮'
}
function typeColor(t: number) {
  return t === 0 ? 'warning' : t === 1 ? 'primary' : 'info'
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/api/menu/list')
    // 构建树：parentId=0 为根，挂 children
    const list = res.data || []
    const byId: Record<number, any> = {}
    list.forEach((m: any) => { m.children = []; byId[m.id] = m })
    const roots: any[] = []
    list.forEach((m: any) => {
      if (m.parentId && byId[m.parentId]) byId[m.parentId].children.push(m)
      else roots.push(m)
    })
    rows.value = roots
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>