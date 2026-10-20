<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">角色管理</h2>
    <el-card>
      <el-table :data="rows" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="角色标识" width="180">
          <template #default="{ row }"><el-tag>{{ row.name }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="240" />
        <el-table-column label="创建时间" width="200">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const loading = ref(false)
const rows = ref<any[]>([])

function formatTime(t: string) {
  return t ? t.replace('T', ' ').substring(0, 19) : ''
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/api/role/list')
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>