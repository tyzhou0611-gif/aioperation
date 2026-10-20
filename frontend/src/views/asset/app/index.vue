<template>
  <div>
    <h2 style="margin:0 0 16px;font-size:22px;color:#333">应用管理</h2>

    <el-card>
      <div style="display:flex;justify-content:space-between;flex-wrap:wrap;gap:12px;margin-bottom:12px">
        <div style="display:flex;gap:10px;flex-wrap:wrap">
          <el-select v-model="query.env" placeholder="环境" clearable style="width:110px" @change="search">
            <el-option label="生产" value="production" /><el-option label="预发" value="staging" />
            <el-option label="测试" value="test" /><el-option label="开发" value="dev" />
          </el-select>
          <el-select v-model="query.status" placeholder="状态" clearable style="width:110px" @change="search">
            <el-option label="运行中" value="RUNNING" /><el-option label="已停止" value="STOPPED" /><el-option label="异常" value="ERROR" />
          </el-select>
          <el-input v-model="query.keyword" placeholder="搜索 名称/编码/负责人" clearable style="width:200px" @keyup.enter="search" />
          <el-button type="primary" @click="search">查询</el-button>
        </div>
        <el-button type="primary" @click="openDialog()">+ 新增应用</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="name" label="应用名" min-width="120" />
        <el-table-column prop="code" label="编码" width="130" />
        <el-table-column prop="type" label="类型" width="90" />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="language" label="语言" width="80" />
        <el-table-column prop="deployType" label="部署" width="80" />
        <el-table-column prop="env" label="环境" width="90" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="serverId" label="宿主ID" width="80" align="center" />
        <el-table-column prop="owner" label="负责人" width="90" />
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

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑应用' : '新增应用'" width="600px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="应用名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="编码"><el-input v-model="form.code" placeholder="唯一标识，如 user-service" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width:100%"><el-option v-for="t in APP_TYPES" :key="t" :label="t" :value="t" /></el-select>
        </el-form-item>
        <el-form-item label="版本"><el-input v-model="form.version" /></el-form-item>
        <el-form-item label="语言"><el-input v-model="form.language" placeholder="Java/Python/Go" /></el-form-item>
        <el-form-item label="部署方式">
          <el-select v-model="form.deployType" style="width:100%"><el-option v-for="d in DEPLOY_TYPES" :key="d" :label="d" :value="d" /></el-select>
        </el-form-item>
        <el-form-item label="环境"><el-select v-model="form.env" style="width:100%"><el-option label="生产" value="production" /><el-option label="预发" value="staging" /><el-option label="测试" value="test" /><el-option label="开发" value="dev" /></el-select></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status" style="width:100%"><el-option label="运行中" value="RUNNING" /><el-option label="已停止" value="STOPPED" /><el-option label="异常" value="ERROR" /></el-select></el-form-item>
        <el-form-item label="宿主服务器">
          <el-select v-model="form.serverId" clearable style="width:100%"><el-option v-for="s in servers" :key="s.id" :label="s.hostname + ' (' + s.ip + ')'" :value="s.id" /></el-select>
        </el-form-item>
        <el-form-item label="负责人"><el-input v-model="form.owner" /></el-form-item>
        <!-- 依赖关系 → 拓扑连线来源 -->
        <el-form-item label="依赖中间件">
          <el-select v-model="mwDeps" multiple filterable placeholder="选择依赖的中间件/数据库" style="width:100%">
            <el-option v-for="m in mwOptions" :key="m.id" :label="`${m.name} (${m.type})`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="依赖应用">
          <el-select v-model="appDeps" multiple filterable placeholder="选择依赖的其他应用" style="width:100%">
            <el-option v-for="a in appOptions.filter(x => x.id !== editId)" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <QuickCommand v-model="cmdVisible" :target-type="'APP'" :target-id="cmdTarget?.id || null"
                  :target-name="cmdTarget?.name || ''" :target-meta="'编码 ' + cmdTarget?.code" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import QuickCommand from '@/components/QuickCommand.vue'
import { useCopilotStore } from '@/stores/copilot'
import type { AppAsset, MiddlewareAsset } from '@/types'

const route = useRoute()
const copilot = useCopilotStore()
function askAi(row: AppAsset) { copilot.openWith({ type: 'APP', id: row.id, name: row.name }) }

const APP_TYPES = ['WEB', 'JOB', 'API', 'GATEWAY']
const DEPLOY_TYPES = ['JAR', 'WAR', 'Docker', 'K8s']

const loading = ref(false)
const rows = ref<AppAsset[]>([])
const total = ref(0)
const servers = ref<any[]>([])
const mwOptions = ref<MiddlewareAsset[]>([])
const appOptions = ref<AppAsset[]>([])
const query = reactive({ keyword: '', env: '', status: '', page: 1, pageSize: 10 })

const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const form = reactive<any>({ name:'', code:'', type:'API', version:'', language:'Java', deployType:'JAR', env:'production', status:'RUNNING', serverId:null, owner:'' })
const mwDeps = ref<number[]>([])
const appDeps = ref<number[]>([])

const cmdVisible = ref(false)
const cmdTarget = ref<AppAsset | null>(null)

function statusType(s: string) { return s === 'RUNNING' ? 'success' : s === 'ERROR' ? 'danger' : 'info' }
function statusLabel(s: string) { return s === 'RUNNING' ? '运行中' : s === 'ERROR' ? '异常' : '已停止' }

function parseIds(v: string | null): number[] {
  if (!v) return []
  try { return JSON.parse(v) } catch { return [] }
}

async function search() {
  loading.value = true
  try {
    const res = await request.get('/api/app/page', { params: { page: query.page, pageSize: query.pageSize, keyword: query.keyword || undefined, env: query.env || undefined, status: query.status || undefined } })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } finally { loading.value = false }
}

async function loadRefs() {
  const s = await request.get('/api/server/page', { params: { page: 1, pageSize: 200 } })
  servers.value = s.data.records || []
  const m = await request.get('/api/middleware/options'); mwOptions.value = m.data || []
  const a = await request.get('/api/app/options'); appOptions.value = a.data || []
}

function openDialog(row?: AppAsset) {
  if (row) {
    isEdit.value = true; editId.value = row.id; Object.assign(form, row)
    mwDeps.value = parseIds(row.dependsMiddlewareIds); appDeps.value = parseIds(row.dependsAppIds)
  } else {
    isEdit.value = false; editId.value = null
    Object.assign(form, { name:'', code:'', type:'API', version:'', language:'Java', deployType:'JAR', env:'production', status:'RUNNING', serverId:null, owner:'' })
    mwDeps.value = []; appDeps.value = []
  }
  dialogVisible.value = true
}

async function save() {
  if (!form.name || !form.code) { ElMessage.warning('应用名和编码必填'); return }
  const payload = { ...form, dependsMiddlewareIds: JSON.stringify(mwDeps.value), dependsAppIds: JSON.stringify(appDeps.value) }
  if (isEdit.value && editId.value) await request.put(`/api/app/${editId.value}`, payload)
  else await request.post('/api/app', payload)
  ElMessage.success('保存成功'); dialogVisible.value = false; search(); loadRefs()
}

function del(id: number) {
  ElMessageBox.confirm('确认删除该应用？', '提示', { type: 'warning' }).then(async () => {
    await request.delete(`/api/app/${id}`); ElMessage.success('已删除'); search(); loadRefs()
  }).catch(() => {})
}

function showDetail(row: any) {
  ElMessageBox.alert(
    `应用名：${row.name}\n编码：${row.code}\n类型：${row.type}\n版本：${row.version || '-'}\n语言：${row.language || '-'}\n部署：${row.deployType || '-'}\n环境：${row.env}\n状态：${row.status}\n负责人：${row.owner || '-'}\n依赖中间件：${row.dependsMiddlewareIds || '[]'}\n依赖应用：${row.dependsAppIds || '[]'}`,
    '应用详情', { customStyle: { whiteSpace: 'pre-line' } }
  )
}

function openCmd(row: AppAsset) { cmdTarget.value = row; cmdVisible.value = true }

async function openFromQuery() {
  const id = route.query.id
  if (!id) return
  try {
    const res = await request.get(`/api/app/${id}`)
    if (res.data) showDetail(res.data)
    else ElMessage.warning('未找到该应用')
  } catch { ElMessage.warning('未找到该应用') }
}

onMounted(() => { search(); loadRefs(); openFromQuery() })
</script>