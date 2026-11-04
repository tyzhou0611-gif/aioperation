<template>
  <div>
    <h2 style="margin:0 0 16px;font-size:22px;color:#333">项目管理</h2>

    <el-card>
      <div style="display:flex;justify-content:space-between;flex-wrap:wrap;gap:12px;margin-bottom:12px">
        <div style="display:flex;gap:10px;flex-wrap:wrap">
          <el-input v-model="keyword" placeholder="搜索 项目名称/编码" clearable style="width:220px" @keyup.enter="load" />
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="resetSearch">重置</el-button>
        </div>
        <el-button v-permission="'asset:project:add'" type="primary" @click="openDialog()">+ 新增项目</el-button>
      </div>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
                title="项目用于在【智能大屏】按项目过滤机器与链路。一台机器/中间件可同时归属多个项目。" />

      <el-table :data="rows" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="name" label="项目名称" min-width="140" />
        <el-table-column prop="code" label="编码" width="140" />
        <el-table-column prop="owner" label="负责人" width="100" />
        <el-table-column prop="serverCount" label="服务器" width="90" align="center" />
        <el-table-column prop="middlewareCount" label="中间件" width="90" align="center" />
        <el-table-column prop="appCount" label="应用" width="80" align="center" />
        <el-table-column label="可见角色" min-width="160">
          <template #default="{ row }">
            <el-tag v-for="n in row.roleNames || []" :key="n" size="small" style="margin-right:4px">{{ n }}</el-tag>
            <span v-if="!(row.roleNames || []).length" class="hint">未授权</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'asset:project:assign'" link type="primary" size="small" @click="openAssets(row)">分配资产</el-button>
            <el-button v-permission="'asset:project:edit'" link type="primary" size="small" @click="openDialog(row)">编辑</el-button>
            <el-button link type="success" size="small" @click="viewInScreen(row)">大屏查看</el-button>
            <el-button v-permission="'asset:project:delete'" link type="danger" size="small" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!rows.length && !loading" description="暂无项目，点击右上角【新增项目】创建" />
    </el-card>

    <!-- 新增/编辑项目 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑项目' : '新增项目'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="项目名称" required><el-input v-model="form.name" placeholder="如：订单中心" /></el-form-item>
        <el-form-item label="项目编码" required><el-input v-model="form.code" placeholder="唯一标识，如 order-center" /></el-form-item>
        <el-form-item label="负责人"><el-input v-model="form.owner" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ACTIVE">启用</el-radio>
            <el-radio value="INACTIVE">停用</el-radio>
          </el-radio-group>
          <span style="margin-left:10px;color:#909399;font-size:12px">停用后大屏项目下拉中隐藏</span>
        </el-form-item>
        <el-form-item label="可见角色">
          <el-select v-model="form.roleIds" multiple filterable placeholder="选择可访问该项目的角色" style="width:100%">
            <el-option v-for="r in roles" :key="r.id" :label="r.name + (r.description ? ' · ' + r.description : '')" :value="r.id" />
          </el-select>
          <div class="hint">这些角色的用户只能看到该项目下的服务器</div>
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 分配资产 -->
    <el-dialog v-model="assetVisible" :title="`分配资产 - ${assetProject?.name || ''}`" width="680px">
      <el-form label-width="90px">
        <el-form-item label="服务器">
          <el-select v-model="assetForm.serverIds" multiple filterable clearable placeholder="选择项目所属服务器" style="width:100%">
            <el-option v-for="s in serverOptions" :key="s.id" :label="serverLabel(s)" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="中间件">
          <el-select v-model="assetForm.middlewareIds" multiple filterable clearable placeholder="选择项目使用的中间件/数据库" style="width:100%">
            <el-option v-for="m in middlewareOptions" :key="m.id" :label="`${m.name} (${m.type})`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="应用">
          <el-select v-model="assetForm.appIds" multiple filterable clearable placeholder="选择项目包含的应用" style="width:100%">
            <el-option v-for="a in appOptions" :key="a.id" :label="`${a.name} (${a.code})`" :value="a.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <div style="color:#909399;font-size:12px;line-height:1.8">
        大屏选择该项目时，只显示这里关联的资产，以及两端都在项目内的链路（自动推导与手动配置）。
        同一台服务器可以分配给多个项目。
      </div>
      <template #footer>
        <el-button @click="assetVisible = false">取消</el-button>
        <el-button type="primary" :loading="assetSaving" @click="saveAssets">保存分配</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import type { Project } from '@/types'

const router = useRouter()
const rows = ref<Project[]>([])
const roles = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')

const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const saving = ref(false)
const form = reactive({ name: '', code: '', owner: '', status: 'ACTIVE', description: '', roleIds: [] as number[] })

const assetVisible = ref(false)
const assetSaving = ref(false)
const assetProject = ref<Project | null>(null)
const assetForm = reactive<{ serverIds: number[]; middlewareIds: number[]; appIds: number[] }>({
  serverIds: [], middlewareIds: [], appIds: []
})
const serverOptions = ref<any[]>([])
const middlewareOptions = ref<any[]>([])
const appOptions = ref<any[]>([])
let optionsLoaded = false

function serverLabel(s: any) {
  return `${s.hostname || s.ip} (${s.ip})`
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/api/project/list', { params: { keyword: keyword.value.trim() || undefined } })
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

function resetSearch() {
  keyword.value = ''
  load()
}

function openDialog(row?: Project) {
  isEdit.value = !!row
  editId.value = row?.id ?? null
  form.name = row?.name || ''
  form.code = row?.code || ''
  form.owner = row?.owner || ''
  form.status = row?.status || 'ACTIVE'
  form.description = row?.description || ''
  form.roleIds = row?.roleIds ? [...row.roleIds] : []
  dialogVisible.value = true
}

async function loadRoles() {
  try {
    const r = await request.get('/api/role/list')
    roles.value = r.data || []
  } catch { roles.value = [] }
}

async function save() {
  if (!form.name.trim()) { ElMessage.warning('请输入项目名称'); return }
  if (!form.code.trim()) { ElMessage.warning('请输入项目编码'); return }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(), code: form.code.trim(), owner: form.owner,
      status: form.status, description: form.description
    }
    let id = editId.value
    if (isEdit.value && editId.value) {
      await request.put(`/api/project/${editId.value}`, payload)
    } else {
      const r = await request.post('/api/project', payload)
      id = r.data?.id || null
    }
    if (id) {
      await request.put(`/api/project/${id}/roles`, { roleIds: form.roleIds || [] })
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function del(row: Project) {
  ElMessageBox.confirm(`确定删除项目「${row.name}」吗？资产关联会一并删除（资产本身不受影响）。`, '删除项目',
    { type: 'warning' })
    .then(async () => {
      await request.delete(`/api/project/${row.id}`)
      ElMessage.success('已删除')
      await load()
    })
    .catch(() => {})
}

async function ensureOptions() {
  if (optionsLoaded) return
  const [s, m, a] = await Promise.all([
    request.get('/api/server/page', { params: { page: 1, pageSize: 500 } }),
    request.get('/api/middleware/options'),
    request.get('/api/app/options')
  ])
  serverOptions.value = s.data?.records || []
  middlewareOptions.value = m.data || []
  appOptions.value = a.data || []
  optionsLoaded = true
}

async function openAssets(row: Project) {
  assetProject.value = row
  try {
    await ensureOptions()
    const res = await request.get(`/api/project/${row.id}/assets`)
    assetForm.serverIds = res.data?.serverIds || []
    assetForm.middlewareIds = res.data?.middlewareIds || []
    assetForm.appIds = res.data?.appIds || []
    assetVisible.value = true
  } catch (e: any) {
    ElMessage.error(e?.message || '加载项目资产失败')
  }
}

async function saveAssets() {
  if (!assetProject.value) return
  assetSaving.value = true
  try {
    await request.put(`/api/project/${assetProject.value.id}/assets`, {
      serverIds: assetForm.serverIds,
      middlewareIds: assetForm.middlewareIds,
      appIds: assetForm.appIds
    })
    ElMessage.success('资产分配已保存')
    assetVisible.value = false
    await load()
  } finally {
    assetSaving.value = false
  }
}

function viewInScreen(row: Project) {
  router.push({ path: '/topology', query: { projectId: row.id } })
}

onMounted(async () => {
  await Promise.all([loadRoles(), load()])
})
</script>

<style scoped>
.hint { color: #909399; font-size: 12px; }
</style>