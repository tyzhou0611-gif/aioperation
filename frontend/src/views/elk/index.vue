<template>
  <div>
    <h2 style="margin:0 0 16px;font-size:22px;color:#333">ELK / Logstash 管理</h2>

    <el-card>
      <div style="display:flex;justify-content:space-between;margin-bottom:16px;flex-wrap:wrap;gap:8px">
        <el-select v-model="activeConn" placeholder="选择ELK连接" style="width:220px">
          <el-option v-for="c in connections" :key="c.id" :label="c.name + ' (' + c.esUrl + ')'" :value="c.id" />
        </el-select>
        <div style="display:flex;gap:8px">
          <el-button type="primary" @click="showConn=true">+ 新建连接</el-button>
          <el-button type="success" :disabled="!activeConn" @click="test">测试连接</el-button>
          <el-button @click="loadIndices" :disabled="!activeConn">获取索引</el-button>
        </div>
      </div>

      <!-- 搜索区 -->
      <el-form :inline="true" style="margin-bottom:12px">
        <el-form-item>
          <el-input v-model="query" placeholder="日志关键词" style="width:300px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!activeConn" @click="search">搜索</el-button>
        </el-form-item>
      </el-form>

      <!-- 搜索结果 -->
      <div v-if="logResults.length" style="margin-bottom:16px">
        <div v-for="(log, i) in logResults" :key="i" style="border:1px solid #ebeef5;border-radius:6px;margin-bottom:8px;padding:8px">
          <pre style="margin:0;font-size:12px;white-space:pre-wrap;word-break:break-all">{{ log.source }}</pre>
        </div>
      </div>

      <!-- 索引列表 -->
      <el-table v-if="indices.length" :data="indices" stripe style="width:100%">
        <el-table-column prop="index" label="索引名" min-width="180" />
        <el-table-column prop="health" label="健康" width="80">
          <template #default="{ row }">
            <el-tag :type="row.health === 'green' ? 'success' : row.health === 'yellow' ? 'warning' : 'danger'" size="small">{{ row.health }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="docs" label="文档数" width="100" />
        <el-table-column prop="size" label="存储大小" width="100" />
      </el-table>
    </el-card>

    <!-- 新建连接弹窗 -->
    <el-dialog v-model="showConn" title="新建ELK连接" width="500px">
      <el-form :model="newConn" label-width="100px">
        <el-form-item label="连接名"><el-input v-model="newConn.name" placeholder="如：prod-elk" /></el-form-item>
        <el-form-item label="ES地址"><el-input v-model="newConn.esUrl" placeholder="http://192.168.1.100:9200" /></el-form-item>
        <el-form-item label="用户名"><el-input v-model="newConn.esUsername" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="newConn.esPassword" type="password" show-password /></el-form-item>
        <el-form-item label="Logstash地址"><el-input v-model="newConn.logstashUrl" placeholder="http://192.168.1.100:9600" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showConn=false">取消</el-button>
        <el-button type="primary" @click="createConn">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'

const connections = ref<any[]>([])
const activeConn = ref<number | null>(null)
const indices = ref<any[]>([])
const logResults = ref<any[]>([])
const query = ref('')
const showConn = ref(false)
const newConn = ref<any>({ name:'', esUrl:'', esUsername:'', esPassword:'', logstashUrl:'' })

onMounted(load)

async function load() {
  const r = await request.get('/api/elk')
  connections.value = r.data || []
  if (connections.value.length) activeConn.value = connections.value[0].id
}

async function createConn() {
  if (!newConn.value.name || !newConn.value.esUrl) { ElMessage.warning('连接名和ES地址必填'); return }
  await request.post('/api/elk', newConn.value)
  ElMessage.success('创建成功')
  showConn.value = false
  newConn.value = { name:'', esUrl:'', esUsername:'', esPassword:'', logstashUrl:'' }
  load()
}

async function test() {
  const r = await request.post(`/api/elk/${activeConn.value}/test`)
  ElMessage[r.data.reachable ? 'success' : 'error'](r.data.message)
}

async function search() {
  const r = await request.post(`/api/elk/${activeConn.value}/search`, { query: query.value, size: 20 })
  logResults.value = r.data?.hits || []
}

async function loadIndices() {
  const r = await request.get(`/api/elk/${activeConn.value}/indices`)
  indices.value = r.data || []
}
</script>