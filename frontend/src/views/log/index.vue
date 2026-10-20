<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">日志管理</h2>

    <el-card>
      <el-alert
        v-if="!externalSource"
        title="当前为演示模式，日志为模拟数据"
        type="info"
        show-icon
        :closable="false"
        style="margin-bottom:16px"
      />

      <!-- 筛选栏 -->
      <div style="display:flex;gap:12px;margin-bottom:16px;flex-wrap:wrap">
        <el-select v-model="query.serverId" placeholder="选择服务器" clearable style="width:200px">
          <el-option
            v-for="s in servers" :key="s.id"
            :label="s.hostname + ' (' + s.ip + ')'" :value="s.id"
          />
        </el-select>
        <el-input v-model="query.keyword" placeholder="关键词搜索" clearable style="width:220px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <!-- 日志列表 -->
      <el-table :data="logs" v-loading="loading" stripe style="width:100%" @row-click="openContext">
        <el-table-column prop="createTime" label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column prop="level" label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)" size="small">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="100" />
        <el-table-column prop="serverIp" label="IP" width="130" />
        <el-table-column label="内容" min-width="300">
          <template #default="{ row }">
            <span v-html="highlight(row.message)"></span>
          </template>
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

    <!-- 上下文抽屉 -->
    <el-drawer v-model="drawerVisible" title="日志上下文" size="50%">
      <el-descriptions :column="1" border style="margin-bottom:16px">
        <el-descriptions-item label="日志ID">{{ current.id }}</el-descriptions-item>
        <el-descriptions-item label="级别">
          <el-tag :type="levelType(current.level)">{{ current.level }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="来源">{{ current.source }}</el-descriptions-item>
        <el-descriptions-item label="内容">
          <span v-html="highlight(current.message)"></span>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert title="上下文前后N行需对接 Loki/ES 后提供" type="warning" :closable="false" show-icon />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'

const externalSource = ref(false)
const loading = ref(false)
const servers = ref<any[]>([])
const logs = ref<any[]>([])
const total = ref(0)
const drawerVisible = ref(false)
const current = ref<any>({})

const query = reactive({ serverId: null as number | null, keyword: '', page: 1, size: 20 })
const route = useRoute()

function levelType(level: string) {
  return level === 'ERROR' ? 'danger' : level === 'WARN' ? 'warning' : 'info'
}

function formatTime(t: string) {
  return t ? t.replace('T', ' ').substring(0, 19) : ''
}

function highlight(msg: string) {
  if (!msg) return ''
  const kw = query.keyword.trim()
  let html = msg.replace(/</g, '&lt;').replace(/>/g, '&gt;')
  if (kw) {
    const re = new RegExp('(' + kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'gi')
    html = html.replace(re, '<mark style="background:#ffd54f;padding:0 2px">$1</mark>')
  }
  return html
}

async function loadServers() {
  const res = await request.get('/api/server/page', { params: { page: 1, pageSize: 100 } })
  servers.value = res.data.records || []
}

async function search() {
  loading.value = true
  try {
    const res = await request.get('/api/log/search', { params: query })
    logs.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function reset() {
  query.serverId = null; query.keyword = ''; query.page = 1
  search()
}

function openContext(row: any) {
  current.value = row
  drawerVisible.value = true
}

onMounted(() => {
  // AI 跳转带入的查询条件
  if (route.query.serverId) query.serverId = Number(route.query.serverId)
  if (route.query.keyword) query.keyword = String(route.query.keyword)
  loadServers(); search()
})
</script>