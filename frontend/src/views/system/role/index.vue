<template>
  <div class="role-page">
    <div class="page-head">
      <div>
        <h2>角色管理</h2>
        <p class="sub">按职责划分角色标签；仅超级管理员可以创建角色、配置页面与功能权限、给用户分配角色。</p>
      </div>
      <div class="head-actions">
        <el-button @click="load">刷新</el-button>
        <el-button v-if="userStore.isSuperAdmin" type="primary" @click="openCreate">+ 新增自定义角色</el-button>
      </div>
    </div>

    <div class="tag-rule">
      <span class="tag-rule-title">标签规则</span>
      <span>超级管理员 红 · 平台运维 蓝 · 监控值班 橙 · 变更发布 紫 · 数据作业 青 · 基础设施 天蓝 · DBA 金 · 安全 玫红 · 审计 灰 · 项目 绿 · 服务台 青绿 · 只读 浅灰；自定义角色可自选颜色。</span>
    </div>

    <el-alert v-if="!userStore.isSuperAdmin" type="info" :closable="false" show-icon class="readonly-tip"
              title="当前为只读查看模式：只有超级管理员可以修改角色和权限。" />

    <el-card shadow="never">
      <el-table :data="roles" v-loading="loading" stripe style="width:100%">
        <el-table-column label="角色" min-width="230">
          <template #default="{ row }">
            <div class="role-cell">
              <RoleTag :label="row.label" :color="row.tagColor" />
              <span class="role-code">{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.builtin ? 'info' : 'success'" effect="plain">
              {{ row.builtin ? '内置' : '自定义' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据范围" width="100">
          <template #default="{ row }">{{ scopeLabel(row.scope) }}</template>
        </el-table-column>
        <el-table-column label="权限数" width="90" align="center">
          <template #default="{ row }">
            <span class="perm-count">{{ row.name === 'ROLE_SUPER_ADMIN' ? '全部' : (row.permissionCount || 0) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="职责说明" min-width="360" show-overflow-tooltip />
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatTime(row.updateTime || row.createTime) }}</template>
        </el-table-column>
        <el-table-column v-if="userStore.isSuperAdmin" label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <template v-if="row.name !== 'ROLE_SUPER_ADMIN'">
              <el-button type="primary" link size="small" @click="openEdit(row)">配置权限</el-button>
              <el-button v-if="!row.builtin" type="danger" link size="small" @click="removeRole(row)">删除</el-button>
            </template>
            <el-tag v-else size="small" type="danger" effect="plain">全部权限</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '配置角色权限' : '新增自定义角色'" width="940px" top="4vh" destroy-on-close>
      <el-form :model="form" label-width="96px" class="role-form">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="角色名称" required>
              <el-input v-model="form.label" placeholder="例如：业务运维" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="角色标识" required>
              <el-input v-model="form.name" :disabled="isEdit" placeholder="ROLE_BIZ_OPS" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="数据范围">
              <el-select v-model="form.scope" style="width:100%">
                <el-option label="全局" value="GLOBAL" />
                <el-option label="租户" value="TENANT" />
                <el-option label="项目" value="PROJECT" />
                <el-option label="只读" value="READONLY" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="标签颜色">
              <div class="color-row">
                <el-color-picker v-model="form.tagColor" :predefine="ROLE_COLORS" />
                <RoleTag :label="form.label || '角色预览'" :color="form.tagColor" />
              </div>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="职责说明">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>

        <el-form-item label="访问权限">
          <div class="perm-box">
            <div class="perm-toolbar">
              <el-input v-model="permKeyword" size="small" placeholder="搜索页面 / 权限标识" clearable style="width:220px" />
              <el-button size="small" @click="checkAll(true)">全选</el-button>
              <el-button size="small" @click="checkAll(false)">清空</el-button>
              <el-button size="small" @click="checkType(1)">全选页面</el-button>
              <el-button size="small" @click="checkType(2)">全选功能</el-button>
              <span class="perm-summary">{{ checkedSummary }}</span>
            </div>
            <el-alert type="info" :closable="false" show-icon class="perm-tip"
                      title="页面和功能权限独立勾选：只读角色只勾页面；需要操作能力时再勾选对应功能按钮。" />
            <el-tree
              ref="treeRef"
              :data="menuTree"
              node-key="id"
              show-checkbox
              check-strictly
              default-expand-all
              :filter-node-method="filterNode"
              class="perm-tree"
              @check="onCheck"
            >
              <template #default="{ data }">
                <span class="perm-node">
                  <span :class="['perm-type', 't' + data.type]">{{ typeLabel(data.type) }}</span>
                  <span class="perm-name">{{ data.name }}</span>
                  <span v-if="data.path" class="perm-path">{{ data.path }}</span>
                  <span v-if="data.permission" class="perm-code">{{ data.permission }}</span>
                </span>
              </template>
            </el-tree>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { TreeInstance } from 'element-plus'
import RoleTag from '@/components/RoleTag.vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const loading = ref(false)
const saving = ref(false)
const roles = ref<any[]>([])
const menuFlat = ref<any[]>([])
const menuTree = ref<any[]>([])
const treeRef = ref<TreeInstance>()
const permKeyword = ref('')
const checkedKeys = ref<number[]>([])

const ROLE_COLORS = [
  '#ef4444', '#3b82f6', '#f59e0b', '#8b5cf6', '#06b6d4', '#0ea5e9',
  '#d97706', '#ec4899', '#64748b', '#22c55e', '#14b8a6', '#94a3b8'
]

const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const form = reactive<any>({
  name: '', label: '', description: '', tagColor: '#7c3aed', scope: 'GLOBAL', menuIds: [] as number[]
})

const checkedSummary = computed(() => {
  const map = new Map<number, any>(menuFlat.value.map(m => [m.id, m]))
  let pages = 0, functions = 0
  for (const id of checkedKeys.value) {
    const m = map.get(id)
    if (!m) continue
    if (m.type === 1) pages++
    if (m.type === 2) functions++
  }
  return `已选页面 ${pages} 个 / 功能 ${functions} 个`
})

function scopeLabel(s: string) {
  return ({ GLOBAL: '全局', TENANT: '租户', PROJECT: '项目', READONLY: '只读' } as any)[s] || s || '全局'
}
function typeLabel(t: number) {
  return t === 0 ? '目录' : t === 1 ? '页面' : '功能'
}
function formatTime(t: string) {
  return t ? t.replace('T', ' ').substring(0, 19) : ''
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/api/role/list')
    roles.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function loadMenuTree() {
  if (!userStore.isSuperAdmin) return
  const res = await request.get('/api/role/menu-tree')
  menuFlat.value = res.data || []
  const map = new Map<number, any>()
  menuFlat.value.forEach((m: any) => map.set(m.id, { ...m, children: [] }))
  const roots: any[] = []
  menuFlat.value.forEach((m: any) => {
    const node = map.get(m.id)
    if (m.parentId && map.has(m.parentId)) map.get(m.parentId).children.push(node)
    else roots.push(node)
  })
  const sortRec = (nodes: any[]) => {
    nodes.sort((a, b) => (a.sort || 0) - (b.sort || 0))
    nodes.forEach(n => sortRec(n.children))
  }
  sortRec(roots)
  menuTree.value = roots
}

function refreshChecked() {
  checkedKeys.value = (treeRef.value?.getCheckedKeys(false) || []) as number[]
}
function onCheck() { refreshChecked() }

function filterNode(value: string, data: any) {
  if (!value) return true
  const k = value.toLowerCase()
  return [data.name, data.path, data.permission].some(v => (v || '').toLowerCase().includes(k))
}
watch(permKeyword, (v) => treeRef.value?.filter(v))

async function openCreate() {
  isEdit.value = false
  editId.value = null
  permKeyword.value = ''
  Object.assign(form, { name: '', label: '', description: '', tagColor: '#7c3aed', scope: 'GLOBAL', menuIds: [] })
  dialogVisible.value = true
  await nextTick()
  treeRef.value?.setCheckedKeys([], false)
  refreshChecked()
}

async function openEdit(row: any) {
  isEdit.value = true
  editId.value = row.id
  permKeyword.value = ''
  Object.assign(form, {
    name: row.name,
    label: row.label,
    description: row.description || '',
    tagColor: row.tagColor || '#7c3aed',
    scope: row.scope || 'GLOBAL',
    menuIds: []
  })
  dialogVisible.value = true
  await nextTick()
  treeRef.value?.setCheckedKeys(row.menuIds || [], false)
  refreshChecked()
}

function checkAll(checked: boolean) {
  treeRef.value?.setCheckedKeys(checked ? menuFlat.value.map(m => m.id) : [], false)
  refreshChecked()
}

function checkType(type: number) {
  const keys = new Set<number>((treeRef.value?.getCheckedKeys(false) || []) as number[])
  menuFlat.value.filter(m => m.type === type).forEach(m => keys.add(m.id))
  treeRef.value?.setCheckedKeys([...keys], false)
  refreshChecked()
}

async function save() {
  if (!form.label || !form.label.trim()) { ElMessage.warning('请填写角色名称'); return }
  if (!isEdit.value && !/^ROLE_[A-Z0-9_]{2,40}$/.test((form.name || '').trim().toUpperCase())) {
    ElMessage.warning('角色标识格式应为 ROLE_XXX（大写字母/数字/下划线）')
    return
  }
  const menuIds = (treeRef.value?.getCheckedKeys(false) || []) as number[]
  saving.value = true
  try {
    const payload: any = {
      label: form.label.trim(),
      description: form.description,
      tagColor: form.tagColor,
      scope: form.scope,
      menuIds
    }
    if (isEdit.value && editId.value) {
      await request.put(`/api/role/${editId.value}`, payload)
      ElMessage.success('角色权限已更新')
    } else {
      payload.name = form.name.trim().toUpperCase()
      await request.post('/api/role', payload)
      ElMessage.success('自定义角色已创建')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

function removeRole(row: any) {
  ElMessageBox.confirm(`确定删除角色「${row.label || row.name}」？删除后不可恢复。`, '删除角色', { type: 'warning' })
    .then(async () => {
      await request.delete(`/api/role/${row.id}`)
      ElMessage.success('角色已删除')
      load()
    })
    .catch(() => {})
}

onMounted(async () => {
  await load()
  loadMenuTree()
})
</script>

<style scoped>
.role-page h2 { margin: 0; font-size: 22px; color: #303133; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.sub { margin: 6px 0 0; font-size: 12.5px; color: #909399; }
.head-actions { display: flex; gap: 10px; flex-shrink: 0; }
.readonly-tip { margin-bottom: 12px; }
.tag-rule {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 8px 12px; margin-bottom: 12px;
  border: 1px solid #eef1f8; border-radius: 8px;
  background: linear-gradient(90deg, #fafbff, #ffffff);
  font-size: 12px; color: #7b849c; line-height: 1.6;
}
.tag-rule-title { flex: 0 0 auto; font-weight: 700; color: #4b57c9; }
.role-cell { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.role-code { font-size: 11px; color: #a0a8b8; font-family: Consolas, monospace; }
.perm-count { font-family: Consolas, monospace; font-weight: 600; color: #606266; }
.color-row { display: flex; align-items: center; gap: 10px; }
.perm-box { width: 100%; border: 1px solid #ebeef5; border-radius: 10px; padding: 10px; background: #fafbff; }
.perm-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.perm-summary { margin-left: auto; font-size: 12px; color: #909399; }
.perm-tip { margin-bottom: 8px; }
.perm-tree { max-height: 380px; overflow: auto; padding: 4px; background: #fff; border-radius: 8px; }
.perm-node { display: inline-flex; align-items: center; gap: 7px; font-size: 13px; }
.perm-type { font-size: 10px; padding: 0 5px; border-radius: 4px; line-height: 17px; font-weight: 600; }
.perm-type.t0 { color: #64748b; background: #f1f5f9; }
.perm-type.t1 { color: #0369a1; background: #e0f2fe; }
.perm-type.t2 { color: #b45309; background: #fef3c7; }
.perm-name { color: #303133; }
.perm-path { font-size: 11px; color: #a0a8b8; }
.perm-code { font-size: 10.5px; color: #7c8db5; background: #f3f6ff; padding: 0 5px; border-radius: 4px; font-family: Consolas, monospace; }
</style>