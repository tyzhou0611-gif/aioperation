<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">审计日志</h2>

    <el-card>
      <div style="display:flex;gap:12px;margin-bottom:16px">
        <el-input v-model="query.username" placeholder="操作用户" clearable style="width:180px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="username" label="用户" width="100" />
        <el-table-column prop="ipAddress" label="IP" width="140" />
        <el-table-column prop="httpMethod" label="方法" width="80">
          <template #default="{ row }">
            <el-tag :type="methodType(row.httpMethod)" size="small">{{ row.httpMethod }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="requestUrl" label="请求URL" min-width="220" show-overflow-tooltip />
        <el-table-column prop="responseStatus" label="状态" width="80" />
        <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>

      <div style="display:flex;justify-content:flex-end;margin-top:16px">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[20,50,100]"
          layout="total, sizes, prev, pager, next"
          @change="search"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import request from '@/utils/request'

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const query = reactive({ username: '', page: 1, size: 20 })

function methodType(m: string) {
  return m === 'POST' ? 'success' : m === 'PUT' ? 'warning' : m === 'DELETE' ? 'danger' : 'info'
}

function formatTime(t: string) {
  return t ? t.replace('T', ' ').substring(0, 19) : ''
}

async function search() {
  loading.value = true
  try {
    const res = await request.get('/api/audit/page', { params: query })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function reset() {
  query.username = ''; query.page = 1; search()
}

onMounted(search)
</script>