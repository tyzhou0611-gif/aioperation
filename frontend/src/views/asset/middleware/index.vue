<template>
  <div>
    <h2 style="margin:0 0 16px;font-size:22px;color:#333">中间件 / 数据库管理</h2>

    <el-tabs v-model="category" @tab-change="onCategoryChange">
      <el-tab-pane label="数据库类" name="DB" />
      <el-tab-pane label="中间件类" name="MW" />
    </el-tabs>

    <el-card>
      <div style="display:flex;justify-content:space-between;flex-wrap:wrap;gap:12px;margin-bottom:12px">
        <div style="display:flex;gap:10px;flex-wrap:wrap">
          <el-select v-model="query.type" placeholder="类型" clearable style="width:150px" @change="search">
            <el-option v-for="t in currentTypes" :key="t" :label="t" :value="t" />
          </el-select>
          <el-select v-model="query.env" placeholder="环境" clearable style="width:120px" @change="search">
            <el-option label="生产" value="production" /><el-option label="预发" value="staging" />
            <el-option label="测试" value="test" /><el-option label="开发" value="dev" />
          </el-select>
          <el-input v-model="query.keyword" placeholder="搜索 名称/IP/集群" clearable style="width:200px" @keyup.enter="search" />
          <el-button type="primary" @click="search">查询</el-button>
        </div>
        <el-button type="primary" @click="openDialog()">+ 新增</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="name" label="实例名" min-width="130" />
        <el-table-column prop="type" label="类型" width="110">
          <template #default="{ row }"><el-tag size="small">{{ row.type }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column label="地址" width="180">
          <template #default="{ row }">{{ row.ip }}:{{ row.port }}</template>
        </el-table-column>
        <el-table-column prop="clusterName" label="集群" width="120" />
        <el-table-column prop="env" label="环境" width="90" />
        <el-table-column prop="serverId" label="宿主服务器ID" width="110" align="center" />
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="showDetail(row)">详情</el-button>
            <el-button type="primary" link size="small" @click="openDialog(row)">编辑</el-button>
            <el-button type="success" link size="small" @click="openCmd(row)">一键命令</el-button>
            <el-button type="warning" link size="small" @click="askAi(row)">AI助手</el-button>
            <el-button type="danger" link size="small" @click="del(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="display:flex;justify-content:flex-end;margin-top:14px">
        <el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total"
                       :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" @change="search" />
      </div>
    </el-card>

    <!-- 新增/编辑 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑中间件' : '新增中间件'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="实例名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width:100%">
            <el-option v-for="t in ALL_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本"><el-input v-model="form.version" /></el-form-item>
        <el-form-item label="IP"><el-input v-model="form.ip" /></el-form-item>
        <el-form-item label="端口"><el-input-number v-model="form.port" :min="0" :max="65535" /></el-form-item>
        <el-form-item label="集群"><el-input v-model="form.clusterName" /></el-form-item>
        <el-form-item label="环境">
          <el-select v-model="form.env" style="width:100%"><el-option label="生产" value="production" /><el-option label="预发" value="staging" /><el-option label="测试" value="test" /><el-option label="开发" value="dev" /></el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width:100%"><el-option label="在线" value="ONLINE" /><el-option label="离线" value="OFFLINE" /><el-option label="未知" value="UNKNOWN" /></el-select>
        </el-form-item>
        <el-form-item label="宿主服务器">
          <el-select v-model="form.serverId" clearable placeholder="选择服务器" style="width:100%">
            <el-option v-for="s in servers" :key="s.id" :label="s.hostname + ' (' + s.ip + ')'" :value="s.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <QuickCommand v-model="cmdVisible" :target-type="'MIDDLEWARE'" :target-id="cmdTarget?.id || null"
                  :target-name="cmdTarget?.name || ''" :middleware-type="cmdTarget?.type"
                  :target-meta="cmdTarget?.ip + ':' + cmdTarget?.port" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import QuickCommand from '@/components/QuickCommand.vue'
import { useCopilotStore } from '@/stores/copilot'
import type { MiddlewareAsset } from '@/types'

const route = useRoute()
const copilot = useCopilotStore()
function askAi(row: MiddlewareAsset) { copilot.openWith({ type: 'MIDDLEWARE', id: row.id, name: row.name }) }

const DB_TYPES = ['MYSQL', 'ORACLE', 'MONGODB', 'DAMENG', 'GREENPLUM', 'GBASE', 'ELASTICSEARCH']
const MW_TYPES = ['KAFKA', 'NACOS', 'REDIS', 'ZOOKEEPER', 'RABBITMQ']
const ALL_TYPES = [...DB_TYPES, ...MW_TYPES]

const category = ref('DB')
const currentTypes = computed(() => category.value === 'DB' ? DB_TYPES : MW_TYPES)

const loading = ref(false)
const rows = ref<MiddlewareAsset[]>([])
const total = ref(0)
const servers = ref<any[]>([])
const query = reactive({ keyword: '', type: '', env: '', page: 1, pageSize: 10 })

const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const form = reactive<any>({ name: '', type: 'MYSQL', version: '', ip: '', port: 3306, clusterName: '', env: 'production', status: 'UNKNOWN', serverId: null })

const cmdVisible = ref(false)
const cmdTarget = ref<MiddlewareAsset | null>(null)

function statusType(s: string) { return s === 'ONLINE' ? 'success' : s === 'OFFLINE' ? 'danger' : 'info' }
function statusLabel(s: string) { return s === 'ONLINE' ? '在线' : s === 'OFFLINE' ? '离线' : '未知' }

function onCategoryChange() { query.type = ''; search() }

async function search() {
  loading.value = true
  try {
    const params: any = { page: query.page, pageSize: query.pageSize, keyword: query.keyword || undefined, env: query.env || undefined }
    // 未选具体类型时，按分类过滤：传类型列表后端不支持，故前端按当前分类请求全部再筛，或传 type
    if (query.type) params.type = query.type
    const res = await request.get('/api/middleware/page', { params })
    let recs = res.data.records || []
    // 若未指定具体type，按分类过滤
    if (!query.type) {
      const set = new Set(currentTypes.value)
      recs = recs.filter((r: any) => set.has(r.type))
    }
    rows.value = recs
    total.value = query.type ? res.data.total : recs.length
  } finally { loading.value = false }
}

async function loadServers() {
  const res = await request.get('/api/server/page', { params: { page: 1, pageSize: 200 } })
  servers.value = res.data.records || []
}

function openDialog(row?: MiddlewareAsset) {
  if (row) { isEdit.value = true; editId.value = row.id; Object.assign(form, row) }
  else { isEdit.value = false; editId.value = null; Object.assign(form, { name:'', type: currentTypes.value[0], version:'', ip:'', port:3306, clusterName:'', env:'production', status:'UNKNOWN', serverId:null }) }
  dialogVisible.value = true
}

async function save() {
  if (!form.name || !form.type) { ElMessage.warning('实例名和类型必填'); return }
  if (isEdit.value && editId.value) await request.put(`/api/middleware/${editId.value}`, form)
  else await request.post('/api/middleware', form)
  ElMessage.success('保存成功'); dialogVisible.value = false; search()
}

function del(id: number) {
  ElMessageBox.confirm('确认删除？', '提示', { type: 'warning' }).then(async () => {
    await request.delete(`/api/middleware/${id}`); ElMessage.success('已删除'); search()
  }).catch(() => {})
}

function showDetail(row: any) {
  ElMessageBox.alert(
    `实例名：${row.name}\n类型：${row.type}\n版本：${row.version || '-'}\n地址：${row.ip}:${row.port}\n集群：${row.clusterName || '-'}\n环境：${row.env}\n状态：${row.status}\n宿主服务器ID：${row.serverId || '-'}`,
    '资产详情', { customStyle: { whiteSpace: 'pre-line' } }
  )
}

function openCmd(row: MiddlewareAsset) { cmdTarget.value = row; cmdVisible.value = true }

async function openFromQuery() {
  const id = route.query.id
  if (!id) return
  try {
    const res = await request.get(`/api/middleware/${id}`)
    if (res.data) showDetail(res.data)
    else ElMessage.warning('未找到该中间件资产')
  } catch { ElMessage.warning('未找到该中间件资产') }
}

onMounted(() => { loadServers(); search(); openFromQuery() })
</script>