<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">服务器管理</h2>

    <el-card>
      <div style="display:flex;justify-content:space-between;margin-bottom:16px;flex-wrap:wrap;gap:12px">
        <div style="display:flex;gap:12px">
          <el-input v-model="query.keyword" placeholder="搜索 IP / 主机名 / 环境" clearable style="width:260px" @keyup.enter="search" />
          <el-button type="primary" @click="search">查询</el-button>
        </div>
        <div style="display:flex;gap:12px">
          <el-button type="warning" plain @click="syncFromPrometheus" :loading="syncing">
            <el-icon style="margin-right:4px"><Refresh /></el-icon>从 Prometheus 同步
          </el-button>
          <el-button @click="probeAll" :loading="probing">探测全部状态</el-button>
          <el-button type="primary" @click="openDialog()">+ 新增服务器</el-button>
        </div>
      </div>

      <el-table :data="rows" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="ip" label="IP地址" width="140" />
        <el-table-column prop="hostname" label="主机名" width="150" />
        <el-table-column prop="os" label="操作系统" width="130" />
        <el-table-column prop="cpu" label="CPU" width="150" show-overflow-tooltip />
        <el-table-column prop="memory" label="内存" width="110" />
        <el-table-column prop="environment" label="环境" width="110">
          <template #default="{ row }"><el-tag size="small" type="info">{{ row.environment }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openDialog(row)">编辑</el-button>
            <el-button type="success" link size="small" @click="probe(row.id)" :loading="probingId===row.id">探测</el-button>
            <el-button type="warning" link size="small" @click="openCmd(row)">一键命令</el-button>
            <el-button type="info" link size="small" @click="askAi(row)">AI助手</el-button>
            <el-button type="danger" link size="small" @click="del(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="display:flex;justify-content:flex-end;margin-top:16px">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10,20,50]"
          layout="total, sizes, prev, pager, next"
          @change="search"
        />
      </div>
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑服务器' : '新增服务器'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="IP地址" prop="ip"><el-input v-model="form.ip" /></el-form-item>
        <el-form-item label="主机名" prop="hostname"><el-input v-model="form.hostname" /></el-form-item>
        <el-form-item label="操作系统"><el-input v-model="form.os" /></el-form-item>
        <el-form-item label="CPU"><el-input v-model="form.cpu" /></el-form-item>
        <el-form-item label="内存"><el-input v-model="form.memory" /></el-form-item>
        <el-form-item label="磁盘"><el-input v-model="form.disk" /></el-form-item>
        <el-form-item label="环境">
          <el-select v-model="form.environment" style="width:100%">
            <el-option label="生产 production" value="production" />
            <el-option label="预发 staging" value="staging" />
            <el-option label="测试 test" value="test" />
            <el-option label="开发 dev" value="dev" />
          </el-select>
        </el-form-item>
        <el-form-item label="SSH端口"><el-input-number v-model="form.sshPort" :min="1" :max="65535" /></el-form-item>
        <el-form-item label="SSH用户"><el-input v-model="form.sshUser" /></el-form-item>
        <el-form-item label="SSH密码">
          <div style="display:flex;width:100%;gap:8px">
            <el-input v-model="form.sshPassword" type="password" show-password
                      :placeholder="pwdPlaceholder" style="flex:1" />
            <el-button v-if="isEdit && pwdSet" @click="clearPwd">清除密码</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
    <!-- 一键命令 -->
    <QuickCommand v-model="cmdVisible" :target-type="'SERVER'" :target-id="cmdTarget?.id || null"
                  :target-name="cmdTarget?.hostname || cmdTarget?.ip || ''" :target-meta="cmdTarget?.ip" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import QuickCommand from '@/components/QuickCommand.vue'
import { useCopilotStore } from '@/stores/copilot'
import type { FormInstance, FormRules } from 'element-plus'

const route = useRoute()
const copilot = useCopilotStore()
function askAi(row: any) { copilot.openWith({ type: 'SERVER', id: row.id, name: row.hostname || row.ip }) }

const pwdSet = ref(false)
const pwdPlaceholder = ref('')

const loading = ref(false)
const cmdVisible = ref(false)
const cmdTarget = ref<any>(null)
const saving = ref(false)
const probing = ref(false)
const syncing = ref(false)
const probingId = ref<number | null>(null)
const rows = ref<any[]>([])
const total = ref(0)
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const query = reactive({ keyword: '', page: 1, pageSize: 10 })
const form = reactive<any>({
  ip: '', hostname: '', os: '', cpu: '', memory: '', disk: '',
  environment: 'production', sshPort: 22, sshUser: 'root', sshPassword: ''
})
const rules: FormRules = {
  ip: [{ required: true, message: '请输入IP地址' }],
  hostname: [{ required: true, message: '请输入主机名' }]
}

function statusType(s: string) {
  return s === 'ONLINE' ? 'success' : s === 'OFFLINE' ? 'danger' : 'warning'
}
function statusLabel(s: string) {
  return s === 'ONLINE' ? '在线' : s === 'OFFLINE' ? '离线' : s === 'MAINTENANCE' ? '维护' : s
}

async function search() {
  loading.value = true
  try {
    const res = await request.get('/api/server/page', { params: query })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function openDialog(row?: any) {
  form.sshPassword = ''
  form.clearPassword = false
  if (row) {
    isEdit.value = true; editId.value = row.id
    pwdSet.value = !!row.sshPasswordSet
    pwdPlaceholder.value = pwdSet.value ? '已设置密码，如需修改请输入新密码（留空则不修改）' : '请输入SSH密码'
    Object.assign(form, row, { sshPassword: '', clearPassword: false })
  } else {
    isEdit.value = false; editId.value = null; pwdSet.value = false
    pwdPlaceholder.value = '请输入SSH密码'
    Object.assign(form, { ip:'', hostname:'', os:'', cpu:'', memory:'', disk:'', environment:'production', sshPort:22, sshUser:'root', sshPassword:'', clearPassword:false })
  }
  dialogVisible.value = true
}

function clearPwd() {
  form.sshPassword = ''
  form.clearPassword = true
  ElMessage.info('保存后将清除该服务器已保存的SSH密码')
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (isEdit.value && editId.value) {
      await request.put(`/api/server/${editId.value}`, form)
      ElMessage.success('更新成功')
    } else {
      await request.post('/api/server', form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    search()
  } finally {
    saving.value = false
  }
}

async function del(id: number) {
  await ElMessageBox.confirm('确认删除该服务器？', '提示', { type: 'warning' })
  await request.delete(`/api/server/${id}`)
  ElMessage.success('删除成功')
  search()
}

async function probe(id: number) {
  probingId.value = id
  try {
    const res: any = await request.post(`/api/server/${id}/probe`)
    ElMessage.info(res.msg)
    search()
  } finally {
    probingId.value = null
  }
}

async function probeAll() {
  probing.value = true
  try {
    const res: any = await request.post('/api/server/probe/all')
    ElMessage.success(res.msg)
    search()
  } finally {
    probing.value = false
  }
}

function openCmd(row: any) { cmdTarget.value = row; cmdVisible.value = true }

async function syncFromPrometheus() {
  syncing.value = true
  try {
    const res: any = await request.post('/api/server/sync')
    ElMessage.success(res.msg)
    query.page = 1
    search()
  } finally {
    syncing.value = false
  }
}

async function openFromQuery() {
  const id = route.query.id
  if (!id) return
  try {
    const res = await request.get(`/api/server/${id}`)
    if (res.data) openDialog(res.data)
    else ElMessage.warning('未找到该服务器')
  } catch { ElMessage.warning('未找到该服务器') }
}

onMounted(() => { search(); openFromQuery() })
</script>