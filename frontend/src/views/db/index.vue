<template>
  <div class="db-page">
    <div class="page-head">
      <div>
        <h2>SQL 工作台</h2>
        <div class="sub">连接自动同步自「资产管理 → 中间件/数据库管理 → 数据库类」，账号密码请在该资产中维护</div>
      </div>
      <el-button v-permission="'db:test'" :loading="syncing" @click="syncConnections">从资产管理同步</el-button>
    </div>

    <el-row :gutter="12">
      <!-- 连接列表 -->
      <el-col :span="6">
        <el-card shadow="never" class="conn-card">
          <template #header>
            <div class="card-head">
              <span>数据库连接
                <el-tag size="small" type="info" style="margin-left:4px">
                  {{ filteredConnections.length }}/{{ connections.length }}
                </el-tag>
              </span>
              <el-input v-model="connKeyword" size="small" placeholder="搜索" clearable style="width:110px" />
            </div>
          </template>
          <div class="conn-filter">
            <el-select v-model="connTypeFilter" size="small" clearable placeholder="全部数据库类型" style="width:100%">
              <el-option v-for="t in connTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </div>
          <div class="conn-list">
            <div v-for="c in filteredConnections" :key="c.id"
                 class="conn-item" :class="{ active: activeConn?.id === c.id }" @click="selectConn(c)">
              <div class="conn-main">
                <span class="dot" :class="statusClass(c.status)"></span>
                <div style="min-width:0">
                  <div class="conn-name">{{ c.name }}</div>
                  <div class="conn-sub">
                    {{ c.dbType }} · {{ c.host }}<template v-if="c.port">:{{ c.port }}</template>
                    <template v-if="c.databaseName">/{{ c.databaseName }}</template>
                  </div>
                </div>
              </div>
              <div class="conn-actions" @click.stop>
                <el-tooltip content="测试连接" placement="top">
                  <el-button v-permission="'db:test'" link size="small" :loading="testingId === c.id" @click="testConn(c)">
                    <el-icon><Connection /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tag v-if="c.passwordSet === false" size="small" type="warning" effect="plain">未配密码</el-tag>
              </div>
            </div>
            <el-empty v-if="!filteredConnections.length" description="暂无连接，请在资产管理中添加数据库类资产" :image-size="60" />
          </div>
        </el-card>
      </el-col>

      <!-- 工作区 -->
      <el-col :span="18">
        <el-card v-if="!activeConn" shadow="never" class="empty-card">
          <el-empty description="暂无可用数据库连接">
            <el-button type="primary" @click="goAssets">去资产管理添加数据库</el-button>
          </el-empty>
        </el-card>

        <el-card v-else shadow="never" class="work-card">
          <template #header>
            <div class="card-head">
              <div class="conn-title">
                <b>{{ activeConn.name }}</b>
                <el-tag size="small" type="info" style="margin-left:8px">{{ activeConn.dbType }}</el-tag>
                <span class="conn-sub" style="margin-left:10px">
                  {{ activeConn.host }}<template v-if="activeConn.port">:{{ activeConn.port }}</template>
                  <template v-if="activeConn.databaseName">/{{ activeConn.databaseName }}</template>
                  <template v-if="currentDatabase && currentDatabase !== activeConn.databaseName">/{{ currentDatabase }}</template>
                </span>
              </div>
              <div>
                <el-button v-permission="'db:test'" size="small" :loading="testingId === activeConn.id" @click="testConn(activeConn)">测试连接</el-button>
                <el-button size="small" :loading="tablesLoading" @click="refreshSchema">刷新</el-button>
              </div>
            </div>
          </template>

          <el-alert v-if="activeConn && activeConn.passwordSet === false" type="warning" show-icon :closable="false"
                     style="margin-bottom:10px"
                     title="该数据库资产尚未配置密码"
                     description="请到 资产管理 → 中间件/数据库管理 → 数据库类 编辑该实例，填写数据库账号与密码后回到此处点击「从资产管理同步」。" />

          <el-tabs v-model="activeTab">
            <el-tab-pane label="SQL 查询" name="query">
              <el-row :gutter="12">
                <!-- 表浏览 -->
                <el-col :span="5">
                  <div class="table-browser">
                    <el-select v-model="currentDatabase" size="small" clearable
                               :placeholder="activeConn.dbType === 'MYSQL' ? '选择数据库' : '选择 Schema'"
                               style="width:100%;margin-bottom:8px" :loading="databasesLoading"
                               @change="onDatabaseChange">
                      <el-option v-for="d in databases" :key="d" :label="d" :value="d" />
                    </el-select>
                    <el-input v-model="tableKeyword" size="small" placeholder="搜索表" clearable style="margin-bottom:8px" />
                    <div class="table-list">
                      <div v-for="t in filteredTables" :key="t.name" class="table-item"
                           :title="t.remarks || t.name" @click="useTable(t)">
                        <el-icon><Grid /></el-icon>
                        <span class="tname">{{ t.name }}</span>
                        <el-tag v-if="t.type === 'VIEW'" size="small" type="warning">视图</el-tag>
                        <el-button link size="small" @click.stop="showSchema(t)">结构</el-button>
                      </div>
                      <div v-if="!filteredTables.length" class="table-empty">
                        {{ tablesLoading ? '加载中...' : (activeConn.dbType === 'MYSQL' && !currentDatabase ? (databases.length ? '请先选择数据库' : '未发现可用数据库，请检查账号权限') : '暂无表') }}
                      </div>
                    </div>
                  </div>
                </el-col>

                <!-- 编辑器与结果 -->
                <el-col :span="19">
                  <div class="editor-toolbar">
                    <el-button v-permission="'db:execute'" type="primary" size="small" :loading="running" @click="runSql">▶ 执行 (Ctrl+Enter)</el-button>
                    <el-button size="small" @click="clearSql">清空</el-button>
                    <el-select v-model="maxRows" size="small" style="width:120px">
                      <el-option :value="100" label="最多 100 行" />
                      <el-option :value="500" label="最多 500 行" />
                      <el-option :value="1000" label="最多 1000 行" />
                      <el-option :value="5000" label="最多 5000 行" />
                    </el-select>
                    <span class="hint">单条语句；只读查询自动限制返回行数</span>
                  </div>

                  <el-input v-model="sqlText" type="textarea" :rows="9" class="sql-editor"
                            placeholder="输入 SQL，例如：SELECT * FROM app_asset LIMIT 100"
                            @keydown.ctrl.enter.prevent="runSql" />

                  <div v-if="result" class="result-head">
                    <template v-if="result.success">
                      <el-tag type="success" size="small">成功</el-tag>
                      <span class="meta">
                        {{ result.columns ? `返回 ${result.rowCount ?? 0} 行` : `影响 ${result.affectedRows ?? 0} 行` }}
                        · 耗时 {{ result.elapsedMs ?? 0 }} ms
                        <span v-if="result.hasMore" style="color:#e6a23c">（已达行数上限，结果可能不完整）</span>
                      </span>
                      <el-button v-if="result.columns?.length" link type="primary" size="small" @click="exportCsv">导出 CSV</el-button>
                    </template>
                    <template v-else>
                      <el-tag type="danger" size="small">失败</el-tag>
                      <span class="meta">{{ result.error }}</span>
                    </template>
                  </div>

                  <div v-if="result?.success && result.columns" class="result-table">
                    <el-table :data="resultRows" border stripe size="small" max-height="380">
                      <el-table-column type="index" label="#" width="55" align="center" />
                      <el-table-column v-for="(col, i) in result.columns" :key="i"
                                       :prop="'c' + i" :label="col" min-width="120" show-overflow-tooltip />
                    </el-table>
                  </div>
                  <el-alert v-else-if="result && !result.success" :title="result.error" type="error"
                            :closable="false" show-icon style="margin-top:10px" />
                </el-col>
              </el-row>
            </el-tab-pane>

            <el-tab-pane label="执行历史" name="history">
              <div class="history-head">
                <span class="hint">当前连接最近 {{ logs.length }} 条执行记录</span>
                <el-button size="small" :loading="logsLoading" @click="loadHistory">刷新</el-button>
              </div>
              <el-table :data="logs" stripe size="small" max-height="470">
                <el-table-column prop="createTime" label="时间" width="165">
                  <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
                </el-table-column>
                <el-table-column prop="sqlText" label="SQL" min-width="300" show-overflow-tooltip />
                <el-table-column label="结果" width="80" align="center">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.success === 1 ? 'success' : 'danger'">
                      {{ row.success === 1 ? '成功' : '失败' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="rowCount" label="行数" width="80" align="center" />
                <el-table-column prop="elapsedMs" label="耗时(ms)" width="95" align="center" />
                <el-table-column prop="operator" label="操作人" width="100" />
                <el-table-column label="操作" width="80" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" size="small" @click="useHistory(row)">载入</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!logs.length && !logsLoading" description="暂无执行记录" :image-size="60" />
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>
    </el-row>

    <!-- 连接编辑已收回到资产管理，工作台不再提供手工新建/编辑/删除 -->

    <!-- 表结构 -->
    <el-dialog v-model="schemaVisible" :title="`表结构 - ${schemaTable}`" width="720px">
      <el-table :data="columns" stripe size="small" max-height="420">
        <el-table-column prop="name" label="字段" min-width="140" />
        <el-table-column prop="type" label="类型" width="140" />
        <el-table-column prop="size" label="长度" width="80" align="center" />
        <el-table-column prop="nullable" label="可空" width="90" align="center" />
        <el-table-column prop="default" label="默认值" min-width="120" show-overflow-tooltip />
        <el-table-column prop="remarks" label="备注" min-width="140" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Grid } from '@element-plus/icons-vue'
import request from '@/utils/request'

const route = useRoute()
const router = useRouter()

interface DbConn {
  id: number
  name: string
  dbType: string
  host: string
  port: number | null
  databaseName: string | null
  username: string | null
  status: string | null
  lastConnectTime: string | null
  passwordSet?: boolean
  middlewareId?: number | null
}

interface TableInfo { name: string; type: string; remarks: string | null }
interface ColumnInfo { name: string; type: string; size: string; nullable: string; default: string | null; remarks: string | null }
interface ExecResult {
  success: boolean
  columns?: string[]
  rows?: any[][]
  rowCount?: number
  affectedRows?: number
  hasMore?: boolean
  elapsedMs?: number
  error?: string
}
interface ExecLog {
  id: number
  sqlText: string
  success: number
  rowCount: number | null
  elapsedMs: number | null
  operator: string | null
  createTime: string
}

const DB_TYPES = [
  { value: 'MYSQL', label: 'MySQL', port: 3306 },
  { value: 'POSTGRESQL', label: 'PostgreSQL', port: 5432 },
  { value: 'GREENPLUM', label: 'Greenplum', port: 5432 },
  { value: 'GBASE', label: 'GBase', port: 5432 },
  { value: 'ORACLE', label: 'Oracle', port: 1521 },
  { value: 'DAMENG', label: '达梦 DM', port: 5236 },
  { value: 'H2', label: 'H2（本地文件）', port: 0 }
]

const connections = ref<DbConn[]>([])
const connKeyword = ref('')
const connTypeFilter = ref('')
const activeConn = ref<DbConn | null>(null)
const testingId = ref<number | null>(null)
const syncing = ref(false)

const tables = ref<TableInfo[]>([])
const tableKeyword = ref('')
const tablesLoading = ref(false)
const databases = ref<string[]>([])
const currentDatabase = ref('')
const databasesLoading = ref(false)
const activeTab = ref('query')

const sqlText = ref('')
const maxRows = ref(500)
const running = ref(false)
const result = ref<ExecResult | null>(null)

const logs = ref<ExecLog[]>([])
const logsLoading = ref(false)

const schemaVisible = ref(false)
const schemaTable = ref('')
const columns = ref<ColumnInfo[]>([])

function typeLabel(type: string) {
  return DB_TYPES.find(t => t.value === type)?.label || type
}

// 下拉分类：只展示当前已有的数据库类型，并带上各自数量
const connTypeOptions = computed(() => {
  const types = Array.from(new Set(connections.value.map(c => c.dbType))).sort()
  return types.map(t => ({
    value: t,
    label: `${typeLabel(t)} (${connections.value.filter(c => c.dbType === t).length})`
  }))
})

const filteredConnections = computed(() => {
  const type = connTypeFilter.value
  let list = type ? connections.value.filter(c => c.dbType === type) : connections.value
  const k = connKeyword.value.trim().toLowerCase()
  if (k) {
    list = list.filter(c =>
      [c.name, c.host, c.databaseName, c.dbType].some(v => (v || '').toLowerCase().includes(k)))
  }
  return list
})

const filteredTables = computed(() => {
  const k = tableKeyword.value.trim().toLowerCase()
  if (!k) return tables.value
  return tables.value.filter(t => t.name.toLowerCase().includes(k))
})

const resultRows = computed(() => {
  const r = result.value
  if (!r?.columns || !r.rows) return []
  return r.rows.map((row) => {
    const obj: Record<string, any> = {}
    r.columns!.forEach((_, i) => { obj['c' + i] = row[i] })
    return obj
  })
})

function statusClass(status?: string | null) {
  return (status || 'disconnected').toLowerCase()
}

function fmtTime(t: string | null | undefined) {
  return (t || '').replace('T', ' ').slice(0, 19)
}

async function loadConnections(keepId?: number | null) {
  const res = await request.get('/api/db/connections')
  connections.value = res.data || []
  if (!connections.value.length) {
    activeConn.value = null
    return
  }
  const id = keepId ?? activeConn.value?.id
  activeConn.value = connections.value.find(c => c.id === id) || connections.value[0]
}

async function selectConn(c: DbConn) {
  if (activeConn.value?.id === c.id) return
  activeConn.value = c
  sqlText.value = ''
  result.value = null
  tables.value = []
  logs.value = []
  databases.value = []
  currentDatabase.value = ''
  activeTab.value = 'query'
  await loadDatabases()
  await Promise.all([loadTables(), loadHistory()])
}

async function loadDatabases() {
  if (!activeConn.value) {
    databases.value = []
    currentDatabase.value = ''
    return
  }
  databasesLoading.value = true
  try {
    const res = await request.get(`/api/db/connections/${activeConn.value.id}/databases`)
    databases.value = res.data || []
    const preferred = activeConn.value.databaseName
    if (preferred && databases.value.includes(preferred)) {
      currentDatabase.value = preferred
    } else if (databases.value.length === 1) {
      currentDatabase.value = databases.value[0]
    } else {
      currentDatabase.value = ''
    }
  } catch {
    databases.value = []
    currentDatabase.value = ''
  } finally {
    databasesLoading.value = false
  }
}

async function onDatabaseChange() {
  await loadTables()
}

async function refreshSchema() {
  await loadDatabases()
  await loadTables()
}

async function loadTables() {
  if (!activeConn.value) return
  tablesLoading.value = true
  try {
    const res = await request.get(`/api/db/connections/${activeConn.value.id}/tables`, {
      params: currentDatabase.value ? { database: currentDatabase.value } : {}
    })
    tables.value = res.data || []
  } catch {
    tables.value = []
  } finally {
    tablesLoading.value = false
  }
}

async function loadHistory() {
  if (!activeConn.value) return
  logsLoading.value = true
  try {
    const res = await request.get(`/api/db/connections/${activeConn.value.id}/logs`, { params: { limit: 100 } })
    logs.value = res.data || []
  } finally {
    logsLoading.value = false
  }
}

async function testConn(c: DbConn) {
  testingId.value = c.id
  try {
    const res = await request.post(`/api/db/connections/${c.id}/test`)
    const data = res.data || {}
    if (data.success) ElMessage.success(`连接成功（${data.elapsedMs ?? 0} ms）`)
    else ElMessage.error(`连接失败：${data.message || '未知错误'}`)
    await loadConnections(c.id)
  } finally {
    testingId.value = null
  }
}

async function syncConnections() {
  syncing.value = true
  try {
    const r = await request.post('/api/db/connections/sync')
    const d = r.data || {}
    const parts = [`新增 ${d.created || 0}`, `更新 ${d.updated || 0}`, `移除 ${d.removed || 0}`]
    if (d.passwordUpdated) parts.push(`密码同步 ${d.passwordUpdated}`)
    if (d.backfilled) parts.push(`回填密码 ${d.backfilled}`)
    if (d.migrated) parts.push(`迁移历史连接 ${d.migrated}`)
    if (d.skipped) parts.push(`跳过 ${d.skipped}（缺少 IP）`)
    ElMessage.success('同步完成：' + parts.join('，'))
    const keep = activeConn.value?.id
    await loadConnections(keep)
    if (activeConn.value) {
      await loadDatabases()
      await Promise.all([loadTables(), loadHistory()])
    }
  } finally {
    syncing.value = false
  }
}

function goAssets() { router.push('/asset/middleware') }

function useTable(t: TableInfo) {
  sqlText.value = `SELECT * FROM ${t.name} LIMIT ${maxRows.value}`
}

async function showSchema(t: TableInfo) {
  if (!activeConn.value) return
  try {
    const res = await request.get(`/api/db/connections/${activeConn.value.id}/tables/${encodeURIComponent(t.name)}/columns`, {
      params: currentDatabase.value ? { database: currentDatabase.value } : {}
    })
    columns.value = res.data || []
    schemaTable.value = t.name
    schemaVisible.value = true
  } catch {
    columns.value = []
  }
}

async function runSql() {
  if (!activeConn.value) return
  const sql = sqlText.value.trim()
  if (!sql) { ElMessage.warning('请输入 SQL'); return }
  running.value = true
  try {
    const check = await request.post('/api/db/check-dangerous', { sql })
    if (check.data?.dangerous) {
      try {
        await ElMessageBox.confirm(check.data.message || '该 SQL 具有破坏性，确定执行？', '危险操作确认', {
          type: 'warning', confirmButtonText: '仍要执行', cancelButtonText: '取消'
        })
      } catch {
        return
      }
    }
    const res = await request.post('/api/db/execute', {
      connectionId: activeConn.value.id, sql, maxRows: maxRows.value,
      database: currentDatabase.value || undefined
    })
    result.value = res.data
    if (res.data?.success) {
      ElMessage.success(`执行成功，耗时 ${res.data.elapsedMs ?? 0} ms`)
    }
    await Promise.all([loadHistory(), loadTables()])
  } finally {
    running.value = false
  }
}

function clearSql() {
  sqlText.value = ''
  result.value = null
}

function useHistory(row: ExecLog) {
  sqlText.value = row.sqlText
  activeTab.value = 'query'
}

function exportCsv() {
  const r = result.value
  if (!r?.columns || !r.rows) return
  const esc = (v: any) => {
    const s = v === null || v === undefined ? '' : String(v)
    return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
  }
  const lines = [r.columns.map(esc).join(',')]
  r.rows.forEach(row => lines.push(row.map(esc).join(',')))
  const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'query-result.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}

onMounted(async () => {
  await loadConnections()
  const cid = Number(route.query.connectionId)
  if (cid) {
    const c = connections.value.find(x => x.id === cid)
    if (c) activeConn.value = c
  }
  if (activeConn.value) {
    await loadDatabases()
    await Promise.all([loadTables(), loadHistory()])
  }
})

// AI 助手跳转 /db?connectionId=xx 时，页面已打开也能切换
watch(() => route.query.connectionId, async (v) => {
  const cid = Number(v)
  if (!cid) return
  const c = connections.value.find(x => x.id === cid)
  if (c && c.id !== activeConn.value?.id) await selectConn(c)
})
</script>

<style scoped lang="scss">
.db-page { padding: 0 2px; }
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.page-head h2 { margin: 0; font-size: 22px; color: #333; }
.page-head .sub { color: #909399; font-size: 13px; margin-top: 4px; }

.card-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.conn-title { display: flex; align-items: center; min-width: 0; }
.conn-card { height: calc(100vh - 150px); min-height: 520px; }
.conn-filter { margin-bottom: 8px; }
.conn-list { height: calc(100% - 92px); overflow: auto; }
.conn-item {
  padding: 10px; border-radius: 6px; cursor: pointer; margin-bottom: 6px;
  border: 1px solid transparent; transition: all .15s;
}
.conn-item:hover { background: #f5f7fa; }
.conn-item.active { background: #ecf5ff; border-color: #b3d8ff; }
.conn-main { display: flex; align-items: center; gap: 8px; min-width: 0; }
.dot { width: 8px; height: 8px; border-radius: 50%; flex: 0 0 auto; background: #909399; }
.dot.connected { background: #67c23a; }
.dot.error { background: #f56c6c; }
.dot.disconnected { background: #909399; }
.conn-name { font-size: 14px; color: #303133; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conn-sub { font-size: 12px; color: #909399; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conn-actions { display: flex; align-items: center; gap: 2px; margin-top: 6px; padding-left: 16px; }

.empty-card { height: calc(100vh - 150px); min-height: 520px; display: flex; align-items: center; justify-content: center; }
.work-card { height: calc(100vh - 150px); min-height: 520px; overflow: auto; }

.table-browser { border: 1px solid #ebeef5; border-radius: 6px; padding: 8px; height: 100%; }
.table-list { max-height: 430px; overflow: auto; }
.table-item { display: flex; align-items: center; gap: 6px; padding: 6px 8px; border-radius: 4px; cursor: pointer; font-size: 13px; }
.table-item:hover { background: #f5f7fa; }
.table-item .tname { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.table-empty { text-align: center; color: #c0c4cc; font-size: 12px; padding: 20px 0; }

.editor-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; }
.sql-editor :deep(.el-textarea__inner) { font-family: Consolas, Monaco, 'Courier New', monospace; font-size: 13px; line-height: 1.6; }

.result-head { display: flex; align-items: center; gap: 8px; margin: 10px 0; }
.result-head .meta { color: #606266; font-size: 13px; }
.result-table { margin-top: 4px; }

.history-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
</style>