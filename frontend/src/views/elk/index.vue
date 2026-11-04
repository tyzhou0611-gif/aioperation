<template>
  <div class="elk-page">
    <div class="page-head">
      <div>
        <h2>ELK / Logstash 管理</h2>
        <div class="sub">Elasticsearch 索引浏览、日志检索与集群分片状态</div>
      </div>
      <el-button v-permission="'elk:connection:add'" type="primary" @click="openDialog()">+ 新建连接</el-button>
    </div>

    <el-card>
      <div class="toolbar">
        <el-select v-model="activeConn" placeholder="选择 ELK 连接" style="width:260px" @change="onConnChange">
          <el-option v-for="c in connections" :key="c.id" :label="`${c.name} (${c.esUrl})`" :value="c.id" />
        </el-select>
        <el-tag v-if="current" size="small" :type="current.status === 'CONNECTED' ? 'success' : current.status === 'ERROR' ? 'danger' : 'info'">
          {{ current.status || 'DISCONNECTED' }}
        </el-tag>
        <el-button v-permission="'elk:connection:test'" :disabled="!activeConn" :loading="testing" @click="test">测试连接</el-button>
        <el-button v-permission="'elk:connection:edit'" :disabled="!current" @click="openDialog(current)">编辑</el-button>
        <el-button v-permission="'elk:connection:delete'" :disabled="!current" type="danger" plain @click="delConn">删除</el-button>
        <el-button :disabled="!activeConn" :loading="indicesLoading" @click="loadIndices">刷新索引</el-button>
      </div>

      <el-alert v-if="testResult" :type="testResult.reachable ? 'success' : 'error'" :closable="true"
                show-icon style="margin-bottom:12px" @close="testResult = null">
        <template #title>
          {{ testResult.message }}
          <span v-if="testResult.logstashMessage"> · {{ testResult.logstashMessage }}</span>
          <span class="hint" v-if="testResult.elapsedMs">（{{ testResult.elapsedMs }} ms）</span>
        </template>
      </el-alert>

      <el-tabs v-model="activeTab" class="elk-tabs">
        <el-tab-pane label="日志检索" name="search">
          <el-form :inline="true" class="search-bar">
        <el-form-item label="索引">
          <el-select v-model="searchIndex" clearable filterable placeholder="全部索引" style="width:240px">
            <el-option label="全部索引" value="" />
            <el-option v-for="i in indices" :key="i.index" :label="i.index" :value="i.index" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-input v-model="query" placeholder="日志关键词 / Lucene 查询" clearable style="width:320px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item>
          <el-select v-model="size" style="width:110px">
            <el-option :value="20" label="20 条" />
            <el-option :value="50" label="50 条" />
            <el-option :value="100" label="100 条" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!activeConn" :loading="searching" @click="search">搜索</el-button>
        </el-form-item>
        <el-form-item v-if="searchMeta">
          <span class="hint">共 {{ searchMeta.total }} 条，返回 {{ logs.length }} 条，耗时 {{ searchMeta.took }} ms</span>
        </el-form-item>
      </el-form>

      <!-- 搜索结果 -->
      <el-table v-if="logs.length" :data="logs" stripe size="small" style="width:100%;margin-bottom:16px">
        <el-table-column type="expand">
          <template #default="{ row }">
            <pre class="log-source">{{ row.source }}</pre>
          </template>
        </el-table-column>
        <el-table-column prop="timestamp" label="时间" width="190">
          <template #default="{ row }">{{ fmtTime(row.timestamp) || '-' }}</template>
        </el-table-column>
        <el-table-column prop="index" label="索引" width="220" show-overflow-tooltip />
        <el-table-column label="日志内容" min-width="380" show-overflow-tooltip>
          <template #default="{ row }">{{ preview(row.source) }}</template>
        </el-table-column>
        <el-table-column prop="id" label="_id" width="180" show-overflow-tooltip />
      </el-table>
      <el-empty v-else-if="searched && !searching" description="没有匹配的日志" />

      <!-- 索引列表 -->
      <div class="section-title" v-if="indices.length">索引列表 <span class="hint">（点击行可筛选该索引）</span></div>
      <el-table v-if="indices.length" :data="indices" stripe size="small" style="width:100%"
                highlight-current-row @row-click="onIndexClick">
        <el-table-column prop="index" label="索引名" min-width="240" show-overflow-tooltip />
        <el-table-column prop="health" label="健康" width="90">
          <template #default="{ row }">
            <el-tag :type="row.health === 'green' ? 'success' : row.health === 'yellow' ? 'warning' : 'danger'" size="small">
              {{ row.health || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column prop="docs" label="文档数" width="120" align="right" />
        <el-table-column prop="size" label="存储大小" width="110" align="right" />
      </el-table>
      <el-empty v-else-if="activeConn && !indicesLoading" description="暂无索引，点击「刷新索引」加载" :image-size="70" />
      <el-empty v-else-if="!activeConn" description="请先选择或新建一个 ELK 连接" />
        </el-tab-pane>

        <el-tab-pane label="集群分片" name="shards" lazy>
          <ElkClusterShards :connection-id="activeConn" :active="activeTab === 'shards'" :indices="indices" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 连接编辑 -->
    <el-dialog v-model="showConn" :title="form.id ? '编辑 ELK 连接' : '新建 ELK 连接'" width="560px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="连接名" required><el-input v-model="form.name" placeholder="如：prod-elk" /></el-form-item>
        <el-form-item label="ES 地址" required>
          <el-input v-model="form.esUrl" placeholder="http://192.0.2.7:9200" />
        </el-form-item>
        <el-form-item label="用户名"><el-input v-model="form.esUsername" placeholder="可选，Basic Auth" /></el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.esPassword" type="password" show-password
                    :placeholder="form.id ? '留空表示不修改' : '可选'" />
        </el-form-item>
        <el-form-item label="Logstash 地址">
          <el-input v-model="form.logstashUrl" placeholder="http://192.0.2.7:9600（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showConn = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveConn">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import ElkClusterShards from './ElkClusterShards.vue'

const route = useRoute()

const activeTab = ref(route.query.tab === 'shards' ? 'shards' : 'search')
watch(() => route.query.tab, (t) => {
  if (t === 'shards' || t === 'search') activeTab.value = String(t)
})

interface ElkConn {
  id: number
  name: string
  esUrl: string
  logstashUrl?: string | null
  esUsername?: string | null
  status?: string | null
  passwordSet?: boolean
}
interface IndexInfo { index: string; health: string; status: string; docs: string; size: string; sizeBytes: number }

const connections = ref<ElkConn[]>([])
const activeConn = ref<number | null>(null)
const indices = ref<IndexInfo[]>([])
const logs = ref<any[]>([])
const query = ref('')
const searchIndex = ref('')
const size = ref(20)
const searching = ref(false)
const searched = ref(false)
const searchMeta = ref<any>(null)
const indicesLoading = ref(false)
const testing = ref(false)
const testResult = ref<any>(null)

const showConn = ref(false)
const saving = ref(false)
const form = reactive<any>({ id: null, name: '', esUrl: '', esUsername: '', esPassword: '', logstashUrl: '' })

const current = computed(() => connections.value.find(c => c.id === activeConn.value) || null)

function fmtTime(t?: string) {
  if (!t) return ''
  if (/^\d+$/.test(t)) return new Date(Number(t)).toLocaleString('zh-CN', { hour12: false })
  return t.replace('T', ' ').replace('Z', '').substring(0, 23)
}

function preview(source: string) {
  try {
    const o = JSON.parse(source)
    return o.message || o.msg || o.log || o.event || source.replace(/\s+/g, ' ').slice(0, 300)
  } catch {
    return String(source || '').replace(/\s+/g, ' ').slice(0, 300)
  }
}

async function load() {
  const r = await request.get('/api/elk')
  connections.value = r.data || []
  if (!connections.value.length) {
    activeConn.value = null
    return
  }
  const want = Number(route.query.connectionId)
  if (want && connections.value.some(c => c.id === want)) {
    activeConn.value = want
  } else if (!activeConn.value || !connections.value.some(c => c.id === activeConn.value)) {
    activeConn.value = connections.value[0].id
  }
  await loadIndices()
}

// AI 助手跳转 /asset/middleware/elk?connectionId=xx 时，页面已打开也能切换
watch(() => route.query.connectionId, async (v) => {
  const cid = Number(v)
  if (!cid || !connections.value.some(c => c.id === cid)) return
  if (activeConn.value !== cid) {
    activeConn.value = cid
    await onConnChange()
  }
})

async function onConnChange() {
  indices.value = []
  logs.value = []
  searched.value = false
  searchMeta.value = null
  testResult.value = null
  searchIndex.value = ''
  await loadIndices()
}

async function loadIndices() {
  if (!activeConn.value) return
  indicesLoading.value = true
  try {
    const r = await request.get(`/api/elk/${activeConn.value}/indices`)
    indices.value = r.data || []
  } catch {
    indices.value = []
  } finally {
    indicesLoading.value = false
  }
}

async function test() {
  if (!activeConn.value) return
  testing.value = true
  try {
    const r = await request.post(`/api/elk/${activeConn.value}/test`)
    testResult.value = r.data
    if (r.data?.reachable) ElMessage.success('Elasticsearch 连接成功')
    else ElMessage.error(r.data?.message || '连接失败')
    await loadConnectionsOnly()
  } finally {
    testing.value = false
  }
}

async function loadConnectionsOnly() {
  const r = await request.get('/api/elk')
  connections.value = r.data || []
}

async function search() {
  if (!activeConn.value) return
  searching.value = true
  searched.value = false
  try {
    const r = await request.post(`/api/elk/${activeConn.value}/search`, {
      query: query.value,
      index: searchIndex.value,
      size: size.value
    })
    logs.value = r.data?.hits || []
    searchMeta.value = { total: r.data?.total ?? 0, took: r.data?.took ?? 0 }
    searched.value = true
  } finally {
    searching.value = false
  }
}

function onIndexClick(row: IndexInfo) {
  searchIndex.value = row.index
  ElMessage.info(`已选择索引：${row.index}`)
}

function openDialog(c?: ElkConn | null) {
  if (c) {
    form.id = c.id
    form.name = c.name
    form.esUrl = c.esUrl
    form.esUsername = c.esUsername || ''
    form.esPassword = ''
    form.logstashUrl = c.logstashUrl || ''
  } else {
    form.id = null
    form.name = ''
    form.esUrl = ''
    form.esUsername = ''
    form.esPassword = ''
    form.logstashUrl = ''
  }
  showConn.value = true
}

async function saveConn() {
  if (!form.name?.trim()) { ElMessage.warning('请输入连接名'); return }
  if (!form.esUrl?.trim()) { ElMessage.warning('请输入 ES 地址'); return }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(), esUrl: form.esUrl.trim(), esUsername: form.esUsername,
      esPassword: form.esPassword, logstashUrl: form.logstashUrl
    }
    let id = form.id
    if (form.id) await request.put(`/api/elk/${form.id}`, payload)
    else {
      const r = await request.post('/api/elk', payload)
      id = r.data?.id
    }
    ElMessage.success('保存成功')
    showConn.value = false
    activeConn.value = id ?? activeConn.value
    await load()
  } finally {
    saving.value = false
  }
}

async function delConn() {
  if (!current.value) return
  await ElMessageBox.confirm(`确定删除连接「${current.value.name}」吗？`, '删除连接', { type: 'warning' })
  await request.delete(`/api/elk/${current.value.id}`)
  ElMessage.success('已删除')
  activeConn.value = null
  await load()
}

onMounted(load)
</script>

<style scoped>
.elk-page { padding: 0 2px; }
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.page-head h2 { margin: 0; font-size: 22px; color: #333; }
.page-head .sub { color: #909399; font-size: 13px; margin-top: 4px; }
.toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px; }
.search-bar { margin-bottom: 6px; }
.hint { color: #909399; font-size: 12px; }
.section-title { font-size: 14px; font-weight: 600; color: #303133; margin: 6px 0 8px; }
.elk-tabs { margin-top: 2px; }
.elk-tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
.log-source {
  margin: 0; padding: 12px 16px; background: #f7f8fa; border-radius: 4px;
  font-size: 12px; white-space: pre-wrap; word-break: break-all; max-height: 420px; overflow: auto;
}
</style>